package io.github.nitsuya.aa.display.aap

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.nitsuya.aa.display.BuildConfig

/**
 * USB accessory 插入时的入口：拿到 accessory 授权后把 AAP 出口拉起来。
 *
 * 这个 Activity 没有界面（透明主题），只是 USB 权限流程需要有个前台组件来接收回调。
 */
class AapUsbAttachActivity : Activity() {

    private companion object {
        const val TAG = AapConsts.TAG
        const val ACTION_USB_PERMISSION = "io.github.nitsuya.aa.display.local.aap.USB_PERMISSION"
    }

    private var accessory: UsbAccessory? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Log.i(TAG, "accessory 授权结果：granted=$granted")
            if (granted) AapEgressService.start(this@AapUsbAttachActivity)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 16+ 的"本地网络"权限（AARE 提醒：不授权无线/本地 TCP 会静默丢连接）。
        // normal 级声明即授予，这里只是兜底；fire-and-forget，不阻断后续流程。
        if (android.os.Build.VERSION.SDK_INT >= 36 &&
            ContextCompat.checkSelfPermission(this, "android.permission.ACCESS_LOCAL_NETWORK") !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { requestPermissions(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"), 1) }
        }
        // 调试入口（仅 debug 构建）：am start -n .../.aap.AapUsbAttachActivity --ei aap_tcp_port 5278
        // 之后 adb forward tcp:5277 tcp:5278，DHU 连 localhost:5277 就能打到这台手机上的 AAP 栈。
        val tcpPort = intent?.getIntExtra(AapConsts.EXTRA_TCP_PORT, -1) ?: -1
        if (tcpPort > 0) {
            if (!BuildConfig.DEBUG) {
                Log.w(TAG, "TCP 调试入口只在 debug 构建里可用")
                finish()
                return
            }
            startForegroundService(
                Intent(this, AapEgressService::class.java)
                    .setAction(AapConsts.ACTION_START_TCP)
                    .putExtra(AapConsts.EXTRA_TCP_PORT, tcpPort),
            )
            finish()
            return
        }
        val manager = getSystemService(UsbManager::class.java)
        // 路线闸门（放在最前）：原版路线（hook gearhead）下插上 AA 车机交给 Android Auto 自己处理，
        // 不申请 accessory 权限、不启动 AAP 通道。
        if (AapEgressService.route(this) != AapConsts.ROUTE_AAP) {
            Log.i(TAG, "当前路线为原版，USB accessory 交给 Android Auto 处理")
            finish()
            return
        }
        accessory = intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
            ?: AapEgressService.findAapAccessory(manager)
        val acc = accessory
        if (acc == null) {
            Log.w(TAG, "attach 回调里没有 accessory")
            finish()
            return
        }
        ContextCompat.registerReceiver(
            this, permissionReceiver, IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (manager.hasPermission(acc)) {
            AapEgressService.start(this)
            finish()
            return
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        manager.requestPermission(
            acc,
            PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags),
        )
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(permissionReceiver) }
        super.onDestroy()
    }
}
