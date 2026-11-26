package io.github.nitsuya.aa.display.xposed.hook.aa

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.github.kyuubiran.ezxhelper.init.InitFields
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.getIdByName
import com.github.kyuubiran.ezxhelper.utils.getObjectOrNull
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.loadClass
import com.github.kyuubiran.ezxhelper.utils.staticMethod
import de.robv.android.xposed.callbacks.XCallback
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.service.AaActivityService
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.hook.AaHook
import io.github.nitsuya.aa.display.xposed.hook.HookTarget
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.AaCoolwalkAutoOpenHook
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.AaCoolwalkCompositorHook
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.AaCoolwalkLayoutHook
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.AaCoolwalkProjectionHook
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.CoolwalkComposeConstraintsGuard
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.CoolwalkDrawingSpecWiden
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.CoolwalkFacetBarSurfaceHook
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.CoolwalkFacetChrome
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.CoolwalkHookEnv
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.CoolwalkHuScreenParamsHook
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.CoolwalkProjectionBoundsHook
import io.github.nitsuya.aa.display.xposed.hook.abortMethod
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.logDebug
import io.github.nitsuya.template.bases.runMain
import io.github.qauxv.ui.CommonContextWrapper
import kotlinx.coroutines.delay
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Constructor
import java.lang.reflect.Method

object AaUiHook: AaHook() {
    override val tagName: String = CoolwalkHookEnv.TAG

    /**
     * true：安装 Coolwalk 隐藏 UI 控件栈（车机侧边栏折叠 + 投屏铺满整块车机宽度），
     *       控制方案保持在手机端悬浮窗（DisplayWindow / ACTION_SCREEN_CONTROL）。
     * false：走旧行为，在车机侧边栏重建 home/全屏/返回/语音助手图标。
     */
    private var hideAaUiControls: Boolean = true

    /** Coolwalk 分支共享状态；仅在 [hideAaUiControls] 为 true 时填充。 */
    private val env = CoolwalkHookEnv()

    private lateinit var layoutInfoConstructor: Constructor<*>
    private var startMethod: Method? = null

    private var resLayoutLeftResourceId: Int = 0
    private var resLayoutRightResourceId: Int = 0

    private var resLayoutGhFacetBarId: Int = 0
    private var resIdStatusBarId: Int = 0
    private var resIdAssistantIconContainerId: Int = 0
    private var resIdAssistantIconId: Int = 0
    private var resIdLauncherAndDashboardIconContainerId: Int = 0
    private var resIdLauncherAndDashboardIconId: Int = 0
    private var canHookLayout: Boolean = false
    private var canHookFacetBar: Boolean = false
    private var canHookAssistantIcon: Boolean = false

    override fun isSupportProcess(processName: String): Boolean {
        // Coolwalk 隐藏栈需要 :car（侧边栏/合成器）与 :projection（布局/投屏）两个进程；
        // 旧的侧边栏重建只在 :projection 生效，由 isEnabled 再过滤一次。
        return processProjection == processName || processCar == processName
    }

    override fun isEnabled(config: SharedPreferences?, processName: String): Boolean {
        hideAaUiControls = AADisplayConfig.HideAaUiControls.get(config)
        env.autoOpenEnabled = AADisplayConfig.AutoOpen.get(config)
        env.forceRightAngle = AADisplayConfig.ForceRightAngle.get(config)
        if (hideAaUiControls) return true
        // 旧行为不涉及 :car。
        return processProjection == processName
    }

    override fun loadDexClass(bridge: DexKitBridge, target: HookTarget) {
        if (hideAaUiControls) {
            loadDexClassCoolwalk(bridge, target)
        } else {
            loadDexClassLegacy(bridge)
        }
    }

    // ------------------------------------------------------------------
    // Coolwalk 隐藏 UI 控件（来自 Split-Lite 分支）
    // ------------------------------------------------------------------

