package io.github.nitsuya.aa.display.xposed

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.os.*
import android.view.*
import com.github.kyuubiran.ezxhelper.utils.*
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.model.RecentTask
import io.github.nitsuya.aa.display.ui.aa.AaVirtualDisplayAdapter
import io.github.nitsuya.aa.display.ui.window.DisplayWindow
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.CoolwalkRailStore
import io.github.nitsuya.aa.display.util.DisplayProfileSettle
import io.github.nitsuya.aa.display.util.EyeCareSuspender
import io.github.nitsuya.aa.display.util.ProjectionState
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.CoolwalkRailMath
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.RailPhase
import io.github.nitsuya.aa.display.xposed.hook.aa.coolwalk.RailSnapshot
import io.github.nitsuya.aa.display.xposed.util.Instances
import io.github.qauxv.ui.CommonContextWrapper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class CoreManagerService private constructor(): ICoreManager.Stub() {
    companion object {
        const val TAG = "CoreManagerService"

        val instance: CoreManagerService by lazy {
            CoreManagerService().apply {
                log(TAG, "AADisplay service initialized")
            }
        }

        @SuppressLint("StaticFieldLeak")
        private lateinit var systemContextHost: Context
        var systemContext: Context
            get() = systemContextHost
            set(value) {
                log(TAG, "SystemContext.params is null: ${value.params}")
                systemContextHost = value.createContext(value.params ?: ContextParams.Builder().build())
            }

        /**
         * system_server 侧的模块配置。来源由入口类注入（现代 API 走远端配置，
         * 旧框架走 XSharedPreferences），这里只负责取。
         */
        val config: SharedPreferences? get() = HookPreferences.get()

        // 写入在主线程，读取可能发生在 Binder 线程 / IO 线程，必须 volatile。
        @Volatile
        private var mDisplayWindow: DisplayWindow? = null
        @Volatile
        private var mAaVirtualDisplayAdapter: AaVirtualDisplayAdapter? = null

        /**
         * 正在 bind / 建屏中的 adapter。构造完立刻登记，超时或失败时据此主动销毁，
         * 避免迟到回调复活一个已经作废的会话（见 [mCreateTimeoutRunnable]）。
         */
        @Volatile
        private var mCreatingAdapter: AaVirtualDisplayAdapter? = null

        /** 触发本次建屏的客户端；建屏失败/超时时直接给它回失败，它不在排队列表里。 */
        @Volatile
        private var mCreatingListener: IVirtualDisplayCreatedListener? = null

        @Volatile
        private var mDisplayCreateInProgress = false

        /** 创建进行中收到的重复 onCreateDisplay 请求，创建完成后统一回调。 */
        private val mPendingDisplayListeners = mutableListOf<IVirtualDisplayCreatedListener>()

        @Volatile
        private var mDestroyInProgress = false

        /**
         * 创建过的所有悬浮窗实例。收尾时逐个兜底摘窗：
         * 单例字段可能已被新会话覆盖，历史实例的窗口就会漏在屏幕上。
         */
        private val mAllDisplayWindows = mutableListOf<DisplayWindow>()

        /** 客户端（模块 App 进程）的 binder，用来监听进程死亡。 */
        @Volatile
        private var mClientBinder: IBinder? = null
        @Volatile
        private var mClientDeathRecipient: IBinder.DeathRecipient? = null

        /**
         * 销毁看门狗。
         *
         * 正常收尾由 [DisplayWindow.onDestroy] 的倒计时协程触发；该协程一旦异常终止，
         * [mDestroyInProgress] 会永远停在 true，本次开机内再也销毁不掉虚拟屏
         * （用户看到的现象就是“退出投屏后虚拟屏一直留着”）。超时后强制收尾。
         */
        private val mDestroyTimeoutRunnable = Runnable {
            if (mDestroyInProgress) {
                log(TAG, "display destroy timeout; forcing teardown")
                teardownDisplayNow()
            }
        }

        /**
         * 创建回调迟迟不来时复位标志，避免本次开机内投屏永久失效。
         *
         * 只复位标志不够：在途的 [mCreatingAdapter] 仍握着 ShellManagerService 的连接，
         * 回调一旦迟到，它会把自己写成 `mAaVirtualDisplayAdapter` 并再建一个虚拟屏，
         * 把之后建立的会话顶掉（现象是黑屏或同时存在两个 VirtualDisplay）。
         * 所以这里必须把在途实例一起销毁，并给排队中的客户端回失败。
         */
        private val mCreateTimeoutRunnable = Runnable {
            if (mDisplayCreateInProgress && mAaVirtualDisplayAdapter == null) {
                log(TAG, "display create timeout; abort in-flight create")
                mDisplayCreateInProgress = false
                abortInFlightAdapter("create timeout")
                failPendingDisplayListeners("create timeout")
                runCatching { markProjectionState(false) }
                    .onFailure { log(TAG, "markProjectionState failed", it) }
                runCatching { restoreEyeCareAfterProjection() }
                    .onFailure { log(TAG, "restoreEyeCare failed", it) }
            }
        }
        private const val DISPLAY_CREATE_TIMEOUT_MS = 20_000L

        /**
         * 销毁在途（还没回调 onReady）的 adapter。
         * 对只完成 bind、没建出 VirtualDisplay 的实例，[AaVirtualDisplayAdapter.onDestroy]
         * 只做解绑，是安全的。
         */
        private fun abortInFlightAdapter(reason: String) {
            val adapter = mCreatingAdapter ?: return
            mCreatingAdapter = null
            log(TAG, "abort in-flight adapter: $reason")
            runCatching { adapter.onDestroy() }
                .onFailure { log(TAG, "abort in-flight adapter failed", it) }
        }

        /**
         * 建屏失败/超时：发起者与排队等 displayId 的客户端都必须收到失败回调，否则 App 侧会
         * 永远停在“连接中”（`isDisplayCreateRequested` 一直是 true），残留的 listener 还会串到
         * 下一次会话。
         */
        private fun failPendingDisplayListeners(reason: String) {
            val creating = mCreatingListener
            mCreatingListener = null
            // 客户端进程已死时这个回调必然 DeadObjectException，属于预期路径，按 debug 记录，
            // 不要每次杀 App 都往 system_server 日志里打一条 E + 完整堆栈。
            runCatching { creating?.onAvailableDisplay(Display.INVALID_DISPLAY, false) }
                .onFailure { android.util.Log.d(TAG, "creating listener failure callback failed", it) }
            val pending = synchronized(mPendingDisplayListeners) {
                mPendingDisplayListeners.toList().also { mPendingDisplayListeners.clear() }
            }
            if (pending.isEmpty()) return
            log(TAG, "display create failed ($reason); notify ${pending.size} pending listener(s)")
            pending.forEach { l ->
                runCatching { l.onAvailableDisplay(Display.INVALID_DISPLAY, false) }
                    .onFailure { android.util.Log.d(TAG, "pending failure callback failed", it) }
            }
        }

        /**
         * 软重连会取消 [DisplayWindow] 的销毁倒计时，但 service 侧的看门狗必须一起撤销，
         * 否则 (DelayDestroyTime + 30) 秒后它会把刚恢复的会话强拆掉。
         */
        private fun cancelDestroyWatchdogOnReconnect() {
            if (!mDestroyInProgress) return
            mDestroyInProgress = false
            mMainHandler.removeCallbacks(mDestroyTimeoutRunnable)
            log(TAG, "reconnected during destroy countdown; cancel pending teardown")
        }
        private data class DisplayProfile(
            val width: Int,
            val height: Int,
            val densityDpi: Int
        ) {
            val isLandscape: Boolean
                get() = width >= height
        }
        private var mLockedDisplayProfile: DisplayProfile? = null

        /** true once [systemContext] has been assigned (Coolwalk settle needs a real Context). */
        private val hasSystemContext: Boolean
            get() = ::systemContextHost.isInitialized

        private const val RAIL_SETTLE_RETRY_MS = 450L
        private val mMainHandler = Handler(Looper.getMainLooper())
        private val mRailSettleRetryRunnable = Runnable { retryRailAwareSettle() }

        /**
         * system_server 侧的协程作用域。
         *
         * [GlobalScope] + [Dispatchers.Main] 没有 [CoroutineExceptionHandler]：投屏创建/销毁
         * 路径里任何一个异常都会逃到线程默认处理器，直接打死 system_server（整机软重启）。
         * 这里用 SupervisorJob 隔离单个任务，并由 handler 兜底记录。
         */
        private val coroutineExceptionHandler = CoroutineExceptionHandler { _, t ->
            log(TAG, "uncaught coroutine exception", t)
        }
        private val safeMainScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + coroutineExceptionHandler)
        private val safeIoScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineExceptionHandler)

        @Volatile
        private var mCoolwalkReconnectEpochMs = 0L

        private fun cancelRailSettleRetry() {
            mMainHandler.removeCallbacks(mRailSettleRetryRunnable)
        }

        private fun scheduleRailSettleRetry() {
            cancelRailSettleRetry()
            mMainHandler.postDelayed(mRailSettleRetryRunnable, RAIL_SETTLE_RETRY_MS)
        }

        /**
         * After soft reconnect, GhFacetBar may appear (or be starved) a beat after the
         * first create. Re-settle so we neither stay at full HU with a live rail strip
         * nor stay at HU−rail after the strip is gone.
         */
        private fun retryRailAwareSettle() {
            val current = mLockedDisplayProfile ?: return
            val adapter = mAaVirtualDisplayAdapter ?: return
            if (!hasSystemContext) return
            val railW = resolveRailWidthPx()
            val fullW = resolveObservedFullHuWidthPx(current.width, current.height)
            val settled = DisplayProfileSettle.settle(
                DisplayProfileSettle.Size(current.width, current.height, current.densityDpi),
                railWidthPx = railW,
                fullHuWidthPx = fullW,
            )
            val next = sanitizeDisplayProfile(settled.width, settled.height, settled.densityDpi)
            if (next == current) return
            mLockedDisplayProfile = next
            log(
                TAG,
                "displayProfile relocked(rail-settle): ${current.width}*${current.height} -> " +
                    "${next.width}*${next.height} rail=$railW full=$fullW"
            )
            if (next.width > current.width &&
                DisplayProfileSettle.isContentSlotVsFull(current.width, next.width)
            ) {
                instance.notifyCoolwalkFullBleed()
            }
            safeMainScope.launch {
                adapter.onReconnected(next.width, next.height, next.densityDpi)
                mDisplayWindow?.onResume(next.width, next.height)
                // 第三条重连路径，同样要把销毁看门狗撤掉。
                cancelDestroyWatchdogOnReconnect()
            }
        }

        /**
         * Compositor FacetBar strip is the only profile input — never touchRailWidthPx
         * or a cached FullBleed phase (stale after reconnect blacks the left gutter).
         */
        private fun resolveRailWidthPx(): Int {
            if (!hasSystemContext) return 0
            val liveRail = DisplayProfileSettle.observeLiveRailWidthPx(systemContext)
            return if (liveRail <= 1) 0 else liveRail
        }

        private fun resolveObservedFullHuWidthPx(reportedWidth: Int, reportedHeight: Int): Int {
            if (!hasSystemContext) return reportedWidth
            val snap = CoolwalkRailStore.effectiveSnapshot(systemContext.contentResolver)
            val observed = DisplayProfileSettle.observeFullHuWidthPx(
                systemContext,
                reportedWidth.coerceAtLeast(1),
                reportedHeight,
            )
            val measured = observed.coerceAtLeast(reportedWidth)
            return CoolwalkRailMath.pickConnectionFullHuWidth(
                snap.fullHuWidthPx,
                snap.layoutHeightPx,
                measured,
                reportedHeight,
                snap.touchRailWidthPx,
            )
        }

        private fun sanitizeDisplayProfile(width: Int, height: Int, densityDpi: Int): DisplayProfile {
            return DisplayProfile(
                width = width.coerceAtLeast(1),
                height = height.coerceAtLeast(1),
                densityDpi = densityDpi.coerceAtLeast(1)
            )
        }

        /**
         * Lock profile per active AA display session.
         * - New display session: (re)learn from current size.
         * - Reconnect within same session: keep locked size unless orientation flips,
         *   or Coolwalk reclaimed the vertical rail (then widen to the full HU).
         */
        private fun resolveDisplayProfile(
            width: Int,
            height: Int,
            densityDpi: Int,
            newSession: Boolean
        ): DisplayProfile {
            val reported = sanitizeDisplayProfile(width, height, densityDpi)
            val railW = resolveRailWidthPx()
            val fullW = resolveObservedFullHuWidthPx(reported.width, reported.height)
            val settled = DisplayProfileSettle.settle(
                DisplayProfileSettle.Size(reported.width, reported.height, reported.densityDpi),
                railWidthPx = railW,
                fullHuWidthPx = fullW,
            )
            val candidate = sanitizeDisplayProfile(settled.width, settled.height, settled.densityDpi)
            val current = mLockedDisplayProfile
            if (current == null) {
                mLockedDisplayProfile = candidate
                log(
                    TAG,
                    "displayProfile locked: ${candidate.width}*${candidate.height},${candidate.densityDpi}" +
                        " (reported=${reported.width} rail=$railW full=$fullW)"
                )
                if (!newSession) scheduleRailSettleRetry()
                return candidate
            }
            if (newSession) {
                cancelRailSettleRetry()
                if (current != candidate) {
                    mLockedDisplayProfile = candidate
                    log(
                        TAG,
                        "displayProfile relocked(new-session): ${current.width}*${current.height},${current.densityDpi} -> ${candidate.width}*${candidate.height},${candidate.densityDpi}"
                    )
                }
                return mLockedDisplayProfile!!
            }
            if (current.isLandscape != candidate.isLandscape) {
                cancelRailSettleRetry()
                mLockedDisplayProfile = candidate
                log(
                    TAG,
                    "displayProfile relocked(orientation): ${current.width}*${current.height},${current.densityDpi} -> ${candidate.width}*${candidate.height},${candidate.densityDpi}"
                )
                return candidate
            }
            // Only Coolwalk rail reclaim may widen a locked profile mid-session; every other
            // reconnect jitter keeps the locked size exactly as before.
            if (candidate.width > current.width &&
                candidate.height == current.height &&
                DisplayProfileSettle.isContentSlotVsFull(current.width, candidate.width)
            ) {
                mLockedDisplayProfile = candidate
                log(
                    TAG,
                    "displayProfile relocked(coolwalk-settle): ${current.width}*${current.height} -> " +
                        "${candidate.width}*${candidate.height} reported=${reported.width} rail=$railW full=$fullW"
                )
                instance.notifyCoolwalkFullBleed()
                scheduleRailSettleRetry()
                return candidate
            }
            if (current != candidate) {
                log(
                    TAG,
                    "displayProfile keep-locked(reconnect): locked=${current.width}*${current.height},${current.densityDpi}, incoming=${candidate.width}*${candidate.height},${candidate.densityDpi}"
                )
            }
            // Rail / starve often lands after the first soft-reconnect create; one retry.
            scheduleRailSettleRetry()
            return current
        }

        private fun clearDisplayProfileLock() {
            cancelRailSettleRetry()
            CoolwalkRailStore.clear(if (hasSystemContext) systemContext.contentResolver else null)
            mLockedDisplayProfile?.also {
                log(TAG, "displayProfile cleared: ${it.width}*${it.height},${it.densityDpi}")
            }
            mLockedDisplayProfile = null
        }

        /**
         * MIUI 护眼矩阵是全局的（display 0 → SF 广播全屏），投屏期间抑制、断开后还原。
         * 软重连会重复进 [onCreateDisplay]，[EyeCareSuspender.suspend] 自身幂等。
         */
        private fun suspendEyeCareForProjection() {
            if (!hasSystemContext) return
            val enabled = AADisplayConfig.SuspendEyeCareOnProjection.get(config)
            val r = EyeCareSuspender.suspend(systemContext, enabled)
            if (r is EyeCareSuspender.Result.Failed) {
                log(TAG, "eyeCare suspend failed", r.error)
            } else if (r !is EyeCareSuspender.Result.Disabled &&
                r !is EyeCareSuspender.Result.NothingToDo
            ) {
                log(TAG, "eyeCare suspend: $r")
            }
        }

        private fun restoreEyeCareAfterProjection() {
            if (!hasSystemContext) return
            val r = EyeCareSuspender.restore(systemContext)
            if (r is EyeCareSuspender.Result.Failed) {
                log(TAG, "eyeCare restore failed", r.error)
            } else if (r !is EyeCareSuspender.Result.NotActive) {
                log(TAG, "eyeCare restore: $r")
            }
        }

        /**
         * 投屏状态位，供普通应用进程里的 hook 读取
         * （[io.github.nitsuya.aa.display.xposed.hook.GboardImeHook]）。
         * 与护眼抑制同一对生命周期点，软重连重复调用保持幂等。
         */
        private fun markProjectionState(active: Boolean) {
            if (!hasSystemContext) return
            val cr = systemContext.contentResolver
            val ok = if (active) ProjectionState.markActive(cr) else ProjectionState.markInactive(cr)
            if (!ok) log(TAG, "projection state write failed: active=$active")
        }

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        fun systemReady() {
//            systemContext.registerReceiver(CoreBroadcastReceiver, IntentFilter().apply {
//                addAction("com.google.android.gearhead.ASSISTANT_STATE_CHANGED")
//                addAction("com.google.android.apps.auto.carservice.service.impl.NEARBY_ACTION_STOP_PROJECTION")
//                addAction("com.google.android.gms.car.STARTUP_LATENCY_MEASUREMENT_EVENT")
//                addAction("com.google.android.gms.car.FRX")
//                addAction("com.google.android.gms.car.DISCONNECTED")
//                addAction("com.google.android.gms.car.BIND_CAR_INPUT")
//                addAction("com.google.android.gms.car.PROJECTION_STARTED")
//                addAction("com.google.android.gms.car.PROJECTION_ENDED")
//                addAction("com.google.android.gms.car.FIRST_ACTIVITY")
//            })

            TipUtil.init(systemContext, "[AADisplay] ")
            Instances.init(systemContext)

            // 提前把 EzXHelper 的 modulePath 解析好。悬浮窗（CommonContextWrapper）要用它，
            // 而那条路径跑在 system_server 主 looper 上、只在插车时才走到 —— 放到这里做，
            // 失败会在开机日志里直接暴露，而不是等到投屏那一刻。
            log(TAG, "module assets ready: ${ModuleAssets.ensureModulePath()}")

            // 上一次开机挂起后未能还原（进程被杀 / 直接重启）时，screen_paper_mode_enabled 会带着
            // 我们置的值持久化下来使护眼一直失效，这里兜底回滚。
            EyeCareSuspender.recoverStaleSuspend(systemContext).also { r ->
                if (r !is EyeCareSuspender.Result.NotActive) {
                    log(TAG, "eyeCare stale recover: $r")
                }
            }

            // 同理清掉可能残留的投屏状态位，避免第三方进程一直按“投屏中”行事。
            markProjectionState(false)

//            safeIoScope.launch {
//                try{
//                    com.google.android.gms.car.a::class.java.invokeStaticMethod("b", args(systemContext), argTypes(Context::class.java))
//                    val classDynamicApiFactory = com.google.android.gms.car.a::class.java.field("a", true, Class::class.java).get(null)
//                    val instance = PathClassLoader(
//                        "/data/local/car_sdk_impl/sdk_impl.jar",
//                        CarSdkClassLoader(this.javaClass.classLoader)
//                    )
//                    instance.loadClass("com.google.android.gms.car.internal.GmsVersionChecker").invokeStaticMethod("disableGmsVersionChecks")
//                    instance.loadClass("com.google.android.gms.car.CarVersionUtils").invokeStaticMethod("versionCheck")
//                    classDynamicApiFactory.field("instance", true, ClassLoader::class.java).set(null, instance)
//                    val car = Car.createCar(systemContext, object: CarConnectionCallback(){
//                        override fun onConnected(car: Car) {
//                            log(TAG, "AA已连接")
//                            val carManager = car.getCarManager("car_1p")
//                            Companion.instance?.showToast("AA carManager: ${carManager!=null} -> ${carManager?.javaClass?.name}")
//                            log(TAG, "AA carManager: ${carManager!=null} -> ${carManager?.javaClass?.name}") //CarFirstPartyManager
//                            Companion.instance?.showToast("AA已连接")
//                        }
//                        override fun onDisconnected(car: Car) {
//                            log(TAG, "AA已断开")
//                            Companion.instance?.showToast("AA 已断开")
//                        }
//                    }).apply {
//                        connect()
//                        log(TAG, "AA初始化完成")
//                        Companion.instance?.showToast("AA初始化完成")
//                    }
//                    if(car == null){
//                        Companion.instance?.showToast("AA初始化失败")
//                        log(TAG, "AA初始化失败")
//                    }
//                } catch (e: Throwable){
//                    Companion.instance?.showToast("AA初始化异常:" + e.message)
//                    log(TAG, "AA初始化异常", e)
//                }
//            }
        }

        fun getDisplayId(): Int{
            return mAaVirtualDisplayAdapter?.mDisplayId ?: Display.INVALID_DISPLAY
        }

        fun getDensityDpi(): Int{
            return mAaVirtualDisplayAdapter?.mDensityDpi ?: 0
        }

        /**
         * 监听客户端进程死亡。
         *
         * 正常销毁走 App 侧 `AaMainFragment.onDestroy → CoreApi.onDestroyDisplay()`；
         * 但模块 App 被系统回收 / 被用户强停时这条路径根本不会执行，虚拟屏（system_server
         * 持有）、手机端悬浮窗和投屏状态位就都留了下来。这里对 AIDL 回调的 binder 挂
         * death recipient，进程一没就由 system_server 自己收尾。
         */
        private fun watchClient(listener: IVirtualDisplayCreatedListener) {
            val binder = runCatching { listener.asBinder() }.getOrNull() ?: return
            if (binder === mClientBinder) return
            unlinkClientDeath()
            val recipient = IBinder.DeathRecipient {
                log(TAG, "client process died; tearing down virtual display")
                mMainHandler.post { teardownAfterClientDeath() }
            }
            if (runCatching { binder.linkToDeath(recipient, 0) }.isSuccess) {
                mClientBinder = binder
                mClientDeathRecipient = recipient
            }
        }

        private fun unlinkClientDeath() {
            val binder = mClientBinder
            val recipient = mClientDeathRecipient
            mClientBinder = null
            mClientDeathRecipient = null
            if (binder != null && recipient != null) {
                runCatching { binder.unlinkToDeath(recipient, 0) }
            }
        }

        /** 客户端已死：没人能再调 onDestroyDisplay，跳过倒计时直接收尾。 */
        private fun teardownAfterClientDeath() {
            if (mDestroyInProgress) return
            mDestroyInProgress = true
            val window = mDisplayWindow
            mDisplayWindow = null
            safeMainScope.launch {
                window?.let {
                    runCatching { it.onDestroyPromptly() }
                        .onFailure { t -> log(TAG, "onDestroyPromptly after client death failed", t) }
                }
                teardownDisplayNow()
            }
        }

        /**
         * 真正释放虚拟屏、悬浮窗、护眼与投屏状态位。
         * 幂等：watchdog 与正常倒计时可能先后到达，重复调用不会重复 release。
         */
        private fun teardownDisplayNow() {
            mMainHandler.removeCallbacks(mDestroyTimeoutRunnable)
            mMainHandler.removeCallbacks(mCreateTimeoutRunnable)
            unlinkClientDeath()
            val adapter = mAaVirtualDisplayAdapter
            mAaVirtualDisplayAdapter = null
            // 在途（还没回调 onReady）的实例也要收掉，否则它会在 teardown 之后把会话建回来。
            val creating = mCreatingAdapter
            mCreatingAdapter = null
            mDisplayWindow = null
            // 悬浮窗不能只依赖 DisplayWindow 自己的倒计时收尾：看门狗 / 客户端死亡路径下
            // 那段协程可能已经不在了；而且单例字段可能已被新实例覆盖。所有创建过的实例
            // 都兜底摘一次（幂等）。
            val windows = synchronized(mAllDisplayWindows) {
                mAllDisplayWindows.toList().also { mAllDisplayWindows.clear() }
            }
            log(TAG, "teardown: display windows=${windows.size}")
            windows.forEach {
                runCatching { it.detachWindowsNow() }
                    .onFailure { t -> log(TAG, "detachWindowsNow failed", t) }
            }
            runCatching { adapter?.onDestroy() }
                .onFailure { log(TAG, "teardown adapter failed", it) }
            if (creating != null && creating !== adapter) {
                runCatching { creating.onDestroy() }
                    .onFailure { log(TAG, "teardown in-flight adapter failed", it) }
            }
            mDisplayCreateInProgress = false
            failPendingDisplayListeners("teardown")
            runCatching { clearDisplayProfileLock() }
                .onFailure { log(TAG, "clearDisplayProfileLock failed", it) }
            runCatching { restoreEyeCareAfterProjection() }
                .onFailure { log(TAG, "restoreEyeCare failed", it) }
            runCatching { markProjectionState(false) }
                .onFailure { log(TAG, "markProjectionState failed", it) }
            mDestroyInProgress = false
        }
    }

    override fun getVersionName(): String {
        return BuildConfig.VERSION_NAME
    }

    override fun getVersionCode(): Int {
        return BuildConfig.VERSION_CODE
    }

    override fun getUid(): Int {
        return Process.myUid()
    }

    override fun getBuildTime(): Long {
        return BuildConfig.BUILD_TIME
    }

    override fun onCreateDisplay(width: Int, height: Int, densityDpi: Int, surface: Surface?, listener: IVirtualDisplayCreatedListener){
        launchDisplayAttach(width, height, densityDpi, surface, Display.INVALID_DISPLAY, listener, "onCreateDisplay")
    }

    /**
     * 路线 B（AAP）：虚拟屏已由模块 App 进程创建（App uid 的 VD 合成健康），
     * system_server 只接管任务托管/输入注入/镜像/手机端悬浮窗，不再自己建屏。
     */
    override fun onExternalDisplayAttached(displayId: Int, width: Int, height: Int, densityDpi: Int, listener: IVirtualDisplayCreatedListener) {
        launchDisplayAttach(width, height, densityDpi, null, displayId, listener, "onExternalDisplayAttached")
    }

    private fun launchDisplayAttach(
        width: Int, height: Int, densityDpi: Int,
        surface: Surface?, externalDisplayId: Int,
        listener: IVirtualDisplayCreatedListener, entry: String,
    ) {
        safeMainScope.launch {
            log(
                TAG,
                "$entry request: ${width}x$height,$densityDpi surface=${surface != null} external=$externalDisplayId existing=${mAaVirtualDisplayAdapter != null}"
            )
            // 客户端进程被杀时由 system_server 兜底销毁，见 [watchClient]。
            watchClient(listener)
            try {
                // 已有显示：走软重连分支，不重新计算/锁定 profile（否则 ignored 的请求也会改状态）。
                mAaVirtualDisplayAdapter?.let { adapter ->
                    val external = externalDisplayId != Display.INVALID_DISPLAY
                    // 外接模式只有 displayId 相同才算软重连（App 重建 VD 会换 id）；
                    // legacy 重连打到外接 adapter 属于模式不匹配（setSurface 在外部模式下是 no-op，
                    // 照常回调会让会话跑在一块没人消费的屏上），统一按 rebind 处理。
                    val canReconnect = if (external) {
                        adapter.mExternalOwned && adapter.mDisplayId == externalDisplayId
                    } else {
                        !adapter.mExternalOwned
                    }
                    if (canReconnect) {
                        val profile = resolveDisplayProfile(
                            width = width,
                            height = height,
                            densityDpi = densityDpi,
                            newSession = false
                        )
                        adapter.onReconnected(profile.width, profile.height, profile.densityDpi)
                        if (!external) adapter.setSurface(surface)
                        mDisplayWindow?.onResume(profile.width, profile.height)
                        // 重连会取消窗口倒计时，看门狗必须一起撤销，否则 t+DelayDestroyTime+30s 强拆。
                        cancelDestroyWatchdogOnReconnect()
                        suspendEyeCareForProjection()
                        markProjectionState(true)
                        runCatching { listener.onAvailableDisplay(adapter.mDisplayId, false) }
                            .onFailure { log(TAG, "reconnect listener callback failed", it) }
                        return@launch
                    }
                    log(
                        TAG,
                        "$entry: destroy stale adapter external=${adapter.mExternalOwned} id=${adapter.mDisplayId} newExternal=$externalDisplayId"
                    )
                    mAaVirtualDisplayAdapter = null
                    runCatching { adapter.onDestroy() }
                        .onFailure { log(TAG, "destroy stale adapter failed", it) }
                    // rebind 也要撤销销毁看门狗，否则老会话安排的 t+DelayDestroyTime+30s 会强拆新屏。
                    cancelDestroyWatchdogOnReconnect()
                }

                if (mDisplayCreateInProgress) {
                    log(
                        TAG,
                        "onCreateDisplay queued: create already in progress for ${width}x$height,$densityDpi"
                    )
                    synchronized(mPendingDisplayListeners) { mPendingDisplayListeners.add(listener) }
                    return@launch
                }

                val profile = resolveDisplayProfile(
                    width = width,
                    height = height,
                    densityDpi = densityDpi,
                    newSession = true
                )
                val config = HookPreferences.latest()
                config?.apply {
                    log(TAG, "config: ${this.all.map { "${it.key}=${it.value}[${it.value?.javaClass?.name}]" }.joinToString() }")
                }
                mDisplayCreateInProgress = true
                mCreatingListener = listener
                mMainHandler.postDelayed(mCreateTimeoutRunnable, DISPLAY_CREATE_TIMEOUT_MS)
                suspendEyeCareForProjection()
                markProjectionState(true)
                try {
                    // 构造完立刻登记：超时/失败路径靠它把在途实例销毁，迟到回调才不会复活会话。
                    val adapter = AaVirtualDisplayAdapter(systemContext, config){
                        if (mCreatingAdapter !== this) {
                            // 这次创建已经超时/失败作废，迟到回调绝不能把自己写成当前会话。
                            log(TAG, "stale display adapter callback; destroying it")
                            runCatching { onDestroy() }
                                .onFailure { log(TAG, "destroy stale adapter failed", it) }
                        } else {
                            try {
                                onConnected(
                                    profile.width, profile.height, profile.densityDpi,
                                    surface,
                                    externalDisplayId = externalDisplayId,
                                ) { displayId ->
                                    runCatching { listener.onAvailableDisplay(displayId, true) }
                                        .onFailure { log(TAG, "listener callback failed", it) }
                                    // 创建期间被排队的历史请求，统一拿同一个 displayId 回调。
                                    val pending = synchronized(mPendingDisplayListeners) {
                                        mPendingDisplayListeners.toList().also { mPendingDisplayListeners.clear() }
                                    }
                                    pending.forEach { l ->
                                        runCatching { l.onAvailableDisplay(displayId, false) }
                                            .onFailure { log(TAG, "pending listener callback failed", it) }
                                    }
                                }
                                // onConnected 成功后才对外暴露 adapter，避免半初始化对象被重连分支使用。
                                mAaVirtualDisplayAdapter = this
                                mDisplayWindow?.onDestroyPromptly()
                                mDisplayWindow = DisplayWindow(
                                    CommonContextWrapper.createAppCompatContext(systemContext),
                                    this,
                                    profile.width,
                                    profile.height,
                                    profile.densityDpi
                                ).also { w ->
                                    synchronized(mAllDisplayWindows) { mAllDisplayWindows.add(w) }
                                }
                            } catch (t: Throwable) {
                                // 投屏画面本身由 VirtualDisplay 承载，悬浮窗建失败也不该拖垮系统。
                                log(TAG, "onCreateDisplay failed after virtual display setup", t)
                                // 走到这里 VirtualDisplay 可能已经建出来了：自己收掉，别留孤儿屏。
                                if (mAaVirtualDisplayAdapter === this) {
                                    mAaVirtualDisplayAdapter = null
                                }
                                runCatching { onDestroy() }
                                    .onFailure { log(TAG, "destroy failed adapter failed", it) }
                                // 客户端可能已经拿到 displayId：显式回一次失败，让它重新发起建屏，
                                // 否则它会一直拿着一个已经被收掉的屏幕等画面（黑屏）。
                                runCatching { listener.onAvailableDisplay(Display.INVALID_DISPLAY, false) }
                                    .onFailure { log(TAG, "listener failure callback failed", it) }
                            } finally {
                                mDisplayCreateInProgress = false
                                mMainHandler.removeCallbacks(mCreateTimeoutRunnable)
                                if (mCreatingAdapter === this) {
                                    mCreatingAdapter = null
                                    // 成功路径已经回过 create=true，之后 teardown 不该再给它发失败。
                                    mCreatingListener = null
                                }
                            }
                        }
                    }
                    mCreatingAdapter = adapter
                } catch (t: Throwable) {
                    log(TAG, "virtual display adapter create failed", t)
                    mDisplayCreateInProgress = false
                    mMainHandler.removeCallbacks(mCreateTimeoutRunnable)
                    abortInFlightAdapter("adapter create failed")
                    failPendingDisplayListeners("adapter create failed")
                    runCatching { markProjectionState(false) }
                        .onFailure { log(TAG, "markProjectionState failed", it) }
                    runCatching { restoreEyeCareAfterProjection() }
                        .onFailure { log(TAG, "restoreEyeCare failed", it) }
                }
            } catch (t: Throwable) {
                log(TAG, "onCreateDisplay failed", t)
                mDisplayCreateInProgress = false
                mMainHandler.removeCallbacks(mCreateTimeoutRunnable)
                // 重连分支抛错时 adapter 还活着，不能把投屏状态位和护眼抑制一起清掉。
                if (mAaVirtualDisplayAdapter == null) {
                    abortInFlightAdapter("create request failed")
                    failPendingDisplayListeners("create request failed")
                    runCatching { markProjectionState(false) }
                        .onFailure { log(TAG, "markProjectionState failed", it) }
                    runCatching { restoreEyeCareAfterProjection() }
                        .onFailure { log(TAG, "restoreEyeCare failed", it) }
                }
            }
        }
    }

    override fun setDisplaySurface(surface: Surface?){
        safeMainScope.launch {
            log(TAG, "setDisplaySurface: surface=${surface != null}, display=${mAaVirtualDisplayAdapter?.mDisplayId ?: Display.INVALID_DISPLAY}")
            runCatching { mAaVirtualDisplayAdapter?.setSurface(surface) }
                .onFailure { log(TAG, "setDisplaySurface failed", it) }
        }
    }

    override fun onDestroyDisplay(){
        safeMainScope.launch {
            if (mDestroyInProgress) {
                log(TAG, "onDestroyDisplay ignored: teardown already in progress")
                return@launch
            }
            mDestroyInProgress = true
            // 外接（AAP Route B）屏的生命周期归 App：收到 onDestroyDisplay 时 App 侧已经
            // （或即将）释放自己的 VirtualDisplay，这块屏随时会消失。先让 adapter 停止
            // “Home 任务被移除就重新拉起”的自愈逻辑，否则倒计时期间会在已消失的 displayId
            // 上反复 startActivity。
            runCatching { mAaVirtualDisplayAdapter?.markSessionEnded("onDestroyDisplay") }
            // 倒计时协程若异常终止，收尾永远不会发生；看门狗在倒计时结束后再兜底强制收尾。
            val delaySec = runCatching {
                AADisplayConfig.DelayDestroyTime.get(config)
            }.getOrDefault(0).coerceAtLeast(0)
            mMainHandler.removeCallbacks(mDestroyTimeoutRunnable)
            mMainHandler.postDelayed(mDestroyTimeoutRunnable, (delaySec + 30) * 1000L)
            val finished = java.util.concurrent.atomic.AtomicBoolean(false)
            val finishDestroy = {
                if (finished.compareAndSet(false, true)) {
                    teardownDisplayNow()
                }
            }
            val window = mDisplayWindow
            if (window == null) {
                // The control window may not exist yet, but the adapter can already own a
                // VirtualDisplay. Do not skip its release or its post-destroy shell commands.
                log(TAG, "onDestroyDisplay without DisplayWindow")
                finishDestroy()
                return@launch
            }
            try {
                window.onDestroy {
                    // 旧窗口的倒计时可能在“新会话已经建好新窗口”之后才到点。
                    // 此时它代表的是上一块屏，不能把当前会话一起拆掉。
                    if (mDisplayWindow === window || mDisplayWindow == null) {
                        finishDestroy()
                    } else {
                        log(TAG, "stale window destroy callback ignored: old=${System.identityHashCode(window)} current=${System.identityHashCode(mDisplayWindow)}")
                    }
                }
            } catch (t: Throwable) {
                log(TAG, "window.onDestroy failed; forcing teardown", t)
                finishDestroy()
            }
        }
    }

    override fun startLauncher() {
        safeIoScope.launch {
            mAaVirtualDisplayAdapter?.run {
                startLauncher()
            }
        }
    }

    override fun startActivity(packageName: String, userId: Int) {
        safeIoScope.launch {
            mAaVirtualDisplayAdapter?.run {
                startActivity(packageName, userId)
            }
        }
    }

    override fun startTaskId(taskId: Int, packageName: String, userId: Int) {
        safeIoScope.launch {
            mAaVirtualDisplayAdapter?.startTaskId(taskId, packageName, userId)
        }
    }

    override fun moveTaskId(taskId: Int, isVirtualDisplay: Boolean) {
        safeIoScope.launch {
            mAaVirtualDisplayAdapter?.moveTaskId(taskId, isVirtualDisplay)
        }
    }

    override fun moveTaskToFront(taskId: Int) {
        safeIoScope.launch {
            mAaVirtualDisplayAdapter?.moveTaskToFront(taskId)
        }
    }

    override fun moveSecondTaskToFront() {
        safeIoScope.launch {
            mAaVirtualDisplayAdapter?.moveSecondTaskToFront()
        }
    }

    @SuppressLint("MissingPermission")
    override fun removeTask(taskId: Int){
        safeIoScope.launch {
            mAaVirtualDisplayAdapter?.removeTask(taskId)
        }
    }

    override fun pressKey(action: Int) {
        safeIoScope.launch {
            mAaVirtualDisplayAdapter?.onPressKey(action)
        }
    }

    override fun touch(event: MotionEvent) {
        runBlocking(Dispatchers.IO) {
//        safeMainScope.launch {
            mAaVirtualDisplayAdapter?.onTouch(event)
        }
    }

