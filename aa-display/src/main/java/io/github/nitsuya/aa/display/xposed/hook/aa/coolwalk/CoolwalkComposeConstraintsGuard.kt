package io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk

import com.github.kyuubiran.ezxhelper.utils.hookBefore
import io.github.nitsuya.aa.display.xposed.hook.HookTarget
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.logDebug
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * gearhead 17.x 的车机 UI 是 Compose。隐藏 UI 控件栈
 * （[io.github.nitsuya.aa.display.xposed.hook.aa.AaUiHook] 的 hideAaUiControls）会把
 * Coolwalk 的 GhFacetBar VD 饿到 1×H、Dashboard VD 饿到 1×1，并把残留窗口宽度写 0。
 *
 * Compose 的 `Constraints(minWidth, maxWidth, minHeight, maxHeight)` 工厂里是硬断言：
 * `maxWidth >= minWidth && maxHeight >= minHeight && minWidth >= 0 && minHeight >= 0`，
 * 不满足就抛 [IllegalArgumentException]
 * `maxWidth must be >= than minWidth,\nmaxHeight must be >= than minHeight,\nminWidth and minHeight must be >= 0`。
 *
 * 分栏容器（`Lmrs;` MeasurePolicy，`Llwd;` 里带 `IS_HORIZONTAL` 的那套布局）测量时用
 * 「主轴可用量 − 第一组已用量」当第二组的预算，再经 `Constraints.copy` 写回主轴 max。
 * 容器只剩 0/1 px 时这个差值为负 → 断言失败 → 在 `AndroidComposeView.dispatchDraw`
 * 的延迟测量里直接打死 `gearhead:projection`（连车/连 DHU 时表现为 Android Auto 闪退）。
 *
 * 这里只钩那两个校验入口：**参数合法时立即原样放行**（稳态开销就是四次 int 比较），
 * 只有本来必定抛异常的四元组才夹回合法值——负的 max 收到 0，min 再夹到 max 以内，
 * 等价于「这一轴没有可用空间」，子项量成 0，和 Compose 处理 0 尺寸容器的行为一致。
 * 不吞异常、不改任何有效约束的语义，也不依赖被饿死的具体窗口是哪一个。
 */
object CoolwalkComposeConstraintsGuard {

    // AA 17.8 把这条断言改成了带格式符的版本：
    //   "maxWidth(%d) must be >= than minWidth(%d),\nmaxHeight(%d) must be >= than minHeight(%d)"
    // 旧串 "maxWidth must be >= than minWidth" 因此不再是被包含的子串，DexKit 命中为 0。
    // 取两段共有的中缀 "must be >= than minWidth"：17.2 与 17.8 都能匹配。
    private const val FAILURE_MARKER = "must be >= than minWidth"
    private const val LOG_LIMIT = 8

    /**
     * 所有会抛这条断言的 `Constraints(minWidth, maxWidth, minHeight, maxHeight): long` 校验入口。
     * 17.3 里有两个（一个直接比较后抛，一个先与出布尔再抛），必须都钩，
     * 只钩其中一个等于赌哪条路径先炸。
     */
    private var factories: List<Method> = emptyList()

    private var installed = false
    private var repaired = 0

    fun resolve(bridge: DexKitBridge, target: HookTarget) {
        if (factories.isNotEmpty()) return
        val candidates = runCatching {
            bridge.findMethod {
                matcher {
                    usingStrings { add(FAILURE_MARKER, StringMatchType.Contains, false) }
                }
            }.mapNotNull { md ->
                runCatching { md.getMethodInstance(target.classLoader) }.getOrNull()
            }
        }.onFailure { e ->
            log(CoolwalkHookEnv.TAG, "AaUiHook: DexKit Constraints factory query failed", e)
        }.getOrDefault(emptyList())

        val matched = candidates.filter { m ->
            Modifier.isStatic(m.modifiers) &&
                m.returnType == Long::class.javaPrimitiveType &&
                m.parameterTypes.size == 4 &&
                m.parameterTypes.all { it == Int::class.javaPrimitiveType }
        }.onEach { it.isAccessible = true }
        if (matched.isEmpty()) {
            log(
                CoolwalkHookEnv.TAG,
                "AaUiHook: Compose Constraints factory not found, candidates=" +
                    candidates.map { "${it.declaringClass.name}#${it.name}" },
            )
            return
        }
        factories = matched
        log(
            CoolwalkHookEnv.TAG,
            "AaUiHook: Compose Constraints factories=${matched.size} " +
                matched.map { "${it.declaringClass.name}#${it.name}" },
        )
    }

    fun install() {
        if (installed) return
        if (factories.isEmpty()) {
            log(CoolwalkHookEnv.TAG, "AaUiHook: Compose Constraints guard unavailable")
            return
        }
        installed = true
        var hooked = 0
        for (method in factories) {
            runCatching {
                method.hookBefore { param -> clampConstraintArgs(param.args) }
                hooked++
            }.onFailure { e ->
                log(
                    CoolwalkHookEnv.TAG,
                    "AaUiHook: hook Constraints ${method.declaringClass.name}#${method.name} failed",
                    e,
                )
            }
        }
        logDebug(
            CoolwalkHookEnv.TAG,
            "AaUiHook: Compose Constraints guard hooked=$hooked/${factories.size}",
        )
    }

    internal fun clampConstraintArgs(args: Array<Any?>?) {
        if (args == null || args.size != 4) return
        val minW = args[0] as? Int ?: return
        val maxW = args[1] as? Int ?: return
        val minH = args[2] as? Int ?: return
        val maxH = args[3] as? Int ?: return
        if (minW >= 0 && minH >= 0 && maxW >= minW && maxH >= minH) return

        val safeMaxW = maxW.coerceAtLeast(0)
        val safeMinW = minW.coerceIn(0, safeMaxW)
        val safeMaxH = maxH.coerceAtLeast(0)
        val safeMinH = minH.coerceIn(0, safeMaxH)
        args[0] = safeMinW
        args[1] = safeMaxW
        args[2] = safeMinH
        args[3] = safeMaxH
        note("w[$minW..$maxW]→[$safeMinW..$safeMaxW] h[$minH..$maxH]→[$safeMinH..$safeMaxH]")
    }

    private fun note(detail: String) {
        repaired++
        when {
            // 第一次带栈打印，用来确认修复点确实落在分栏容器的测量路径上。
            repaired == 1 -> log(
                CoolwalkHookEnv.TAG,
                "AaUiHook: repair Constraints $detail #1",
                Throwable("constraints-repair-trace"),
            )
            repaired <= LOG_LIMIT ->
                logDebug(CoolwalkHookEnv.TAG, "AaUiHook: repair Constraints $detail #$repaired")
            repaired % 500 == 0 ->
                logDebug(CoolwalkHookEnv.TAG, "AaUiHook: repaired Constraints x$repaired")
        }
    }
}