    private fun loadDexClassCoolwalk(bridge: DexKitBridge, target: HookTarget) {
        // Compose 约束断言守卫：两个进程都要装，:car 会在下面提前 return。
        CoolwalkComposeConstraintsGuard.resolve(bridge, target)
        // 车机画布真值来源：:car 里没有 HU 尺寸的 VD，也收不到 :projection 的上报，
        // 只能读 gearhead 自己的 DisplayParams。同样必须在 :car 的提前 return 之前。
        CoolwalkHuScreenParamsHook.resolve(bridge, target)

        env.contentBoundsMethods = try {
            val byKey = bridge.findMethod {
                matcher { usingStrings("content_bounds") }
            }
            val byLog = bridge.findMethod {
                matcher {
                    usingStrings {
                        add("onProjectionStart updated config", StringMatchType.StartsWith, false)
                    }
                }
            }
            (byKey + byLog).mapNotNull { md ->
                runCatching { md.getMethodInstance(target.classLoader) }.getOrNull()
            }.distinctBy { "${it.declaringClass.name}#${it.name}#${it.parameterTypes.joinToString { p -> p.name }}" }
                .also { list ->
                    log(
                        tagName,
                        "AaUiHook: content_bounds methods=${list.size} " +
                            list.map { "${it.declaringClass.name}#${it.name}" },
                    )
                }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: DexKit content_bounds methods failed", e)
            emptyList()
        }

        env.facetBarSurfaceMethods = try {
            val byWidth = bridge.findMethod {
                matcher { usingStrings("onWindowSurfaceAvailable width:") }
            }
            val byDims = bridge.findMethod {
                matcher { usingStrings("onWindowSurfaceAvailable dimensions:") }
            }
            (byWidth + byDims).mapNotNull { md ->
                runCatching { md.getMethodInstance(target.classLoader) }.getOrNull()
            }.distinctBy { "${it.declaringClass.name}#${it.name}#${it.parameterTypes.joinToString { p -> p.name }}" }
                .also { list ->
                    log(
                        tagName,
                        "AaUiHook: facet-bar surface methods=${list.size} " +
                            list.map { "${it.declaringClass.name}#${it.name}" },
                    )
                }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: DexKit facet-bar surface methods failed", e)
            emptyList()
        }

        env.projectionBoundsClassName = try {
            bridge.findClass {
                matcher { usingStrings("{blX=") }
            }.mapNotNull { runCatching { it.name }.getOrNull() }
                .firstOrNull()
                .also { name ->
                    log(tagName, "AaUiHook: projection bounds class=${name ?: "null"}")
                }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: DexKit projection bounds class failed", e)
            null
        }
        CoolwalkProjectionBoundsHook.rememberClassName(env.projectionBoundsClassName)

        env.startMethod = env.resolveCarStartActivityMethod()
        if (env.startMethod == null) {
            resolveProjectionLauncher(bridge, target)
        }

        if (target.processName == processCar) {
            env.loadProjectionResources()
            return
        }

        val classes = bridge.findClass {
            matcher {
                usingStrings {
                    add("LayoutInfo{layoutResourceId=", StringMatchType.StartsWith, false)
                }
            }
        }
        if (classes.isEmpty() || classes.size > 1) {
            throw NoSuchMethodException("AaUiHook: not found LayoutInfo class：${classes.size}")
        }
        env.layoutInfoConstructors = env.resolveLayoutInfoConstructors(classes[0].name)
        log(
            tagName,
            "AaUiHook: LayoutInfo ctors=${env.layoutInfoConstructors.size} " +
                env.layoutInfoConstructors.joinToString { "p${it.parameterCount}" },
        )
        env.loadProjectionResources()
    }

