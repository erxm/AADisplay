package io.github.nitsuya.aa.display.xposed.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import io.github.nitsuya.aa.display.xposed.log

/**
 * 让模块 App 直接启停 Android Auto 的开发者车机服务器（5277）。
 *
 * AA 的 `DeveloperHeadUnitNetworkService` 有两个限制：
 * - `exported=false`：模块 App 自己 `startService` 会报
 *   `Requires permission not exported from uid ...`；
 * - 它跑在 `com.google.android.projection.gearhead:projection`，`onBind()` 返回 null。
 *
 * 所以在 gearhead 进程内注册一个动态广播，由**同 UID** 的进程替 AA 的开发者设置点一下开关：
 * - 启动：显式组件、不带 action（AA 设置里 `startService(new Intent(context, ...))` 的等价路径）；
 * - 停止：`action = "shutdown"`（AA 设置里的停止路径，`onStartCommand` 会关掉 ServerSocket）。
 *
 * 后台启动前台服务在 Android 12+ 可能被拦（ForegroundServiceStartNotAllowedException），
 * 这时会把失败结果回传给 App，由 App 用 root shell 兜底。
 */
object HeadUnitServerBridge {
    private const val TAG = "AAD_HeadUnitServer"
    private const val AA_PACKAGE = "com.google.android.projection.gearhead"
    private const val MODULE_PACKAGE = "io.github.nitsuya.aa.display"
    private const val SERVICE_CLASS =
        "com.google.android.projection.gearhead.companion.DeveloperHeadUnitNetworkService"

    /** 模块 App → gearhead 的广播必须由持有该 permission 的发送方发出（manifest 里是 signature 级）。 */
    const val BROADCAST_PERMISSION = "io.github.nitsuya.aa.display.permission.HEAD_UNIT_SERVER"

    /** 模块 App → gearhead：启动 / 停止。 */
    const val ACTION_START = "io.github.nitsuya.aa.display.action.START_HEAD_UNIT_SERVER"
    const val ACTION_STOP = "io.github.nitsuya.aa.display.action.STOP_HEAD_UNIT_SERVER"

    /** gearhead → 模块 App：执行结果。 */
    const val ACTION_RESULT = "io.github.nitsuya.aa.display.action.HEAD_UNIT_SERVER_RESULT"
    const val EXTRA_STOP = "stop"
    const val EXTRA_SUCCESS = "success"
    const val EXTRA_MESSAGE = "message"

    private val registerLock = Any()

    @Volatile
    private var registered = false

    /**
     * 动态注册在 gearhead 进程里，必须限制发送方。
     *
     * 只校验 `intent.getPackage()` 是没用的（任何应用都能 `setPackage`）；API 34 之前也拿不到
     * 系统填充的发送方包名。所以注册时声明 [BROADCAST_PERMISSION]（signature 级）：发送方
     * 必须持有该权限，只有本模块签名的应用能满足，且从 minSdk 31 起全版本有效。
     */
    fun register(appContext: Context) {
        synchronized(registerLock) {
            if (registered) return
            registered = true
        }
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.action
                    if (action != ACTION_START && action != ACTION_STOP) return
                    // 发送方已由 [BROADCAST_PERMISSION] 在 AMS 层过滤，这里只留诊断信息。
                    log(TAG, "head unit broadcast: action=$action pkg=${intent.`package`}")
                    val stop = action == ACTION_STOP
                    val result = runCatching {
                        val target = Intent().setClassName(AA_PACKAGE, SERVICE_CLASS)
                        if (stop) {
                            target.action = "shutdown"
                            context.startService(target)
                        } else {
                            startServerService(context, target)
                        }
                    }
                    val ok = result.isSuccess
                    log(
                        TAG,
                        "head unit server ${if (stop) "stop" else "start"} in pid ${Process.myPid()}: ok=$ok" +
                            (result.exceptionOrNull()?.let { " error=$it" } ?: "")
                    )
                    // 有序广播：第一个成功的进程直接中止，避免每个 gearhead 进程都启动一遍。
                    if (ok) runCatching { abortBroadcast() }
                    runCatching {
                        context.sendBroadcast(
                            Intent(ACTION_RESULT).apply {
                                setPackage(MODULE_PACKAGE)
                                putExtra(EXTRA_STOP, stop)
                                putExtra(EXTRA_SUCCESS, ok)
                                result.exceptionOrNull()?.message?.let { putExtra(EXTRA_MESSAGE, it) }
                            }
                        )
                    }
                }
            }
            val filter = IntentFilter(ACTION_START).apply { addAction(ACTION_STOP) }
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                BROADCAST_PERMISSION,
                null,
                ContextCompat.RECEIVER_EXPORTED
            )
            log(TAG, "receiver registered: pid=${Process.myPid()}")
        }.onFailure {
            synchronized(registerLock) { registered = false }
            log(TAG, "receiver register failed", it)
        }
    }

    private fun startServerService(context: Context, target: Intent) {
        // AA 自己的服务在 onCreate 里就 startForeground()，正常路径用 startForegroundService。
        // 后台限制下若被拒，再退回 startService 试一次（部分 ROM 只拦其中一条）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.startForegroundService(target) }
                .getOrElse { context.startService(target) }
        } else {
            context.startService(target)
        }
    }
}
