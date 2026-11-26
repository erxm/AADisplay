package io.github.nitsuya.aa.display.ui.window

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.CountDownTimer
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.*
import androidx.core.view.ViewCompat
import androidx.core.view.allViews
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import com.github.kyuubiran.ezxhelper.utils.tryOrNull
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.databinding.WindowControllerBinding
import io.github.nitsuya.aa.display.databinding.WindowMirrorBinding
import io.github.nitsuya.aa.display.ui.aa.AaVirtualDisplayAdapter
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.TipUtil
import io.github.nitsuya.aa.display.xposed.hook.AndroidHook
import io.github.nitsuya.aa.display.xposed.log
import io.github.nitsuya.aa.display.xposed.util.Instances
import io.github.nitsuya.aa.display.xposed.util.RomUtil
import io.github.nitsuya.template.bases.runIO
import io.github.nitsuya.template.bases.runMain
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt


class DisplayWindow(
      private val mContext: Context
    , private val displayAdapter: AaVirtualDisplayAdapter
    , private var mDisplayWidth: Int
    , private var mDisplayHeight: Int
    , private var mDensityDpi: Int
): View.OnTouchListener {
    companion object {
        private const val TAG = "AADisplay_DisplayWindow"
        /** 等待镜像窗布局稳定的最大帧数，超时后按当前尺寸居中。 */
        private const val MAX_CENTER_ATTEMPTS = 30
    }

    private var mControllerBinding: WindowControllerBinding? = null
    private lateinit var mControllerLayoutParams: WindowManager.LayoutParams

    private var mMirrorBinding: WindowMirrorBinding? = null
    private lateinit var mMirrorLayoutParams: WindowManager.LayoutParams

    private var mControllerStatus = false
    private var mMirrorStatus = false
    private var mControllerCollapsed = true
    private var mControllerDockRight = true
    /** 收尾只执行一次：onDestroy 可能在延迟销毁期间被重复调用。 */
    private var mDestroyed = false
    /**
     * 悬浮窗已经从 WindowManager 摘掉。
     *
     * 与 [mDestroyed] 区分：延迟销毁的倒计时期间窗口仍然有效，用户重连时
     * [onResume] 仍要能取消销毁并把控制器放回去；只有 [close] 真正执行之后，
     * 才禁止任何再挂窗的操作（否则在途的 onResume/showController 会把窗口加回来，
     * 且此后没人再负责关闭它）。
     */
    private var mWindowsDetached = false
    /** 首帧尚未测量时贴边计算的重试计数。 */
    private var mDockRetryCount = 0
    /** 镜像窗首帧尚未测量时居中/越界回收的重试计数。 */
    private var mMirrorLayoutRetryCount = 0
    /**
     * 首次居中前的“尺寸稳定”探测状态。
     *
     * addView 后第一趟测量拿到的是**过渡尺寸**（SurfaceView 这时还是 0 高，wrap_content
     * 只量到顶栏的高度），直接用它居中会把小窗摆到屏幕下方（实测 y=1165，而正确值约 764），
     * 而且 mMirrorPositioned 一置位就再也不会纠正。这里等相邻两帧尺寸一致后再居中。
     */
    private var mMirrorCenterAttempt = 0
    private var mMirrorCenterLastWidth = -1
    private var mMirrorCenterLastHeight = -1
    private val mControllerPeekPx by lazy { (mWindowContext.resources.displayMetrics.density * 14f).toInt() }

    /**
     * 读 dp 尺寸；模块资源表暂时不可用时（热装 APK 后 system_server 还缓存着旧路径，
     * 会抛 Resources$NotFoundException）按 dp 数值兜底，避免整条 onResume 挂掉。
     */
    private fun dimenPx(resId: Int, fallbackDp: Float): Int {
        val density = mWindowContext.resources.displayMetrics.density
        return runCatching { mWindowContext.resources.getDimensionPixelSize(resId) }
            .getOrElse { (density * fallbackDp).toInt() }
            .coerceAtLeast(1)
    }

    /** 控制器根布局为卡片阴影让出的透明边，贴边坐标要按它回退（见 applyControllerDockPosition） */
    private val mControllerShadowPadPx by lazy {
        dimenPx(R.dimen.window_shadow_pad, 6f)
    }

    /**
     * 镜像小窗的几何状态（MIUI 小窗逻辑：整体可拖动、可等比缩放）。
     *
     * mMirrorFitRatio 是“恰好铺满手机可用区”的缩放，mMirrorScale 是用户在这个基准上
     * 的比例（拖右下角把手改它），两者相乘才是 mDisplayRatio。触摸转发要除以
     * mDisplayRatio 把坐标换回车机分辨率，所以缩放必须只走 updateDipslaySize()
     * 这一条路径，否则画面和点击位置会错开。
     */
    private var mMirrorFitRatio = 1f
    private var mMirrorScale = 1f
    private var mMirrorPositioned = false
    private var mMirrorDragLastX = 0f
    private var mMirrorDragLastY = 0f
    // 浮点累积位移：每帧不足 1px 的移动如果直接 toInt() 会被永久丢弃，慢速拖动会“丢步”。
    private var mMirrorDragAccumX = 0f
    private var mMirrorDragAccumY = 0f
    private var mControllerDragAccumX = 0f
    private var mControllerDragAccumY = 0f
    private var mResizeStartRawX = 0f
    private var mResizeStartRawY = 0f
    private var mResizeStartScale = 1f
    private var mResizing = false

    private val mMirrorShadowPadPx by lazy {
        dimenPx(R.dimen.mirror_window_shadow_pad, 10f)
    }
    private val mMirrorMinWidthPx by lazy {
        dimenPx(R.dimen.mirror_min_width, 180f)
    }
    /** 多任务面板：单个图标条目的参考宽度 / RecyclerView 的 padding，用来算网格列数 */
    private val mMirrorRecentItemWidthPx by lazy {
        dimenPx(R.dimen.mirror_recent_item_width, 62f)
    }
    private val mMirrorRecentPaddingPx by lazy {
        (mWindowContext.resources.displayMetrics.density * 4f).toInt()
    }
    /** 顶栏 + 底部导航条占掉的高度，算可用空间时要从屏幕高度里扣掉 */
    private val mMirrorChromeHeightPx by lazy {
        dimenPx(R.dimen.mirror_top_bar_height, 34f) +
            dimenPx(R.dimen.mirror_nav_btn_size, 48f) +
            (mWindowContext.resources.displayMetrics.density * 8f).toInt()
    }

    /**
     * 悬浮窗必须落在手机主屏（display 0）上。
     *
     * mContext 最终来自 AMS 的 mUiContext，构造 DisplayWindow 的时机正好卡在
     * gearhead 换屏之后，它可能还绑在刚被移除的虚拟屏上，直接 addView 会抛
     * InvalidDisplayException（"Attempted to add window to a display that does
     * not exist"），首次 showController 就被 tryOrNull 吞掉。这里显式绑定默认屏，
     * 顺带让 currentWindowMetrics 也取到手机主屏的尺寸。
     *
     * 注意：尺寸/dp 换算也必须统一走这个 window context。用 mContext.resources
     * 会把控制器贴到车机虚拟屏（如 1280 宽）的坐标上。
     */
    private val mWindowContext: Context by lazy {
        val display = Instances.displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val defaultMetrics = android.util.DisplayMetrics().also {
            runCatching { display?.getRealMetrics(it) }
        }
        var ctx = display?.let { d ->
            tryOrNull {
                mContext.createDisplayContext(d)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            }
        } ?: mContext
        // 再兜一层：AMS 的 mUiContext 换屏后可能带着车机密度（170），
        // 即使 WindowManager 落在默认屏（460dpi），inflate 出来的 dp 尺寸也会缩小。
        // 这里按默认屏的真实密度覆盖一次，保证悬浮球的 44dp 就是 44dp。
        val ctxDpi = ctx.resources.displayMetrics.densityDpi
        if (defaultMetrics.densityDpi > 0 && ctxDpi != defaultMetrics.densityDpi) {
            val cfg = android.content.res.Configuration(ctx.resources.configuration).apply {
                densityDpi = defaultMetrics.densityDpi
            }
            runCatching { ctx.createConfigurationContext(cfg) }.getOrNull()?.let { ctx = it }
        }
        ctx
    }
    private val mWindowManager: WindowManager by lazy {
        mWindowContext.getSystemService(WindowManager::class.java)
    }

    /**
     * 布局 inflate 用上下文：必须保留模块自己的 ClassLoader（MaterialCardView 等控件），
     * 同时把 dp 密度修正到默认屏。不能用 [mWindowContext] 直接 inflate——它由 system_server
     * 的 createWindowContext 生成，ClassLoader 是 android 包的，会 ClassNotFoundException。
     */
    private val mInflateContext: Context by lazy {
        val display = Instances.displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = android.util.DisplayMetrics().also {
            runCatching { display?.getRealMetrics(it) }
        }
        val currentDpi = mContext.resources.displayMetrics.densityDpi
        if (metrics.densityDpi > 0 && metrics.densityDpi != currentDpi) {
            runCatching {
                mContext.createConfigurationContext(
                    android.content.res.Configuration(mContext.resources.configuration).apply {
                        densityDpi = metrics.densityDpi
                    }
                )
            }.getOrElse { mContext }
        } else {
            mContext
        }
    }

    private var mDisplayRatio = 1f
    private var mDisplayPower = true

    /**
     * Android 16 removed SurfaceControl.getInternalDisplayToken().  Obtain the primary physical
     * display token from the Android 16 SurfaceControl API, then retain the old lookup for
     * releases which still expose it.
     */
    private val mInternalDisplayToken: IBinder by lazy {
        runCatching {
            val surfaceControl = Class.forName("android.view.SurfaceControl")
            val displayIds = surfaceControl
                .getDeclaredMethod("getPhysicalDisplayIds")
                .apply { isAccessible = true }
                .invoke(null) as LongArray
            require(displayIds.isNotEmpty()) { "no physical displays" }
            surfaceControl
                .getDeclaredMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(null, displayIds.first()) as IBinder
        }.onSuccess {
            log(TAG, "display power: using SurfaceControl physical display token")
        }.getOrElse { physicalTokenError ->
            runCatching {
                SurfaceControlHidden.getInternalDisplayToken()
            }.onSuccess {
                log(TAG, "display power: using legacy SurfaceControl display token")
            }.getOrElse { legacyError ->
                throw IllegalStateException(
                    "cannot obtain internal display token; physicalDisplay=$physicalTokenError, " +
                        "SurfaceControl=$legacyError"
                )
            }
        }
    }

    private var mDestroyJob: Job? = null
    private var mChangeAlphaCountDownTimer = object : CountDownTimer(5000,5000){
        override fun onFinish() {
            mControllerBinding?.apply {
                ViewCompat.animate(root).setDuration(500).alpha(0.5F).start()
            }
        }
        override fun onTick(millisUntilFinished: Long) {}
    }

    private var mScreenOffReplaceLockScreen = AADisplayConfig.ScreenOffReplaceLockScreen.get(CoreManagerService.config)
    private val mDelayDestroyTime = AADisplayConfig.DelayDestroyTime.get(CoreManagerService.config).let { value ->
        if (value < 0) 0 else value
    }

    private val isSupportInteractive = RomUtil.isMiui()
    private var interactiveMonitor = object: BroadcastReceiver(){
        val monitor by lazy {
            Instances.powerManagerHidden.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                , "${BuildConfig.APPLICATION_ID}:Monitor", displayAdapter.mVirtualDisplay.display.displayId).apply {
                    setReferenceCounted(false)
            }
        }
        fun addAction(intentFilter: IntentFilter): IntentFilter {
            return intentFilter.apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        }
        override fun onReceive(context: Context, intent: Intent) {
            intent.action?.let { action ->
                onReceive(context, action)
            }
        }
        fun onReceive(context: Context, action: String){
            if(!isSupportInteractive) return
            try {
                when(action){
                    Intent.ACTION_SCREEN_ON -> Settings.Secure.putInt(context.contentResolver, "synergy_mode", 0)
                    Intent.ACTION_SCREEN_OFF -> Settings.Secure.putInt(context.contentResolver, "synergy_mode", 1)
                    //miui
                    //Settings.Global.putInt(contentResolver, "ucar_casting_state", 1);
                    //Settings.Secure.putInt(contentResolver, "screen_project_in_screening", 1);
                    //Settings.Secure.putInt(context.contentResolver, "synergy_mode", 1);
                }
            } catch (e : Throwable){}
        }
        private var attached = false

        fun init(){
            // onResume 每次软重连都会调用；重复 registerReceiver 会抛
            // "Receiver already registered"，先解绑再绑定。
            if (attached) release()
            attached = true
            runCatching {
                if(mScreenOffReplaceLockScreen){
                    AndroidHook.Power.hook()
                } else if(isSupportInteractive){
                    mContext.registerReceiver(this, addAction(IntentFilter()))
                    onReceive(mContext, if(Instances.powerManager.isInteractive) Intent.ACTION_SCREEN_ON else Intent.ACTION_SCREEN_OFF)
                } else {
                    if (!monitor.isHeld) {
                        monitor.acquire()
                    }
                }
                AndroidHook.FuckAppUseApplicationContext.hook()
                if (AADisplayConfig.ForceAppFullscreenOnProjection.get(CoreManagerService.config)) {
                    AndroidHook.AppFullscreenOnProjection.hook()
                }
            }.onFailure { log(TAG, "interactiveMonitor.init failed", it) }
        }
        fun release(){
            if (!attached) return
            attached = false
            runCatching {
                if(mScreenOffReplaceLockScreen){
                    AndroidHook.Power.unHook()
                } else if(isSupportInteractive){
                    mContext.unregisterReceiver(this)
                    onReceive(mContext, Intent.ACTION_SCREEN_ON)
                } else {
                    if (monitor.isHeld) monitor.release()
                }
                AndroidHook.FuckAppUseApplicationContext.unHook()
                AndroidHook.AppFullscreenOnProjection.unHook()
            }.onFailure { log(TAG, "interactiveMonitor.release failed", it) }
        }
    }



    init {
        runCatching {
            // mContext 来自 AMS 的 mUiContext，换屏后可能还绑在车机虚拟屏上（170dpi）；
            // 直接 inflate 会让悬浮球只有 59px（正常 161px）。用默认屏密度覆盖后的
            // 模块上下文 inflate，既保留模块 ClassLoader，又保证 dp 按手机屏幕换算。
            val inflateContext = ContextThemeWrapper(mInflateContext, R.style.Theme_AADisplay_Window)
            log(
                TAG,
                "inflate context density=${inflateContext.resources.displayMetrics.density} " +
                    "dpi=${inflateContext.resources.displayMetrics.densityDpi}",
            )
            with(inflateContext) {
                mControllerBinding = WindowControllerBinding.inflate(LayoutInflater.from(this))
                // 小窗版镜像窗不再铺满全屏，多任务面板直接叠在车机画面上，
                // 不需要再为状态栏留出 padding
                mMirrorBinding = WindowMirrorBinding.inflate(LayoutInflater.from(this))
            }
        }.onFailure {
            log(TAG, "init: new window failed may you forget reboot", it)
            TipUtil.showToast("new window failed\nmay you forget reboot")
        }.onSuccess {
            doInit()
        }
        interactiveMonitor.init()
    }

    fun doInit() {
        initLayoutParams()
        mControllerBinding?.apply {
            root.allViews.forEach {
                it.setOnTouchListener(this@DisplayWindow)
            }
            ibHandle.setOnClickListener {
                expandController()
            }
            ibHideController.setOnClickListener {
                collapseController()
            }
            if(mScreenOffReplaceLockScreen){
                ibExtinguish.visibility = View.VISIBLE
                ibExtinguish.setOnClickListener {
                    toggleDisplayPower(false)
                }
            }
            ibMirrorDisplay.setOnClickListener {
                hideController()
                showMirror()
            }
            ibMirrorDisplay.setOnLongClickListener {
                collapseController()
                true
            }
            ibExpand.setOnClickListener {
                expandController()
            }
        }
        mMirrorBinding?.apply {
            // 顶栏 / 底部导航条空白处拖动 = 移动整个小窗
            arrayOf(llMirrorTopBar, rlMirrorNav).forEach {
                it.setOnTouchListener { _, event -> onMirrorDragTouch(event) }
            }
            ibClose.setOnClickListener {
                hideMirror()
                showController()
            }
            ivResize.setOnTouchListener { v, event -> onMirrorResizeTouch(v, event) }
            ivResize.setOnLongClickListener {
                resetMirrorSizeAndPosition()
                TipUtil.showToast(mContext.getString(R.string.mirror_size_reset))
                true
            }
            ibBack.setOnClickListener {
                displayAdapter.onPressKey(KeyEvent.KEYCODE_BACK)
            }
            ibHome.setOnClickListener {
                displayAdapter.startLauncher()
            }
            ibRecentTask.setOnClickListener { v ->
                val tapCount = v.getTag(R.id.tap_count) as? Int ?: 0
                v.setTag(R.id.tap_count,   tapCount + 1)
                if(tapCount > 0) return@setOnClickListener
                runIO {
                    delay(300)
                    runMain {
                        when(v.getTag(R.id.tap_count) as? Int ?: 0){
                            1 -> toggleRecentTask()
                            2 -> displayAdapter.moveSecondTaskToFront()
                        }
                        v.setTag(R.id.tap_count,  0)
                    }
                }
            }
            tvVirtualDisplayInfo.text = "$mDisplayWidth*$mDisplayHeight,$mDensityDpi"
            rvRecentTaskLeft.apply {
                layoutManager = GridLayoutManager(context, 1)
                adapter = DisplayRecyclerViewAdapter(this, iconMode = true) {
                    hideRecentTask()
                }
            }
            rvRecentTaskRight.apply {
                layoutManager = GridLayoutManager(context, 1)
                adapter = DisplayRecyclerViewAdapter(this, iconMode = true){
                    hideRecentTask()
                }.apply {
                    otherAdapter = (rvRecentTaskLeft.adapter as DisplayRecyclerViewAdapter).also {
                        it.otherAdapter = this@apply
                    }
                }
            }
            arrayOf(rvRecentTaskLeft, rvRecentTaskRight).forEach {
                it.setOnTouchListener { v, event ->
                    when(event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.setTag(R.id.drag_last_x, event.x)
                            v.setTag(R.id.drag_last_y, event.y)
                        }
                        MotionEvent.ACTION_UP -> {
                            if (v.id != 0
                                && abs((v.getTag(R.id.drag_last_x) as? Float ?: 0f) - event.x) <= 5
                                && abs((v.getTag(R.id.drag_last_y) as? Float ?: 0f) - event.y) <= 5) {
                                hideRecentTask()
                            }
                        }
                    }
                    return@setOnTouchListener false
                }
            }
        }
        updateDipslaySize()
        mMirrorBinding?.apply {
            svMirror.setOnTouchListener { _, event ->
                val pointerCoords: Array<MotionEvent.PointerCoords?> = arrayOfNulls(event.pointerCount)
                val pointerProperties: Array<MotionEvent.PointerProperties?> = arrayOfNulls(event.pointerCount)
                val oldCoords = MotionEvent.PointerCoords()
                for (i in 0 until event.pointerCount) {
                    val pointerProperty = MotionEvent.PointerProperties()
                    event.getPointerCoords(i, oldCoords)
                    event.getPointerProperties(i, pointerProperty)
                    pointerCoords[i] = MotionEvent.PointerCoords()
                    pointerCoords[i]!!.apply {
                        x = oldCoords.x / mDisplayRatio
                        y = oldCoords.y / mDisplayRatio
                    }
                    pointerProperties[i] = pointerProperty
                }
                val newEvent = MotionEvent.obtain(event.downTime, event.eventTime, event.action, event.pointerCount, pointerProperties, pointerCoords, event.metaState, event.buttonState, event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags, event.source, event.flags)
                displayAdapter.onTouch(newEvent)
                newEvent.recycle()
                true
            }
            svMirror.holder.addCallback(object: SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    displayAdapter.addMirror(svMirror.surfaceControl)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    displayAdapter.removeMirror(svMirror.surfaceControl)
                }
            })
        }
        showController()
    }

    private fun initLayoutParams() {
        mControllerLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
             WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                or (if(mScreenOffReplaceLockScreen) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        mMirrorLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
             WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                or (if(mScreenOffReplaceLockScreen) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0)
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            ,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 小窗：尺寸跟着内容走，位置由 x/y 决定（gravity 必须是 START|TOP，
            // 否则 x/y 的含义会变成相对其它边的偏移，拖动逻辑就对不上了）
            gravity = Gravity.START or Gravity.TOP
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private fun updateDipslaySize(){
        mControllerBinding?.apply {
            ibMirrorDisplay.visibility = View.VISIBLE
        }
        val bounds = mWindowManager.currentWindowMetrics.bounds
        // “恰好铺满手机可用区”的基准比例：宽度扣掉阴影 padding，高度还要再扣掉
        // 顶栏和底部导航条，否则 100% 时小窗会比屏幕高
        val availableWidth = (bounds.width() - mMirrorShadowPadPx * 2).coerceAtLeast(1)
        val availableHeight =
            (bounds.height() - mMirrorShadowPadPx * 2 - mMirrorChromeHeightPx).coerceAtLeast(1)
        mMirrorFitRatio = (availableWidth.toFloat() / mDisplayWidth)
            .coerceAtMost(availableHeight.toFloat() / mDisplayHeight)
        mMirrorScale = mMirrorScale.coerceIn(minMirrorScale(), 1f)
        mDisplayRatio = mMirrorFitRatio * mMirrorScale
        mMirrorBinding?.apply {
            // buffer 保持车机原生分辨率，view 尺寸只做缩放，改大小是纯 GPU 拉伸
            svMirror.holder.setFixedSize(mDisplayWidth, mDisplayHeight)
            svMirror.updateLayoutParams{
                width = (mDisplayWidth * mDisplayRatio).toInt()
                height = (mDisplayHeight * mDisplayRatio).toInt()
            }
            updateRecentTaskPanelSize()
        }
        if (mMirrorStatus) {
            mMirrorBinding?.root?.post { clampMirrorPosition() }
        }
    }

    /**
     * 多任务面板跟着 sv_mirror 的实际尺寸走。
     *
     * 面板不能用 match_parent：小窗是 wrap_content，FrameLayout 在测量阶段会先按
     * AT_MOST 量一次面板，图标网格的高度就会把卡片撑得比车机画面还高，面板溢出
     * 卡片边界（看起来就是“错位”）。这里显式写死成和车机画面等大，并按可用宽度
     * 换算网格列数，缩放后依然贴合。
     */
    private fun updateRecentTaskPanelSize() {
        val binding = mMirrorBinding ?: return
        val width = (mDisplayWidth * mDisplayRatio).toInt().coerceAtLeast(1)
        val height = (mDisplayHeight * mDisplayRatio).toInt().coerceAtLeast(1)
        binding.llRecentTask.updateLayoutParams {
            this.width = width
            this.height = height
        }
        // 左右两栏各占一半，减去 padding 后按参考条目宽度算列数
        val columnWidth = (width / 2 - mMirrorRecentPaddingPx * 2).coerceAtLeast(1)
        val spanCount = (columnWidth / mMirrorRecentItemWidthPx).coerceIn(1, 4)
        arrayOf(binding.rvRecentTaskLeft, binding.rvRecentTaskRight).forEach { rv ->
            (rv.layoutManager as? GridLayoutManager)?.takeIf { it.spanCount != spanCount }
                ?.spanCount = spanCount
        }
    }

    /** 缩到 @dimen/mirror_min_width 就不再往下走，免得小窗小到点不着 */
    private fun minMirrorScale(): Float {
        val fullWidth = mDisplayWidth * mMirrorFitRatio
        if (fullWidth <= 0f) return 1f
        return (mMirrorMinWidthPx / fullWidth).coerceIn(0.1f, 1f)
    }

    /**
     * 顶栏 / 底部导航条上的拖动：整窗平移。
     *
     * 这里直接吃掉事件（返回 true），因为拖动区本身没有点击行为；按钮各自有
     * OnClickListener，不会走到这里。
     */
    private fun onMirrorDragTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                mMirrorDragLastX = event.rawX
                mMirrorDragLastY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val binding = mMirrorBinding ?: return true
                mMirrorDragAccumX += event.rawX - mMirrorDragLastX
                mMirrorDragAccumY += event.rawY - mMirrorDragLastY
                mMirrorDragLastX = event.rawX
                mMirrorDragLastY = event.rawY
                val dx = mMirrorDragAccumX.toInt()
                val dy = mMirrorDragAccumY.toInt()
                if (dx != 0 || dy != 0) {
                    mMirrorDragAccumX -= dx
                    mMirrorDragAccumY -= dy
                    mMirrorLayoutParams.x += dx
                    mMirrorLayoutParams.y += dy
                    tryOrNull { mWindowManager.updateViewLayout(binding.root, mMirrorLayoutParams) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> clampMirrorPosition()
        }
        return true
    }

    /**
     * 右下角把手拖动：等比缩放。
     *
     * 以按下点为基准，用对角位移量换算成新的 mMirrorScale，再回到
     * updateDipslaySize() 统一改 mDisplayRatio —— 触摸转发也读这个字段，
     * 所以画面和点击位置始终一致。
     *
     * 没进入拖动前把事件交回 View（返回 false），长按恢复默认大小才有机会触发；
     * 一旦开始拖动就补一个 ACTION_CANCEL 掐掉 View 排队中的长按，避免拖着拖着
     * 突然弹出“已恢复默认大小”。
     */
    private fun onMirrorResizeTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                mResizeStartRawX = event.rawX
                mResizeStartRawY = event.rawY
                mResizeStartScale = mMirrorScale
                mResizing = false
            }
            MotionEvent.ACTION_MOVE -> {
                val fullWidth = mDisplayWidth * mMirrorFitRatio
                if (fullWidth <= 0f) return mResizing
                // 右下方向为放大：把 dx、dy 投影到对角线上，避免斜拖时手感突变
                val dx = event.rawX - mResizeStartRawX
                val dy = event.rawY - mResizeStartRawY
                if (!mResizing) {
                    if (sqrt(dx.pow(2) + dy.pow(2)) <= 5f) return false
                    mResizing = true
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    v.onTouchEvent(cancel)
                    cancel.recycle()
                }
                val aspect = (mDisplayHeight.toFloat() / mDisplayWidth).coerceAtLeast(0.01f)
                val delta = (dx + dy / aspect) / 2f
                val target = mResizeStartScale + delta / fullWidth
                val clamped = target.coerceIn(minMirrorScale(), 1f)
                if (abs(clamped - mMirrorScale) >= 0.001f) {
                    mMirrorScale = clamped
                    updateDipslaySize()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mMirrorBinding?.root?.post { clampMirrorPosition() }
                if (mResizing) {
                    mResizing = false
                    return true
                }
            }
        }
        return if (mResizing) true else v.onTouchEvent(event)
    }

    /** 把小窗收回屏幕内；比屏幕大时允许负偏移，这样超出的部分还能拖着看 */
    private fun clampMirrorPosition() {
        val binding = mMirrorBinding ?: return
        if (!mMirrorStatus) return
        val bounds = mWindowManager.currentWindowMetrics.bounds
        val width = binding.root.width.takeIf { it > 0 } ?: binding.root.measuredWidth
        val height = binding.root.height.takeIf { it > 0 } ?: binding.root.measuredHeight
        if (width <= 0 || height <= 0) {
            if (mMirrorLayoutRetryCount++ < 30 && binding.root.isAttachedToWindow) {
                binding.root.postOnAnimation { clampMirrorPosition() }
            }
            return
        }
        mMirrorLayoutRetryCount = 0
        val slackX = bounds.width() - width
        val slackY = bounds.height() - height
        mMirrorLayoutParams.x = mMirrorLayoutParams.x.coerceIn(minOf(0, slackX), maxOf(0, slackX))
        mMirrorLayoutParams.y = mMirrorLayoutParams.y.coerceIn(minOf(0, slackY), maxOf(0, slackY))
        tryOrNull { mWindowManager.updateViewLayout(binding.root, mMirrorLayoutParams) }
    }

    /** 长按缩放把手：回到 100% 并重新居中 */
    private fun resetMirrorSizeAndPosition() {
        mMirrorScale = 1f
        updateDipslaySize()
        // updateDipslaySize 刚改过 svMirror 的布局参数，这一帧量到的还是旧尺寸，
        // 同样要等布局稳定后再居中。
        mMirrorPositioned = false
        scheduleCenterMirror()
    }

    /**
     * 等小窗尺寸稳定后再居中。
     *
     * 用 postOnAnimation 连续采样：同一帧的 measure/layout 还没结束时拿到的宽高不可信，
     * 相邻两帧一致才说明布局已经稳定。最多试 [MAX_CENTER_ATTEMPTS] 帧，避免某次
     * 布局异常时永远不居中。
     */
    private fun scheduleCenterMirror() {
        val binding = mMirrorBinding ?: return
        mMirrorCenterAttempt = 0
        mMirrorCenterLastWidth = -1
        mMirrorCenterLastHeight = -1
        binding.root.postOnAnimation { centerMirrorWhenStable() }
    }

    private fun centerMirrorWhenStable() {
        val binding = mMirrorBinding ?: return
        if (!mMirrorStatus) return
        val width = binding.root.width.takeIf { it > 0 } ?: binding.root.measuredWidth
        val height = binding.root.height.takeIf { it > 0 } ?: binding.root.measuredHeight
        val stable = width > 0 && height > 0 &&
            width == mMirrorCenterLastWidth && height == mMirrorCenterLastHeight
        mMirrorCenterLastWidth = width
        mMirrorCenterLastHeight = height
        val attempts = mMirrorCenterAttempt++
        if (width > 0 && height > 0 && (stable || attempts >= MAX_CENTER_ATTEMPTS)) {
            centerMirror()
            return
        }
        if (binding.root.isAttachedToWindow) {
            binding.root.postOnAnimation { centerMirrorWhenStable() }
        }
    }

    /** 首次显示（以及恢复默认）时把小窗放到屏幕中间 */
    private fun centerMirror() {
        val binding = mMirrorBinding ?: return
        val bounds = mWindowManager.currentWindowMetrics.bounds
        val width = binding.root.width.takeIf { it > 0 } ?: binding.root.measuredWidth
        val height = binding.root.height.takeIf { it > 0 } ?: binding.root.measuredHeight
        if (width <= 0 || height <= 0) {
            if (mMirrorLayoutRetryCount++ < 30 && binding.root.isAttachedToWindow) {
                binding.root.postOnAnimation { centerMirror() }
            }
            return
        }
        mMirrorLayoutRetryCount = 0
        mMirrorLayoutParams.x = ((bounds.width() - width) / 2).coerceAtLeast(0)
        mMirrorLayoutParams.y = ((bounds.height() - height) / 2).coerceAtLeast(0)
        mMirrorPositioned = true
        tryOrNull { mWindowManager.updateViewLayout(binding.root, mMirrorLayoutParams) }
    }

    suspend fun onResume(width: Int, height: Int){
        // 窗口已经摘掉后可能还有在途的 onResume/重连调用：
        // 一旦放行就会把刚摘掉的悬浮窗重新加回来，且再也没人收尾。
        // 注意不能看 mDestroyed：延迟销毁倒计时期间的窗口仍然有效，重连要能取消倒计时。
        if (mWindowsDetached) {
            log(TAG, "onResume ignored: windows already detached")
            return
        }
        mDisplayWidth = width
        mDisplayHeight = height
        // 软重连可能带新的 density，不更新会让顶栏一直显示旧值。
        mDensityDpi = displayAdapter.mDensityDpi
        mMirrorBinding?.tvVirtualDisplayInfo?.text = "$mDisplayWidth*$mDisplayHeight,$mDensityDpi"
        interactiveMonitor.init()
        // 投屏在延迟销毁倒计时内重连：取消销毁，窗口恢复有效，
        // 否则下一次 onDestroy 会命中 "already destroyed" 而跳过倒计时直接收尾。
        mDestroyJob?.cancelAndJoin()
        mDestroyJob = null
        mDestroyed = false
        updateDipslaySize()
        mControllerBinding?.apply {
            tvDestroyTime.visibility = View.GONE
            ibMirrorDisplay.visibility = View.VISIBLE
        }
        showController()
    }

    suspend fun onDestroyPromptly() {
        log(TAG, "onDestroyPromptly: win=${System.identityHashCode(this)} already=$mDestroyed")
        // 即使已经进入销毁倒计时（mDestroyed=true），也必须取消那个倒计时：
        // 旧会话的窗口倒计时到点会回调 onDestroySucceed -> teardownDisplayNow()，
        // 把之后建立的新会话（尤其是 AAP Route B 的新虚拟屏）一起拆掉。
        mDestroyJob?.cancelAndJoin()
        mDestroyJob = null
        if (mDestroyed) {
            // 进入倒计时时已经释放过 monitor / 恢复过屏幕电源，这里只补关窗。
            close()
            return
        }
        mDestroyed = true
        runCatching { interactiveMonitor.release() }
        runCatching { toggleDisplayPower(true) }
        close()
    }

    /**
     * 非 suspend 的兜底关窗。
     *
     * system_server 侧的看门狗 / 客户端死亡路径必须立刻摘掉悬浮窗，既不能等
     * [onDestroy] 的倒计时，也不能假设 [close] 已经执行过（倒计时协程可能早已终止）。
     * 重复调用是幂等的。
     */
    fun detachWindowsNow() {
        log(
            TAG,
            "detachWindowsNow: win=${System.identityHashCode(this)} " +
                "controller=$mControllerStatus mirror=$mMirrorStatus"
        )
        mDestroyed = true
        mDestroyJob?.cancel()
        // 看门狗 / 客户端死亡路径不会走 onDestroy 的收尾，屏幕电源与 monitor 必须在这里
        // 释放；否则虚拟屏已经收掉，监视器仍持有 SCREEN_BRIGHT wake lock（还会关着护眼）。
        runCatching { interactiveMonitor.release() }
            .onFailure { log(TAG, "detachWindowsNow release monitor failed", it) }
        runCatching { toggleDisplayPower(true) }
            .onFailure { log(TAG, "detachWindowsNow restore display power failed", it) }
        runCatching { close() }
            .onFailure { log(TAG, "detachWindowsNow failed", it) }
    }
    suspend fun onDestroy(onDestroySucceed: () -> Unit) {
        if (mDestroyed) {
            // 延迟销毁期间可能再次收到 onDestroy；保证收尾只执行一次，且不重复 unregister。
            log(TAG, "onDestroy ignored: already destroyed")
            onDestroySucceed()
            return
        }
        mDestroyed = true
        runCatching { interactiveMonitor.release() }
        runCatching { toggleDisplayPower(true) }
        mDestroyJob?.cancelAndJoin()

        val binding = mControllerBinding
        if(mDelayDestroyTime == 0 || binding == null){
            // 悬浮窗 inflate 失败时也必须走完收尾：释放 VD、恢复护眼、清投屏状态位。
            close()
            onDestroySucceed()
            return
        }
        binding.apply {
            ibMirrorDisplay.visibility = View.GONE
            tvDestroyTime.setOnClickListener {
                mDestroyJob?.cancel()
                close()
                onDestroySucceed()
            }
            mDestroyJob = flow {
                for (i in mDelayDestroyTime downTo 0) {
                    emit(i)
                    delay(1000)
                }
            }.onStart {
                tvDestroyTime.visibility = View.VISIBLE
            }.onEach {
                if(mControllerStatus){
                    tvDestroyTime.text = "${it}S"
                }
            }.onCompletion {
                if(it == null){
                    close()
                    onDestroySucceed()
                }
            }.launchIn(CoroutineScope(Dispatchers.Main))
        }
    }

    fun toggleDisplayPower(displayPower: Boolean = !mDisplayPower){
        try {
            if(mScreenOffReplaceLockScreen){
                if (mDisplayPower == displayPower) return
                val mode = if (displayPower) {
                    SurfaceControlHidden.POWER_MODE_NORMAL
                } else {
                    SurfaceControlHidden.POWER_MODE_OFF
                }
                SurfaceControlHidden.setDisplayPowerMode(mInternalDisplayToken, mode)
                mDisplayPower = displayPower
                log(TAG, "display power: ${if (displayPower) "on" else "off"}")
            } else {
                Instances.powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "${BuildConfig.APPLICATION_ID}:wakeup").apply {
                    acquire()
                    release()
                }
            }
        } catch (e : Throwable){
            log(TAG, "", e)
        }
    }

    private fun showController(){
        // 已经摘过窗的实例不能再加窗，否则会留下无人收尾的悬浮窗。
        if (mWindowsDetached) {
            log(TAG, "showController ignored: windows already detached")
            return
        }
        if(mMirrorStatus){
            hideMirror()
        }
        if(mControllerStatus) return
        mControllerBinding?.apply {
            // 状态位和真实窗口可能不同步（上一次 removeView 失败被吞掉等）。
            // 已经挂在 WindowManager 上就只接管状态，不能重复 addView。
            if (!root.isAttachedToWindow) {
                // addView 失败时不能把状态置 true，否则本会话内永远不会再尝试显示。
                val added = runCatching { mWindowManager.addView(root, mControllerLayoutParams) }
                    .onFailure { log(TAG, "addView(controller) failed", it) }
                    .isSuccess
                if (!added) return
            }
            applyControllerCollapsedState()
            // post 会在 traversal 之前执行，此时 view 还没测量；用 postOnAnimation 并允许内部重试。
            root.postOnAnimation {
                applyControllerDockPosition()
            }
            mChangeAlphaCountDownTimer.start()
            mControllerStatus = true
            log(TAG, "controller shown: win=${System.identityHashCode(this)} root=${System.identityHashCode(root)}")
        }
    }
    private fun hideController(){
        mControllerBinding?.apply {
            val attached = root.isAttachedToWindow
            // 只要视图还挂在 WindowManager 上就必须 remove。只信 mControllerStatus 的话，
            // 状态位一旦和真实窗口不同步，控制器窗口会永久留在屏幕上（实测退出投屏后残留）。
            if (!mControllerStatus && !attached) return
            // removeView 是**异步**的（deferred doDie），先 remove 再读 isAttachedToWindow
            // 会一直为 true，下一次 close 还会重复 remove。这里用 immediate 同步摘除。
            runCatching { mWindowManager.removeViewImmediate(root) }
                .onFailure { log(TAG, "removeView(controller) failed", it) }
            mControllerStatus = false
            log(
                TAG,
                "controller hidden: win=${System.identityHashCode(this)} " +
                    "root=${System.identityHashCode(root)} attached=$attached " +
                    "after=${root.isAttachedToWindow}"
            )
        }
    }

    private fun showMirror(){
        if (mWindowsDetached) {
            log(TAG, "showMirror ignored: windows already detached")
            return
        }
        if(mMirrorStatus) return
        hideRecentTask()
        mMirrorBinding?.apply {
            if (!root.isAttachedToWindow) {
                val added = runCatching { mWindowManager.addView(root, mMirrorLayoutParams) }
                    .onFailure { log(TAG, "addView(mirror) failed", it) }
                    .isSuccess
                if (!added) {
                    // 镜像窗起不来就退回控制器，别让用户什么都没有。
                    showController()
                    return
                }
            }
            mMirrorStatus = true
            // 第一次弹出时居中；之后保留用户上次拖到的位置，只做越界回收。
            // 居中必须等布局稳定：addView 后第一趟测到的是过渡尺寸（见 mMirrorCenterAttempt）。
            if (mMirrorPositioned) {
                root.post { clampMirrorPosition() }
            } else {
                scheduleCenterMirror()
            }
        }
    }
    private fun hideMirror(){
        mMirrorBinding?.apply {
            val attached = root.isAttachedToWindow
            // 同 hideController：以视图是否还挂着为准，避免状态位不同步导致窗口残留。
            if (!mMirrorStatus && !attached) return
            runCatching { mWindowManager.removeViewImmediate(root) }
                .onFailure { log(TAG, "removeView(mirror) failed", it) }
            mMirrorStatus = false
            log(TAG, "mirror hidden: win=${System.identityHashCode(this)} attached=$attached after=${root.isAttachedToWindow}")
        }
    }

    private fun toggleRecentTask(){
        mMirrorBinding?.apply {
            if(llRecentTask.visibility == View.VISIBLE){
                hideRecentTask()
                return
            }
            llRecentTask.visibility = View.VISIBLE
            updateRecentTaskPanelSize()
            runIO {
                // 小窗只显示图标，不取快照
                displayAdapter.getRecentTask(withSnapshot = false).also {recentTask ->
                    runMain {
                        (rvRecentTaskLeft.adapter as DisplayRecyclerViewAdapter)?.setItems(recentTask.virtualDisplay)
                        (rvRecentTaskRight.adapter as DisplayRecyclerViewAdapter)?.setItems(recentTask.mainDisplay)
                    }
                }
            }
        }
    }
    private fun hideRecentTask(){
        mMirrorBinding?.apply {
            llRecentTask.visibility = View.GONE
            (rvRecentTaskLeft.adapter as DisplayRecyclerViewAdapter)?.clearItem()
            (rvRecentTaskRight.adapter as DisplayRecyclerViewAdapter)?.clearItem()
        }
    }

    private fun expandController(){
        setControllerCollapsed(false)
    }
    private fun collapseController(){
        setControllerCollapsed(true)
    }

    private fun setControllerCollapsed(collapsed: Boolean) {
        mControllerCollapsed = collapsed
        applyControllerCollapsedState()
        mControllerBinding?.root?.post {
            applyControllerDockPosition()
        }
    }

    private fun applyControllerCollapsedState() {
        mControllerBinding?.apply {
            cvPanel.visibility = if (mControllerCollapsed) View.GONE else View.VISIBLE
            cvHandle.visibility = if (mControllerCollapsed) View.VISIBLE else View.GONE
            llPanel.visibility = if (mControllerCollapsed) View.GONE else View.VISIBLE
            ibExpand.visibility = View.GONE
        }
    }

    private fun applyControllerDockPosition() {
        val binding = mControllerBinding ?: return
        // 用默认屏窗口的 bounds，而不是 Resources.displayMetrics：多窗口/旋转/车机 Resources 下两者会不一致。
        val bounds = tryOrNull { mWindowManager.currentWindowMetrics.bounds } ?: return
        val visibleWidth = getControllerVisibleWidth(binding)
        val visibleHeight = getControllerVisibleHeight(binding)
        if (visibleWidth <= 0 || visibleHeight <= 0) {
            // addView 后第一帧还没测量完，拿不到宽高；此时直接 return 会让控制器停在 (0,0)。
            // 下一帧再试，最多 30 帧。
            if (mDockRetryCount++ < 30 && binding.root.isAttachedToWindow) {
                binding.root.postOnAnimation { applyControllerDockPosition() }
            }
            return
        }
        mDockRetryCount = 0

        // 根布局给卡片阴影留了一圈透明 padding，卡片左上角相对窗口原点偏移了一个 pad，
        // 而 x/y 定位的是窗口。四种贴边情况算出来的都是「卡片该在哪」，所以统一减掉
        // 一个 pad；否则卡片会离屏幕边缘差一个 pad，收起时露出的宽度也少一个 pad。
        val pad = mControllerShadowPadPx
        val dockX = if (mControllerCollapsed) {
            if (mControllerDockRight) {
                bounds.width() - mControllerPeekPx
            } else {
                -(visibleWidth - mControllerPeekPx)
            }
        } else {
            if (mControllerDockRight) {
                bounds.width() - visibleWidth
            } else {
                0
            }
        }
        mControllerLayoutParams.x = dockX - pad
        mControllerLayoutParams.y = mControllerLayoutParams.y.coerceIn(
            -pad,
            (bounds.height() - visibleHeight - pad).coerceAtLeast(-pad)
        )
        tryOrNull { mWindowManager.updateViewLayout(binding.root, mControllerLayoutParams) }
    }

    private fun getControllerVisibleWidth(binding: WindowControllerBinding): Int {
        return if (mControllerCollapsed) {
            binding.cvHandle.width.takeIf { it > 0 } ?: binding.cvHandle.measuredWidth
        } else {
            binding.cvPanel.width.takeIf { it > 0 } ?: binding.cvPanel.measuredWidth
        }
    }

    private fun getControllerVisibleHeight(binding: WindowControllerBinding): Int {
        return if (mControllerCollapsed) {
            binding.cvHandle.height.takeIf { it > 0 } ?: binding.cvHandle.measuredHeight
        } else {
            binding.cvPanel.height.takeIf { it > 0 } ?: binding.cvPanel.measuredHeight
        }
    }

    private fun close() {
        log(TAG, "close: win=${System.identityHashCode(this)}")
        mWindowsDetached = true
        mChangeAlphaCountDownTimer.cancel()
        hideMirror()
        hideController()
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        return mControllerBinding?.run {
            var isDrag = false
            when(event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.apply {
                        setTag(R.id.is_drag, false)
                        setTag(R.id.drag_last_x, event.rawX)
                        setTag(R.id.drag_last_y, event.rawY)
                        setTag(R.id.drag_distance, 0f)
                    }
                    mControllerDragAccumX = 0f
                    mControllerDragAccumY = 0f
                    mChangeAlphaCountDownTimer.cancel()
                    ViewCompat.animate(root).setDuration(200).alpha(1.0f).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    isDrag = v.getTag(R.id.is_drag) as Boolean
                    val dragMoveX = event.rawX
                    val dragMoveY = event.rawY
                    val dragLastX = (v.getTag(R.id.drag_last_x) as? Float ?: 0f)
                    val dragLastY = (v.getTag(R.id.drag_last_y) as? Float ?: 0f)
                    if(!isDrag){
                        val dragSumDistance = abs(sqrt((dragMoveX - dragLastX).pow(2) + (dragMoveY - dragLastY).pow(2))) + (v.getTag(R.id.drag_distance) as? Float ?: 0f)
                        if(dragSumDistance > 5f){
                            isDrag = true
                            v.setTag(R.id.is_drag, true)
                            // 掐掉 View 排队中的点击/长按，避免拖动结束时误触发。
                            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                            v.onTouchEvent(cancel)
                            cancel.recycle()
                        } else {
                            v.setTag(R.id.drag_distance, dragSumDistance)
                        }
                    }
                    // 只有越过拖动阈值后才移动窗口；轻微抖动的点击不应让面板位移。
                    if (isDrag) {
                        mControllerDragAccumX += dragMoveX - dragLastX
                        mControllerDragAccumY += dragMoveY - dragLastY
                        val dx = mControllerDragAccumX.toInt()
                        val dy = mControllerDragAccumY.toInt()
                        if (dx != 0 || dy != 0) {
                            mControllerDragAccumX -= dx
                            mControllerDragAccumY -= dy
                            mControllerLayoutParams.x += dx
                            mControllerLayoutParams.y += dy
                            tryOrNull { mWindowManager.updateViewLayout(root, mControllerLayoutParams) }
                        }
                    }
                    v.apply {
                        setTag(R.id.drag_last_x, dragMoveX)
                        setTag(R.id.drag_last_y, dragMoveY)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDrag = v.getTag(R.id.is_drag) as Boolean
                    val bounds = tryOrNull { mWindowManager.currentWindowMetrics.bounds }
                    if (bounds != null) {
                        mControllerDockRight = event.rawX > (bounds.width() / 2f)
                    }
                    applyControllerDockPosition()
                    // CANCEL 时也要恢复自动淡出，否则 alpha 会停在 1.0 不再变化。
                    mChangeAlphaCountDownTimer.start()
                }
            }
            return if(isDrag) true else v.onTouchEvent(event)
        } ?: false
    }



}
