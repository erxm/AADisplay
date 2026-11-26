package io.github.nitsuya.aa.display.xposed.hook.aa

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.hook.HookTarget
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.logDebug
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Coolwalk 空媒体卡就是 **Dashboard** presentation（800×480 车机上约 304×460），
 * 显示“无法获取媒体内容”。
 *
 * name=Dashboard 的 VD starve 在 [AaCoolwalkCompositorHook] / [CoolwalkCompositorPolicy]
 * （create / Builder / resize）里。这里只做两件事：
 *
 * 1. 保持 [MEDIA_CAR_APP] **启用**，让 AA Now Playing 元数据仍能输出到车机；
 * 2. 隐藏残留的 Dashboard Presentation 窗口，并在 `content_bounds` 扩宽之后
 *    吞掉 Coolwalk 自己的 Dashboard 覆盖断言。
 *
 * 第 2 点是隐藏 UI 控件栈（[AaUiHook.hideAaUiControls]）的必需伴生守卫：
 * 内容窗口被扩到整块车机宽度后，兄弟 Dashboard 窗口仍保留原右侧几何，
 * AA 在 `:car` 里断言 `does not mostly cover or provide a hint for one side of`
 * 并抛 [IllegalStateException] 杀掉 `:car`，`:projection` 随后因死 binder 一并崩溃
 * （连接 DHU 时表现为 Android Auto 闪退）。
 *
 * 绝不隐藏 [io.github.nitsuya.aa.display.ui.aa.AaDisplayActivity] 的投屏窗口。
 */
object AaMediaPlaceholderHook : AaHook() {
    override val tagName: String = "AAD_AaMediaPlaceholderHook"

    private const val MEDIA_CAR_APP =
        "com.google.android.apps.auto.components.media.app.MediaCarAppService"

    private var insetAssertMethods: List<Method> = emptyList()

    /** 与 [AaUiHook] 同一个开关：只有隐藏 UI 控件栈开启时才需要这个守卫。 */
    private var hideAaUiControls: Boolean = true

    override fun isSupportProcess(processName: String): Boolean {
        return processProjection == processName || processCar == processName
    }

    override fun isEnabled(config: SharedPreferences?, processName: String): Boolean {
        hideAaUiControls = AADisplayConfig.HideAaUiControls.get(config)
        return hideAaUiControls
    }

    override fun loadDexClass(bridge: DexKitBridge, target: HookTarget) {
        insetAssertMethods = listOf(
            "does not mostly cover or provide a hint for one side of",
            "does not fully cover",
        ).flatMap { needle ->
            runCatching {
                bridge.findMethod {
                    matcher { usingStrings(needle) }
                }.mapNotNull { md ->
                    runCatching { md.getMethodInstance(target.classLoader) }.getOrNull()
                }
            }.onFailure { e ->
                log(tagName, "DexKit inset assert query failed: $needle", e)
            }.getOrDefault(emptyList())
        }.distinctBy {
            "${it.declaringClass.name}#${it.name}#${it.parameterTypes.joinToString { p -> p.name }}"
        }
        log(
            tagName,
            "inset assert methods=${insetAssertMethods.size} " +
                insetAssertMethods.map { "${it.declaringClass.name}#${it.name}" },
        )
    }

    override fun hook(config: SharedPreferences?, target: HookTarget) {
        ensureMediaCarAppEnabled()
        hideDashboardOnAddView()
        suppressDashboardCoverAssert()
    }

    /**
     * 车机媒体宿主需要保持可用。旧版本曾禁用该组件，导致 Now Playing 完全断流。
     */
    private fun ensureMediaCarAppEnabled() {
        runCatching {
            val ctx: Context = InitFields.appContext
            val cn = ComponentName(ctx.packageName, MEDIA_CAR_APP)
            val pm = ctx.packageManager
            val state = pm.getComponentEnabledSetting(cn)
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            ) {
                logDebug(tagName, "MediaCarApp already enabled/default state=$state")
                return
            }
            pm.setComponentEnabledSetting(
                cn,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            logDebug(tagName, "re-enabled $MEDIA_CAR_APP (was state=$state)")
        }.onFailure { e ->
            log(tagName, "ensure $MEDIA_CAR_APP enabled failed", e)
        }
    }

