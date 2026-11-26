package io.github.nitsuya.aa.display.aap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaCodecList
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.MotionEvent.PointerCoords
import android.view.MotionEvent.PointerProperties
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import aap_protobuf.service.inputsource.message.KeyEventOuterClass.KeyEvent as AapKeyEvent
import aap_protobuf.service.inputsource.message.PointerActionOuterClass.PointerAction
import aap_protobuf.service.inputsource.message.RelativeEventOuterClass.RelativeEvent
import aap_protobuf.service.inputsource.message.TouchEventOuterClass.TouchEvent
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.sink.message.VideoCodecResolutionTypeOuterClass.VideoCodecResolutionType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.media.sink.message.VideoFrameRateTypeOuterClass.VideoFrameRateType
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.SharedPreferencesAccess
import io.github.nitsuya.aa.display.xposed.IVirtualDisplayCreatedListener
import net.prototux.aare.aap.AapLog
import net.prototux.aare.aap.AapSession
import net.prototux.aare.aap.PhoneDescription
import net.prototux.aare.aap.ServiceChannel
import net.prototux.aare.aap.Transport
import net.prototux.aare.aap.channels.InputChannel
import net.prototux.aare.aap.channels.VideoChannel

/**
 * AAP 出口的编排层：一条连接 = 一个 [AapSession]。
 *
 * 内容生产者仍然是本模块原有的那套：system_server 里建虚拟屏（[CoreApi.onCreateDisplay]），
 * 只是把虚拟屏的 surface 换成了编码器的输入面，于是“虚拟屏画的内容”直接变成 H.264 帧，
 * 由 AARE 的 [VideoChannel] 说给车机。这样 AAP 只替换出口，不替换内容来源。
 */
object AapEgress {

    private const val TAG = AapConsts.TAG

    private val log = object : AapLog {
        override fun i(msg: String) {
            Log.i(TAG, msg)
        }

        override fun w(msg: String, t: Throwable?) {
            Log.w(TAG, msg, t)
        }

        override fun e(msg: String, t: Throwable?) {
            Log.e(TAG, msg, t)
        }
    }

    @Volatile
    private var session: AapSession? = null

    @Volatile
    private var videoChannel: VideoChannel? = null

    @Volatile
    private var encoder: AapVideoEncoder? = null

    /** 路线 B：虚拟屏输出先落进本进程 GL 消费者，再画进编码器。 */
    private var glConsumer: AapGlVideoConsumer? = null

    /** 路线 B：App 自建的虚拟屏（`aad_exp_ext_vd`，默认启用），释放也归本进程。 */
    @Volatile
    private var appVirtualDisplay: android.hardware.display.VirtualDisplay? = null

    @Volatile
    private var transport: Transport? = null

    /** 用于强拉亮屏/注册屏幕广播的模块 App context。 */
    @Volatile
    private var appContext: Context? = null

    /** 会话结束通知：由 AapEgressService 用来做断线重连（含退避）。 */
    @Volatile
    var onSessionClosed: ((reason: String) -> Unit)? = null

    /** 会话就绪通知：用来把重连退避清零。 */
    @Volatile
    var onSessionRunning: (() -> Unit)? = null

    @Volatile
    private var downTimeMs = 0L

    /** 当前视频尺寸，滚轮注入要用它算屏幕中心。 */
    @Volatile
    private var videoWidth = AapConsts.FALLBACK_WIDTH

    @Volatile
    private var videoHeight = AapConsts.FALLBACK_HEIGHT

    val isRunning: Boolean get() = session != null

    /** 当前连接方式（tcp/usb/localabstract），用于主界面状态展示；未连接时为 null。 */
    val transportName: String? get() = transport?.name

    /** 上一次成功的视频配置：息屏唤醒后要用它原样重建视频管线。 */
    @Volatile
    private var lastVideoConfig: VideoConfiguration? = null

    private var screenReceiver: BroadcastReceiver? = null
    /** 入睡（SCREEN_OFF）时刻的编码器累计帧数；-1=未记录或无编码器。 */
    @Volatile private var framesAtSleep: Long = -1
    /** 投屏期间保持亮屏的 WakeLock（SCREEN_BRIGHT）。 */
    private var keepAwakeLock: PowerManager.WakeLock? = null

