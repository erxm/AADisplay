package io.github.nitsuya.aa.display.util

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/**
 * MIUI / HyperOS 护眼（纸质）模式被 `DisplayFeatureManagerService` 实现为 SF 侧的
 * **per-display colorTransformMatrix**，而 `getEffectedDisplayIndex()` 在非折叠机上
 * 恒返回 `[0]`，SF 再把该矩阵传播到**所有** display —— 包括 AA 投屏用的 VirtualDisplay。
 * 于是护眼一开，车机画面跟着变黄。
 *
 * MIUI 没有任何“排除某个 display”的接口：
 * - `projectionDisplayFlag` / `isCastMode` / `isScreenProjection` 不参与颜色变换
 *   （实测投屏 VD 已经是 `projectionDisplayFlag=1` 却照样带黄矩阵）；
 * - `read_apps_only` / `EffectScopeManager` 仅在 `IS_GREEN_MOUNTAIN_EYECARE_V3` 机型生效；
 * - framework 里没有 display 级 color matrix API（只有 per-layer
 *   `SurfaceControl$Transaction.setColorTransform`）。
 *
 * 也不能用 `screen_game_mode` 的 `mForceDisableEyeCare` 位：实测它会把
 * `setScreenEffect mode=3` ramp 到 0，但 **SF 矩阵不变**，画面依旧发黄。
 *
 * 因此唯一有效开关是 `Settings.System.screen_paper_mode_enabled`：置 0 时矩阵回到中性，
 * 置回 1 时按 `screen_paper_mode_level` 等原设置立即恢复（等级类键我们从不触碰）。
 *
 * 代价：抑制是**全局**的 —— 投屏期间手机自己的屏幕也暂时没有护眼效果，
 * 设置里的护眼开关也会显示为关闭。这是 MIUI 实现决定的，没有 per-display 方案。
 *
 * 生命周期：[suspend] 在 VD 创建时调用，[restore] 在 VD 销毁时调用；
 * 原值持久化在模块自有的 Settings.Global 键里，[recoverStaleSuspend] 在
 * `systemReady()` 兜底回滚“上一次开机中途被杀、没来得及 restore”的残留状态。
 */
object EyeCareSuspender {
    /** MIUI 护眼总开关，也是本机唯一能改变 SF colorTransformMatrix 的键。 */
    private const val KEY_PAPER_MODE_ENABLED = "screen_paper_mode_enabled"

    /** 模块自有键：投屏前的护眼开关原值（-1 = 无挂起）。 */
    const val SETTINGS_SAVED_PAPER_MODE = "aadisplay_eyecare_saved_paper_mode"

    /** 模块自有键：挂起时刻的 uptime，用于跨开机残留识别。 */
    const val SETTINGS_SUSPEND_UPTIME_MS = "aadisplay_eyecare_suspend_uptime_ms"

    /**
     * 模块自有键：挂起时刻的 [Settings.Global.BOOT_COUNT]。
     * uptime 比较不可靠：框架软重启不会归零，整机重启时若 saved 很小也可能漏判；
     * boot count 变了就一定是新的一次开机。
     */
    const val SETTINGS_SUSPEND_BOOT_COUNT = "aadisplay_eyecare_suspend_boot_count"

    private const val NONE = -1

    private val mHandler = Handler(Looper.getMainLooper())

    /** 投屏期间监听护眼被重新打开（日程/用户手动），压回去并记下待还原值。 */
    private var mObserver: ContentObserver? = null

    /** 结果只回报给调用方打日志，避免这里依赖 xposed 的 log。 */
    sealed class Result {
        object Disabled : Result()
        object Unsupported : Result()
        object AlreadyActive : Result()
        object NotActive : Result()
        object NothingToDo : Result()
        data class Applied(val savedValue: Int) : Result()
        data class Restored(val toValue: Int) : Result()
        data class Failed(val error: Throwable) : Result()
    }

    /**
     * 该机型是否有 MIUI 护眼实现。没有就完全不写任何键，
     * 避免在原生 / 其他 OEM 上留下无意义的设置项。
     */
    private fun isSupported(cr: ContentResolver): Boolean =
        runCatching { Settings.System.getString(cr, KEY_PAPER_MODE_ENABLED) != null }
            .getOrDefault(false)

    private fun readPaperMode(cr: ContentResolver): Int =
        runCatching { Settings.System.getInt(cr, KEY_PAPER_MODE_ENABLED, 0) }.getOrDefault(0)

    private fun readSaved(cr: ContentResolver): Int =
        runCatching { Settings.Global.getInt(cr, SETTINGS_SAVED_PAPER_MODE, NONE) }.getOrDefault(NONE)

    /**
     * Settings.Global 跨开机存活，而 [SystemClock.uptimeMillis] 每次开机归零：
     * 存的 uptime 比现在大，就一定来自上一次开机
     * （与 [CoolwalkRailStore.isCrossBootStale] 同一判据）。
     */
    private fun currentBootCount(cr: ContentResolver): Int =
        runCatching { Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)