    /**
     * AA 17.8+：`CarSystemUiControllerService` 已删除，启动入口变成 `ProjectionContext`
     * 单例上的 `startCarActivity(Intent, ActivityOptions)`。用 AA 自己的字符串常量定位，
     * 避免挑到同类里那个空实现的 `(Intent)V` 兄弟方法。
     *
     * 来源：移植自 lagXkjy/AADisplay 分支 AADisplay-Split-Lite (v0.24#17.8-r1)
     * 的 `AaUiHook.resolveStartMethodLive`（GPLv3）。
     */
    private fun resolveProjectionLauncher(bridge: DexKitBridge, target: HookTarget) {
        val hits = try {
            bridge.findMethod {
                matcher {
                    usingStrings {
                        add(
                            CoolwalkHookEnv.PROJECTION_START_NEEDLE,
                            StringMatchType.Equals,
                            false,
                        )
                    }
                }
            }.mapNotNull { md ->
                runCatching { md.getMethodInstance(target.classLoader) }.getOrNull()
            }.filter { m ->
                m.parameterTypes.size >= 2 &&
                    m.parameterTypes[0] == Intent::class.java &&
                    (m.returnType == Void.TYPE || m.returnType == Void::class.java)
            }
        } catch (e: Throwable) {
            log(tagName, "AaUiHook: ProjectionContext DexKit failed", e)
            return
        }
        val needle = hits.singleOrNull()
        if (needle == null) {
            log(
                tagName,
                "AaUiHook: ProjectionContext hits=${hits.size} " +
                    hits.map { "${it.declaringClass.name}#${it.name}" },
            )
            return
        }
        if (!env.resolveProjectionLauncherStart(needle)) {
            log(
                tagName,
                "AaUiHook: ProjectionContext resolve failed " +
                    "${needle.declaringClass.name}#${needle.name}",
            )
        }
    }

    private fun hookCoolwalk(target: HookTarget) {
        env.loadProjectionResources()
        // 必须在其它几何改写之前：0/1 px 容器会让 Compose 的 Constraints 断言炸掉进程。
        CoolwalkComposeConstraintsGuard.install()
        // 必须在下面所有按宽度决策的钩子之前装：它负责把真正的车机宽度喂给协调器，
        // 否则 :car 只知道内容槽宽度（HU − 侧边栏），右侧就会留一条黑边。
        CoolwalkHuScreenParamsHook.install(target.classLoader)
        CoolwalkDrawingSpecWiden.installGearhead(target.classLoader)
        CoolwalkProjectionBoundsHook.install(
            target.classLoader,
            env.projectionBoundsClassName ?: CoolwalkProjectionBoundsHook.cachedClassName(),
        )
        CoolwalkFacetBarSurfaceHook.install(env)
        AaCoolwalkProjectionHook.install(env)
        if (target.processName == processCar) {
            AaCoolwalkLayoutHook.installRailWidthDimens(env)
            AaCoolwalkCompositorHook.install(env)
            if (env.canHookFacetBar) {
                CoolwalkFacetChrome.install(env)
            }
            // 不安装 AaCoolwalkHuTouchHook：触控/控制方案保留手机端悬浮窗。
            logDebug(tagName, "AaUiHook: Coolwalk hooks installed (:car)")
            return
        }
        AaCoolwalkLayoutHook.install(env)
        AaCoolwalkCompositorHook.install(env)
        if (env.canHookFacetBar) {
            CoolwalkFacetChrome.install(env)
        }
        AaCoolwalkAutoOpenHook.install(env, target)
        logDebug(tagName, "AaUiHook: Coolwalk hooks installed (:projection)")
    }

    // ------------------------------------------------------------------
    // 旧行为：在车机侧边栏重建控件
    // ------------------------------------------------------------------

