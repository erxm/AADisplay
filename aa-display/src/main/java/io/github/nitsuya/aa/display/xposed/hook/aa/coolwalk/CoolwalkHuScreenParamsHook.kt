package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import android.util.Size
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import io.github.nitsuya.aa.display.xposed.hook.HookTarget
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.logDebug
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 把 gearhead 自己协商出来的车机画布尺寸喂给 [CoolwalkRailCoordinator]。
 *
 * **为什么非要有这一步。** `:car` 进程里根本看不到车机原始宽度：
 * - HU 尺寸的 `LayoutDisplay`（DHU 上 1280×720）只在 `:projection` 里存活 15ms 就销毁，
 *   `:car` 里没有任何 HU 尺寸的 VirtualDisplay，所以
 *   [CoolwalkRailCoordinator.observeLiveVirtualDeviceHuSize] 在这里永远空手而归；
 * - gearhead → system_server 的 binder 被 [io.github.nitsuya.aa.display.xposed.BridgeService]
 *   的 uid 闸门拒掉（日志里成串的 `Someone else trying to get my binder?`），
 *   而 `CoolwalkRailStore` 的 Settings.Global 镜像只有 system_server 写得进去，
 *   system_server 自己也只知道内容槽宽度 → `:projection` 观测到的真宽度传不到 `:car`。
 *
 * 于是 `:car` 唯一能看到的就是「HU − 侧边栏」的内容槽（DHU 实测 1200），
 * `bestObservedFullHuWidthPx()` 退化成 0，[CoolwalkProjectionBoundsHook] 只能
 * `coerceAtLeast(trX)` → `rightGap=0` → 不扩宽，[CoolwalkDrawingSpecWiden] 和
 * `DisplayProfileSettle` 同样在 1200/1200 上空转，GhostActivityDisplay 按内容槽建出来
 * （`layerStackRect: Rect(0, 0 - 1200, 720)`）→ 高德等投屏应用右侧缺一条黑边，
 * 而 gearhead 自绘的 Coolwalk 桌面和手机端预览都正常。
 *
 * **数据从哪来。** `:car` 手里其实有真值，只是没人读：
 * `CAR.WM.LOADING.MAN: Show for STARTUP, screen dimens DisplayParams(selectedIndex=0,
 * codecWidth=1280, …, dispWidth=1280, dispHeight=720, dpi=160, scaledDimensions=1280x720, …)`。
 * 混淆会改类名/字段名，但**不改字符串常量**，所以这里按 `toString()` 取值
 * （[AaDpiHook][io.github.nitsuya.aa.display.xposed.hook.aa.AaDpiHook] 用的是同一个
 * `DisplayParams(selectedIndex=` 特征），并且不写死任何分辨率——实测车机有
 * 800×533@170、715×633@170、DHU 1280×720@160 等多种，判据只能来自运行时。
 *
 * 时序也够：DisplayParams 在 42.164 打出，[CoolwalkProjectionBoundsHook] 42.175 才第一次
 * 判定，Ghost display 42.183 才建，所以这里喂进去的宽度赶在所有消费者之前。
 */
object CoolwalkHuScreenParamsHook {

    /** `DisplayParams.toString()` 前缀，与 `AaDpiHook` 保持一致。 */
    private const val PARAMS_MARKER = "DisplayParams(selectedIndex="

    /** 类名兜底特征：只有 DisplayParams 会同时带这个字段名。 */
    private const val SCALED_MARKER = "scaledDimensions="

    /** 自校验特征：任何被当成 DisplayParams 的对象，`toString()` 里必须有它。 */
    private const val DISP_MARKER = "dispWidth="

    /** 打印上面那条日志的方法——它拿到的必然是**选中**的那组参数。 */
    private const val SHOW_MARKER = "screen dimens"

    /** 只做数量级消毒，不是分辨率白名单（715/800/1280 都在带内）。 */
    private const val MIN_HU_PX = 200
    private const val MAX_HU_PX = 8192

    /** DexKit 命中过多时的上限，避免给一堆同名类都挂钩子。 */
    private const val MAX_CANDIDATES = 4

    /**
     * `toString()` 被 R8 优化掉时的兜底：直接读构造器实参。
     * 索引来自 `AaDpiHook` 已经校验过的同一个构造器签名
     * （它就是靠 `args[8]` 改 dpi 的），与 toString 的字段顺序一一对应。
     */
    private const val ARG_DISP_W = 4
    private const val ARG_DISP_H = 5
    private const val ARG_SCALED = 12

    private val RE_DISP_W = Regex("dispWidth=(\\d{1,5})")
    private val RE_DISP_H = Regex("dispHeight=(\\d{1,5})")
    private val RE_SCALED = Regex("scaledDimensions=(\\d{1,5})x(\\d{1,5})")
    private val RE_DPI = Regex("dpi=(\\d{1,5})")

    private var installed = false

