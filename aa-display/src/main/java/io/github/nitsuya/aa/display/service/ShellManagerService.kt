package io.github.nitsuya.aa.display.service

import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.topjohnwu.superuser.Shell
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.SharedPreferencesAccess
import io.github.nitsuya.aa.display.xposed.IShellManager

class ShellManagerService: Service() {
    companion object {
        private const val TAG = "AADisplay_ShellManagerService"
    }

    private lateinit var config: SharedPreferences
    private val stub: IShellManager.Stub = object: IShellManager.Stub(){
        override fun createVirtualDisplayBefore(): Boolean {
            if (!isCallerAllowed("createVirtualDisplayBefore")) return false
            return execConfigShell(
                "createVirtualDisplayBefore",
                AADisplayConfig.CreateVirtualDisplayBefore.get(config)
            )
        }

        override fun destroyVirtualDisplayAfter(): Boolean {
            if (!isCallerAllowed("destroyVirtualDisplayAfter")) return false
            return execConfigShell(
                "destroyVirtualDisplayAfter",
                AADisplayConfig.DestroyVirtualDisplayAfter.get(config)
            )
        }

        private fun execConfigShell(phase: String, commands: Array<String>): Boolean {
            if (commands.isEmpty()) {
                Log.i(TAG, "shell[$phase] skipped: no configured commands")
                return true
            }

            return try {
                val shell = Shell.getShell()
                if (!shell.isRoot) {
                    Log.e(TAG, "shell[$phase] failed: root shell unavailable")
                    return false
                }
                val result = shell.newJob().add(*commands).exec()
                Log.i(
                    TAG,
                    "shell[$phase]: success=${result.isSuccess}, code=${result.code}, " +
                        "commands=${commands.joinToString(" | ")}, " +
                        "out=${result.out.joinToString("\\n").take(512)}, " +
                        "err=${result.err.joinToString("\\n").take(512)}"
                )
                result.isSuccess
            } catch (e: Throwable) {
                Log.e(TAG, "shell[$phase] threw", e)
                false
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        config = SharedPreferencesAccess.openForHooks(this, AADisplayConfig.ConfigName)
        SharedPreferencesAccess.makeReadableForHooks(this, AADisplayConfig.ConfigName)
        Log.i(TAG, "onCreate")
    }
    /**
     * 真正的权限校验必须在 Stub 的方法里做。
     *
     * [onBind] 由 ActivityThread 在主线程本地调用（不在 binder 事务里），那里
     * `Binder.getCallingUid()` 拿到的是本进程 uid，白名单恒为真；而 manifest 里本服务是
     * exported 的，任意第三方应用都能 bind 并拿到这个 IBinder。唯一合法调用方是
     * system_server 里的 AaVirtualDisplayAdapter。
     */
    private fun isCallerAllowed(phase: String): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.SYSTEM_UID || uid == Process.myUid()) return true
        Log.w(TAG, "shell[$phase] rejected: caller uid=$uid")
        return false
    }

    override fun onBind(intent: Intent?): IBinder? {
        // 这里不做判断（拿不到真实调用方），交给 [isCallerAllowed] 逐方法校验。
        Log.i(TAG, "onBind intent=$intent")
        return stub
    }
}
