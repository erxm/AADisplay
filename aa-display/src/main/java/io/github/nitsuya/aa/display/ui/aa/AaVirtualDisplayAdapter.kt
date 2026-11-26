package io.github.nitsuya.aa.display.ui.aa

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.ITaskStackListener
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.*
import android.view.*
import android.window.TaskSnapshot
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toBitmap
import com.github.kyuubiran.ezxhelper.utils.*
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.model.RecentTask
import io.github.nitsuya.aa.display.model.RecentTaskInfo
import io.github.nitsuya.aa.display.service.ShellManagerService
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.HookPreferences
import io.github.nitsuya.aa.display.xposed.IShellManager
import io.github.nitsuya.aa.display.xposed.TipUtil
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.util.Instances
import io.github.nitsuya.template.bases.runIO
import io.github.nitsuya.template.bases.runMain


class AaVirtualDisplayAdapter(
      private val context: Context
    , private val config: SharedPreferences?
    , private val onReady: (suspend AaVirtualDisplayAdapter.(it:AaVirtualDisplayAdapter) -> Unit)
) {
    companion object {
        const val TAG = "AADisplay_AaVirtualDisplayAdapter"
        private const val WINDOWING_MODE_PINNED = 2

        /** 输入法渲染在投屏 VD 自己身上。 */
        private const val DISPLAY_IME_POLICY_LOCAL = 0
        /** 输入法交给 fallback display，即 default display / 手机主屏。 */
        private const val DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1
        /** 该屏不显示输入法。 */
        private const val DISPLAY_IME_POLICY_HIDE = 2

        /** Package names to ignore in recent task list */
        private val IGNORE_RECENT_PACKAGE = setOf(
            BuildConfig.APPLICATION_ID,
            "com.android.launcher3"
        )

        private val BLOCKED_HOME_PACKAGES = setOf(
            BuildConfig.APPLICATION_ID,
            "android",
            "com.android.internal.app",
            "com.android.settings"
        )
    }

    /** Default launch package name: the app package to launch when virtual display is created, can be null */
    private var mLauncherPackage: String? = null

    /** Home package for AADisplay task-view behavior, internally synced to launch package. */
    private var mHomePackage: String? = null
    
    /** Task ID of the Home package */
    private var mHomeTaskId: Int? = null
    
    /** Task ID of the default launch package */
    private var mLauncherPackageTaskId: Int? = null
    private var mIsDestroying = false
    private val mTrackedPackageUsers = linkedMapOf<String, MutableSet<Int>>()
    private val mTaskStackListener = TaskStackListener()
    var mDisplayId = Display.INVALID_DISPLAY
    var mDensityDpi: Int = 0
    /** 路线 B：虚拟屏由模块 App 进程创建并持有，system_server 只做任务托管/输入/镜像。 */
    var mExternalOwned = false

    /**
     * 会话已结束（收到 onDestroyDisplay），但延迟销毁倒计时还没到点。
     * 外接屏在 App 释放 VD 后立刻消失，此时不能再按“Home 任务没了就重拉”自愈，
     * 否则会往不存在的 displayId 反复 startActivity。
     */
    @Volatile
    private var mSessionEnded = false

    fun markSessionEnded(reason: String) {
        if (!mExternalOwned || mSessionEnded) return
        mSessionEnded = true
        log(TAG, "session ended[$reason]: stop auto-relaunch on display=$mDisplayId")
    }

    private val mTransaction = SurfaceControl.Transaction()
    private var mSurfaceControls = mutableMapOf<SurfaceControl, SurfaceControl>()
    public lateinit var mVirtualDisplay: VirtualDisplay
    private lateinit var mDisplayWindowManager: WindowManager
    private val mForceView = View(context)

    private var mDoInit = false
    private var mShellManager: IShellManager? = null
    private var mServiceConnection = object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            log(TAG, "ShellManagerService connected: $name")
            mShellManager = IShellManager.Stub.asInterface(service)
            if(mDoInit) return
            mDoInit = !mDoInit
            // 这是同步 Binder 调用，会跑用户配置的 root 命令（可能几秒）；本回调在 system_server
            // 主线程上，同步等它会被 Watchdog 抓，所以先丢到 IO，再回主线程通知就绪。
            val shellManager = mShellManager
            runIO {
                try {
                    shellManager?.createVirtualDisplayBefore()
                } catch (e: Throwable) {
                    log(TAG, "createVirtualDisplayBefore failed", e)
                }
                runMain {
                   onReady(this@AaVirtualDisplayAdapter)
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            log(TAG, "ShellManagerService disconnected: $name")
            mShellManager = null
        }
    }

    init {
        refreshLauncherPackage("init")
        val bound = CoreManagerService.systemContext.bindService(
            Intent(ShellManagerService::class.java.name).apply {
                setPackage(BuildConfig.APPLICATION_ID)
            }
            , mServiceConnection
            , AppCompatActivity.BIND_AUTO_CREATE
        )
        log(TAG, "bind ShellManagerService requested=$bound")
    }

    fun setSurface(surface: Surface?){
        if (mExternalOwned) {
            // 外接模式下虚拟屏归 App 进程，surface 由 App 自己 set。
            log(TAG, "setSurface ignored in external mode: surface=${surface != null}")
            return
        }
        if (!::mVirtualDisplay.isInitialized) {
            log(TAG, "setSurface ignored before virtual display init: surface=${surface != null}")
            return
        }
        log(TAG, "setSurface: surface=${surface != null}, display=$mDisplayId")
        mVirtualDisplay.surface = surface
    }

    @SuppressLint("WrongConstant")
    fun onConnected(
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface?,
        externalDisplayId: Int = Display.INVALID_DISPLAY,
        onVirtualDisplayCreated: ((Int) -> Unit),
    ) {
        mIsDestroying = false
        mTrackedPackageUsers.clear()
        refreshLauncherPackage("connect")
        trackPackage(mLauncherPackage, 0)
        trackPackage(mHomePackage, 0)
        mExternalOwned = externalDisplayId != Display.INVALID_DISPLAY
        if (mExternalOwned) {
            // 路线 B（AAP）：屏已由模块 App 进程建好，这里只接管任务托管/输入注入/镜像，
            // 不再自己 createVirtualDisplay、不碰 surface（owner 是 App 进程）。
            mDisplayId = externalDisplayId
            mDensityDpi = densityDpi
            log(TAG, "external virtual display attached: id=$mDisplayId, ${width}x$height,$densityDpi, launcher=${mLauncherPackage.orEmpty()}")
        } else {
            val indent = Binder.clearCallingIdentity()
            try {
                mVirtualDisplay = Instances.displayManager.createVirtualDisplay(
                    "AADisplay-${System.currentTimeMillis()}",
                    width,
                    height,
                    densityDpi,
                    surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                        or DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE
                        or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                        or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                        //or (1 shl 8) //DisplayManager.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
                        or (1 shl 10) //DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED
                        or (1 shl 11) //DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                        or (1 shl 12) //DisplayManager.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                        or (1 shl 13) //DisplayManager.VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
                )
            } finally {
                Binder.restoreCallingIdentity(indent)
            }
            mDisplayId = mVirtualDisplay.display.displayId
            mDensityDpi = densityDpi
            log(TAG, "virtual display created: id=$mDisplayId, ${width}x$height,$densityDpi, surface=${surface != null}, launcher=${mLauncherPackage.orEmpty()}")
        }

        // 外接屏对 setDisplayImePolicy 会抛 SecurityException（untrusted virtual display），
        // 若仍放在同一个 try 里，会把后的 setShouldShowSystemDecors(false) 一起吞掉，
        // SystemUI 就会把锁屏时钟画到虚拟屏上（2026-11-21 实测）。逐项独立处理。
        runCatching {
            // 与 nitsuya 上游一致：按配置下发，不再强制 LOCAL。
            // 默认 FALLBACK_DISPLAY(1)，虚拟屏唤起的输入法落到手机主屏，
            // 车机那块 1280x720 横屏不再被键盘挡住半屏。
            val imePolicy = AADisplayConfig.DisplayImePolicy.get(config).let {
                if (it in DISPLAY_IME_POLICY_LOCAL..DISPLAY_IME_POLICY_HIDE) it
                else DISPLAY_IME_POLICY_FALLBACK_DISPLAY
            }
            log(TAG, "set display IME policy: $imePolicy")
            Instances.iWindowManager.setDisplayImePolicy(mDisplayId, imePolicy)
        }.onFailure { log(TAG, "setDisplayImePolicy failed: ${it.message}") }
        runCatching {
            Instances.iWindowManager.setShouldShowWithInsecureKeyguard(mDisplayId, false)
        }.onFailure { log(TAG, "setShouldShowWithInsecureKeyguard failed: ${it.message}") }
        runCatching {
            Instances.iWindowManager.setShouldShowSystemDecors(mDisplayId, false)
        }.onFailure { log(TAG, "setShouldShowSystemDecors failed: ${it.message}") }

        if (!mExternalOwned) {
        //mDisplayWindowManager = context.createDisplayContext(mVirtualDisplay.display).getSystemService(WindowManager::class.java).apply {
        mDisplayWindowManager = context.createDisplayContext(mVirtualDisplay.display).createWindowContext(mVirtualDisplay.display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null).getSystemService(WindowManager::class.java).apply {
            addView(
                mForceView,
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSPARENT
                ).also {
                    it.gravity = Gravity.START or Gravity.TOP
                    it.screenOrientation = if (width > height) {
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                    it.alpha = 0f
                    it.width = 0
                    it.height = 0
                }
            )
        }
        } else {
            // App 自建的虚拟屏合成正常，不需要透明 overlay 强制点亮；
            // 而且这个窗口要在 owner 进程的 display context 里建，system_server 建不到。
            log(TAG, "external mode: skip force view")
        }
        Instances.iActivityTaskManager.registerTaskStackListener(mTaskStackListener)
        // When virtual display is created, launch default package first (if configured)
        if(mLauncherPackage != null) {
            startDefaultPackage()
        } else {
            // If no default package is configured, launch Home package as fallback
            startHomeLauncher()
        }
        onVirtualDisplayCreated(mDisplayId)
    }

    fun onReconnected(width: Int, height: Int, densityDpi: Int){
        // 外接模式下屏归 App，尺寸/生命周期由 App 自己管理。
        if (!mExternalOwned) {
            mVirtualDisplay.resize(width, height, densityDpi)
        }
        mDensityDpi = densityDpi
        refreshLauncherPackage("reconnect")
        trackPackage(mLauncherPackage, 0)
        trackPackage(mHomePackage, 0)
    }

    fun onDestroy() {
        if (mIsDestroying) {
            log(TAG, "onDestroy ignored: teardown already in progress")
            return
        }
        mIsDestroying = true
        if (!mExternalOwned && !::mVirtualDisplay.isInitialized) {
            log(TAG, "onDestroy ignored: virtual display was never initialized")
            tryOrNull { CoreManagerService.systemContext.unbindService(mServiceConnection) }
            mShellManager = null
            return
        }
        trackPackage(mLauncherPackage, 0)
        trackPackage(mHomePackage, 0)
        val protectedPackages = linkedSetOf<String>().apply {
            add(BuildConfig.APPLICATION_ID)
            // Android Auto 宿主：AAP 路线下它只是碰巧把任务带到过虚拟屏（例如车机服务器
            // 开着时的 CarVirtualDeviceActivity），收尾时 force-stop 会把用户的车机服务/
            // 蓝牙连接一起带走。原版路线同理，绝不能由投屏收尾去杀 gearhead。
            add("com.google.android.projection.gearhead")
            mLauncherPackage?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            mHomePackage?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            addAll(getForegroundPackagesOnDisplay(Display.DEFAULT_DISPLAY))
        }
        tryOrNull { Instances.iActivityTaskManager.unregisterTaskStackListener(mTaskStackListener) }
        tryOrNull {
            val taskInfos = Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId)
            taskInfos.forEach { task ->
                trackPackageFromTask(task)
                removeTask(task.taskId)
            }
            mTrackedPackageUsers
                .filterKeys { pkg -> pkg.isNotBlank() && !protectedPackages.contains(pkg) }
                .forEach { (pkg, userIds) ->
                    userIds.ifEmpty { mutableSetOf(0) }.forEach { userId ->
                        try {
                            Instances.activityManagerHidden.forceStopPackageAsUser(pkg, userId)
                            log(TAG, "onDestroy forceStop: $pkg (user=$userId)")
                        } catch (e: Throwable) {
                            log(TAG, "onDestroy forceStop failed: $pkg (user=$userId)", e)
                        }
                    }
                }
            mTrackedPackageUsers.clear()
        }
        tryOrNull {
            if(mDisplayId != Display.INVALID_DISPLAY) {
                // Second pass after initial removals to catch tasks recreated during teardown races.
                val remains = Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId)
                remains.forEach { task ->
                    removeTask(task.taskId)
                }
            }
        }
        mSurfaceControls.values.forEach { it.release() }
        mSurfaceControls.clear()
        if (mExternalOwned) {
            // 外接模式下虚拟屏归 App：surface 摘除与 release 都由 App 自己做。
            log(TAG, "external virtual display detached (release owned by app): id=$mDisplayId")
        } else {
            tryOrNull { mDisplayWindowManager.removeView(mForceView) }
            try {
                mVirtualDisplay.release()
                log(TAG, "virtual display released")
            } catch (e: Throwable) {
                log(TAG, "virtual display release failed", e)
            }
        }
        mDisplayId = Display.INVALID_DISPLAY
        mDensityDpi = 0

        // The configured teardown commands are explicitly post-release. Keep the service bound
        // until its Binder call finishes so a valid connected session cannot be silently skipped.
        val shellManager = mShellManager
        mShellManager = null
        if (shellManager == null) {
            log(TAG, "onDestroy destroyVirtualDisplayAfter skipped: ShellManagerService unavailable")
            tryOrNull { CoreManagerService.systemContext.unbindService(mServiceConnection) }
        } else {
            // 同上：这条 Binder 调用也在跑 root 命令，不能占 system_server 主线程。
            // 解绑放到命令结束之后，保证调用期间服务仍然连着。
            runIO {
                try {
                    val succeeded = shellManager.destroyVirtualDisplayAfter()
                    log(TAG, "onDestroy destroyVirtualDisplayAfter: success=$succeeded")
                } catch (e: Throwable) {
                    log(TAG, "onDestroy destroyVirtualDisplayAfter failed", e)
                } finally {
                    tryOrNull { CoreManagerService.systemContext.unbindService(mServiceConnection) }
                }
            }
        }
    }

    private fun getForegroundPackagesOnDisplay(displayId: Int): Set<String> {
        val tasks = try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        } catch (_: Throwable) {
            emptyList()
        }
        if (tasks.isEmpty()) return emptySet()
        val topMost = tasks.firstOrNull { it.topActivity != null }
        val packages = linkedSetOf<String>()
        topMost?.topActivity?.packageName?.let { pkg ->
            if (pkg.isNotBlank()) packages.add(pkg)
        }
        runCatching {
            topMost?.getObjectAs("baseActivity", ComponentName::class.java) as? ComponentName
        }.getOrNull()?.packageName?.let { pkg ->
            if (pkg.isNotBlank()) packages.add(pkg)
        }
        return packages
    }

    fun onTouch(event: MotionEvent) = injectInputEvent(event)

    fun onPressKey(action: Int) {
        injectInputEvent(createKeyEvent(KeyEvent.ACTION_DOWN, action))
        injectInputEvent(createKeyEvent(KeyEvent.ACTION_UP, action))
        if (action == KeyEvent.KEYCODE_BACK) {
            // Match Home-button behavior: if back navigation returns to home and a PiP task is left
            // pinned, remove it so the launcher view is clean.
            Handler(Looper.getMainLooper()).post {
                clearPinnedTasksIfHomeFront("back")
            }
            Handler(Looper.getMainLooper()).postDelayed({
                clearPinnedTasksIfHomeFront("back-delay")
            }, 350L)
        }
    }

    fun addMirror(surfaceControl: SurfaceControl){
        val sc = SurfaceControl::class.java.newInstance(args(), argTypes()) as SurfaceControl
        try{
            if(!Instances.iWindowManager.mirrorDisplay(mDisplayId, sc)){
                sc.release()
                return
            }
        } catch (e: Throwable){
            TipUtil.showToast("addMirror error: ${e.message}")
            log(TAG, "addMirror error:", e)
            sc.release()
            return
        }
        if (!sc.isValid) {
            sc.release()
            TipUtil.showToast("addMirror not Valid")
            return
        }
        try {
            mTransaction
                .apply {
                    invokeMethod("show", args(sc), argTypes(SurfaceControl::class.java))
                }
                .reparent(sc, surfaceControl)
                .apply()
        } catch (e: Throwable){
            log(TAG, "addMirror show error:", e)
            // reparent/show 失败时 SurfaceControl 必须释放，否则每次镜像都会泄漏一个图层。
            runCatching { sc.release() }
            return
        }
        mSurfaceControls.put(surfaceControl, sc)?.release()
    }

    fun removeMirror(surfaceControl: SurfaceControl){
        mSurfaceControls.remove(surfaceControl)?.also {sc ->
            mTransaction.apply {
                invokeMethod("remove", args(sc), argTypes(SurfaceControl::class.java))
            }.apply()
            sc.release()
        }
    }

    /**
     * @param withSnapshot 是否连带取任务快照。手机端镜像小窗只显示图标，
     *   传 false 可以省掉每个任务一次 getTaskSnapshot IPC 和 HardwareBuffer 包装。
     */
    fun getRecentTask(withSnapshot: Boolean = true): RecentTask {
        return try{
            RecentTask(
                recentTaskInfo(0, withSnapshot),
                if(mDisplayId == Display.INVALID_DISPLAY) emptyList() else recentTaskInfo(mDisplayId, withSnapshot)
            )
        } catch (e: Throwable){
            log(TAG, "RecentTask Exception", e)
            RecentTask(emptyList(), emptyList())
        }
    }

    /**
     * Launch the app corresponding to Home key (usually the launcher)
     * Called when user presses Home key
     */
    fun startLauncher(){
        startHomeLauncher()
        // On some ROMs, moving Home to front auto-pins the previous video task (PiP).
        // Clean up pinned tasks on the AA virtual display so Home returns to a normal state.
        clearPinnedTasksOnDisplay("home")
        Handler(Looper.getMainLooper()).postDelayed({
            clearPinnedTasksOnDisplay("home-delay")
        }, 350L)
    }

    /**
     * Launch the app corresponding to Home package name
     * If the app is already running, bring it to front; otherwise start a new instance
     */
    private fun startHomeLauncher(){
        if(mHomePackage == null) {
            log(TAG, "startHomeLauncher skipped: no launcher package")
            return
        }
        if(mHomeTaskId != null){
            moveTaskToFront(mHomeTaskId!!)
        } else {
            startActivity(mHomePackage!!, 0)
        }
    }

    /**
     * Launch the app corresponding to default launch package name
     * Called when virtual display is created. If the app is already running, bring it to front; otherwise start a new instance
     */
    private fun startDefaultPackage(){
        if(mLauncherPackage == null) {
            log(TAG, "startDefaultPackage skipped: no launcher package")
            return
        }
        if(mLauncherPackageTaskId != null){
            moveTaskToFront(mLauncherPackageTaskId!!)
        } else {
            startActivity(mLauncherPackage!!, 0)
        }
    }

    fun startActivity(packageName: String, userId: Int): Boolean{
        try {
            if(mDisplayId == Display.INVALID_DISPLAY) return false
            val componentName = resolveLaunchComponent(packageName) ?: return false
            log(TAG, "startActivity: $componentName on display=$mDisplayId user=$userId")
            context.invokeMethod(
                "startActivityAsUser",
                args(
                    Intent().apply {
                        //addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        component = componentName
                        `package` = component?.packageName ?: return false
                        action = Intent.ACTION_VIEW
                        putExtra("displayId", mDisplayId)
                        //putExtra("isUcarMode", true)
                        setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    ActivityOptions.makeBasic().apply {
                        launchDisplayId = mDisplayId
                        this.invokeMethod("setCallerDisplayId", args(mDisplayId), argTypes(Integer.TYPE))
                    }.toBundle(),
                    UserHandle::class.java.newInstance(
                        args(userId),
                        argTypes(Integer.TYPE)
                    )
                ), argTypes(Intent::class.java, Bundle::class.java, UserHandle::class.java)
            )
            return true
      } catch (e: Throwable) {
          log(TAG, "startActivity error:", e)
          return false
      }
    }

    private fun refreshLauncherPackage(reason: String) {
        // 现代 API 的远端配置是实时的；旧框架下由 HookPreferences.latest() 负责 reload。
        val configured = AADisplayConfig.LauncherPackage.get(HookPreferences.latest() ?: config)
        val resolved = resolveLauncherPackage(configured)
        if (mLauncherPackage != resolved) {
            log(TAG, "launcher[$reason]: configured=${configured.orEmpty()}, resolved=${resolved.orEmpty()}")
        }
        mLauncherPackage = resolved
        mHomePackage = resolved
    }

    private fun resolveLauncherPackage(configured: String?): String? {
        val requested = configured?.trim()?.takeIf { it.isNotEmpty() }
        if (requested != null && resolveLaunchComponent(requested) != null) {
            return requested
        }
        if (requested != null) {
            log(TAG, "configured launcher unavailable: $requested")
        }

        val defaultHome = resolveDefaultHomePackage()
        val homeCandidates = queryHomeLauncherPackages()
        val fallback = homeCandidates.firstOrNull { pkg -> pkg != defaultHome }

        log(
            TAG,
            "launcher fallback: defaultHome=${defaultHome.orEmpty()}, candidates=${homeCandidates.joinToString()}, selected=${fallback.orEmpty()}"
        )
        return fallback
    }

    private fun resolveLaunchComponent(packageName: String): ComponentName? {
        val requested = packageName.trim()
        if (requested.isEmpty()) return null
        return if (requested.contains("/")) {
            val packageComponent = requested.split("/", limit = 2)
            if (packageComponent.size != 2) return null
            ComponentName.createRelative(packageComponent[0], packageComponent[1])
        } else {
            Instances.packageManager.getLaunchIntentForPackage(requested)?.component
        }
    }

    private fun resolveDefaultHomePackage(): String? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(
                homeIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        } ?: return null

        val pkg = resolveInfo.activityInfo?.packageName?.trim().orEmpty()
        val cls = resolveInfo.activityInfo?.name?.trim().orEmpty()
        return pkg.takeIf { isHomeCandidateAllowed(it, cls) }
    }

    private fun queryHomeLauncherPackages(): List<String> {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val candidates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                homeIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
        }

        return candidates
            .mapNotNull { resolveInfo ->
                val pkg = resolveInfo.activityInfo?.packageName?.trim().orEmpty()
                val cls = resolveInfo.activityInfo?.name?.trim().orEmpty()
                pkg.takeIf { isHomeCandidateAllowed(it, cls) && resolveLaunchComponent(it) != null }
            }
            .distinct()
    }

    private fun isHomeCandidateAllowed(pkg: String, cls: String): Boolean {
        if (pkg.isBlank() || BLOCKED_HOME_PACKAGES.contains(pkg)) return false
        if (cls.contains("FallbackHome", ignoreCase = true)) return false
        if (cls.contains("ResolverActivity", ignoreCase = true)) return false
        return true
    }

    fun startTaskId(taskId: Int?, packageName: String, userId: Int): Boolean {
        if(mDisplayId == Display.INVALID_DISPLAY) return false
        if(taskId == null){
            return startActivity(packageName, userId)
        }
        return try {
            moveTaskId(taskId, true)
        } catch (e: Throwable){
            log(TAG,"startTaskId error:", e)
            startActivity(packageName, userId)
        }
    }

    fun moveTaskId(taskId: Int, isVirtualDisplay: Boolean): Boolean {
        if(mDisplayId == Display.INVALID_DISPLAY) return false
        try {
            Instances.iActivityTaskManager.moveRootTaskToDisplay(taskId, if(isVirtualDisplay) mDisplayId else 0)
        } catch (e: Throwable){
            log(TAG,"moveTaskId error:", e)
        }
        return try {
            moveTaskToFront(taskId)
        } catch (e: Throwable){
            log(TAG,"moveTaskId error:", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun moveTaskToFront(taskId: Int): Boolean {
        if(mDisplayId == Display.INVALID_DISPLAY) return false
        return try {
            Instances.activityManager.moveTaskToFront(taskId, 0)
            true
        } catch (e: Throwable){
            log(TAG,"moveTaskToFront error:", e)
            false
        }
    }

    fun removeTask(taskId: Int): Boolean {
        if(mDisplayId == Display.INVALID_DISPLAY) return false
        return try {
            Instances.iActivityTaskManager.removeTask(taskId)
            true
        } catch (e: Throwable){
            log(TAG,"removeTask error:", e)
            false
        }
    }

    /**
     * Move the second task to front
     * If the second task is the Home package app, move the third task to front instead
     */
    fun moveSecondTaskToFront(){
        if(mDisplayId == Display.INVALID_DISPLAY)
            return
        val allRootTaskInfosOnDisplay = Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId).filter { i -> i.topActivity != null }
        if(allRootTaskInfosOnDisplay.size < 2){
            return
        }
        moveTaskToFront(
            if(allRootTaskInfosOnDisplay.size == 2 || allRootTaskInfosOnDisplay[1].topActivity!!.packageName != mHomePackage){
                allRootTaskInfosOnDisplay[1].taskId
            } else {
                allRootTaskInfosOnDisplay[2].taskId
            }
        )
    }

    private fun clearPinnedTasksOnDisplay(reason: String) {
        if (mDisplayId == Display.INVALID_DISPLAY) return
        val tasks = try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId)
        } catch (e: Throwable) {
            log(TAG, "clearPinnedTasksOnDisplay error:", e)
            return
        }
        tasks
            .filter { taskInfo ->
                taskInfo.topActivity != null && isPinnedWindowMode(taskInfo)
            }
            .forEach { taskInfo ->
                log(
                    TAG,
                    "clearPinnedTasksOnDisplay[$reason]: remove task=${taskInfo.taskId}, top=${taskInfo.topActivity?.flattenToShortString()}"
                )
                removeTask(taskInfo.taskId)
            }
    }

    private fun clearPinnedTasksIfHomeFront(reason: String) {
        if (mDisplayId == Display.INVALID_DISPLAY) return
        val tasks = try {
            Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(mDisplayId)
        } catch (e: Throwable) {
            log(TAG, "clearPinnedTasksIfHomeFront error:", e)
            return
        }
        if (tasks.isEmpty()) return
        val front = tasks.firstOrNull { it.topActivity != null }
        val frontPackage = front?.topActivity?.packageName
        if (frontPackage == null || mHomePackage == null) return
        if (frontPackage == mHomePackage) {
            clearPinnedTasksOnDisplay(reason)
        }
    }

    private fun isPinnedWindowMode(taskInfo: Any): Boolean {
        return try {
            val mode = taskInfo.invokeMethod("getWindowingMode", args(), argTypes()) as? Int
            mode == WINDOWING_MODE_PINNED
        } catch (_: Throwable) {
            false
        }
    }

    private fun injectInputEvent(event: InputEvent): Boolean {
        if (mDisplayId == Display.INVALID_DISPLAY) return false
        return try {
            event.invokeMethod("setDisplayId", args(mDisplayId), argTypes(Integer.TYPE))
            val result = Instances.iInputManager.injectInputEvent(event, 0)
            if (!result) {
                log(TAG, "injectInputEvent failed: ${event.javaClass.simpleName}")
            }
            result
        } catch (e: Throwable) {
            log(TAG, "injectInputEvent exception:", e)
            false
        }
    }

    /**
     * Matches Android's VirtualDisplayTaskEmbedder key event shape for back navigation.
     * See AOSP: VirtualDisplayTaskEmbedder#createKeyEvent()
     */
    private fun createKeyEvent(action: Int, keyCode: Int): KeyEvent {
        val whenMillis = SystemClock.uptimeMillis()
        return KeyEvent(
            whenMillis,
            whenMillis,
            action,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_VIRTUAL_HARD_KEY,
            InputDevice.SOURCE_KEYBOARD
        )
    }

    private fun trackPackage(packageName: String?, userId: Int = 0) {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        mTrackedPackageUsers.getOrPut(pkg) { linkedSetOf() }.add(userId)
    }

    private fun trackPackageFromTask(taskInfo: Any) {
        val userId = runCatching {
            taskInfo.getObjectAs("userId", Int::class.javaPrimitiveType) as? Int
        }.getOrNull() ?: 0
        runCatching {
            taskInfo.getObjectAs("topActivity", ComponentName::class.java) as? ComponentName
        }.getOrNull()?.packageName?.let { trackPackage(it, userId) }
        runCatching {
            taskInfo.getObjectAs("baseActivity", ComponentName::class.java) as? ComponentName
        }.getOrNull()?.packageName?.let { trackPackage(it, userId) }
        runCatching {
            taskInfo.getObjectAs("baseIntent", Intent::class.java) as? Intent
        }.getOrNull()?.component?.packageName?.let { trackPackage(it, userId) }
    }

    /**
     * Get recent task list for the specified display
     * Filter out ignored package names and Home package app
     */
    private fun recentTaskInfo(displayId: Int, withSnapshot: Boolean = true): List<RecentTaskInfo> {
        val allRootTaskInfosOnDisplay = Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
        log(TAG, "RecentTask $displayId, ${allRootTaskInfosOnDisplay.size}")
        return allRootTaskInfosOnDisplay
            .map { taskInfo ->
                val topActivity = taskInfo.topActivity ?: return@map null
                // Filter out ignored package names and Home package
                if(IGNORE_RECENT_PACKAGE.contains(topActivity.packageName) || mHomePackage == topActivity.packageName) {
                    return@map null
                }

                var taskDescription = taskInfo.taskDescription
                if(taskDescription == null){
                    taskDescription = Instances.iActivityTaskManager.getTaskDescription(taskInfo.taskId) ?: return@map null
                }

                var icon = runCatching { taskDescription.icon }.getOrNull()
                if (icon == null) {
                    icon = Instances.packageManager.getActivityIcon(topActivity).toBitmap()
                }
                var label = taskDescription.label
                if(label == null){
                    label = Instances.packageManager.getActivityInfo(topActivity, 0).loadLabel(Instances.packageManager).toString()
                }

                val packageName = topActivity.packageName

                var snapshot: Bitmap? = if (!withSnapshot) null else runCatching {
                    try {
                        if (Build.VERSION.SDK_INT >= 34) {//14+
                            Instances.iActivityTaskManager.getTaskSnapshot(taskInfo.taskId, true, true)
                        } else {
                            Instances.iActivityTaskManager.getTaskSnapshot(taskInfo.taskId, true)
                        }
                    } catch (e: Throwable){
                        Instances.iActivityTaskManager.getTaskSnapshot(taskInfo.taskId, true)
                    }?.let { taskSnapshot ->
                        taskSnapshot.hardwareBuffer?.let { buffer ->
                            try {
                                // Bitmap 会持有自己的引用；HardwareBuffer 必须由调用方关闭，
                                // 否则每次打开最近任务都会泄漏一张全尺寸图形 buffer。
                                Bitmap.wrapHardwareBuffer(buffer, taskSnapshot.colorSpace)
                            } finally {
                                buffer.close()
                            }
                        }
                    }
                }.let { result ->
                    if(result.isFailure){
                        log(TAG,"load snapshot exception", result.exceptionOrNull())
                        null
                    } else {
                        result.getOrNull()
                    }
                }

                log(TAG, "RecentTask: $packageName, ${taskInfo.taskId}, snapshot:${snapshot != null}")

                RecentTaskInfo(
                    icon,
                    taskInfo.taskId,
                    label,
                    snapshot
                )
            }
            .filterNotNull()
    }


    inner class TaskStackListener : ITaskStackListener.Stub() {
        override fun onTaskStackChanged() {}
        override fun onActivityPinned(packageName: String?, userId: Int, taskId: Int, stackId: Int) {}
        override fun onActivityUnpinned() {}
        override fun onActivityRestartAttempt(task: ActivityManager.RunningTaskInfo?, homeTaskVisible: Boolean, clearedTask: Boolean, wasVisible: Boolean) {}
        override fun onActivityForcedResizable(packageName: String?, taskId: Int, reason: Int) {}
        override fun onActivityDismissingDockedTask() {}
        override fun onActivityLaunchOnSecondaryDisplayFailed(taskInfo: ActivityManager.RunningTaskInfo?, requestedDisplayId: Int) {}
        override fun onActivityLaunchOnSecondaryDisplayRerouted(taskInfo: ActivityManager.RunningTaskInfo?, requestedDisplayId: Int) {}
        /**
         * Called when a new task is created
         * Record task IDs for Home package and default launch package
         */
        override fun onTaskCreated(taskId: Int, componentName: ComponentName?) {
            val packageName = componentName?.packageName ?: return
            trackPackage(packageName, 0)
            if(packageName == mHomePackage) {
                mHomeTaskId = taskId
            } else if(packageName == mLauncherPackage) {
                mLauncherPackageTaskId = taskId
            }
        }
        
        /**
         * Called when a task is removed
         * If the removed task is the Home package, restart it
         */
        override fun onTaskRemoved(taskId: Int) {
            if(mIsDestroying || mSessionEnded) {
                if(mHomeTaskId == taskId) {
                    mHomeTaskId = null
                } else if(mLauncherPackageTaskId == taskId) {
                    mLauncherPackageTaskId = null
                }
                return
            }
            if(mHomeTaskId == taskId) {
                mHomeTaskId = null
                startHomeLauncher()
            } else if(mLauncherPackageTaskId == taskId) {
                mLauncherPackageTaskId = null
            }
        }
        override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {}
        override fun onTaskDescriptionChanged(taskInfo: ActivityManager.RunningTaskInfo) {}
        override fun onActivityRequestedOrientationChanged(taskId: Int, requestedOrientation: Int) {}
        override fun onTaskRemovalStarted(taskInfo: ActivityManager.RunningTaskInfo?) {}
        override fun onTaskProfileLocked(taskInfo: ActivityManager.RunningTaskInfo?) {}
        override fun onTaskProfileLocked(taskInfo: ActivityManager.RunningTaskInfo?, userId: Int) {}
        override fun onTaskSnapshotChanged(taskId: Int, snapshot: TaskSnapshot?) {}
        override fun onBackPressedOnTaskRoot(taskInfo: ActivityManager.RunningTaskInfo?) {}
        override fun onTaskDisplayChanged(taskId: Int, newDisplayId: Int) {}
        override fun onRecentTaskListUpdated() {}
        override fun onRecentTaskRemovedForAddTask(taskId: Int) {}
        override fun onRecentTaskListFrozenChanged(frozen: Boolean) {}
        override fun onTaskFocusChanged(taskId: Int, focused: Boolean) {}
        override fun onTaskRequestedOrientationChanged(taskId: Int, requestedOrientation: Int) {}
        override fun onActivityRotation(displayId: Int) {}
        override fun onTaskMovedToBack(taskInfo: ActivityManager.RunningTaskInfo?) {}
        override fun onLockTaskModeChanged(mode: Int) {}
        override fun onTaskSnapshotInvalidated(taskId: Int) {}

        //Samsung OneUi
        override fun onActivityDismissingSplitTask(str: String?) {}
        override fun onOccludeChangeNotice(componentName: ComponentName?, z: Boolean) {}
        override fun onTaskbarIconVisibleChangeRequest(componentName: ComponentName?, z: Boolean) {}
        //Samsung OneUi 7
        override fun onTaskWindowingModeChanged(i: Int) {}
    }
}
