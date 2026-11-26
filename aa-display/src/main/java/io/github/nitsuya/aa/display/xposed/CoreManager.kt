package io.github.nitsuya.aa.display.xposed

import android.os.IBinder.DeathRecipient
import android.os.Parcel
import android.os.RemoteException
import android.os.ServiceManager
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import io.github.nitsuya.aa.display.model.RecentTask
import io.github.nitsuya.template.bases.runIO
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object CoreManager : ICoreManager, DeathRecipient {
    private const val TAG = "CoreManager"

    private class ServiceProxy(private val obj: ICoreManager) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            return try {
                val result = method.invoke(obj, *args.orEmpty())
                // touch 是每帧调用的热路径，逐条日志会造成 log storm 并拖慢输入。
                if (method.name != "touch") {
                    Log.i(TAG, "Call service method ${method.name}")
                }
                result
            } catch (e: Throwable) {
                val cause = (e as? InvocationTargetException)?.targetException ?: e
                if (cause is android.os.DeadObjectException || cause is android.os.RemoteException) {
                    // 服务端已死：清缓存，下一次调用重新取 binder。
                    service = null
                    serviceMissUptimeMs = 0L
                }
                Log.w(TAG, "service call ${method.name} failed", cause)
                defaultValue(method.returnType)
            }
        }

        /** 远端失败时不要抛进调用方（可能在 hook / UI 主线程），按返回类型给安全默认值。 */
        private fun defaultValue(type: Class<*>): Any? = when (type) {
            Void.TYPE, Void::class.java -> null
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> false
            java.lang.Integer.TYPE, java.lang.Integer::class.java -> 0
            java.lang.Long.TYPE, java.lang.Long::class.java -> 0L
            java.lang.Float.TYPE, java.lang.Float::class.java -> 0f
            java.lang.Double.TYPE, java.lang.Double::class.java -> 0.0
            IntArray::class.java -> intArrayOf()
            else -> null
        }
    }

    @Volatile
    private var service: ICoreManager? = null

    /** 取 binder 失败后的退避窗口，单位 ms —— 只影响失败重试频率，不改变成功路径。 */
    private const val SERVICE_MISS_BACKOFF_MS = 500L

    @Volatile
    private var serviceMissUptimeMs = 0L

    override fun binderDied() {
        service = null
        serviceMissUptimeMs = 0L
        Log.e(TAG, "Binder died")
    }

    override fun asBinder() = service?.asBinder()

    override fun getVersionName(): String? {
        return getService()?.versionName
    }

    override fun getVersionCode() = getService()?.versionCode ?: 0

    override fun getUid() = getService()?.uid ?: -1

    override fun getBuildTime(): Long {
        return getService()?.buildTime ?: 0
    }

    override fun onCreateDisplay(with: Int, height: Int, densityDpi: Int, surface: Surface?, listener: IVirtualDisplayCreatedListener) {
        val remote = getService()
        if (remote == null) {
            Log.e(TAG, "onCreateDisplay skipped; service unavailable: ${with}x$height,$densityDpi surface=${surface != null}")
            return
        }
        Log.i(TAG, "onCreateDisplay: ${with}x$height,$densityDpi surface=${surface != null}")
        remote.onCreateDisplay(with, height, densityDpi, surface, listener)
    }

    override fun onExternalDisplayAttached(
        displayId: Int,
        width: Int,
        height: Int,
        densityDpi: Int,
        listener: IVirtualDisplayCreatedListener,
    ) {
        val remote = getService()
        if (remote == null) {
            Log.e(TAG, "onExternalDisplayAttached skipped; service unavailable: id=$displayId ${width}x$height,$densityDpi")
            return
        }
        Log.i(TAG, "onExternalDisplayAttached: id=$displayId ${width}x$height,$densityDpi")
        remote.onExternalDisplayAttached(displayId, width, height, densityDpi, listener)
    }

    override fun setDisplaySurface(surface: Surface?) {
        val remote = getService()
        if (remote == null) {
            Log.e(TAG, "setDisplaySurface skipped; service unavailable: surface=${surface != null}")
            return
        }
        Log.i(TAG, "setDisplaySurface: surface=${surface != null}")
        remote.setDisplaySurface(surface)
    }

    override fun onDestroyDisplay() {
        getService()?.onDestroyDisplay()
    }

    override fun startLauncher() {
        getService()?.startLauncher()
    }

    override fun startActivity(packageName: String, userId: Int) {
        getService()?.startActivity(packageName, userId)
    }

    override fun startTaskId(taskId: Int, packageName: String, userId: Int) {
        getService()?.startTaskId(taskId, packageName, userId)
    }

    override fun moveTaskId(taskId: Int, isVirtualDisplay: Boolean) {
        getService()?.moveTaskId(taskId, isVirtualDisplay)
    }

    override fun moveTaskToFront(taskId: Int) {
        getService()?.moveTaskToFront(taskId)
    }

    override fun moveSecondTaskToFront() {
        getService()?.moveSecondTaskToFront()
    }

    override fun removeTask(taskId: Int){
        getService()?.removeTask(taskId)
    }

    override fun pressKey(action: Int) {
        getService()?.pressKey(action)
    }

    override fun touch(motionEvent: MotionEvent) {
        getService()?.touch(motionEvent)
    }

    override fun toggleDisplayPower() {
        getService()?.toggleDisplayPower()
    }

    override fun displayPower(displayPower: Boolean) {
        getService()?.displayPower(displayPower)
    }

    override fun addMirror(surfaceControl: SurfaceControl) {
        getService()?.addMirror(surfaceControl)
    }

    override fun removeMirror(surfaceControl: SurfaceControl){
        getService()?.removeMirror(surfaceControl)
    }

    override fun getRecentTask(): RecentTask? {
        return getService()?.recentTask
    }

    override fun testCode(action: String){
        getService()?.testCode(action)
    }

    override fun toast(msg: String){
        getService()?.toast(msg)
    }

    override fun printLog(tag: String, msg: String){
        getService()?.printLog(tag, msg)
    }

    override fun reportCoolwalkRailSnapshot(
        phase: Int,
        touchRailWidthPx: Int,
        fullHuWidthPx: Int,
        facetDisplayId: Int,
    ) {
        try {
            getService()?.reportCoolwalkRailSnapshot(phase, touchRailWidthPx, fullHuWidthPx, facetDisplayId)
        } catch (e: Throwable) {
            Log.e(TAG, "reportCoolwalkRailSnapshot failed", e)
        }
    }

    override fun getCoolwalkRailSnapshot(): IntArray {
        return try {
            getService()?.coolwalkRailSnapshot ?: intArrayOf(0, 0, 0, -1)
        } catch (e: Throwable) {
            Log.e(TAG, "getCoolwalkRailSnapshot failed", e)
            intArrayOf(0, 0, 0, -1)
        }
    }

    override fun getCoolwalkReconnectEpochMs(): Long {
        return try {
            getService()?.coolwalkReconnectEpochMs ?: 0L
        } catch (e: Throwable) {
            Log.e(TAG, "getCoolwalkReconnectEpochMs failed", e)
            0L
        }
    }

    override fun notifyCoolwalkFullBleed() {
        try {
            getService()?.notifyCoolwalkFullBleed()
        } catch (e: Throwable) {
            Log.e(TAG, "notifyCoolwalkFullBleed failed", e)
        }
    }

    /** @return false when the binder is missing — callers fall back to local-only state. */
    fun tryReportCoolwalkRailSnapshot(
        snapshot: io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.RailSnapshot,
    ): Boolean {
        val svc = getService() ?: return false
        return try {
            svc.reportCoolwalkRailSnapshot(
                snapshot.phase.code,
                snapshot.touchRailWidthPx,
                snapshot.fullHuWidthPx,
                snapshot.facetDisplayId,
            )
            true
        } catch (e: Throwable) {
            Log.e(TAG, "reportCoolwalkRailSnapshot failed", e)
            false
        }
    }

    fun tryGetCoolwalkRailSnapshot(): IntArray? {
        return try {
            getService()?.coolwalkRailSnapshot
        } catch (e: Throwable) {
            Log.e(TAG, "getCoolwalkRailSnapshot failed", e)
            null
        }
    }

    fun tryGetCoolwalkReconnectEpochMs(): Long {
        return try {
            getService()?.coolwalkReconnectEpochMs ?: 0L
        } catch (e: Throwable) {
            Log.e(TAG, "getCoolwalkReconnectEpochMs failed", e)
            0L
        }
    }

    fun tryNotifyCoolwalkFullBleed(): Boolean {
        val svc = getService() ?: return false
        return try {
            svc.notifyCoolwalkFullBleed()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "notifyCoolwalkFullBleed failed", e)
            false
        }
    }

    private fun getService(): ICoreManager? {
        if (service != null) return service
        // 取不到时的退避：AADisplay 自己的进程一次就成功并缓存，只有 gearhead 会一直失败
        // （BridgeService 的 uid 闸门），而 Coolwalk 几何钩子每构造一次 bounds 就问两遍
        // 协调器，等于每次都往 PMS 打一发 transact —— 连车时能刷出几十条
        // `Someone else trying to get my binder?`，白烧 CPU 还拖慢 system_server。
        val now = SystemClock.uptimeMillis()
        val lastMiss = serviceMissUptimeMs
        if (lastMiss != 0L && now - lastMiss in 0 until SERVICE_MISS_BACKOFF_MS) return null
        val pm = ServiceManager.getService("package")
        if (pm == null) {
            Log.d(TAG, "package service unavailable")
            serviceMissUptimeMs = now
            return null
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        val remote = try {
            data.writeInterfaceToken(BridgeService.DESCRIPTOR)
            data.writeInt(BridgeService.ACTION_GET_BINDER)
            pm.transact(BridgeService.TRANSACTION, data, reply, 0)
            reply.readException()
            val binder = reply.readStrongBinder()
            ICoreManager.Stub.asInterface(binder)
        } catch (e: Throwable) {
            Log.d(TAG, "Failed to get binder: $e")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
        if (remote != null) {
            Log.i(TAG, "Binder acquired")
            serviceMissUptimeMs = 0L
            // binder 可能在拿到后立刻死亡，linkToDeath 自身也会抛 DeadObjectException。
            runCatching { remote.asBinder().linkToDeath(this, 0) }
                .onFailure { Log.w(TAG, "linkToDeath failed", it) }
            service = Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(ICoreManager::class.java),
                ServiceProxy(remote)
            ) as ICoreManager
        } else {
            serviceMissUptimeMs = now
        }
        return service
    }
}
