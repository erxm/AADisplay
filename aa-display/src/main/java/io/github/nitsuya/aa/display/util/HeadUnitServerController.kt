package io.github.nitsuya.aa.display.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.topjohnwu.superuser.Shell
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.xposed.hook.HeadUnitServerBridge
import io.github.nitsuya.template.bases.runIO
import io.github.nitsuya.template.bases.runMain
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模块 App 侧启停 AA 车机服务器（5277）。
 *
 * 优先走 gearhead 进程内的 [HeadUnitServerBridge]（有序广播，服务端 abort 去重）；
 * 2.5 秒内没有进程回结果（AA 进程没起来），或 hook 明确失败（后台上限），
 * 再用 root shell 直接 `am start-foreground-service`。
 *
 * 注意：普通 `am startservice` 即使用 root 也会被
 * `Background start not allowed ... uid=0 pkg=com.android.shell` 拒绝，
 * 必须用 foreground-service 版本（root 调用被记为 SYSTEM_UID，放行）。
 */
object HeadUnitServerController {
    private const val TAG = "AAD_HeadUnitServer"
    private const val AA_PACKAGE = "com.google.android.projection.gearhead"
    private const val MODULE_PACKAGE = "io.github.nitsuya.aa.display"
    private const val SERVICE_CLASS =
        "com.google.android.projection.gearhead.companion.DeveloperHeadUnitNetworkService"
    private const val FALLBACK_DELAY_MS = 2500L

    private val handler = Handler(Looper.getMainLooper())

    /** 同一时刻只允许一次请求：连点开关不该叠加多份 receiver / fallback / root 命令。 */
    private val inFlight = AtomicBoolean(false)

    /**
     * 启停车机服务器；拿到结果后在主线程回调 [onResult]。
     * 上一次请求还没结束时直接忽略，不再回调（避免把“已启动”当成真实结果）。
     */
    fun request(context: Context, stop: Boolean, onResult: (String) -> Unit) {
        val app = context.applicationContext
        if (!inFlight.compareAndSet(false, true)) {
            Log.w(TAG, "request ignored: previous request still in flight")
            return
        }
        var finished = false
        var receiver: BroadcastReceiver? = null
        var fallback: Runnable? = null
        /** hook 报失败后是否已经走过 root 兜底：gearhead 多个进程各回一条失败时只执行一次。 */
        var rootAttempted = false

        fun finish(message: String) {
            if (finished) return
            finished = true
            inFlight.set(false)
            fallback?.let { handler.removeCallbacks(it) }
            receiver?.let { runCatching { app.unregisterReceiver(it) } }
            onResult(message)
        }

        /**
         * hook 明确失败（例如后台拉起前台服务被拦）→ 立刻走 root，并撤掉 2.5s 定时兜底，
         * 否则慢一点的 root shell 会让兜底再跑第二遍 `am start-foreground-service`。
         * 这里不先 [finish]：最终结果必须来自 root。
         */
        fun rootInsteadOfHook() {
            if (rootAttempted) return
            rootAttempted = true
            fallback?.let { handler.removeCallbacks(it) }
            requestViaRoot(app, stop) { finish(it) }
        }

        val resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != HeadUnitServerBridge.ACTION_RESULT) return
                // 结果广播是导出的：伪造的失败结果会触发 root 兜底，必须校验发送方。
                // getSentFromPackage 是 BroadcastReceiver 的方法（不在 Intent 上），
                // 且实测可能返回 null，所以只在非空时作为强校验。
                val fromPackage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    sentFromPackage
                } else {
                    null
                }
                if (!isFromGearhead(intent, fromPackage)) return
                val ok = intent.getBooleanExtra(HeadUnitServerBridge.EXTRA_SUCCESS, false)
                if (ok) {
                    finish(
                        app.getString(
                            if (stop) R.string.head_unit_stopped else R.string.head_unit_started
                        )
                    )
                } else {
                    // hook 明确失败（例如后台拉起前台服务被拦）：直接 root 兜底。
                    rootInsteadOfHook()
                }
            }
        }
        receiver = resultReceiver

        runCatching {
            ContextCompat.registerReceiver(
                app,
                resultReceiver,
                IntentFilter(HeadUnitServerBridge.ACTION_RESULT),
                ContextCompat.RECEIVER_EXPORTED
            )
        }.onFailure {
            rootInsteadOfHook()
            return
        }

        val action = if (stop) HeadUnitServerBridge.ACTION_STOP else HeadUnitServerBridge.ACTION_START
        runCatching {
            app.sendOrderedBroadcast(Intent(action).setPackage(AA_PACKAGE), null)
        }.onFailure {
            // 广播都发不出去：直接 root。
            rootInsteadOfHook()
            return
        }

        val rootFallback = Runnable { rootInsteadOfHook() }
        fallback = rootFallback
        handler.postDelayed(rootFallback, FALLBACK_DELAY_MS)
    }

    /**
     * 结果广播只有 gearhead 会发。
     *
     * API 34+ 用系统填充的发送方包名做强校验；系统没给（实测有 null 的情况）时，
     * 退一步要求广播显式发给本模块（Bridge 用 setPackage(MODULE_PACKAGE)）。
     * API 34 以下没有发送方包名，同样用目标包名做弱校验。
     */
    private fun isFromGearhead(intent: Intent, fromPackage: String?): Boolean {
        val trusted = if (fromPackage != null) {
            fromPackage == AA_PACKAGE
        } else {
            intent.getPackage() == MODULE_PACKAGE
        }
        if (!trusted) {
            Log.w(
                TAG,
                "ignore result from untrusted sender: pkg=${intent.getPackage()} from=$fromPackage"
            )
            return false
        }
        return true
    }

    private fun requestViaRoot(context: Context, stop: Boolean, onResult: (String) -> Unit) {
        runIO {
            val component = "$AA_PACKAGE/$SERVICE_CLASS"
            val cmd = if (stop) {
                "am start-foreground-service -n $component -a shutdown"
            } else {
                "am start-foreground-service -n $component"
            }
            val result = runCatching { Shell.cmd(cmd).exec() }.getOrNull()
            val output = (result?.out.orEmpty() + result?.err.orEmpty())
                .filter { it.isNotBlank() }
                .joinToString("\n")
            // 用退出码判断：输出里出现 "Error" 的字符串判定太脆（服务本来就在跑也会走到这里）。
            val ok = result?.isSuccess == true
            val message = if (ok) {
                context.getString(if (stop) R.string.head_unit_stopped else R.string.head_unit_started)
            } else {
                context.getString(
                    R.string.head_unit_failed,
                    output.ifBlank { context.getString(R.string.unknown_error) }
                )
            }
            runMain { onResult(message) }
        }
    }
}