    /** 原始 inset 逻辑照常执行；只吞掉 Dashboard 覆盖断言的 [IllegalStateException]。 */
    private fun suppressDashboardCoverAssert() {
        if (insetAssertMethods.isEmpty()) {
            log(tagName, "no Coolwalk inset-assert methods; cover crash not guarded")
            return
        }
        var hooked = 0
        for (method in insetAssertMethods) {
            try {
                XposedBridge.hookMethod(method, object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any? {
                        return try {
                            XposedBridge.invokeOriginalMethod(
                                param.method,
                                param.thisObject,
                                param.args,
                            )
                        } catch (e: InvocationTargetException) {
                            val cause = e.cause ?: e
                            if (!isDashboardCoverAssert(cause)) throw cause
                            logDebug(
                                tagName,
                                "suppress ${method.declaringClass.simpleName}#${method.name} " +
                                    "Dashboard cover assert",
                            )
                            dummyInsetResult(method)
                        }
                    }
                })
                hooked++
            } catch (e: Throwable) {
                log(tagName, "hook inset ${method.declaringClass.name}#${method.name} failed", e)
            }
        }
        log(tagName, "hooked inset assert methods=$hooked/${insetAssertMethods.size}")
    }

    private fun isDashboardCoverAssert(t: Throwable): Boolean {
        if (t !is IllegalStateException) return false
        val msg = t.message ?: return false
        if (!msg.contains("Dashboard")) return false
        return msg.contains("does not mostly cover") || msg.contains("does not fully cover")
    }

    private fun dummyInsetResult(method: Method): Any? {
        val rt = method.returnType
        if (rt == Void.TYPE || rt == Void::class.java) return null
        if (rt == Rect::class.java) return Rect()
        return null
    }

    private fun hideDashboardOnAddView() {
        try {
            findMethod(Class.forName("android.view.WindowManagerGlobal")) {
                name == "addView" && parameterCount >= 2
            }.hookBefore { param ->
                val root = param.args[0] as? View ?: return@hookBefore
                val lp = param.args.getOrNull(1) as? WindowManager.LayoutParams
                if (!isDashboardWindow(lp, root)) return@hookBefore
                hideDashboard(root, lp)
            }
        } catch (e: Throwable) {
            log(tagName, "hook WindowManagerGlobal.addView failed", e)
        }
    }

    private fun isDashboardWindow(lp: WindowManager.LayoutParams?, root: View): Boolean {
        val title = lp?.title?.toString().orEmpty()
        if (title.contains("Dashboard", ignoreCase = true)) return true
        // 歌词/媒体浏览残留窗口 —— 绝不命中 AaDisplayActivity 投屏。
        if (!title.contains("AaDisplayActivity", ignoreCase = true) &&
            !title.contains("AaMainFragment", ignoreCase = true)
        ) {
            if (title.contains("ClusterLyric", ignoreCase = true)) return true
            if (title.contains("MediaBrowse", ignoreCase = true) &&
                title.contains(BuildConfig.APPLICATION_ID, ignoreCase = true)
            ) {
                return true
            }
        }
        if (lp != null && lp.type == 2030 /* TYPE_PRIVATE_PRESENTATION */) {
            val w = lp.width
            val h = lp.height
            if (w in 200..400 && h in 360..520 && h > w) return true
        }
        return root.javaClass.name.contains("Dashboard", ignoreCase = true)
    }

    private fun hideDashboard(root: View, lp: WindowManager.LayoutParams?) {
        root.alpha = 0f
        root.visibility = View.GONE
        root.isClickable = false
        root.isFocusable = false
        if (root is ViewGroup) {
            root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        if (lp != null) {
            lp.width = 0
            lp.height = 0
            lp.alpha = 0f
        }
        logDebug(tagName, "hide Dashboard title=${lp?.title}")
    }
}
