package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.getIdByName
import com.github.kyuubiran.ezxhelper.utils.loadClass
import com.github.kyuubiran.ezxhelper.utils.staticMethod
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.logDebug
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Shared mutable hook state for Coolwalk / facet chrome submodules in one gearhead process.
 */
class CoolwalkHookEnv {

    var layoutInfoConstructors: List<Constructor<*>> = emptyList()
    var startMethod: Method? = null

    /**
     * 17.8+：启动入口是 `ProjectionContext` 单例上的实例方法，需要同名类里
     * 返回自身的无参静态 getter 才能拿到 receiver。≤17.4 的 SysUi 静态方法为 null。
     */
    var startSingletonGetter: Method? = null
    @Volatile var hasVerticalRailField: Field? = null
    @Volatile var layoutResourceIdField: Field? = null
    var contentBoundsMethods: List<Method> = emptyList()
    var facetBarSurfaceMethods: List<Method> = emptyList()
    /** Coolwalk compositor window bounds class (`{blX=` toString); may be null if DexKit miss. */
    var projectionBoundsClassName: String? = null

    var resLayoutLeftResourceId: Int = 0
    var resLayoutRightResourceId: Int = 0
    var resIdStatusBarId: Int = 0
    var resIdLauncherAndDashboardIconContainerId: Int = 0
    var resIdLauncherAndDashboardIconId: Int = 0
    val facetBarLayoutIds = mutableSetOf<Int>()
    val railHostLayoutIds = mutableSetOf<Int>()
    val railWidthDimenIds = mutableSetOf<Int>()
    var canHookFacetBar: Boolean = false
    var mInjectingFacetBar: Boolean = false
    val facetBarInjectedTag = Any()
    val mFacetEnsureHandler = Handler(Looper.getMainLooper())
    var mFacetEnsureDeadlineMs = 0L
    val mReclaimFollowUps = java.util.WeakHashMap<android.view.View, Runnable>()

    var mLayoutWidthDp: Int = 0
    var mLayoutHeightDp: Int = 0
    @Volatile var mLoggedDashboardStarve = false
    @Volatile var mLastProjectionConfigRewrite: String? = null

    var mAutoOpenSessionAtMs = 0L
    /** 对应 AADisplayConfig.AutoOpen：false 时不主动拉起 AADisplay，只做几何调整。 */
    var autoOpenEnabled: Boolean = true
    /** 对应 AADisplayConfig.ForceRightAngle：仅控制圆角（decoration radius），不影响左右留白回收。 */
    var forceRightAngle: Boolean = true
    @Volatile var mAaDisplayShownThisSession = false
    @Volatile var mAutoOpenArmed = false
    var mAutoOpenShownReceiver: android.content.BroadcastReceiver? = null
    var mCarConnectedKickHooked = false
    var mCarConnectedListenerHooked = false

    fun layoutWidthPx(): Int =
        kotlin.math.max(
            mLayoutWidthDp,
            kotlin.math.max(CoolwalkRailCoordinator.layoutWidthPx(), CoolwalkRailCoordinator.observedFullHuWidthPx()),
        ).takeIf { it > 0 } ?: 0

    fun layoutHeightPx(): Int =
        kotlin.math.max(
            mLayoutHeightDp,
            CoolwalkRailCoordinator.layoutHeightPx(),
        ).takeIf { it > 0 } ?: 0

    fun logProjectionConfigRewriteOnce(message: String) {
        if (mLastProjectionConfigRewrite == message) return
        mLastProjectionConfigRewrite = message
        logDebug(TAG, message)
    }