    private fun isCrossBootStale(cr: ContentResolver): Boolean {
        val saved = runCatching {
            Settings.Global.getLong(cr, SETTINGS_SUSPEND_UPTIME_MS, 0L)
        }.getOrDefault(0L)
        if (saved > SystemClock.uptimeMillis()) return true
        // boot count 变了就是新的一次开机，不能只看 uptime。
        val savedBoot = runCatching {
            Settings.Global.getInt(cr, SETTINGS_SUSPEND_BOOT_COUNT, -1)
        }.getOrDefault(-1)
        val currentBoot = currentBootCount(cr)
        return savedBoot >= 0 && currentBoot >= 0 && savedBoot != currentBoot
    }

    private fun writeSaved(cr: ContentResolver, value: Int) {
        runCatching {
            Settings.Global.putInt(cr, SETTINGS_SAVED_PAPER_MODE, value)
            Settings.Global.putLong(cr, SETTINGS_SUSPEND_UPTIME_MS, SystemClock.uptimeMillis())
            Settings.Global.putInt(cr, SETTINGS_SUSPEND_BOOT_COUNT, currentBootCount(cr))
        }
    }

    private fun clearSaved(cr: ContentResolver) {
        runCatching {
            Settings.Global.putInt(cr, SETTINGS_SAVED_PAPER_MODE, NONE)
            Settings.Global.putLong(cr, SETTINGS_SUSPEND_UPTIME_MS, 0L)
            Settings.Global.putInt(cr, SETTINGS_SUSPEND_BOOT_COUNT, -1)
        }
    }

    /** 投屏开始：关掉护眼总开关，记下原值。软重连会重复调用，保持幂等。 */
    fun suspend(context: Context, enabled: Boolean): Result {
        if (!enabled) return Result.Disabled
        return runCatching {
            val cr = context.contentResolver
            if (!isSupported(cr)) return Result.Unsupported
            val alreadySuspended = readSaved(cr) != NONE && !isCrossBootStale(cr)
            if (alreadySuspended) {
                if (readPaperMode(cr) != 0) Settings.System.putInt(cr, KEY_PAPER_MODE_ENABLED, 0)
                startObserver(context)
                return Result.AlreadyActive
            }
            val original = readPaperMode(cr)
            if (original == 0) {
                // 护眼本来就关着：不写任何键，断开时也无需还原。
                return Result.NothingToDo
            }
            writeSaved(cr, original)
            Settings.System.putInt(cr, KEY_PAPER_MODE_ENABLED, 0)
            startObserver(context)
            Result.Applied(original)
        }.getOrElse { Result.Failed(it) }
    }

    /** 投屏结束：写回挂起期间记录的“用户意图值”。 */
    fun restore(context: Context): Result {
        return runCatching {
            val cr = context.contentResolver
            stopObserver(cr)
            val saved = readSaved(cr)
            if (saved == NONE) return Result.NotActive
            if (readPaperMode(cr) != saved) {
                Settings.System.putInt(cr, KEY_PAPER_MODE_ENABLED, saved)
            }
            clearSaved(cr)
            Result.Restored(saved)
        }.getOrElse { Result.Failed(it) }
    }

    /**
     * 开机兜底：上一次开机里挂起后没能 restore（system_server 被杀 / 直接重启），
     * 护眼会一直停在关闭状态。`systemReady()` 里调用一次即可补回。
     */
    fun recoverStaleSuspend(context: Context): Result {
        return runCatching {
            val cr = context.contentResolver
            if (readSaved(cr) == NONE) return Result.NotActive
            if (!isCrossBootStale(cr)) return Result.NotActive
            restore(context)
        }.getOrElse { Result.Failed(it) }
    }

    /**
     * 护眼日程（`paper_mode_scheduler_type`）或用户手动可能在投屏中途把护眼打开，
     * 车机会立刻变黄。这里压回 0，同时把“用户意图值”更新为 1，
     * 断开投屏后按该意图恢复，不会吞掉用户的操作。
     */
    private fun startObserver(context: Context) {
        if (mObserver != null) return
        val cr = context.contentResolver
        val observer = object : ContentObserver(mHandler) {
            override fun onChange(selfChange: Boolean) {
                runCatching {
                    if (readSaved(cr) == NONE) return
                    val current = readPaperMode(cr)
                    if (current == 0) return
                    writeSaved(cr, current)
                    Settings.System.putInt(cr, KEY_PAPER_MODE_ENABLED, 0)
                }
            }
        }
        runCatching {
            cr.registerContentObserver(
                Settings.System.getUriFor(KEY_PAPER_MODE_ENABLED),
                false,
                observer,
            )
            mObserver = observer
        }
    }

    private fun stopObserver(cr: ContentResolver) {
        val observer = mObserver ?: return
        mObserver = null
        runCatching { cr.unregisterContentObserver(observer) }
    }
}