    private fun loadDexClassLegacy(bridge: DexKitBridge) {
        val classes = bridge.findClass {
            matcher {
                usingStrings {
                    add(
                        "LayoutInfo{layoutResourceId=",
                        StringMatchType.StartsWith,
                        false
                    )
                }
            }
        }
        if (classes.isEmpty() || classes.size > 1) {
            throw NoSuchMethodException("AaUiHook: not found LayoutInfo class：${classes.size}")
        }
        layoutInfoConstructor = resolveLayoutInfoConstructor(classes[0].name)

        try{
            startMethod = loadClass("com.google.android.projection.gearhead.service.CarSystemUiControllerService").staticMethod("a", null, argTypes(Intent::class.java))
        } catch (e: Throwable){
            // 17.8+ 已删除该类，走 CoolwalkHookEnv 的新入口；这里只是兼容旧版，不需要堆栈。
            logDebug(tagName, "AaUiHook: CarSystemUiControllerService.a unavailable (AA 17.8+)")
        }

        resLayoutGhFacetBarId = InitFields.appContext.resources.getIdentifier("gh_coolwalk_vertical_facet_bar", "layout", InitFields.appContext.packageName)
        resIdStatusBarId = getIdByName("status_bar")
        resIdAssistantIconContainerId = getIdByName("assistant_icon_container")
        resIdAssistantIconId = getIdByName("assistant_icon")
        resIdLauncherAndDashboardIconContainerId = getIdByName("launcher_and_dashboard_icon_container")
        resIdLauncherAndDashboardIconId = getIdByName("launcher_and_dashboard_icon")

        resLayoutLeftResourceId = InitFields.appContext.resources.getIdentifier("sys_ui_layout_canonical_vertical_rail_lhd", "layout", InitFields.appContext.packageName)
        resLayoutRightResourceId = InitFields.appContext.resources.getIdentifier("sys_ui_layout_canonical_vertical_rail_rhd", "layout", InitFields.appContext.packageName)

        canHookLayout = resLayoutLeftResourceId != 0 && resLayoutRightResourceId != 0
        if (!canHookLayout) {
            log(
                tagName,
                "AaUiHook: skip layout override, missing canonical layout resources: lhd=$resLayoutLeftResourceId, rhd=$resLayoutRightResourceId"
            )
        }
        canHookFacetBar =
            resLayoutGhFacetBarId != 0 &&
            resIdStatusBarId != 0 &&
            resIdLauncherAndDashboardIconContainerId != 0 &&
            resIdLauncherAndDashboardIconId != 0
        if (!canHookFacetBar) {
            log(
                tagName,
                "AaUiHook: skip facet-bar override, missing resources: facet=$resLayoutGhFacetBarId, status=$resIdStatusBarId, launcherContainer=$resIdLauncherAndDashboardIconContainerId, launcherIcon=$resIdLauncherAndDashboardIconId"
            )
        }
        // 助手图标在部分 AA 版本已被移除，缺失时降级：只保留方控触发语音助手
        canHookAssistantIcon = resIdAssistantIconContainerId != 0 && resIdAssistantIconId != 0
        if (!canHookAssistantIcon) {
            log(
                tagName,
                "AaUiHook: skip assistant icon hook, missing resources: assistantContainer=$resIdAssistantIconContainerId, assistantIcon=$resIdAssistantIconId"
            )
        }
    }

    override fun hook(config: SharedPreferences?, target: HookTarget) {
        log(tagName,  "AaUiHook: ~~~~~~~~~~~~~~~~~~~~~~~~~~~ hideAaUiControls=$hideAaUiControls")
        if (hideAaUiControls) {
            hookCoolwalk(target)
            return
        }
        // 仅旧行为需要接管 View 点击监听（侧边栏图标改写点击目标）。
        hookBaseClick()
        if (canHookLayout) {
            hookLayout()
        }
        if (canHookFacetBar) {
            hookFacetBar(config)
        }
        hookRadius(config)
    }

    private fun hookLayout() {
        layoutInfoConstructor.hookAfter { param -> log(tagName, param.thisObject.toString()) }
        layoutInfoConstructor.hookBefore { param ->
            if (param.args.size < 5) return@hookBefore
            val layoutTypeCode = layoutTypeCode(param.args[3] ?: return@hookBefore) ?: return@hookBefore
            // Skip cluster/auxiliary layouts to avoid overriding non-main surfaces.
            if (layoutTypeCode in setOf(7, 8, 9)) return@hookBefore
            val isRightHandDrive = (param.args[4] as? Boolean) == true
            param.args[0] = if (isRightHandDrive) resLayoutRightResourceId else resLayoutLeftResourceId
            // Force canonical vertical rail for LHD/RHD.
            setLayoutTypeArg(param.args, if (isRightHandDrive) 3 else 2)
            if (param.args.size > 5 && param.args[5] is Boolean) {
                param.args[5] = true //hasVerticalRail
            }
        }
    }

