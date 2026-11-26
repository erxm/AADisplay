package io.github.nitsuya.aa.display.aap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.ui.main.MainActivity
import io.github.nitsuya.aa.display.aap.wireless.WirelessAutoStarter
import io.github.nitsuya.aa.display.aap.wireless.WirelessConnector
import io.github.nitsuya.aa.display.aap.wireless.WirelessPermissionException
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.SharedPreferencesAccess
import net.prototux.aare.aap.AapLog
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * 持有 AAP 连接的前台服务。
 *
 * 连接期间要一直跑编码器和虚拟屏，进程被杀整条链路就断，所以用前台服务挂住。
 */
class AapEgressService : Service() {

    companion object {
        private const val TAG = AapConsts.TAG
        private const val CHANNEL_ID = "aap_egress"
        private const val NOTIFICATION_ID = 4101
        /** localabstract 域监听名（抄 AARE 的 aare_hu）：`adb forward tcp:5277 localabstract:aadisplay_hu`。 */
        private const val LOCAL_LISTENER = "aadisplay_hu"

        fun start(context: Context) {
            // 路线闸门：原版路线（hook gearhead）不需要 AAP 通道，插上车机时不要抢连接。
            if (route(context) != AapConsts.ROUTE_AAP) {
                Log.i(TAG, "当前路线为原版（依赖 Android Auto），AAP 通道不启动")
                return
            }
            // 用户主动发起连接时清掉上一次的 H.264 回退标记，重新按设置里的编码尝试。
            AapEgress.resetCodecFallback()
            val intent = Intent(context, AapEgressService::class.java)
            runCatching { context.startForegroundService(intent) }
                .onFailure { context.startService(intent) }
        }

        /** 当前投屏路线；读不到配置时回退原版路线（保持既有行为不变）。 */
        fun route(context: Context): String = runCatching {
            AADisplayConfig.AapRoute.get(
                SharedPreferencesAccess.openForHooks(context, AADisplayConfig.ConfigName),
            )
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: AapConsts.ROUTE_LEGACY

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AapEgressService::class.java)) }
        }

        /** 找一台 Google/Android Auto 形态的 accessory（AAP 车机在 USB 上就长这样）。 */
        fun findAapAccessory(usbManager: UsbManager): UsbAccessory? =
            usbManager.accessoryList?.firstOrNull { acc ->
                acc.manufacturer?.contains("Google", ignoreCase = true) == true &&
                    (acc.model?.contains("Android Auto", ignoreCase = true) == true ||
                        acc.model?.contains("AndroidAuto", ignoreCase = true) == true)
            } ?: usbManager.accessoryList?.firstOrNull()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private enum class Mode { USB, TCP, WIRELESS }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var mode = Mode.USB

    /** 无线 AAP 复用 AARE 的日志接口（前缀与有线的 AapEgress 分开，便于过滤）。 */
    private val wirelessLog = object : AapLog {
        override fun i(msg: String) { Log.i(TAG, msg) }
        override fun w(msg: String, t: Throwable?) { Log.w(TAG, msg, t) }
        override fun e(msg: String, t: Throwable?) { Log.e(TAG, msg, t) }
    }
    private var wirelessConnector: WirelessConnector? = null
    private var wirelessStarter: WirelessAutoStarter? = null

    @Volatile
    private var tcpPort = AapConsts.DEFAULT_TCP_PORT
    // [B3] TCP 监听的 ServerSocket 落字段：原实现包在局部变量里，onDestroy 关不掉，
    // 服务销毁后 accept 线程还挂在端口上；bind 失败也要 stopSelf 收尾。
    private var tcpServer: ServerSocket? = null
    // localabstract 监听（与 tcpServer 同生命周期管理）。
    private var localServer: android.net.LocalServerSocket? = null

    /** 连续失败次数，用来算重连退避；会话就绪时清零。 */
    @Volatile
    private var reconnectAttempt = 0

    @Volatile
    private var reconnectScheduled = false

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        AapEgress.onSessionClosed = { reason -> onSessionClosed(reason) }
        AapEgress.onSessionRunning = { reconnectAttempt = 0 }
        registerWirelessStarter()
    }

    /**
     * 车机蓝牙一上来就自动连：无线 AAP 的第一步是手机通过 RFCOMM 找到车机
     * 的 Android Auto 服务。缺 BLUETOOTH_CONNECT 时只记日志，不影响 USB 路线。
     */
    private fun registerWirelessStarter() {
        if (wirelessStarter != null) return
        if (!aapEnabled() || route() != AapConsts.ROUTE_AAP) return
        val connector = wirelessConnector
            ?: WirelessConnector(this, wirelessLog).also { wirelessConnector = it }
        wirelessStarter = WirelessAutoStarter(this, wirelessLog) { device ->
            // 回调在广播线程上：实际连接会做 RFCOMM/Wi-Fi 握手，必须丢到后台。
            connectWireless(connector, device)
        }.also {
            try {
                it.register()
            } catch (e: WirelessPermissionException) {
                Log.i(TAG, "无线 AAP 未启用：缺少 ${e.missing.joinToString()}")
                wirelessStarter = null
            }
        }
    }

    /** 无线连接：蓝牙已连上的车机优先，否则让连接器自己找。 */
    private fun connectWireless(connector: WirelessConnector, device: android.bluetooth.BluetoothDevice? = null) {
        if (AapEgress.isRunning) return
        mode = Mode.WIRELESS
        Thread({
            try {
                val target = device ?: connector.findConnectedHeadUnits().firstOrNull()
                if (target == null) {
                    Log.i(TAG, "没有已连接且支持无线 Android Auto 的蓝牙设备")
                    if (!hasUsbAccessory()) stopSelf()
                    return@Thread
                }
                val transport = connector.connectBlocking(target)
                AapEgress.start(this, transport)
            } catch (e: WirelessPermissionException) {
                Log.w(TAG, "无线 AAP 缺少权限：${e.missing.joinToString()}")
                if (!hasUsbAccessory()) stopSelf()
            } catch (e: Throwable) {
                Log.e(TAG, "无线 AAP 连接失败", e)
                if (!hasUsbAccessory()) stopSelf()
            }
        }, "aap-wireless-connect").start()
    }

    private fun hasUsbAccessory(): Boolean = runCatching {
        findAapAccessory(getSystemService(UsbManager::class.java)) != null
    }.getOrDefault(false)

    private fun aapEnabled(): Boolean = runCatching {
        AADisplayConfig.AapEnabled.get(
            SharedPreferencesAccess.openForHooks(this, AADisplayConfig.ConfigName),
        )
    }.getOrDefault(true)

    private fun route(): String = route(this)

    /**
     * 会话断了就退避重连：1s、2s、4s……最多 30s，靠前台服务把进程留住。
     * 用户在设置里关掉 AAP 出口时不重连，直接收服务。
     */
    private fun onSessionClosed(reason: String) {
        // TCP 调试入口不受路线闸门限制（与 onStartCommand 的 ACTION_START_TCP 语义一致），
        // 否则在原版路线下调试会话一断就 stopSelf、永远无法重连（2026-11-22 实测复现）。
        if (mode != Mode.TCP && (!aapEnabled() || route() != AapConsts.ROUTE_AAP)) {
            Log.i(TAG, "AAP 出口已关闭或已切到原版路线，不重连（$reason）")
            stopSelf()
            return
        }
        // 监听套接字在服务生命周期内常驻（accept 循环），重连时不需要重新 bind：
        // 关掉再开会让 localabstract 在旧 accept 线程退出前一直报“被占用”。
        scheduleReconnect()
    }

    /** 关闭还在等待连接的监听套接字；accept 线程会以 SocketException 退出。 */
    @Synchronized
    private fun closeListeners() {
        runCatching { tcpServer?.close() }
            .onFailure { Log.w(TAG, "close tcp listener failed: ${it.message}") }
        tcpServer = null
        runCatching { localServer?.close() }
            .onFailure { Log.w(TAG, "close local listener failed: ${it.message}") }
        localServer = null
    }

    private fun scheduleReconnect() {
        if (reconnectScheduled) return
        reconnectScheduled = true
        val delayMs = (1_000L shl reconnectAttempt.coerceAtMost(5)).coerceAtMost(30_000L)
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(6)
        Log.i(TAG, "AAP 断线，${delayMs}ms 后重连（第 $reconnectAttempt 次）")
        handler.postDelayed({
            reconnectScheduled = false
            if (AapEgress.isRunning) return@postDelayed
            when (mode) {
                Mode.TCP -> {
                    listenTcp(tcpPort)
                    listenLocal()
                }
                Mode.USB -> connect()
                Mode.WIRELESS -> {
                    val connector = wirelessConnector
                        ?: WirelessConnector(this, wirelessLog).also { wirelessConnector = it }
                    connectWireless(connector)
                }
            }
        }, delayMs)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AapConsts.ACTION_STOP -> {
                AapEgress.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            // TCP 是 debug 构建的调试入口，不受路线闸门限制（方便在原版路线上临时验证 AAP）。
            AapConsts.ACTION_START_TCP -> {
                listenTcp(intent.getIntExtra(AapConsts.EXTRA_TCP_PORT, AapConsts.DEFAULT_TCP_PORT))
                listenLocal()
                return START_NOT_STICKY
            }
            AapConsts.ACTION_START_WIRELESS -> {
                if (route() != AapConsts.ROUTE_AAP) {
                    Log.i(TAG, "当前路线为原版，忽略无线 AAP 请求")
                    stopSelf()
                    return START_NOT_STICKY
                }
                registerWirelessStarter()
                val connector = wirelessConnector
                    ?: WirelessConnector(this, wirelessLog).also { wirelessConnector = it }
                connectWireless(connector)
                return START_STICKY
            }
            else -> {
                // USB 正常路径受路线闸门限制：原版路线由 gearhead 自己处理投影。
                if (route() != AapConsts.ROUTE_AAP) {
                    Log.i(TAG, "当前路线为原版，忽略 USB 直连请求")
                    stopSelf()
                    return START_NOT_STICKY
                }
                // 授权对话框关闭后再次 start 时 onCreate 不会再跑；这里补一次注册，
                // 让刚拿到的 BLUETOOTH_CONNECT 能生效到“蓝牙连上就自动连”。
                registerWirelessStarter()
                connect()
            }
        }
        return START_STICKY
    }

    /**
     * 调试入口：在 localhost 上等 DHU 连进来，连上就把 socket 交给 AAP 会话。
     *
     * 手机侧主动开口（TLS ClientHello + VersionRequest），DHU 是等着的服务端，两边角色正好对上。
     */
    private fun listenTcp(port: Int) {
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "TCP 调试入口只在 debug 构建里可用")
            stopSelf()
            return
        }
        mode = Mode.TCP
        tcpPort = port
        if (AapEgress.isRunning) return
        // 同一时刻只保留一个 accept 线程：重复 ACTION_START_TCP 再 bind 会 EADDRINUSE，
        // 失败后若退回 ::1，adb forward 连 127.0.0.1 反而彻底连不上。
        if (tcpServer != null) {
            Log.i(TAG, "TCP 监听已在运行，忽略重复请求")
            return
        }
        Thread({
            val server = ServerSocket()
            tcpServer = server
            try {
                server.reuseAddress = true
                // 固定绑 IPv4 回环：getLoopbackAddress() 在本机会给出 ::1，
                // 而 `adb forward tcp:5277 tcp:5278` 是连 127.0.0.1，IPv4/IPv6 不互通，
                // DHU 会一直卡在握手前（表现是 host 侧 connected、手机侧没有 accept）。
                // 绑定失败就是失败：IPv6 回环对 adb forward 没有意义，不要自欺欺人地退回。
                server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                Log.i(TAG, "AAP 调试监听 ${server.localSocketAddress}，等待 DHU")
                // 常驻 accept：会话断开后 DHU 可能自动重连，服务端不必反复 bind
                // （Windows 侧端口/TIME_WAIT、localabstract 的“被占用”都由此避免）。
                while (true) {
                    val socket = server.accept()
                    Log.i(TAG, "DHU 已连接：${socket.remoteSocketAddress}")
                    if (AapEgress.isRunning) {
                        Log.i(TAG, "已有 AAP 会话，关闭多余连接")
                        runCatching { socket.close() }
                    } else {
                        AapEgress.start(this, AapTcpTransport(socket))
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "AAP TCP 监听失败", t)
                // [B3] 绑定/接受失败不能留一个空转的前台服务。
                if (AapEgress.isRunning.not()) stopSelf()
            } finally {
                if (tcpServer === server) tcpServer = null
            }
        }, "aap-tcp-accept").start()
    }

    /**
     * 调试入口（localabstract 域，抄 AARE 的 aare_hu）：
     * `adb forward tcp:5277 localabstract:aadisplay_hu` 后 DHU 连 localhost:5277。
     * Windows 侧 TCP 端口可能被 Hyper-V/WinNAT 动态保留（bind 报 10013），
     * 域 socket 只在手机侧，转发端口号随便挑，绕开保留段。
     */
    private fun listenLocal() {
        if (!BuildConfig.DEBUG) return
        if (AapEgress.isRunning) return
        if (localServer != null) {
            Log.i(TAG, "localabstract 监听已在运行，忽略重复请求")
            return
        }
        Thread({
            val server = runCatching { android.net.LocalServerSocket(LOCAL_LISTENER) }.getOrNull()
            if (server == null) {
                Log.w(TAG, "localabstract:$LOCAL_LISTENER 监听失败（可能被占用）")
                return@Thread
            }
            localServer = server
            try {
                Log.i(TAG, "AAP 调试监听 localabstract:$LOCAL_LISTENER，等待 DHU")
                // 常驻 accept：断开重连时沿用同一个 LocalServerSocket。
                while (true) {
                    val socket = server.accept()
                    Log.i(TAG, "DHU 已连接（localabstract）")
                    if (AapEgress.isRunning) {
                        // TCP 与 localabstract 双监听时后到的连接直接关掉，别抢已建立的会话。
                        runCatching { socket.close() }
                    } else {
                        AapEgress.start(this, AapLocalSocketTransport(socket))
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "localabstract 监听结束：${t.message}")
            } finally {
                runCatching { server.close() }
                if (localServer === server) localServer = null
            }
        }, "aap-local-accept").start()
    }

    override fun onDestroy() {
        AapEgress.onSessionClosed = null
        AapEgress.onSessionRunning = null
        runCatching { wirelessStarter?.unregister() }
        wirelessStarter = null
        wirelessConnector = null
        handler.removeCallbacksAndMessages(null)
        reconnectScheduled = false
        // [B3] 关掉还挂着的监听（accept 线程会以 SocketException 退出，幂等无害）。
        closeListeners()
        AapEgress.stop()
        super.onDestroy()
    }

    private fun connect() {
        mode = Mode.USB
        if (AapEgress.isRunning) return
        if (!aapEnabled()) {
            Log.i(TAG, "AAP 出口已关闭，不自动连接 USB 车机")
            stopSelf()
            return
        }
        // 抽 CarService 证书要扫 gearhead 的 dex，放主线程可能卡住/ANR。
        Thread({
            doConnect()
        }, "aap-usb-connect").start()
    }

    private fun doConnect() {
        // [B2] 跑在独立线程上，任何未捕获异常会直接打死整个进程；包起来并 stopSelf 收尾。
        runCatching {
            val usbManager = getSystemService(UsbManager::class.java)
            val accessory = findAapAccessory(usbManager)
            if (accessory == null) {
                // 没插 USB：车机可能是无线 AA，转到蓝牙发现/连接流程。
                Log.w(TAG, "没有找到 Android Auto accessory，尝试无线 AAP")
                val connector = wirelessConnector
                    ?: WirelessConnector(this, wirelessLog).also { wirelessConnector = it }
                connectWireless(connector)
                return
            }
            if (!usbManager.hasPermission(accessory)) {
                // 正常路径由 AapUsbAttachActivity 申请权限；这里没有权限就退出，避免反复起服务。
                Log.w(TAG, "accessory 没有授权，等待用户确认")
                stopSelf()
                return
            }
            val transport = runCatching { AapUsbTransport(usbManager, accessory) }
                .onFailure { Log.e(TAG, "打开 accessory 失败", it) }
                .getOrNull() ?: run { stopSelf(); return }
            AapEgress.start(this, transport)
        }.onFailure {
            Log.e(TAG, "USB 连接流程异常", it)
            stopSelf()
        }
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.aap_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.aap_notification_title))
            .setContentText(getString(R.string.aap_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // AAP 是连接车机的持续性会话，优先 connectedDevice（不受 Android 15 的
            // dataSync 每日 6 小时限制）。缺少蓝牙/USB 授权时该系统类型会被拒绝，
            // 再退回 dataSync，保证 USB 授权前也能先挂住前台服务。
            try {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "connectedDevice FGS 不可用，退回 dataSync", e)
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