//    private var repairDownTime: Long = Long.MIN_VALUE
//    fun touch2(event: MotionEvent, ratio: Float, repairDownTime: Long) {
//        mAaVirtualDisplayAdapter?.run {
//            val pointerCoords: Array<MotionEvent.PointerCoords?> = arrayOfNulls(event.pointerCount)
//            val pointerProperties: Array<MotionEvent.PointerProperties?> = arrayOfNulls(event.pointerCount)
//            val oldCoords = MotionEvent.PointerCoords()
//            for (i in 0 until event.pointerCount) {
//                val pointerProperty = MotionEvent.PointerProperties()
//                event.getPointerCoords(i, oldCoords)
//                event.getPointerProperties(i, pointerProperty)
//                pointerCoords[i] = MotionEvent.PointerCoords()
//                pointerCoords[i]!!.apply {
//                    x = if(ratio == 1f) oldCoords.x else oldCoords.x / ratio
//                    y = if(ratio == 1f) oldCoords.y else oldCoords.y / ratio
//                }
//                pointerProperties[i] = pointerProperty
//            }
//
//            val newEvent = MotionEvent.obtain(
//                if(repairDownTime == Long.MIN_VALUE) event.downTime else repairDownTime,
//                if(repairDownTime == Long.MIN_VALUE) event.eventTime else SystemClock.uptimeMillis(),
//                event.action,
//                event.pointerCount,
//                pointerProperties,
//                pointerCoords,
//                event.metaState,
//                event.buttonState,
//                event.xPrecision,
//                event.yPrecision,
//                event.deviceId,
//                event.edgeFlags,
//                event.source,
//                event.flags
//            )
//            onTouch(event)
//            newEvent.recycle()
//        }
//    }

    override fun toggleDisplayPower() {
        safeIoScope.launch {
            mDisplayWindow?.toggleDisplayPower()
        }
    }

    override fun displayPower(displayPower: Boolean) {
        safeIoScope.launch {
            mDisplayWindow?.toggleDisplayPower(displayPower)
        }
    }

    override fun addMirror(surfaceControl: SurfaceControl) {
        safeIoScope.launch {
            mAaVirtualDisplayAdapter?.addMirror(surfaceControl)
        }
    }

    override fun removeMirror(surfaceControl: SurfaceControl){
        safeIoScope.launch {
            mAaVirtualDisplayAdapter?.removeMirror(surfaceControl)
        }
    }

    override fun getRecentTask(): RecentTask {
        return runBlocking(Dispatchers.IO){
            mAaVirtualDisplayAdapter?.getRecentTask() ?: RecentTask(emptyList(), emptyList())
        }
    }

    @SuppressLint("RestrictedApi")
    override fun testCode(action: String){
//        safeMainScope.launch {
//            try {
//                when (action) {
//                    "displayPowerMode" -> {
//                        try{
//                            val internalDisplayToken = SurfaceControlHidden.getInternalDisplayToken()
//                            SurfaceControlHidden.setDisplayPowerMode(internalDisplayToken, 0) // POWER_MODE_OFF-0  POWER_MODE_NORMAL-2
//                        } catch (e: Throwable){
//                            log(TAG, "DisplayToken", e)
//                        }
//                    }
//                    "aaSdk" -> {
//                        try{
//                            com.google.android.gms.car.a::class.java.invokeStaticMethod("b", args(systemContext), argTypes(Context::class.java))
//                            val classDynamicApiFactory = com.google.android.gms.car.a::class.java.field("a", true, Class::class.java).get(null)
//                            val instance = PathClassLoader(
//                                "/data/local/car_sdk_impl/sdk_impl.jar",
//                                CarSdkClassLoader(this.javaClass.classLoader)
//                            )
//                            classDynamicApiFactory.field("instance", true, ClassLoader::class.java).set(null, instance)
//                            instance.loadClass("com.google.android.gms.car.internal.GmsVersionChecker").invokeStaticMethod("disableGmsVersionChecks")
//                            instance.loadClass("com.google.android.gms.car.CarVersionUtils").invokeStaticMethod("versionCheck")
//                            val car = Car.createCar(systemContext, object: CarConnectionCallback(){
//                                override fun onConnected(car: Car) {
//                                    log(TAG, "AA已连接")
//                                    TipUtil.showToast("AA已连接")
////                                    val carManager = car.getCarManager("car_1p")
////                                    showToast("AA carManager: ${carManager!=null} -> ${carManager?.javaClass?.name}")
////                                    log(TAG, "AA carManager: ${carManager!=null} -> ${carManager?.javaClass?.name}") //CarFirstPartyManager
//                                }
//                                override fun onDisconnected(car: Car) {
//                                    log(TAG, "AA已断开")
//                                    TipUtil.showToast("AA 已断开")
//                                }
//                            }).apply {
//                                connect()
//                                log(TAG, "AA初始化完成")
//                                TipUtil.showToast("AA初始化完成")
//                            }
//                            if(car == null){
//                                TipUtil.showToast("AA初始化失败")
//                                log(TAG, "AA初始化失败")
//                            }
//                        } catch (e: Throwable){
//                            TipUtil.showToast("AA初始化异常:" + e.message)
//                            log(TAG, "AA初始化异常", e)
//                        }
//                    }
//                }
//            }catch (e: Throwable){
//                TipUtil.showToast("TestCode[$action]: ${e.message}")
//                log(TAG, "TestCode[$action]:", e)
//            }
//        }
    }

    override fun toast(msg: String){
        safeMainScope.launch {
            TipUtil.showToast(msg)
        }
    }

    override fun printLog(tag: String, msg: String){
        safeIoScope.launch {
            log(tag, msg)
        }
    }

    override fun reportCoolwalkRailSnapshot(
        phase: Int,
        touchRailWidthPx: Int,
        fullHuWidthPx: Int,
        facetDisplayId: Int,
    ) {
        if (phase == RailPhase.ReconnectSettling.code) {
            mCoolwalkReconnectEpochMs = SystemClock.uptimeMillis()
        }
        val snapshot = RailSnapshot(
            phase = RailPhase.fromCode(phase),
            effectiveRailWidthPx = 0,
            touchRailWidthPx = touchRailWidthPx,
            fullHuWidthPx = fullHuWidthPx,
            facetDisplayId = facetDisplayId,
            updatedUptimeMs = SystemClock.uptimeMillis(),
            lastEvent = "client-report",
        )
        val prevFull = CoolwalkRailStore.serverSnapshot.fullHuWidthPx
        val cr = if (hasSystemContext) systemContext.contentResolver else null
        CoolwalkRailStore.rememberFromReport(snapshot, cr)
        CoolwalkRailStore.publishServer(snapshot)
        if (cr != null) {
            CoolwalkRailStore.write(cr, snapshot)
        }
        log(
            TAG,
            "CoolwalkRail snapshot phase=${snapshot.phase} touch=$touchRailWidthPx full=$fullHuWidthPx facet=$facetDisplayId"
        )
        if (fullHuWidthPx > prevFull || snapshot.phase == RailPhase.FullBleed) {
            scheduleRailSettleRetry()
        }
    }

    override fun getCoolwalkRailSnapshot(): IntArray {
        val s = CoolwalkRailStore.effectiveSnapshot(
            if (hasSystemContext) systemContext.contentResolver else null,
        )
        return intArrayOf(s.phase.code, s.touchRailWidthPx, s.fullHuWidthPx, s.facetDisplayId)
    }

    override fun getCoolwalkReconnectEpochMs(): Long = mCoolwalkReconnectEpochMs

    /**
     * gearhead cannot reliably deliver package-targeted broadcasts on Android 13+, so the
     * full-bleed signal is relayed from the system uid instead.
     */
    override fun notifyCoolwalkFullBleed() {
        if (!hasSystemContext) return
        safeIoScope.launch {
            try {
                systemContext.sendBroadcast(
                    Intent(AABroadcastConst.ACTION_COOLWALK_FULL_BLEED)
                        .setPackage(BuildConfig.APPLICATION_ID)
                )
            } catch (e: Throwable) {
                log(TAG, "notifyCoolwalkFullBleed failed", e)
            }
        }
    }
}