    private fun resolveLayoutInfoConstructor(className: String): Constructor<*> {
        // 形状判断与 Coolwalk 路径共用：AA 18.1 在布局类型对象参数前多了一个 boolean。
        val clazz = loadClass(className)
        val fallback = clazz.declaredConstructors.firstOrNull { ctor ->
            CoolwalkHookEnv.isLayoutInfoConstructor(ctor.parameterTypes)
        } ?: throw NoSuchMethodException("AaUiHook: not found compatible LayoutInfo constructor for $className")

        fallback.isAccessible = true
        log(tagName, "AaUiHook: fallback constructor selected, paramCount=${fallback.parameterCount}, layoutTypeArg=${fallback.parameterTypes[3].name}")
        return fallback
    }

    private fun layoutTypeCode(raw: Any): Int? {
        return when (raw) {
            is Int -> raw - 1
            is Enum<*> -> raw.toString().toIntOrNull()
            else -> raw.toString().toIntOrNull()
        }
    }

    private fun setLayoutTypeArg(args: Array<Any?>, targetCode: Int) {
        val raw = args.getOrNull(3) ?: return
        when (raw) {
            is Int -> args[3] = targetCode + 1
            is Enum<*> -> {
                val enumClass = raw.javaClass
                val enumValue = enumClass.enumConstants?.firstOrNull { c ->
                    c?.toString()?.toIntOrNull() == targetCode
                }
                if (enumValue != null) {
                    args[3] = enumValue
                }
            }
        }
    }

