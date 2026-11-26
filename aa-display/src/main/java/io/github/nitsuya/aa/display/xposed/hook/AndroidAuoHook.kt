package io.github.nitsuya.aa.display.xposed.hook

import android.app.Application
import android.app.Instrumentation
import android.content.SharedPreferences
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import io.github.nitsuya.aa.display.xposed.HookPreferences
import io.github.nitsuya.aa.display.xposed.hook.aa.AaBasicsHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaBtnEventHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaDpiHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaMediaPlaceholderHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaPropsHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaSignatureHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaUiHook
import io.github.nitsuya.aa.display.xposed.log
import org.luckypray.dexkit.DexKitBridge
import kotlin.system.measureTimeMillis


abstract class AaHook {
    companion object {
        const val processMain =       "com.google.android.projection.gearhead"
        const val processProjection = "com.google.android.projection.gearhead:projection"
        const val processCar =        "com.google.android.projection.gearhead:car"
    }
    abstract val tagName: String
    abstract fun isSupportProcess(processName: String) : Boolean

    /**
     * 配置级开关：即使 [isSupportProcess] 命中，也可以按配置整块跳过。
     * 在 [loadDexClass] / [hook] 之前调用一次，供 hook 缓存自己的开关状态。
     */
    open fun isEnabled(config: SharedPreferences?, processName: String): Boolean = true
    open fun loadDexClass(bridge: DexKitBridge, target: HookTarget) {}
    abstract fun hook(config: SharedPreferences?, target: HookTarget)
}

object AndroidAuoHook : BaseHook() {
    override val tagName: String = "AAD_AndroidAuoHook"
    override fun init(target: HookTarget) {
        val processName = target.processName
        val supported = listOf(AaBasicsHook, AaSignatureHook, AaDpiHook, AaBtnEventHook, AaUiHook, AaMediaPlaceholderHook, AaPropsHook)
            .filter { i -> i.isSupportProcess(processName) }
        if(supported.isEmpty()) return

        val config = HookPreferences.get()

        val hooks = supported.filter { h ->
            runCatching { h.isEnabled(config, processName) }.onFailure { e ->
                log(tagName, "${h.tagName} isEnabled failed; keeping hook enabled", e)
            }.getOrDefault(true)
        }
        if(hooks.isEmpty()) return

        val sourceDir = target.appInfo?.sourceDir
        if (sourceDir == null) {
            log(tagName, "no sourceDir for ${target.packageName}; skip")
            return
        }

        var onCreateApplication: XC_MethodHook.Unhook? = null
        onCreateApplication = findMethod(Instrumentation::class.java) {
            name == "callApplicationOnCreate"
            && parameterCount == 1
            && parameterTypes[0] == Application::class.java
        }.hookBefore { param ->
            onCreateApplication?.unhook()
            try {
                EzXHelperInit.initAppContext()
                // 不依赖 DexKit：即使特征失配，App 侧也要能启停车机服务器。
                (param.args.getOrNull(0) as? Application)?.let { app ->
                    runCatching { HeadUnitServerBridge.register(app) }
                        .onFailure { e -> log(tagName, "HeadUnitServerBridge register failed", e) }
                }
                System.loadLibrary("dexkit")
                val readyHooks = mutableListOf<AaHook>()
                DexKitBridge.create(sourceDir).use { bridge ->
                    if(bridge == null){
                        log(tagName,"DexKitBridge.create() failed")
                        return@hookBefore
                    }
                    val measureTimeMillis = measureTimeMillis {
                        hooks.forEach { h ->
                            // 每个 hook 独立解析：某一个 DexKit 特征失配不能拖垮整块功能。
                            runCatching { h.loadDexClass(bridge, target) }
                                .onSuccess { readyHooks.add(h) }
                                .onFailure { e ->
                                    log(tagName, "${h.tagName} loadDexClass failed; skip this hook", e)
                                }
                        }
                    }
                    log(tagName,"${target.processName} load class measure ${measureTimeMillis}ms")
                }
                readyHooks.forEach { h ->
                    runCatching { h.hook(config, target) }
                        .onFailure { e -> log(tagName, "${h.tagName} hook failed", e) }
                }
            } catch (t: Throwable) {
                log(tagName, "AndroidAuoHook init failed", t)
            }
        }
    }
}