    private var paramsClassNames: List<String> = emptyList()
    private var showMethods: List<Method> = emptyList()

    @Volatile
    private var paramsClasses: List<Class<*>> = emptyList()

    /** 消费者方法把 DisplayParams 存字段时的缓存（形参里找不到的情况）。 */
    @Volatile
    private var cachedHolderField: Field? = null

    /** 已喂给协调器的尺寸，用来给重复上报去重。 */
    @Volatile
    private var acceptedWidth = 0

    @Volatile
    private var acceptedHeight = 0

    /**
     * 构造器观测到的所有不同尺寸。>1 说明车机广告了多组分辨率、构造顺序不代表选中项，
     * 这时候宁可退回今天的行为，也不能靠 grow-only 挑一个最大的乱扩。
     */
    private val observedSizes = ConcurrentHashMap.newKeySet<String>()

    fun resolve(bridge: DexKitBridge, target: HookTarget) {
        if (paramsClassNames.isEmpty()) {
            paramsClassNames = runCatching {
                val byToString = findClassNames(bridge, PARAMS_MARKER)
                val bySizeField =
                    if (byToString.isEmpty()) findClassNames(bridge, SCALED_MARKER) else emptyList()
                (byToString + bySizeField).distinct().take(MAX_CANDIDATES)
            }.onFailure { e ->
                log(CoolwalkHookEnv.TAG, "AaUiHook: DexKit DisplayParams class query failed", e)
            }.getOrDefault(emptyList())
        }
        if (showMethods.isEmpty()) {
            showMethods = runCatching {
                bridge.findMethod {
                    matcher { usingStrings { add(SHOW_MARKER, StringMatchType.Contains, false) } }
                }.mapNotNull { md ->
                    runCatching { md.getMethodInstance(target.classLoader) }.getOrNull()
                }.distinctBy { "${it.declaringClass.name}#${it.name}#${it.parameterTypes.size}" }
                    .take(MAX_CANDIDATES)
                    .onEach { it.isAccessible = true }
            }.onFailure { e ->
                log(CoolwalkHookEnv.TAG, "AaUiHook: DexKit screen-dimens method query failed", e)
            }.getOrDefault(emptyList())
        }
        logDebug(
            CoolwalkHookEnv.TAG,
            "AaUiHook: HU params classes=$paramsClassNames show=" +
                showMethods.map { "${it.declaringClass.name}#${it.name}" },
        )
    }