    fun loadProjectionResources() {
        val pkg = InitFields.appContext.packageName
        val res = InitFields.appContext.resources
        fun layoutId(name: String): Int = res.getIdentifier(name, "layout", pkg).also { id ->
            if (id != 0) facetBarLayoutIds.add(id)
        }

        layoutId("gh_coolwalk_vertical_facet_bar")
        layoutId("gh_coolwalk_facet_bar")
        layoutId("gh_coolwalk_facet_bar_rhd")
        layoutId("gh_coolwalk_vertical_facet_bar_rhd")

        resIdStatusBarId = getIdByName("status_bar")
        resIdLauncherAndDashboardIconContainerId = getIdByName("launcher_and_dashboard_icon_container")
        resIdLauncherAndDashboardIconId = getIdByName("launcher_and_dashboard_icon")

        resLayoutLeftResourceId = res.getIdentifier("sys_ui_layout_canonical_vertical_rail_lhd", "layout", pkg)
        resLayoutRightResourceId = res.getIdentifier("sys_ui_layout_canonical_vertical_rail_rhd", "layout", pkg)
        if (resLayoutLeftResourceId != 0) railHostLayoutIds.add(resLayoutLeftResourceId)
        if (resLayoutRightResourceId != 0) railHostLayoutIds.add(resLayoutRightResourceId)

        for (name in RAIL_WIDTH_DIMEN_NAMES) {
            val id = res.getIdentifier(name, "dimen", pkg)
            if (id != 0) railWidthDimenIds.add(id)
        }

        if (resLayoutLeftResourceId == 0) {
            log(TAG, "AaUiHook: LHD canonical layout missing; still forcing hasVerticalRail=true")
        }
        canHookFacetBar =
            facetBarLayoutIds.isNotEmpty() &&
            resIdStatusBarId != 0 &&
            resIdLauncherAndDashboardIconContainerId != 0 &&
            resIdLauncherAndDashboardIconId != 0
        if (!canHookFacetBar) {
            log(
                TAG,
                "AaUiHook: skip facet-bar override, missing resources: facetIds=$facetBarLayoutIds, " +
                    "status=$resIdStatusBarId, launcherContainer=$resIdLauncherAndDashboardIconContainerId, " +
                    "launcherIcon=$resIdLauncherAndDashboardIconId",
            )
        } else {
            logDebug(TAG, "AaUiHook: facet layout ids=$facetBarLayoutIds railHosts=$railHostLayoutIds")
        }
        logDebug(TAG, "AaUiHook: rail width dimens=$railWidthDimenIds")
    }

    fun resolveLayoutInfoConstructors(className: String): List<Constructor<*>> {
        val clazz = loadClass(className)
        val matched = clazz.declaredConstructors.filter { ctor ->
            isLayoutInfoConstructor(ctor.parameterTypes)
        }
        if (matched.isEmpty()) {
            throw NoSuchMethodException("AaUiHook: not found compatible LayoutInfo constructor for $className")
        }
        for (ctor in matched) {
            ctor.isAccessible = true
        }
        val primary = matched.first()
        log(
            TAG,
            "AaUiHook: LayoutInfo constructor selected, paramCount=${primary.parameterCount}, " +
                "layoutTypeArg=${primary.parameterTypes[3].name}, matches=${matched.size}",
        )
        return matched
    }

    fun resolveCarStartActivityMethod(): Method? {
        val clazz = try {
            loadClass("com.google.android.projection.gearhead.service.CarSystemUiControllerService")
        } catch (e: Throwable) {
            // AA 17.8+ 删掉了这个类，属于预期路径，不要打完整 ClassNotFoundException 堆栈。
            logDebug(TAG, "AaUiHook: CarSystemUiControllerService missing (expected on AA 17.8+)")
            return null
        }
        try {
            return clazz.staticMethod("a", null, argTypes(Intent::class.java)).also {
                logDebug(TAG, "AaUiHook: startMethod=a(Intent)")
            }
        } catch (e: Throwable) {
            log(TAG, "AaUiHook: CarSystemUiControllerService.a missing, scanning static Intent methods", e)
        }
        val candidates = clazz.declaredMethods.filter { m ->
            Modifier.isStatic(m.modifiers) &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == Intent::class.java &&
                (m.returnType == Void.TYPE || m.returnType == Void::class.java)
        }
        val picked = candidates.firstOrNull()
        if (picked != null) {
            picked.isAccessible = true
            logDebug(
                TAG,
                "AaUiHook: startMethod fallback=${picked.name}(Intent) candidates=${candidates.map { it.name }}",
            )
        } else {
            log(TAG, "AaUiHook: no static Intent start method on CarSystemUiControllerService")
        }
        return picked
    }

    /**
     * AA 17.8+：启动入口搬到了 `ProjectionContext` 单例的实例方法上，签名是
     * `(Intent, ActivityOptions)V`。用 DexKit 按 AA 自己的字符串常量
     * [PROJECTION_START_NEEDLE] 精确定位 —— 同类里还有个 `(Intent)V` 兄弟方法是
     * 生命周期空实现，随手挑一个会拿到错的。
     *
     * 来源：移植自 lagXkjy/AADisplay 分支 AADisplay-Split-Lite (v0.24#17.8-r1)
     * 的 `CoolwalkHookEnv.resolveProjectionLauncherStart`（GPLv3）。
     */
    fun resolveProjectionLauncherStart(needleMethod: Method): Boolean {
        val hostClass = needleMethod.declaringClass
        val params = needleMethod.parameterTypes
        if (params.size < 2 || params[0] != Intent::class.java) {
            log(TAG, "AaUiHook: ProjectionContext needle unexpected $needleMethod")
            return false
        }
        if (needleMethod.returnType != Void.TYPE && needleMethod.returnType != Void::class.java) {
            log(TAG, "AaUiHook: ProjectionContext needle not void: $needleMethod")
            return false
        }
        val optionsCtor = runCatching {
            params[1].getDeclaredConstructor().also { it.isAccessible = true }
        }.getOrNull()
        if (optionsCtor == null) {
            log(TAG, "AaUiHook: options type has no no-arg ctor: ${params[1].name}")
            return false
        }
        needleMethod.isAccessible = true
        val getter = hostClass.declaredMethods.firstOrNull { m ->
            Modifier.isStatic(m.modifiers) && m.parameterCount == 0 && m.returnType == hostClass
        }
        if (getter == null) {
            log(TAG, "AaUiHook: singleton getter missing on ${hostClass.name}")
            return false
        }
        getter.isAccessible = true
        startMethod = needleMethod
        startSingletonGetter = getter
        logDebug(
            TAG,
            "AaUiHook: startMethod=ProjectionContext.${needleMethod.name}" +
                "(Intent, ${params[1].simpleName}) getter=${getter.name} class=${hostClass.name}",
        )
        return true
    }