    /**
     * 息屏时 SF 不再给虚拟屏出 buffer，硬件编码器会停在最后一帧；部分机型唤醒后也不会自己恢复。
     * 这里在 SCREEN_ON 后检查编码器帧计数，一段时间没动就重建整条视频管线。
     */
    private fun registerScreenReceiver(ctx: Context) {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> checkVideoAfterWake()
                    Intent.ACTION_SCREEN_OFF -> {
                        // 记录入睡时刻的帧数：>0 说明管线息屏前就在出帧，
                        // 唤醒后 4s 没动才算真停滞；=0 说明管线还没出过帧
                        // （HUD 启动需要 ~2.3s），此时严禁提前重建。
                        framesAtSleep = encoder?.framesEmitted() ?: -1
                    }
                }
            }
        }
        runCatching { ctx.registerReceiver(receiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }) }
            .onFailure { Log.w(TAG, "register screen receiver failed", it) }
        screenReceiver = receiver
    }

    private fun unregisterScreenReceiver() {
        val ctx = appContext ?: return
        screenReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        screenReceiver = null
    }

    // ------------------------------------------------------------ 保持亮屏

    /**
     * 投屏期间持 SCREEN_BRIGHT_WAKE_LOCK（与原版模块 interactiveMonitor 非 MIUI 分支同款）：
     * 手机不因超时息屏 → keyguard 不触发 → App 自建虚拟屏不会被 keyguard sleep token 睡掉。
     *
     * 背景（2026-11-21）：锁屏后车机画面变成锁屏时钟。已试过 `setShouldShowWithInsecureKeyguard(true)`
     * （display 11 上一次生效、display 2/12 上不生效，疑似 HyperOS 对非 trusted 屏不看这个标记）
     * 和 flags 加 CAN_SHOW_WITH_INSECURE_KEYGUARD（DisplayDeviceInfo 里存活但 WMS 不认），
     * 均不可靠；唯一稳定的路径就是不进 keyguard。按电源键显式锁屏仍会锁（与原版模块
     * 不开 ScreenOffReplaceLockScreen 时一致）。
     */
    private fun keepScreenAwake() {
        if (keepAwakeLock?.isHeld == true) return
        val ctx = appContext ?: return
        runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            // ACQUIRE_CAUSES_WAKEUP：车机上会话开始时手机多半已经熄屏躺在支架里，
            // 亮屏锁要把屏幕直接点亮，否则锁/熄屏状态下虚拟屏还是会被睡掉。
            val lock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "AADisplay:aapProjection",
            )
            lock.setReferenceCounted(false)
            lock.acquire()
            keepAwakeLock = lock
            Log.i(TAG, "投屏期间保持亮屏（SCREEN_BRIGHT_WAKE_LOCK|ACQUIRE_CAUSES_WAKEUP）")
        }.onFailure { Log.w(TAG, "保持亮屏失败", it) }
    }

    private fun releaseScreenAwake() {
        runCatching {
            keepAwakeLock?.takeIf { it.isHeld }?.release()
            keepAwakeLock = null
        }
    }

    private fun checkVideoAfterWake() {
        if (session == null) return
        val enc = encoder ?: return
        val config = lastVideoConfig ?: return
        val before = enc.framesEmitted()
        // 2026-11-21 实测教训：HUD 首帧要 ~2.3s，1.2s 的检查窗会把"刚要出帧"的
        // 管线当成停滞拆掉（display 10→11→12 反复重建都是这个误判造成的）。
        // 用 4s 窗 + 入睡帧数佐证，只有"入睡时已在出帧、唤醒后 4s 一帧没动"才重建。
        mainHandler.postDelayed({
            if (session == null || encoder !== enc) return@postDelayed
            val after = enc.framesEmitted()
            if (after == before) {
                // 锁屏（keyguard 生效）时虚拟屏必然被 keyguard sleep token 睡掉，
                // 这时重建只是换一个 displayId 继续睡（2026-11-21 实测 10→11→12 打转）。
                val km = appContext?.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                if (km?.isKeyguardLocked == true) {
                    Log.i(TAG, "唤醒后无新帧（frames=$before），但 keyguard 已锁屏——预期行为，不重建")
                    return@postDelayed
                }
                if (framesAtSleep in 1..after) {
                    Log.w(TAG, "唤醒后视频停滞（frames=$before，入睡时=$framesAtSleep），重建视频管线（保留悬浮窗）")
                    Thread({
                        runCatching { startVideo(config, keepPhoneWindows = true) }
                            .onFailure { Log.e(TAG, "唤醒后重建视频失败", it) }
                    }, "aap-wake-recover").start()
                } else {
                    Log.i(TAG, "唤醒后暂无新帧（frames=$before，入睡时=$framesAtSleep）——管线未热身，不重建")
                }
            } else {
                Log.i(TAG, "唤醒后视频正常（frames=$before->$after）")
            }
        }, 4000L)
    }

    // ------------------------------------------------------------ 延迟销毁
    // 车机拔线/断连之后不要立刻拆窗口和虚拟屏：用户可能只是想重插一下，
    // 立刻拆会导致“投屏整个消失 + 应用被甩回主屏”。这里按 DelayDestroyTime
    // 倒计时，期间能重连就取消，到点才真正销毁。

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var destroyScheduled = false

    /** 读 DelayDestroyTime（0 = 立刻销毁）。 */
    private fun destroyDelaySeconds(): Int {
        val ctx = appContext ?: return 0
        return runCatching {
            val prefs = ctx.getSharedPreferences(
                io.github.nitsuya.aa.display.util.AADisplayConfig.ConfigName,
                Context.MODE_PRIVATE,
            )
            io.github.nitsuya.aa.display.util.AADisplayConfig.DelayDestroyTime.get(prefs)
        }.getOrDefault(0)
    }

    /** 延迟销毁任务：重连时取消。悬浮窗本身由 system_server 的 DisplayWindow 管理。 */
    private val teardownRunnable = Runnable { teardownNow("倒计时结束") }

    private fun scheduleTeardown(reason: String) {
        val delay = destroyDelaySeconds()
        if (delay <= 0) {
            teardownNow(reason)
            return
        }
        Log.i(TAG, "$reason：${delay}s 后销毁投屏管线（重连可取消）")
        destroyScheduled = true
        mainHandler.removeCallbacks(teardownRunnable)
        mainHandler.postDelayed(teardownRunnable, delay * 1000L)
    }

    private fun cancelPendingTeardown() {
        if (!destroyScheduled) return
        destroyScheduled = false
        mainHandler.removeCallbacks(teardownRunnable)
        Log.i(TAG, "重连成功，取消延迟销毁")
    }

    private fun teardownNow(reason: String) {
        mainHandler.removeCallbacks(teardownRunnable)
        destroyScheduled = false
        // 守卫：延迟销毁到点时新会话可能已经起来；会话活着就不拆管线。
        if (session != null) {
            Log.w(TAG, "销毁请求被忽略：AAP 会话仍在运行（$reason）")
            return
        }
        Log.i(TAG, "销毁投屏资源：$reason")
        teardownVideo()
        lastVideoConfig = null
        unregisterScreenReceiver()
    }


    @Synchronized
    fun start(context: Context, transport: Transport) {
        if (session != null) {
            Log.i(TAG, "已有连接，忽略重复启动")
            return
        }
        // 上一轮可能还在延迟销毁倒计时里：重连就取消，别把刚起来的会话拆了。
        cancelPendingTeardown()
        val identity = AapIdentity.loadOrCreate(context)
        // 车机会直接拒签过期证书（auth -3）：到期前两周提醒，别在车上才发现。
        if (AapIdentity.expiringSoon(context)) {
            Log.w(TAG, "AAP 证书两周内到期——请在密钥管理里更新（在线下载或重抽），否则车机会拒签")
        }
        val phone = PhoneDescription(
            deviceName = "AADisplay local",
            label = "AADisplay local",
        )
        val callbacks = object : AapSession.Callbacks {
            override fun onServiceDiscovery(
                session: AapSession,
                response: ServiceDiscoveryResponse,
            ): List<ServiceChannel> = buildChannels(response)

            override fun onRunning(session: AapSession) {
                Log.i(TAG, "AAP 会话就绪：${session.headUnitVersion}")
                runCatching { onSessionRunning?.invoke() }
            }

            override fun onClosed(session: AapSession, reason: String, error: Throwable?) {
                // [A3] onClosed 与 start()/stop() 可能并发（读者线程死亡 vs 用户重连）。
                // 用同一把锁判定：只有“死掉的正是当前会话”才走拆管线流程；老会话迟到的
                // 死亡回调只关它自己的 transport（闭包捕获 start() 的入参），绝不碰当前会话
                // 的状态——否则重连瞬间老回调会把新 transport 关掉，新会话立刻死于 write failed。
                val isCurrent = synchronized(this@AapEgress) {
                    if (this@AapEgress.session === session) {
                        this@AapEgress.session = null
                        true
                    } else {
                        false
                    }
                }
                runCatching { transport.close() }
                if (!isCurrent) return
                Log.i(TAG, "AAP 会话结束：$reason", error)
                // 先把视频管线拆掉：断开后没人消费虚拟屏画面，会堵死 SurfaceFlinger 合成。
                // 悬浮球/镜像窗留着走下面的延迟销毁倒计时，期间重连能救回来。
                runCatching { stopStreamingVideo() }
                scheduleTeardown("AAP 会话结束")
                // [A3] 只清掉仍属于自己的 transport，别误清新会话刚设置的。
                if (this@AapEgress.transport === transport) this@AapEgress.transport = null
                runCatching { onSessionClosed?.invoke(reason) }
            }
        }

        this.transport = transport
        val appCtx = context.applicationContext
        this.appContext = appCtx
        registerScreenReceiver(appCtx)
        val s = AapSession(transport, identity, phone, callbacks, log)
        session = s
        s.start()
        Log.i(TAG, "AAP 会话开始于 ${transport.name}")
    }

    @Synchronized
    fun stop() {
        val s = session ?: return
        session = null
        runCatching { s.stop() }
        // 显式停止（服务销毁/用户关闭）不等倒计时：立刻拆干净。
        teardownNow("stop()")
        transport?.close()
        transport = null
    }

    /** 车机当前会话通告的视频编码（buildChannels 选中后固定）。 */
    @Volatile
    private var videoCodecType: MediaCodecType = MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP

    /**
     * 非 H.264 编码器启动失败后置位：下一次重连强制 H.264，避免反复重建会话。
     * 用户在界面重新发起连接时由 [resetCodecFallback] 清掉。
     */
    @Volatile
    private var forceH264Fallback = false

    fun resetCodecFallback() {
        forceH264Fallback = false
    }

    /** 用户配置的编码器偏好；车机没宣告或手机无编码器时回退 H.264。 */
    private fun preferredVideoCodec(): String {
        if (forceH264Fallback) return "h264"
        return runCatching {
            AADisplayConfig.AapVideoCodec.get(
                appContext?.let { SharedPreferencesAccess.openForHooks(it, AADisplayConfig.ConfigName) },
            )
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "h264"
    }

    /** 用户配置的最大分辨率（像素数）；auto/空 = 跟随车机首选。 */
    private fun maxResolutionPixels(): Int? {
        val value = runCatching {
            AADisplayConfig.AapMaxResolution.get(
                appContext?.let { SharedPreferencesAccess.openForHooks(it, AADisplayConfig.ConfigName) },
            )
        }.getOrNull()?.trim()?.lowercase() ?: return null
        val parts = value.split("x")
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        return if (w > 0 && h > 0) w * h else null
    }

    /**
     * 「投屏显示 → 虚拟屏 DPI」对 AAP 路线同样生效：
     * >50 时覆盖车机下发的 density，0/留空则跟随车机。
     */
    private fun virtualDisplayDpiOverride(): Int? = runCatching {
        AADisplayConfig.VirtualDisplayDpi.get(
            appContext?.let { SharedPreferencesAccess.openForHooks(it, AADisplayConfig.ConfigName) },
        )
    }.getOrNull()?.takeIf { it > 50 }

    private fun hasEncoderFor(type: MediaCodecType): Boolean {
        val mime = mimeFor(type)
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    private fun mimeFor(type: MediaCodecType): String = when (type) {
        MediaCodecType.MEDIA_CODEC_VIDEO_H265 -> "video/hevc"
        MediaCodecType.MEDIA_CODEC_VIDEO_VP9 -> "video/x-vnd.on2.vp9"
        MediaCodecType.MEDIA_CODEC_VIDEO_AV1 -> "video/av01"
        else -> "video/avc"
    }

    /**
     * 从车机通告的服务里挑出视频与输入通道。
     *
     * 车机按编码各宣告一个 media sink；这里按用户偏好选一个，未宣告或本机无编码器时
     * 回退 H.264。只开一个视频通道，避免同一会话里重复 SETUP。
     */
    private fun buildChannels(response: ServiceDiscoveryResponse): List<ServiceChannel> {
        val channels = mutableListOf<ServiceChannel>()
        val sinks = response.channelsList.filter {
            it.hasMediaSinkService() && it.mediaSinkService.videoConfigsCount > 0
        }
        if (sinks.isNotEmpty()) {
            // available_type 缺省是 AUDIO_PCM；视频服务没填时按 H.264 处理，
            // 避免把音频 codec 当成视频 SETUP 出去。
            val byCodec = sinks.groupBy { service ->
                val type = service.mediaSinkService.availableType
                if (type == MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP ||
                    type == MediaCodecType.MEDIA_CODEC_VIDEO_H265 ||
                    type == MediaCodecType.MEDIA_CODEC_VIDEO_VP9 ||
                    type == MediaCodecType.MEDIA_CODEC_VIDEO_AV1
                ) {
                    type
                } else {
                    MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
                }
            }
            val pref = preferredVideoCodec()
            val order = when (pref) {
                "h265" -> listOf(MediaCodecType.MEDIA_CODEC_VIDEO_H265, MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                "vp9" -> listOf(MediaCodecType.MEDIA_CODEC_VIDEO_VP9, MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                "av1" -> listOf(MediaCodecType.MEDIA_CODEC_VIDEO_AV1, MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                "auto" -> listOf(
                    MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP,
                    MediaCodecType.MEDIA_CODEC_VIDEO_H265,
                    MediaCodecType.MEDIA_CODEC_VIDEO_VP9,
                    MediaCodecType.MEDIA_CODEC_VIDEO_AV1,
                )
                else -> listOf(MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
            }
            val chosen = order.firstOrNull { byCodec.containsKey(it) && hasEncoderFor(it) }
                ?: byCodec.keys.firstOrNull { hasEncoderFor(it) }
                ?: byCodec.keys.first()
            val service = byCodec.getValue(chosen).first()
            val sink = service.mediaSinkService
            videoCodecType = chosen
            val ch = VideoChannel(service, VideoListener, chosen)
            videoChannel = ch
            channels += ch
            Log.i(
                TAG,
                "车机视频编码：$chosen（偏好=$pref，可用=${byCodec.keys.joinToString()}）" +
                    " 配置=${sink.videoConfigsList.map { describe(it) }}",
            )
        }
        for (service in response.channelsList) {
            if (!service.hasInputSourceService()) continue
            channels += InputChannel(service, InputListener)
        }
        if (channels.isEmpty()) Log.w(TAG, "车机没有提供视频/输入服务")
        return channels
    }

    private fun describe(config: VideoConfiguration): String =
        "${resolution(config).first}x${resolution(config).second}@${fpsOf(config)} dpi=${config.density}"

    private object VideoListener : VideoChannel.Listener {
        override fun chooseConfiguration(configs: List<VideoConfiguration>, preferred: List<Int>): Int {
            val maxPixels = AapEgress.maxResolutionPixels()
            val allowed = if (maxPixels == null) {
                configs.indices.toList()
            } else {
                configs.indices.filter { AapEgress.resolution(configs[it]).let { (w, h) -> w * h <= maxPixels } }
            }
            val pick = preferred.firstOrNull { it in allowed }
                ?: allowed.maxByOrNull { AapEgress.resolution(configs[it]).let { (w, h) -> w * h } }
                ?: preferred.firstOrNull { it in configs.indices }
                ?: 0
            Log.i(TAG, "选择视频配置 #$pick（最大分辨率=${maxPixels ?: "车机首选"}）")
            return pick
        }

        override fun onVideoStart(index: Int, config: VideoConfiguration) {
            Log.i(TAG, "车机选择视频配置 #$index: ${describe(config)}")
            // 会话建立/重协商时也保留悬浮窗：倒计时内重连（窗口还活着）不再闪一下重建，
            // 镜像窗保持打开；窗口死了的话 attach 会新建，行为不变。
            startVideo(config, keepPhoneWindows = true)
        }

        override fun onVideoFocus(projected: Boolean) {
            Log.i(TAG, "视频焦点：projected=$projected")
        }

        override fun onKeyFrameNeeded() {
            encoder?.requestKeyFrame()
        }
    }

    private object InputListener : InputChannel.Listener {
        override fun onInput(report: InputReport) {
            when {
                report.hasTouchEvent() -> {
                    val touch = report.touchEvent
                    if (touch.action != PointerAction.ACTION_MOVED) {
                        val p0 = touch.pointerDataList.firstOrNull()
                        Log.i(
                            TAG,
                            "AAP 触摸：${touch.action} pointers=${touch.pointerDataList.size} " +
                                "x=${p0?.x} y=${p0?.y} index=${touch.actionIndex}",
                        )
                    }
                    forwardTouch(report.timestamp, touch)
                }
                report.hasKeyEvent() -> forwardKeys(report.keyEvent)
                report.hasRelativeEvent() -> forwardScroll(report.relativeEvent)
            }
        }
    }

    /**
     * 触摸转发：AAP 的 TouchEvent 带完整指针快照（多指时 pointer_data 有多个），
     * 这里按指针数重建 MotionEvent，而不是只取第一根手指。
     */
    private fun forwardTouch(timestampMs: Long, touch: TouchEvent) {
        val pointers = touch.pointerDataList
        if (pointers.isEmpty()) return
        val actionIndex = touch.actionIndex.coerceIn(0, pointers.size - 1)
        val action = when (touch.action) {
            PointerAction.ACTION_DOWN -> MotionEvent.ACTION_DOWN
            PointerAction.ACTION_MOVED -> MotionEvent.ACTION_MOVE
            PointerAction.ACTION_UP -> MotionEvent.ACTION_UP
            PointerAction.ACTION_POINTER_DOWN ->
                MotionEvent.ACTION_POINTER_DOWN or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            PointerAction.ACTION_POINTER_UP ->
                MotionEvent.ACTION_POINTER_UP or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            else -> return
        }
        val now = SystemClock.uptimeMillis()
        val eventTime = if (timestampMs > 0) timestampMs.takeIf { it in 1..now + 5_000 } ?: now else now
        if (action == MotionEvent.ACTION_DOWN || downTimeMs == 0L) downTimeMs = eventTime

        val props = Array(pointers.size) { index ->
            PointerProperties().apply {
                id = pointers[index].pointerId.toInt()
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(pointers.size) { index ->
            PointerCoords().apply {
                x = pointers[index].x.toFloat()
                y = pointers[index].y.toFloat()
                pressure = 1f
                size = 1f
            }
        }
        val event = if (pointers.size == 1) {
            // 单指走最简单的重载：和早期可用版本保持完全一致（多指针构造在部分
            // ROM 上会被 InputDispatcher 静默丢弃）。
            MotionEvent.obtain(downTimeMs, eventTime, action, coords[0].x, coords[0].y, 0)
        } else {
            MotionEvent.obtain(
                downTimeMs, eventTime, action,
                pointers.size, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
        }
        runCatching { CoreApi.touch(event) }
            .onFailure { Log.w(TAG, "注入触摸失败", it) }
        runCatching { event.recycle() }
        if (action == MotionEvent.ACTION_UP ||
            (action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_UP && pointers.size == 1
        ) {
            downTimeMs = 0L
        }
    }

    /**
     * 按键转发：AAP 的 KeyEvent 是「一串 key + down/up」，但模块侧的 pressKey 一次
     * 就注入 down+up，所以只在按下时触发一次，抬起忽略（长按会退化成单击）。
     */
    private fun forwardKeys(event: AapKeyEvent) {
        for (key in event.keysList) {
            if (!key.down) continue
            val keyCode = key.keycode.toInt()
            if (keyCode == 0) continue
            Log.i(TAG, "车机按键：$keyCode")
            runCatching { CoreApi.pressKey(keyCode) }
                .onFailure { Log.w(TAG, "按键注入失败 keycode=$keyCode", it) }
        }
    }

    /**
     * 旋钮转发：AAP 的 RelativeEvent 是「一串 (keycode, delta)」。
     *
     * MotionEvent 的滚轮轴值（setAxisValue）是 @hide，普通应用改不了，所以这里把
     * 旋钮方向翻译成 DPAD 上下，交给列表/网格自己移动焦点——车机上够用。
     */
    private fun forwardScroll(event: RelativeEvent) {
        var delta = 0
        for (rel in event.dataList) delta += rel.delta
        if (delta == 0) return
        // 车机旋钮向前一般是负 delta，对应列表向上。
        val keyCode = if (delta > 0) android.view.KeyEvent.KEYCODE_DPAD_DOWN else android.view.KeyEvent.KEYCODE_DPAD_UP
        Log.i(TAG, "车机旋钮：delta=$delta -> keycode=$keyCode")
        runCatching { CoreApi.pressKey(keyCode) }
            .onFailure { Log.w(TAG, "旋钮按键注入失败", it) }
    }

    @Synchronized
    private fun startVideo(config: VideoConfiguration, keepPhoneWindows: Boolean = false) {
        // [A1] 守卫：唤醒自愈线程从“检查帧数”到真正起管线之间有数秒窗口，期间一次完整
        // teardown 可能已完成——不加守卫会把整条管线（编码器+EGL+虚拟屏+亮屏锁）建在死会话上。
        val s = session
        if (s == null) {
            Log.w(TAG, "AAP 会话已不存在，跳过视频管线启动")
            return
        }
        if (keepPhoneWindows) {
            // 唤醒自愈重建：只拆视频管线，保留悬浮球/镜像窗（重建完 attach 走软重连，
            // 镜像窗由 DisplayWindow.onResume 重新登记 mirror，用户视角窗口不闪）。
            stopStreamingVideo()
        } else {
            teardownVideo()
        }
        // teardownVideo 期间会话可能已被 stop()/倒计时收掉（stop() 先置 session=null），
        // 再核一次；videoChannel 属于当前会话的服务发现，绝不在 teardown 里清空——
        // 会话内重建视频（息屏唤醒）还要用它，清了会让后续帧静默丢弃。
        if (session !== s) {
            Log.w(TAG, "视频管线启动期间会话已切换，放弃")
            return
        }
        val (width, height) = resolution(config)
        videoWidth = width
        videoHeight = height
        val fps = fpsOf(config)
        val carDpi = if (config.density > 0) config.density.toInt() else AapConsts.FALLBACK_DPI
        // 与原版路线共用「虚拟屏 DPI」；0/留空时跟随车机（AAP 默认行为）。
        val dpi = virtualDisplayDpiOverride() ?: carDpi
        if (dpi != carDpi) Log.i(TAG, "虚拟屏 DPI 覆盖：车机=$carDpi → $dpi")
        // 约 0.1 bpp：800x480@30 约 1.2 Mbps，1280x720@30 约 2.8 Mbps，够车机小屏用。
        val bitrate = (width * height * fps / 10).coerceIn(1_000_000, 8_000_000)

        val channel = videoChannel ?: return
        lastVideoConfig = config
        keepScreenAwake()
        // 路线 B：虚拟屏由本进程创建（system_server 只做任务托管/输入/镜像）。
        attachAppOwnedDisplay(width, height, dpi, fps, bitrate, channel)
    }

    /**
     * displayId 就绪后的公共处理。
     *
     * AAP 通道复用 system_server 的 DisplayWindow 作手机端悬浮窗（沿用原版）；
     * 这里只记录日志，窗口/任务由 system_server 的 onExternalDisplayAttached 负责。
     */
    private fun handleDisplayReady(displayId: Int, create: Boolean, width: Int, height: Int, dpi: Int) {
        Log.i(
            TAG,
            if (displayId == Display.INVALID_DISPLAY) "虚拟屏创建失败"
            else "虚拟屏就绪 displayId=$displayId create=$create"
        )
        if (displayId == Display.INVALID_DISPLAY) return
        // 手机端悬浮窗/任务托管已交给 system_server（沿用原版 DisplayWindow）。
    }

    /**
     * 非 H.264 编码不可用时，标记并断开当前会话；服务会自动用 H.264 重连。
     * 只回退一次，避免在“车机宣告了 H.265 但本机编不出来”时无限重连。
     */
    private fun fallbackToH264(reason: String) {
        if (videoCodecType == MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP || forceH264Fallback) return
        forceH264Fallback = true
        Log.w(TAG, "$reason：$videoCodecType 不可用，自动回退 H.264 并重连")
        Thread({
            runCatching { session?.stop() }
            runCatching { transport?.close() }
        }, "aap-codec-fallback").start()
    }

    /**
     * 路线 B：本进程 createVirtualDisplay（flags PUBLIC|OWN_CONTENT_ONLY，GlVdTest 实测组合），
     * 输出面接 GL 消费者 → 编码器，再把 displayId 交给 system_server 做任务托管/输入注入/镜像。
     */
    private fun attachAppOwnedDisplay(
        width: Int,
        height: Int,
        dpi: Int,
        fps: Int,
        bitrate: Int,
        channel: VideoChannel,
    ) {
        Log.i(TAG, "路线 B：虚拟屏由本进程创建（aad_exp_ext_vd≠0），system_server 只做任务托管/输入/镜像")
        val ctx = appContext
        if (ctx == null) {
            Log.e(TAG, "attachAppOwnedDisplay: appContext 为空，跳过")
            return
        }
        // [A1] 入口守卫：调用方 startVideo 已检查，这里再核一次（建屏要数秒）。
        if (session == null) {
            Log.w(TAG, "AAP 会话已不存在，跳过自建虚拟屏")
            return
        }
        val enc = AapVideoEncoder(
            width = width,
            height = height,
            fps = fps,
            bitrate = bitrate,
            mime = mimeFor(videoCodecType),
            onCodecConfig = { data -> channel.sendCodecConfig(data, 0, data.size) },
            onFrame = { buf, offset, length, ptsUs, key ->
                channel.sendFrame(buf, offset, length, ptsUs, key) { }
            },
        )
        encoder = enc
        runCatching { enc.start() }
            .onFailure {
                Log.e(TAG, "启动编码器失败（${mimeFor(videoCodecType)}）", it)
                encoder = null
                // 非 H.264 编码器在本机不可用：标记后断开，让服务用 H.264 重连。
                fallbackToH264("编码器启动失败")
                return
            }
        val gl = AapGlVideoConsumer(width, height, enc.inputSurface)
        try {
            gl.start()
            glConsumer = gl
        } catch (t: Throwable) {
            Log.e(TAG, "GL 消费者启动失败，编码器先停掉", t)
            runCatching { gl.stop() }
            runCatching { enc.stop() }
            encoder = null
            return
        }
        val glSurface = gl.surface
        if (glSurface == null) {
            Log.e(TAG, "GL surface 为空，跳过本次视频")
            runCatching { gl.stop() }
            runCatching { enc.stop() }
            encoder = null
            return
        }
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        // flags 矩阵实测（2026-11-21）：
        //  - 带 PUBLIC(1)：bit5 被 sanitize 剥掉/或直接 IllegalArgumentException（public 屏禁止 SHOW_WHEN_LOCKED_INSECURE）；
        //  - 去 PUBLIC 后 bit5 存活（DisplayDeviceInfo 出现 FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD）；
        //  - bit12(ALWAYS_UNLOCKED) 任何组合下都被 sanitize 剥掉，App uid 留不住（legacy system_server VD 才有）。
        // 超时熄屏由亮屏锁（keepScreenAwake）挡住。
        val extFlags = 0x102A
        Log.i(TAG, "App 自建虚拟屏 flags=0x${Integer.toHexString(extFlags)}")
        val vd = runCatching {
            dm.createVirtualDisplay(
                "AADisplay-AAP-${System.currentTimeMillis()}",
                width, height, dpi, glSurface, extFlags,
            )
        }.onFailure { Log.e(TAG, "App 自建虚拟屏失败", it) }.getOrNull()
        if (vd == null) {
            runCatching { gl.stop() }
            runCatching { enc.stop() }
            encoder = null
            return
        }
        appVirtualDisplay = vd
        val extId = vd.display.displayId
        Log.i(TAG, "App 自建虚拟屏就绪 displayId=$extId ${width}x$height,$dpi")
        // [A1] 管线建好了，但会话可能在这数秒窗口里结束——别把 displayId 报给死会话的宿主，
        // 也不留一条没人消费的管线。按 stopStreamingVideo 同款顺序拆：先摘 surface、停 GL/编码器，再释放。
        if (session == null) {
            Log.w(TAG, "AAP 会话已不存在，立即释放刚建的自建虚拟屏")
            appVirtualDisplay = null
            runCatching { vd.surface = null }
            runCatching { gl.stop() }
            runCatching { enc.stop() }
            encoder = null
            runCatching { vd.release() }
            return
        }

        // system_server 只接管这块外部屏的任务/输入/镜像与手机端 DisplayWindow。
        CoreApi.onExternalDisplayAttached(extId, width, height, dpi, object : IVirtualDisplayCreatedListener.Stub() {
            override fun onAvailableDisplay(displayId: Int, create: Boolean) {
                handleDisplayReady(displayId, create, width, height, dpi)
            }
        })
    }

    @Synchronized
    private fun teardownVideo() {
        stopStreamingVideo()
    }

    /**
     * 只停视频管线（编码器 + 虚拟屏），保留手机端悬浮球/镜像窗。
     *
     * 会话断开以后没人再消费虚拟屏的画面，硬件编码器的输入队列会塞满，
     * 虚拟屏的生产端跟着卡住，SurfaceFlinger 合成就会永久阻塞（实测整机冻结、
     * system_server 被 Watchdog 杀掉）。所以断开的第一时间就要把这条管线拆掉，
     * 悬浮窗本身留着走延迟销毁倒计时。
     */
    @Synchronized
    private fun stopStreamingVideo() {
        // 顺序很重要：先让虚拟屏摘掉输出面并释放，再停编码器。
        // 反过来会让虚拟屏攥着一个已被销毁 buffer 生产者的 surface，
        // SurfaceFlinger 合成线程会一直等帧，把整机拖死。
        releaseScreenAwake()
        runCatching { CoreApi.onDestroyDisplay() }
        // 路线 B：先摘本进程虚拟屏的输出面（SF 是生产者，先停消费者会让它等帧），再停 GL/编码器，
        // 最后释放虚拟屏。App 自建的 VD 不会楔死系统，但保持同样的拆卸顺序最稳。
        val appVd = appVirtualDisplay
        appVirtualDisplay = null
        appVd?.let { runCatching { it.surface = null } }
        // GL 消费者先停（它持有 SurfaceTexture 与编码器 surface 的 EGL 绑定），再停编码器。
        val gl = glConsumer
        glConsumer = null
        gl?.let { runCatching { it.stop() } }
        encoder?.let {
            runCatching { it.stop() }
            encoder = null
        }
        appVd?.let {
            runCatching { it.release() }
            Log.i(TAG, "App 自建虚拟屏已释放")
        }
    }

    private fun resolution(config: VideoConfiguration): Pair<Int, Int> =
        when (config.codecResolution) {
            VideoCodecResolutionType.VIDEO_800x480 -> 800 to 480
            VideoCodecResolutionType.VIDEO_1280x720 -> 1280 to 720
            VideoCodecResolutionType.VIDEO_1920x1080 -> 1920 to 1080
            VideoCodecResolutionType.VIDEO_2560x1440 -> 2560 to 1440
            VideoCodecResolutionType.VIDEO_3840x2160 -> 3840 to 2160
            VideoCodecResolutionType.VIDEO_720x1280 -> 720 to 1280
            VideoCodecResolutionType.VIDEO_1080x1920 -> 1080 to 1920
            VideoCodecResolutionType.VIDEO_1440x2560 -> 1440 to 2560
            VideoCodecResolutionType.VIDEO_2160x3840 -> 2160 to 3840
            else -> AapConsts.FALLBACK_WIDTH to AapConsts.FALLBACK_HEIGHT
        }

    private fun fpsOf(config: VideoConfiguration): Int =
        if (config.frameRate == VideoFrameRateType.VIDEO_FPS_60) 60 else AapConsts.FALLBACK_FPS
}