    private fun findClassNames(bridge: DexKitBridge, marker: String): List<String> =
        bridge.findClass {
            matcher { usingStrings { add(marker, StringMatchType.Contains, false) } }
        }.mapNotNull { runCatching { it.name }.getOrNull() }

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        paramsClasses = paramsClassNames.mapNotNull { name ->
            runCatching { Class.forName(name, false, classLoader) }.getOrNull()
        }
        var shows = 0
        for (method in showMethods) {
            runCatching {
                method.hookBefore { param -> onShowInvoked(method, param.args, param.thisObject) }
                shows++
            }.onFailure { e ->
                log(CoolwalkHookEnv.TAG, "AaUiHook: hook screen-dimens ${method.name} failed", e)
            }
        }
        // 消费者方法拿到的必然是选中项，是首选来源；构造器同时也喂，
        // 但只在「至今只见过一种尺寸」时——车机广告多组分辨率时构造顺序不可信，
        // 而 rememberFullHuSize 是 grow-only，挑错就等于挑了最大的那个。
        var ctors = 0
        for (clazz in paramsClasses) {
            for (ctor in clazz.declaredConstructors) {
                runCatching {
                    ctor.isAccessible = true
                    ctor.hookAfter { param -> onParamsConstructed(clazz, param.thisObject, param.args) }
                    ctors++
                }.onFailure { e ->
                    log(CoolwalkHookEnv.TAG, "AaUiHook: hook ${clazz.name} ctor failed", e)
                }
            }
        }
        if (shows == 0 && ctors == 0) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: HU screen params unavailable — 只能沿用内容槽宽度")
            return
        }
        log(
            CoolwalkHookEnv.TAG,
            "AaUiHook: HU screen params hooked show=$shows ctor=$ctors",
        )
    }

    private fun onShowInvoked(method: Method, args: Array<Any?>?, holder: Any?) {
        val text = args?.firstNotNullOfOrNull { textIfDisplayParams(it) }
            ?: textIfDisplayParams(holder)
            ?: holderFieldText(holder)
        if (text == null) {
            logDebug(CoolwalkHookEnv.TAG, "AaUiHook: ${method.name} 未携带 DisplayParams")
            return
        }
        accept("show:${method.name}", parse(text))
    }

    private fun onParamsConstructed(clazz: Class<*>, instance: Any?, args: Array<Any?>?) {
        val text = runCatching { instance?.toString() }.getOrNull()
        val hu = text?.takeIf { it.contains(DISP_MARKER) }?.let { parse(it) } ?: parseArgs(args)
        if (hu == null) return
        val firstSeen = observedSizes.add("${hu.width}x${hu.height}")
        if (observedSizes.size > 1) {
            // 多组候选分辨率：构造顺序不代表选中项，交给消费者方法定夺。
            if (firstSeen) {
                log(
                    CoolwalkHookEnv.TAG,
                    "AaUiHook: HU 候选 ${hu.width}x${hu.height} 不采信（已见 $observedSizes）" +
                        " [ctor:${clazz.simpleName}]",
                )
            }
            return
        }
        accept("ctor:${clazz.simpleName}", hu)
    }

    private fun accept(source: String, hu: HuScreen?) {
        if (hu == null) return
        if (hu.width == acceptedWidth && hu.height == acceptedHeight) return
        acceptedWidth = hu.width
        acceptedHeight = hu.height
        log(
            CoolwalkHookEnv.TAG,
            "AaUiHook: HU screen ${hu.width}x${hu.height} dpi=${hu.dpi} " +
                "disp=${hu.dispW}x${hu.dispH} [$source]",
        )
        if (hu.width <= hu.height) {
            // rememberFullHuSize 只收横屏画布，会静默丢弃这组；打出来省得下次重新查。
            log(CoolwalkHookEnv.TAG, "AaUiHook: HU 非横屏，rememberFullHuSize 丢弃 [$source]")
            return
        }
        CoolwalkRailCoordinator.rememberFullHuSize(hu.width, hu.height)
        logDebug(
            CoolwalkHookEnv.TAG,
            "AaUiHook: HU full=${CoolwalkRailCoordinator.observedFullHuWidthPx()} [$source]",
        )
    }

    /** 自校验：类可能被认错，但 `dispWidth=` 认不错。 */
    private fun textIfDisplayParams(value: Any?): String? {
        if (value == null) return null
        val classes = paramsClasses
        if (classes.isNotEmpty() && classes.none { it.isInstance(value) }) return null
        val text = runCatching { value.toString() }.getOrNull() ?: return null
        return text.takeIf { it.contains(DISP_MARKER) }
    }

    /** 消费者方法从字段里读 DisplayParams（而不是走形参）的情况。 */
    private fun holderFieldText(holder: Any?): String? {
        if (holder == null || paramsClasses.isEmpty()) return null
        cachedHolderField?.let { field ->
            return textIfDisplayParams(runCatching { field.get(holder) }.getOrNull())
        }
        val fields = runCatching { holder.javaClass.declaredFields }.getOrNull() ?: return null
        for (field in fields) {
            if (paramsClasses.none { it.isAssignableFrom(field.type) }) continue
            runCatching { field.isAccessible = true }
            val text = textIfDisplayParams(runCatching { field.get(holder) }.getOrNull()) ?: continue
            cachedHolderField = field
            return text
        }
        return null
    }

    private fun parse(text: String): HuScreen? {
        if (!text.contains(DISP_MARKER)) return null
        val dispW = RE_DISP_W.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        val dispH = RE_DISP_H.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        val scaled = RE_SCALED.find(text)?.groupValues
        val dpi = RE_DPI.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return build(
            scaledW = scaled?.getOrNull(1)?.toIntOrNull() ?: 0,
            scaledH = scaled?.getOrNull(2)?.toIntOrNull() ?: 0,
            dispW = dispW,
            dispH = dispH,
            dpi = dpi,
        )
    }

    /** `toString()` 被优化掉时读构造器实参，索引见 [ARG_DISP_W]。 */
    private fun parseArgs(args: Array<Any?>?): HuScreen? {
        if (args == null || args.size <= ARG_SCALED) return null
        val dispW = args[ARG_DISP_W] as? Int ?: return null
        val dispH = args[ARG_DISP_H] as? Int ?: return null
        val scaled = args[ARG_SCALED] as? Size
        return build(
            scaledW = scaled?.width ?: 0,
            scaledH = scaled?.height ?: 0,
            dispW = dispW,
            dispH = dispH,
            dpi = 0,
        )
    }

    /** scaledDimensions 才是 gearhead 实际排版用的尺寸（像素非方形时与 disp* 不同）。 */
    private fun build(scaledW: Int, scaledH: Int, dispW: Int, dispH: Int, dpi: Int): HuScreen? {
        val useScaled = inHuRange(scaledW) && inHuRange(scaledH)
        val width = if (useScaled) scaledW else dispW
        val height = if (useScaled) scaledH else dispH
        if (!inHuRange(width) || !inHuRange(height)) return null
        return HuScreen(width, height, dpi, dispW, dispH)
    }

    private fun inHuRange(value: Int): Boolean = value in MIN_HU_PX..MAX_HU_PX

    private class HuScreen(
        val width: Int,
        val height: Int,
        val dpi: Int,
        val dispW: Int,
        val dispH: Int,
    )
}