    /** 调用已解析的启动入口：静态（≤17.4）/ 单例实例（17.8+）。 */
    fun invokeStartCarActivity(intent: Intent) {
        val method = startMethod ?: throw IllegalStateException("startMethod null")
        if (Modifier.isStatic(method.modifiers)) {
            method.invoke(null, intent)
            return
        }
        val getter = startSingletonGetter
            ?: throw IllegalStateException("startSingletonGetter null")
        val receiver = getter.invoke(null)
            ?: throw NullPointerException("ProjectionContext singleton null (not connected)")
        when (method.parameterTypes.size) {
            1 -> method.invoke(receiver, intent)
            2 -> {
                val options = method.parameterTypes[1].getDeclaredConstructor().apply {
                    isAccessible = true
                }.newInstance()
                method.invoke(receiver, intent, options)
            }
            else -> throw IllegalStateException(
                "startMethod arity ${method.parameterTypes.size}: $method",
            )
        }
    }

    companion object {
        const val TAG = "AAD_AaUiHook"

        /**
         * LayoutInfo 构造器形状（AA 16.x → 18.1）：
         * - 17.8：`(IIII ZZ <layoutTypeObj> ZZZ)`
         * - 18.1：`(IIII ZZZ <layoutTypeObj> ZZZ)` —— 对象参数前多了一个 boolean
         *
         * 因此不能写死“对象在 index 6”。只要求 0..2 是 int、3 是 int/enum、4/5 是 boolean，
         * 6..9 中恰好一个非 primitive（布局类型对象），其余都是 boolean。
         */
        fun isLayoutInfoConstructor(p: Array<Class<*>>): Boolean {
            if (p.size < 10) return false
            if (p[0] != Int::class.javaPrimitiveType) return false
            if (p[1] != Int::class.javaPrimitiveType) return false
            if (p[2] != Int::class.javaPrimitiveType) return false
            if (p[3] != Int::class.javaPrimitiveType && !p[3].isEnum) return false
            if (p[4] != Boolean::class.javaPrimitiveType) return false
            if (p[5] != Boolean::class.javaPrimitiveType) return false
            var objectIndex = -1
            for (i in 6..9) {
                if (!p[i].isPrimitive) {
                    if (objectIndex >= 0) return false
                    objectIndex = i
                }
            }
            if (objectIndex < 0) return false
            for (i in 6..9) {
                if (i != objectIndex && p[i] != Boolean::class.javaPrimitiveType) return false
            }
            return true
        }

        /**
         * DexKit needle：AA 17.8 里 ProjectionContext 启动器的字符串常量。
         *
         * 来源：lagXkjy/AADisplay 分支 AADisplay-Split-Lite (v0.24#17.8-r1)，GPLv3。
         */
        const val PROJECTION_START_NEEDLE = "startCarActivity(intent:%s, activityOptions:%s)"

        val RAIL_WIDTH_DIMEN_NAMES = arrayOf(
            "cielo_vertical_rail_width",
            "coolwalk_vertical_rail_width",
            "coolwalk_edge_column_width_padded",
            "rail_coolwalk_edge_column_width_padded",
            "floating_vertical_rail_space_width",
            "gearhead_edge_column_width",
            "gearhead_edge_column_width_padded",
            "rail_max_width",
            "legacy_facet_bar_touch_target_width",
        )

        const val FACET_ENSURE_WINDOW_MS = 2_000L
        const val FACET_ENSURE_POLL_MS = 250L
        val FACET_ENSURE_TOKEN = Any()
        val AUTO_OPEN_TOKEN = Any()
        val FULL_BLEED_RELAUNCH_TOKEN = Any()
        const val AUTO_OPEN_REARM_GAP_MS = 24_000L

        val PROJECTION_CONFIG_KEYS = setOf(
            "content_bounds", "contentBounds", "content_insets", "contentInsets",
        )

        val AUTO_OPEN_DELAYS_MS = longArrayOf(
            0L, 1500L, 5000L, 12_000L, 24_000L,
        )
    }
}
