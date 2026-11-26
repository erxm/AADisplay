package io.github.nitsuya.aa.display.xposed

import android.content.SharedPreferences

/**
 * 被 hook 进程侧的模块配置入口。
 *
 * 迁移到 libxposed 之前，每个 hook 各自 `XSharedPreferences(APPLICATION_ID, ConfigName)`
 * 再 `canRead()` / `reload()`。那套做法要求模块把 `aadisplay_config.xml` 设成 others 可读，
 * 正是 LSPosed 2.2.0 开始告警、2.3.0 计划移除的东西。
 *
 * 现代 API 走 `XposedInterface.getRemotePreferences(group)`：配置存在框架守护进程的
 * SQLite 里，通过 Binder 读，进程内拿到的实现是**实时**的（框架会推变更），因此
 * 没有 `reload()` 的对应物。
 *
 * 两种来源由入口类注入，hook 侧只认这里的 [get] / [latest]：
 * - [ModernInit] 注入 `getRemotePreferences`；
 * - 旧版 LSPosed 上的 [XposedInit] 注入 `XSharedPreferences` + 一个 `reload()` 刷新器。
 *
 * 解析结果会缓存（含解析失败的 null），避免每次 hook 触发都做一次 IPC。
 */
object HookPreferences {
    private const val TAG = "AAD_HookPreferences"

    private var supplier: (() -> SharedPreferences?)? = null
    private var refresher: ((SharedPreferences) -> Unit)? = null

    @Volatile
    private var cached: SharedPreferences? = null

    @Volatile
    private var resolved: Boolean = false

    /**
     * 注入配置来源。入口类在分发任何 hook 之前调用。
     *
     * @param refresher 读取前的刷新动作。`XSharedPreferences` 需要 `reload()`；
     *   远端配置是实时的，传 null。
     */
    @Synchronized
    fun install(
        supplier: () -> SharedPreferences?,
        refresher: ((SharedPreferences) -> Unit)? = null,
    ) {
        this.supplier = supplier
        this.refresher = refresher
        cached = null
        resolved = false
    }

    /** 取配置；只解析一次，失败返回 null（各配置项自带默认值，功能不因此中断）。 */
    fun get(): SharedPreferences? {
        if (resolved) return cached
        return resolve()
    }

    /**
     * 取配置并先刷新一次。legacy 下等于 `reload()`；现代 API 下与 [get] 等价。
     * 用在原先显式 `reload()` 的那些位置。
     */
    fun latest(): SharedPreferences? {
        val config = get() ?: return null
        refresher?.let { refresh ->
            runCatching { refresh(config) }.onFailure { e ->
                log(TAG, "refresh failed; using cached values", e)
            }
        }
        return config
    }

    /** 解析失败后的退避，避免每次 hook 都打一次 IPC；窗口内继续用默认值。 */
    private const val RESOLVE_RETRY_BACKOFF_MS = 2_000L

    @Volatile
    private var lastResolveFailureUptimeMs = 0L

    @Synchronized
    private fun resolve(): SharedPreferences? {
        if (resolved) return cached
        val provider = supplier
        if (provider == null) {
            log(TAG, "no preferences source installed; using defaults")
            resolved = true
            return null
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (lastResolveFailureUptimeMs != 0L &&
            now - lastResolveFailureUptimeMs in 0 until RESOLVE_RETRY_BACKOFF_MS
        ) {
            return null
        }
        val result = runCatching { provider() }.onFailure { e ->
            log(TAG, "failed to obtain module preferences; will retry", e)
        }.getOrNull()
        if (result != null) {
            cached = result
            resolved = true
            lastResolveFailureUptimeMs = 0L
            return result
        }
        // 关键：解析失败不能标记为 resolved，否则整个进程生命周期都读不到配置。
        lastResolveFailureUptimeMs = now
        log(TAG, "module preferences unavailable; using defaults until next retry")
        return null
    }
}
