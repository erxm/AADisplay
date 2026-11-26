package io.github.nitsuya.aa.display.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块 App 侧的配置写入镜像。
 *
 * 现代 Xposed API 下 hook 进程读的是**框架守护进程里的远端配置**
 * （SQLite，见 [io.github.libxposed.service.RemotePreferences]），
 * 而不是模块自己 `shared_prefs` 目录里的 xml。UI（[io.github.nitsuya.aa.display.ui.setting.SettingsActivity]
 * 的 `PreferenceFragmentCompat`、MainActivity 的开关等）仍然写本地
 * SharedPreferences —— 那是 preference 库唯一能接的存储 —— 所以这里挂一个监听器，
 * 把本地的每次改动同步到远端。
 *
 * 旧框架（不支持 api 101 的 LSPosed）走 [XposedInit][io.github.nitsuya.aa.display.xposed.XposedInit]
 * + `XSharedPreferences`，读的就是那份本地 xml，因此本地存储不能去掉，
 * [SharedPreferencesAccess] 放宽权限的逻辑也要保留。两条路径共用同一份本地数据。
 */
object RemoteConfigMirror {

    private const val TAG = "AAD_RemoteConfigMirror"

    @Volatile
    private var installed = false

    /** 本地存储。强引用：[SharedPreferences] 的监听器注册表是弱引用的。 */
    private var local: SharedPreferences? = null

    /** 远端配置，绑定到框架服务后才有值。 */
    @Volatile
    private var remote: SharedPreferences? = null

    /**
     * [XposedServiceHelper.registerListener] 只保留一个监听器，且 helper 自己
     * 用弱引用持有回调链，这里必须是常驻对象字段。
     */
    private val serviceListener = object : XposedServiceHelper.OnServiceListener {
        override fun onServiceBind(service: XposedService) {
            val prefs = runCatching { service.getRemotePreferences(AADisplayConfig.ConfigName) }
                .onFailure { Log.w(TAG, "getRemotePreferences failed", it) }
                .getOrNull() ?: return
            remote = prefs
            Log.i(TAG, "xposed service bound: ${service.frameworkName} ${service.frameworkVersion}")
            syncAll()
        }

        override fun onServiceDied(service: XposedService) {
            Log.i(TAG, "xposed service died")
            remote = null
        }
    }

    private val localListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key != null) pushKey(prefs, key)
    }

    /**
     * 在 [io.github.nitsuya.aa.display.Application.onCreate] 里调用。
     * 每个 App 进程各自安装一次；框架未激活时监听器不会回调，无副作用。
     */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        // 先完成初始化再置位：任何一步失败都要允许后续重试，
        // 否则一次早期失败会让配置镜像在进程生命周期内永久失效。
        val prefs = runCatching {
            SharedPreferencesAccess.openForHooks(context, AADisplayConfig.ConfigName)
        }.onFailure { Log.w(TAG, "open local config failed", it) }.getOrNull() ?: return
        local = prefs
        prefs.registerOnSharedPreferenceChangeListener(localListener)
        runCatching { XposedServiceHelper.registerListener(serviceListener) }
            .onFailure { Log.w(TAG, "registerListener failed", it) }
        installed = true
    }

    /** 本地 → 远端全量同步。绑定成功时跑一次，把历史配置补齐。 */
    private fun syncAll() {
        val source = local ?: return
        val target = remote ?: return
        runCatching {
            val editor = target.edit()
            var count = 0
            for ((key, value) in source.all) {
                putValue(editor, key, value)
                count++
            }
            editor.apply()
            Log.i(TAG, "synced $count keys to remote preferences")
        }.onFailure { Log.w(TAG, "full sync failed", it) }
    }

    private fun pushKey(source: SharedPreferences, key: String) {
        val target = remote ?: return
        runCatching {
            val editor = target.edit()
            val value = source.all[key]
            if (value == null) editor.remove(key) else putValue(editor, key, value)
            editor.apply()
            Log.i(TAG, "pushed $key=$value to remote preferences")
        }.onFailure { Log.w(TAG, "push $key failed", it) }
    }

    private fun putValue(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> Log.w(TAG, "unsupported value type for $key: ${value.javaClass.name}")
        }
    }
}