    private fun hookFacetBar(config: SharedPreferences?) {
        val enableDefVoiceAssist = AADisplayConfig.VoiceAssistShell.get(config).isNullOrBlank()
        val closeLauncherDashboard = AADisplayConfig.CloseLauncherDashboard.get(config)
        val autoOpen = AADisplayConfig.AutoOpen.get(config)
        findMethod(LayoutInflater::class.java) {
            name == "inflate"
            && parameterCount == 3
            && parameterTypes[0] == Int::class.javaPrimitiveType // resource
            && parameterTypes[1] == ViewGroup::class.java // root
            && parameterTypes[2] == Boolean::class.javaPrimitiveType // attachToRoot
        }.hookAfter { param ->
            if (param.args[0] as Int != resLayoutGhFacetBarId) {
                return@hookAfter
            }
            val resultViewGroup = param.result as ViewGroup? ?: return@hookAfter //androidx.constraintlayout.widget.ConstraintLayout
            val ctx = (param.thisObject as LayoutInflater).context
            val ctx2 = CommonContextWrapper.createAppCompatContext(ctx)
            val layoutInflater = LayoutInflater.from(ctx2)
            val resultViewGroupParent = (resultViewGroup.parent as ViewGroup?)?.apply {
                removeView(resultViewGroup)
            }
            if(closeLauncherDashboard){
                // 该 id 只在部分布局里存在；findViewById 返回 null 时不能 NPE，
                // 否则会在 gearhead 主线程的布局 inflate 里直接崩掉。
                resultViewGroup.findViewById<View>(resIdLauncherAndDashboardIconId)?.let { icon ->
                    icon.setOnClickFinallyListener {
                        icon.performLongClick()
                    }
                }
            }
            val aaFacetBar = layoutInflater.inflate(R.layout.aa_facet_bar, resultViewGroupParent, false) as ConstraintLayout
            if(autoOpen){
                aaFacetBar.post {
                    runMain {
                        delay(1000)
                        try{
                            startMethod?.invoke(null, Intent().apply {
                                component = ComponentName(BuildConfig.APPLICATION_ID, AaActivityService::class.java.name)
                                putExtra("android.intent.extra.PACKAGE_NAME", BuildConfig.APPLICATION_ID)
                            })
                        } catch (e: Throwable) {
                            log(tagName, "CarSystemUiControllerService.a start app error", e)
                        }
                    }
                }
            }
            val createBtn: (resId: Int, block: View.() -> Unit) -> Int = { resId, block ->
                val btn = ImageView(ctx).apply {
                    id = View.generateViewId()
                    layoutParams = ConstraintLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setImageResource(resId)
                }
                block(btn)
                aaFacetBar.addView(btn)
                btn.id
            }
            val topIds = arrayListOf(
                resIdStatusBarId,
                resIdLauncherAndDashboardIconContainerId
            )
            val bottomIds = arrayListOf(
                createBtn(R.drawable.ic_aa_home_44){
                    val intentClick = Intent().apply {
                        action = AABroadcastConst.ACTION_SCREEN_CONTROL
                        putExtra(AABroadcastConst.EXTRA_ACTION, KeyEvent.KEYCODE_HOME)
                    }
                    setOnClickListener {
                        ctx.sendBroadcast(intentClick)
                    }
                    setPadding(0, 5, 0, 5)
                },
                createBtn(R.drawable.ic_aa_fullscreen_44){
                    val intentClick = Intent().apply {
                        action = AABroadcastConst.ACTION_SCREEN_CONTROL
                        putExtra(AABroadcastConst.EXTRA_ACTION, KeyEvent.KEYCODE_APP_SWITCH)
                    }
                    setOnClickListener {
                        ctx.sendBroadcast(intentClick)
                    }
                    setPadding(0, 5, 0, 5)
                },
                createBtn(R.drawable.ic_aa_arrow_back_44){
                    val intentClick = Intent().apply {
                        action = AABroadcastConst.ACTION_SCREEN_CONTROL
                        putExtra(AABroadcastConst.EXTRA_ACTION, KeyEvent.KEYCODE_BACK)
                    }
                    setOnClickListener {
                        ctx.sendBroadcast(intentClick)
                    }
                    setPadding(0, 5, 0, 5)
                },
            )
            // 恢复 nitsuya 的助手图标接管：点击原生助手图标改为触发自定义语音助手 Shell（小爱）
            val assistantIconView: View? = if (canHookAssistantIcon) resultViewGroup.findViewById(resIdAssistantIconId) else null
            val assistantContainerView: View? = if (canHookAssistantIcon) resultViewGroup.findViewById(resIdAssistantIconContainerId) else null
            if (assistantIconView != null && assistantContainerView != null) {
                if (!enableDefVoiceAssist) {
                    val intentClick = Intent().apply {
                        action = AABroadcastConst.ACTION_SCREEN_CONTROL
                        putExtra(AABroadcastConst.EXTRA_ACTION, KeyEvent.KEYCODE_SEARCH)
                    }
                    assistantIconView.setOnClickFinallyListener {
                        ctx.sendBroadcast(intentClick)
                    }
                }
                bottomIds.add(resIdAssistantIconContainerId)
            } else if (canHookAssistantIcon) {
                log(tagName, "AaUiHook: assistant icon view not found in facet bar, skip voice assist takeover")
            }
            val relocateViews = arrayListOf<View>().apply {
                resultViewGroup.findViewById<View>(resIdStatusBarId)?.let { add(it) }
                resultViewGroup.findViewById<View>(resIdLauncherAndDashboardIconContainerId)?.let { add(it) }
                if (assistantContainerView != null && bottomIds.contains(resIdAssistantIconContainerId)) {
                    add(assistantContainerView)
                }
            }
            relocateViews.forEach { view ->
                (view.parent as ViewGroup?)?.apply {
                    removeView(view)
                }
                aaFacetBar.addView(view)
            }
            val set = ConstraintSet()
            set.clone(aaFacetBar)
            bottomIds.forEachIndexed { index, vId ->
                set.connect(vId, ConstraintSet.BOTTOM, if(index == 0) ConstraintSet.PARENT_ID else bottomIds[index-1], if(index == 0) ConstraintSet.BOTTOM else ConstraintSet.TOP, 0)
                set.connect(vId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)
                set.connect(vId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
            }
            topIds.forEachIndexed { index, vId ->
                set.connect(vId, ConstraintSet.TOP, if(index == 0) ConstraintSet.PARENT_ID else topIds[index-1], if(index == 0) ConstraintSet.TOP else ConstraintSet.BOTTOM, 0)
                set.connect(vId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 0)
                set.connect(vId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 0)
            }
            set.applyTo(aaFacetBar)
            resultViewGroup.visibility = View.GONE
            aaFacetBar.addView(resultViewGroup)
            param.result = aaFacetBar
        }
    }

    private fun hookBaseClick() {
        try {
            findMethod(View::class.java) {
                name == "setOnLongClickListener"
                && parameterCount == 1
                && parameterTypes[0] == View.OnLongClickListener::class.java
            }.hookBefore(XCallback.PRIORITY_LOWEST) {
                if (it.args[0] is FinallyListener) return@hookBefore
                val view = it.thisObject as View
                if (!view.hasOnLongClickListeners() || (view.getObjectOrNull("mListenerInfo")?.getObjectOrNull("mOnLongClickListener") is FinallyListener).not()) {
                    return@hookBefore
                }
                view.setOnOriLongClickListener(it.args[0] as View.OnLongClickListener)
                it.abortMethod()
            }
        } catch (e: Throwable) {
            log(tagName, "hook View.setOnLongClickListener", e)
        }
        try {
            findMethod(View::class.java) {
                name == "setOnClickListener"
                && parameterCount == 1
                && parameterTypes[0] == View.OnClickListener::class.java
            }.hookBefore(XCallback.PRIORITY_LOWEST) {
                if (it.args[0] is FinallyListener) return@hookBefore
                val view = it.thisObject as View
                if (!view.hasOnClickListeners() || (view.getObjectOrNull("mListenerInfo")?.getObjectOrNull("mOnClickListener") is FinallyListener).not()) {
                    return@hookBefore
                }
                view.setOnOriClickListener(it.args[0] as View.OnClickListener)
                it.abortMethod()
            }
        } catch (e: Throwable) {
            log(tagName, "hook View.setOnClickListener", e)
        }
    }

    private fun hookRadius(config: SharedPreferences?) {
        if(!AADisplayConfig.ForceRightAngle.get(config)){
            return
        }
        try{
            val targetClass = loadClass("com.google.android.gms.car.ProjectionWindowDecorationParams")
            val ctor = targetClass.declaredConstructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size >= 9 &&
                    p[0] == Int::class.javaPrimitiveType &&
                    p[1] == Int::class.javaPrimitiveType &&
                    p[2] == Int::class.javaPrimitiveType &&
                    p[3] == Int::class.javaPrimitiveType &&
                    p[4] == Int::class.javaPrimitiveType &&
                    p[5] == Int::class.javaPrimitiveType &&
                    p[6] == Int::class.javaPrimitiveType &&
                    p[7] == Boolean::class.javaPrimitiveType &&
                    p[8] == Boolean::class.javaPrimitiveType
            } ?: throw NoSuchMethodException("AaUiHook: ProjectionWindowDecorationParams compatible constructor not found")
            ctor.isAccessible = true
            ctor.hookBefore { param ->
                if (param.args.size > 5 && param.args[5] is Int) {
                    param.args[5] = 0
                }
            }
        } catch (e: Throwable) {
            log(tagName, "ProjectionWindowDecorationParams", e)
        }
    }

    interface FinallyListener
    private fun interface OnClickFinallyListener: View.OnClickListener, FinallyListener
    private fun interface OnLongClickFinallyListener: View.OnLongClickListener, FinallyListener
    private fun View.setOnClickFinallyListener(l: OnClickFinallyListener) = this.setOnClickListener(l)
    private fun View.setOnLongClickFinallyListener(l: OnLongClickFinallyListener) = this.setOnLongClickListener(l)
    private fun View.setOnOriClickListener(l: View.OnClickListener) = this.setTag(R.id.ori_click_listener, l)
    private fun View.setOnOriLongClickListener(l: View.OnLongClickListener) = this.setTag(R.id.ori_long_click_listener, l)
    private fun View.performOriClick() {
        val clickListener = this.getTag(R.id.ori_click_listener) as View.OnClickListener? ?: return
        clickListener.onClick(this)
    }
    private fun View.performOriLongClick(): Boolean {
        val clickListener = this.getTag(R.id.ori_long_click_listener) as View.OnLongClickListener? ?: return false
        return clickListener.onLongClick(this)
    }
}
