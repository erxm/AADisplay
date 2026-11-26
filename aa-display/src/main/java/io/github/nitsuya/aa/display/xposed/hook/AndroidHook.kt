package io.github.nitsuya.aa.display.xposed.hook

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.IPackageManager
import android.content.res.Configuration
import android.os.Build
import android.view.Display
import com.github.kyuubiran.ezxhelper.utils.*
import de.robv.android.xposed.XC_MethodHook
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.IsSystemEnv
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.BridgeService
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.log
import io.github.qauxv.util.Initiator

object AndroidHook : BaseHook() {
    override val tagName: String = "AAD_AndroidHook"
    override fun init(target: HookTarget) {
        Initiator.init(target.classLoader)
        log(tagName, "xposed init")
        var serviceManagerHook: XC_MethodHook.Unhook? = null
        serviceManagerHook = findMethod("android.os.ServiceManager") {
            name == "addService"
        }.hookBefore { param ->
            if (param.args[0] == "package") {
                serviceManagerHook?.unhook()
                val pms = param.args[1] as IPackageManager
                log(tagName, "Got pms: $pms")
                runCatching {
                    BridgeService.register(pms)
                    log(tagName, "Bridge service injected")
                }.onFailure {
                    log(tagName, "System service crashed", it)
                }
            }
        }

        var activityManagerServiceConstructorHook: List<XC_MethodHook.Unhook> = emptyList()
        activityManagerServiceConstructorHook = findAllConstructors("com.android.server.am.ActivityManagerService") {
            parameterTypes[0] == Context::class.java
        }.hookAfter {
            activityManagerServiceConstructorHook.forEach { hook -> hook.unhook() }
            // 任何异常都不能逃进 AMS 构造器，否则开机即 system_server 崩溃。
            runCatching {
                CoreManagerService.systemContext = it.thisObject.getObjectAs("mUiContext")
                log(tagName, "get systemUiContext")
            }.onFailure { e -> log(tagName, "init systemUiContext failed", e) }
        }.also {
            if (it.isEmpty())
                log(tagName, "no constructor with parameterTypes[0] == Context found")
        }

        var activityManagerServiceSystemReadyHook: XC_MethodHook.Unhook? = null
        activityManagerServiceSystemReadyHook = findMethod("com.android.server.am.ActivityManagerService") {
            name == "systemReady"
        }.hookAfter {
            activityManagerServiceSystemReadyHook?.unhook()
            runCatching {
                CoreManagerService.systemReady()
                log(tagName, "system ready")
            }.onFailure { e -> log(tagName, "systemReady failed", e) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {//10+
            var className = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)  //12+
                "com.android.server.wm.ActivityTaskSupervisor"
            else  //10+
                "com.android.server.wm.ActivityStackSupervisor"
            findMethod(className){
                name == "isCallerAllowedToLaunchOnDisplay"
                && parameterCount == 4
                && parameterTypes[0] == Int::class.javaPrimitiveType //callingPid
                && parameterTypes[1] == Int::class.javaPrimitiveType //callingUid
                && parameterTypes[2] == Int::class.javaPrimitiveType //launchDisplayId
                && parameterTypes[3] == ActivityInfo::class.java
            }.hookAfter { param ->
                if((param.result as Boolean).not() && param.args[2] == CoreManagerService.getDisplayId()){
                    param.result = true
                    log(tagName,"hook isCallerAllowedToLaunchOnDisplay success")
                }
            }
        }

    }

    object Power {
        /**
         * HyperOS 3 moved short-press dispatch into its Miui shortcut rule.  It receives the
         * final press count, and forwarding it to OriginalPowerKeyRuleBridge locks the device.
         */
        private val miuiPowerPresses by lazy {
            if (!IsSystemEnv) return@lazy emptyList()
            runCatching {
                findAllMethods(
                    loadClass("com.android.server.input.shortcut.singlekeyrule.PowerKeyRule"),
                ) {
                        name == "onMiuiPress" &&
                            parameterTypes.contentEquals(
                                arrayOf(Long::class.javaPrimitiveType, Int::class.javaPrimitiveType),
                            )
                }
                    .also { methods ->
                        if (methods.isNotEmpty()) {
                            log(tagName, "Power: matched ${methods.size} HyperOS power handler(s)")
                        }
                    }
            }.getOrElse { error ->
                log(tagName, "Power HyperOS handler lookup", error)
                emptyList()
            }
        }

        private val powerPresses by lazy {
            if(!IsSystemEnv) return@lazy null
            try{
                val policyClass = loadClass("com.android.server.policy.PhoneWindowManager")
                generateSequence(policyClass) { it.superclass }
                    .flatMap { it.declaredMethods.asSequence() }
                    .filter {
                        if (it.name != "powerPress") return@filter false
                        val types = it.parameterTypes
                        types.size == 3
                            && types[0] == Long::class.javaPrimitiveType
                            && types[1] == Int::class.javaPrimitiveType
                            // Android 12-15: (long, int, boolean); Android 16: (long, int, int).
                            && (types[2] == Boolean::class.javaPrimitiveType
                                || types[2] == Int::class.javaPrimitiveType)
                    }
                    .distinctBy { it.declaringClass.name + it.toGenericString() }
                    .toList()
                    .also { methods ->
                        log(tagName, "Power: matched ${methods.size} powerPress handler(s): " +
                            methods.joinToString { it.declaringClass.name + "#" + it.name })
                    }
                    .takeIf { it.isNotEmpty() }
            } catch (e: Throwable){
                log(tagName,  "Power PhoneWindowManager.powerPress", e)
                null
            }
        }
        private var hookPower : List<XC_MethodHook.Unhook> = emptyList()
        fun hook(){
            unHook()
            hookPower = miuiPowerPresses.map { method -> method.hookBefore { param ->
                val count = param.args[1] as Int
                if (count == 1) {
                    log(tagName, "Power: intercepted HyperOS short press")
                    CoreApi.toggleDisplayPower()
                    param.abortMethod()
                }
            } }.ifEmpty {
                powerPresses?.map { method -> method.hookBefore {
                    if (method.parameterTypes[2] == Int::class.javaPrimitiveType) {
                        log(tagName, "Power: intercepted Android 16 power press")
                        CoreApi.toggleDisplayPower()
                        it.abortMethod()
                    } else if (!(it.args[2] as Boolean)) {
                        log(tagName, "Power: intercepted interactive power press")
                        CoreApi.toggleDisplayPower()
                        it.abortMethod()
                    } else {
                        log(tagName, "Power: waking display from non-interactive power press")
                        CoreApi.displayPower(true)
                    }
                } } ?: emptyList()
            }
            if (hookPower.isEmpty()) {
                log(tagName, "Power: no powerPress hook installed")
            } else {
                log(tagName, "Power: installed ${hookPower.size} powerPress hook(s)")
            }
        }
        fun unHook(){
            hookPower.forEach { it.unhook() }
            hookPower = emptyList()
        }
    }
    object FuckAppUseApplicationContext {
        // bindApplication 与 startProcessAsync 都在 Binder 线程池里并发执行，必须用并发容器。
        private val appInitUseDisplay: java.util.concurrent.ConcurrentHashMap<String, Int> =
            java.util.concurrent.ConcurrentHashMap()
        private val activityTaskManagerService_startProcessAsync by lazy {
            if(!IsSystemEnv) return@lazy null
            try{
                findMethod("com.android.server.wm.ActivityTaskManagerService"){
                    name == "startProcessAsync"
                }
            } catch (e: Throwable){
                log(tagName,  "FuckAppUseAppContext ActivityTaskManagerService.startProcessAsync method", e)
                null
            }
        }
        private val applicationThread_bindApplication by lazy {
            if(!IsSystemEnv) return@lazy null
            try{
                findMethod("android.app.IApplicationThread\$Stub\$Proxy"){
                    name == "bindApplication"
                }
            } catch (e: Throwable){
                log(tagName,  "FuckAppUseAppContext IApplicationThread.bindApplication method", e)
                null
            }
        }

        private var activityTaskManagerService_startProcessAsync_hook : XC_MethodHook.Unhook? = null
        private var applicationThread_bindApplication_hook : XC_MethodHook.Unhook? = null
        fun hook(){
            unHook()
            activityTaskManagerService_startProcessAsync_hook  = activityTaskManagerService_startProcessAsync?.hookBefore { param ->
                try {
                    val activityRecord = param.args[0]
                    val displayId = activityRecord.invokeMethod("getDisplayId") as Int
                    val packageName = activityRecord.getObject("packageName") as String
                    if(displayId == 0){
                        if(appInitUseDisplay.containsKey(packageName)){
                            appInitUseDisplay.remove(packageName)
                        }
                        return@hookBefore
                    }
                    appInitUseDisplay[packageName] = displayId
                } catch (e: Exception) {
                    log(tagName, "activityTaskManagerService_startProcessAsync Hook Exception", e)
                }
            }
            applicationThread_bindApplication_hook = applicationThread_bindApplication?.hookBefore { param ->
                try {
                    // 参数下标随 API 版本变化（Android 16 新增了 boolean），按类型找最稳。
                    val configuration = param.args.firstOrNull { it is Configuration } as? Configuration
                    if(configuration == null){
                        return@hookBefore
                    }
                    val packageName = (param.args[0] as String).run {
                        this.substringBeforeLast(":")
                    }
                    if(appInitUseDisplay.containsKey(packageName)){
                        val densityDpi = CoreManagerService.getDensityDpi()
                        if(densityDpi != 0){
                            configuration.densityDpi = densityDpi
                        }
                    }
                } catch (e: Exception) {
                    log(tagName, "applicationThread_bindApplication Hook Exception", e)
                }
            }
        }

        fun unHook(){
            appInitUseDisplay.clear()
            activityTaskManagerService_startProcessAsync_hook?.apply { unhook() }
            activityTaskManagerService_startProcessAsync_hook = null

            applicationThread_bindApplication_hook?.apply { unhook() }
            applicationThread_bindApplication_hook = null
        }

    }

    /**
     * 让投屏虚拟屏上的应用铺满整屏。
     *
     * 竖屏应用（微信 `SCREEN_ORIENTATION_PORTRAIT`、bilibili 同理）落到这块横屏
     * VirtualDisplay 上时，WM 的 fixed-orientation letterbox 会把它压成 590x720
     * 的居中窄条（`bounds=Rect(345, 0 - 935, 720)`、`letterBoxed=true`），两侧留黑边。
     *
     * 实测结论（1280x720/160 叠加屏 A/B）：
     * - `IWindowManager.setIgnoreOrientationRequest(displayId, true/false)` 对虚拟屏
     *   没有效果，两种取值下边界都是那条 590x720；虚拟屏默认已经是 true。
     * - 把 activity 的 override orientation 去掉（compat `OVERRIDE_ANY_ORIENTATION_TO_USER`）
     *   后边界立刻变成 `Rect(0, 0 - 1280, 720)`、`letterBoxed=false`。
     *
     * 所以这里直接在 [ActivityRecord.getOverrideOrientation] 出口把投屏屏上的固定朝向
     * 改成 `SCREEN_ORIENTATION_UNSPECIFIED`，交给 display 自己的朝向决定布局。
     * 不写任何 compat 覆盖、不碰应用自己的 `getRequestedOrientation()`，
     * 断开投屏时 unhook 即恢复。
     *
     * 全程只按 [CoreManagerService.getDisplayId] 比对，与分辨率无关
     * （1280x720/160 的 DHU 和 800x533/170 的车机行为一致）。
     */
    object AppFullscreenOnProjection {
        private const val SCREEN_ORIENTATION_UNSPECIFIED = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        // Android 14+：ActivityRecord.getOverrideOrientation()，WM 解析朝向的唯一入口。
        private val activityRecord_getOverrideOrientation by lazy {
            if (!IsSystemEnv) return@lazy null
            try {
                findMethod("com.android.server.wm.ActivityRecord") {
                    name == "getOverrideOrientation" && parameterCount == 0
                }
            } catch (e: Throwable) {
                log(tagName, "AppFullscreen ActivityRecord.getOverrideOrientation method", e)
                null
            }
        }

        // Android 12/13 回退：没有 getOverrideOrientation，朝向由 getOrientation(candidate) 汇总。
        private val activityRecord_getOrientation by lazy {
            if (!IsSystemEnv) return@lazy null
            try {
                findMethod("com.android.server.wm.ActivityRecord") {
                    name == "getOrientation"
                            && parameterCount == 1
                            && parameterTypes[0] == Int::class.javaPrimitiveType
                }
            } catch (e: Throwable) {
                log(tagName, "AppFullscreen ActivityRecord.getOrientation method", e)
                null
            }
        }

        private var hookOrientation: XC_MethodHook.Unhook? = null

        private fun isOnProjectionDisplay(activityRecord: Any?): Boolean {
            activityRecord ?: return false
            val projectionDisplayId = CoreManagerService.getDisplayId()
            if (projectionDisplayId == Display.INVALID_DISPLAY) return false
            return try {
                activityRecord.invokeMethod("getDisplayId") as? Int == projectionDisplayId
            } catch (e: Throwable) {
                false
            }
        }

        fun hook() {
            unHook()
            val target = activityRecord_getOverrideOrientation ?: activityRecord_getOrientation
            if (target == null) {
                log(tagName, "AppFullscreen: no orientation method found, skip")
                return
            }
            hookOrientation = target.hookAfter { param ->
                try {
                    if ((param.result as? Int) == SCREEN_ORIENTATION_UNSPECIFIED) return@hookAfter
                    if (!isOnProjectionDisplay(param.thisObject)) return@hookAfter
                    param.result = SCREEN_ORIENTATION_UNSPECIFIED
                } catch (e: Throwable) {
                    log(tagName, "AppFullscreen orientation Hook Exception", e)
                }
            }
            log(tagName, "AppFullscreen hooked: ${target.declaringClass.simpleName}.${target.name}")
        }

        fun unHook() {
            hookOrientation?.apply { unhook() }
            hookOrientation = null
        }
    }
}
