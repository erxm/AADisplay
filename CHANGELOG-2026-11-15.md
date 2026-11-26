# AADisplay 修改记录（2026-11-15）

- 仓库：`AADisplay-erxm1`
- 版本：`0.24#17.8-e3`
- 设备：`912620310093`（nezha / 移植澎湃OS / Android 16，UI 报 API 37）
- 本文只记录**今天实际落地的代码改动**，不含历史版本内容和纯构建/重启操作。

---

## 0. 总览

今天分三个阶段：

1. **整分支源码审查**（25 个文件，约 +583/−217）：修 P0 稳定性问题、资源泄漏、交互 bug、安全与 DexKit 2.3.0 迁移。
2. **DHU 真机回归**：验证 16 KB 对齐、P0 修复、自动打开、悬浮窗，并修掉运行期暴露的三个问题
   （镜像窗居中、进程被杀后虚拟屏泄漏、销毁后控制器悬浮窗残留）。
3. **新功能**：在 AADisplay 高级设置页启停 AA 开发者车机服务器（5277），并补 3 个低风险 P1 修复。

文件清单见文末。

---

## 1. P0：system_server 稳定性

### 1.1 协程异常兜底
**文件**：`aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/CoreManagerService.kt`

原实现用 `GlobalScope.launch(Dispatchers.Main)`，没有 `CoroutineExceptionHandler`。建屏/销毁链路里任何未捕获异常都会逃到线程默认处理器，**直接打死 system_server（整机软重启）**。

```kotlin
private val coroutineExceptionHandler = CoroutineExceptionHandler { _, t ->
    log(TAG, "uncaught coroutine exception", t)
}
private val safeMainScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + coroutineExceptionHandler)
private val safeIoScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineExceptionHandler)
```

所有 `runMain {}` / `runIO {}` 替换为 `safeMainScope.launch {}` / `safeIoScope.launch {}`。

### 1.2 建屏 / 销毁生命周期加固
**文件**：同上。

- 状态字段全部 `@Volatile`。
- 建屏进行中收到的重复 `onCreateDisplay`：写入 `mPendingDisplayListeners`，建屏完成后统一回调，避免并发建屏。
- **20 秒建屏超时**：`mCreateTimeoutRunnable` 复位 `mDisplayCreateInProgress`，避免回调丢失后本次开机永久无法投屏。
- 重连分支整体包 `runCatching`；profile 在“创建进行中”判断之后再解析。
- 只有 `onConnected` 成功后才暴露 `mAaVirtualDisplayAdapter`（避免半初始化对象被重连分支使用）。
- `onDestroyDisplay`：
  - `mDestroyInProgress` 单次执行；
  - `AtomicBoolean finishDestroy` 防止窗口回调重复；
  - 每一步 `runCatching`；
  - **销毁看门狗** `mDestroyTimeoutRunnable`：`DelayDestroyTime + 30s` 后若仍未完成则强制 teardown。
- `setDisplaySurface` 全部包 `runCatching`。

### 1.3 客户端进程死亡兜底（本次投屏泄漏的根因）
**文件**：`CoreManagerService.kt`

原销毁入口只有 App 侧 `AaMainFragment.onDestroy → CoreApi.onDestroyDisplay()`。App 进程被系统回收/强停时这条路径不执行，而 VirtualDisplay 由 system_server 持有，会一直留到下次重启。

- 在 `onCreateDisplay` 里对 `IVirtualDisplayCreatedListener` 的 binder 挂 `DeathRecipient`；
- 客户端进程死亡 → 立即 `teardownDisplayNow()`（跳过 60s 倒计时）；
- 实测：进程被杀后 **63ms** 完成 `virtual display released` + `displayProfile cleared`。

### 1.4 App 侧 ServiceProxy
**文件**：`aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/CoreManager.kt`

- 拆 `InvocationTargetException`；
- `DeadObjectException` / `RemoteException` 时清空缓存的 binder；
- 失败按返回类型给安全默认值（不再把异常抛进 UI/hook 线程）；
- `touch` 是热路径，不再逐条打日志；
- `pm == null` 判空；`linkToDeath` 包 `runCatching`。

### 1.5 配置读取失败重试
**文件**：`xposed/HookPreferences.kt`

原来解析失败也会置 `resolved = true`，整个进程生命周期都拿不到配置。现在只在成功时置位，失败后 2 秒退避重试。

### 1.6 系统进程 hook 加固
**文件**：`xposed/hook/AndroidHook.kt`

- `appInitUseDisplay` 改 `ConcurrentHashMap`；
- `bindApplication` 用 `firstOrNull { it is Configuration }` 取参数，避免参数顺序变化导致 ClassCastException；
- AMS 构造器 / `systemReady` hook 包 `runCatching`。

---

## 2. P1：悬浮窗与 UI

### 2.1 DisplayWindow 生命周期与测量
**文件**：`aa-display/src/main/java/io/github/nitsuya/aa/display/ui/window/DisplayWindow.kt`

- 新增 `mWindowContext`（默认屏 `WindowContext`）：尺寸/dp 换算不再用可能绑定在虚拟屏上的 `mContext.resources`；
- 贴边、拖动、onTouch 统一用 `mWindowManager.currentWindowMetrics.bounds`；
- `interactiveMonitor.init/release` 幂等；
- `mDestroyed` 守卫；`onDestroy` 在 binding 为空时也执行 `close()` + 完成回调；
- `showController/showMirror` 只在 `addView` 成功后才置状态位；镜像失败回退到控制器；
- `removeView` 是**异步**的：收尾改用 **`removeViewImmediate()`**（实测 `attached=true after=false`）；
- 可能同时存在多个 `DisplayWindow` 实例：新增 `mAllDisplayWindows` 注册表，teardown 时全量 `detachWindowsNow()`；
- 新增 `mWindowsDetached`：只有 `close()` 真正执行后才禁止 `onResume/showController/showMirror` 重新挂窗；
  延迟销毁倒计时期间仍允许重连取消销毁（`mDestroyed` 会复位）。

### 2.2 悬浮窗交互
**文件**：同上。

- 拖动位移用浮点累积（`mMirrorDragAccumX/Y`、`mControllerDragAccumX/Y`），修复慢拖“丢步”；
- 处理 `ACTION_CANCEL`，合成的 CANCEL 事件及时 `recycle()`；
- 超过阈值才移动窗口，轻微抖动不再让面板位移；
- 拖动结束后 alpha 恢复 1.0。

### 2.3 镜像窗首次居中
**文件**：同上。

`addView` 后第一趟 `wrap_content` 测量是过渡尺寸（SurfaceView 还是 0 高，只量到顶栏 150px），直接居中会把小窗放到 y=1165（正确约 764），且 `mMirrorPositioned` 置位后不再纠正。

- 新增 `scheduleCenterMirror()/centerMirrorWhenStable()`：连续两帧尺寸一致才居中，最多 30 帧；
- 长按缩放把手“恢复默认”也走同一条路径。

### 2.4 资源泄漏
**文件**：`ui/aa/AaVirtualDisplayAdapter.kt`、`ui/window/DisplayWindow.kt`

- `Bitmap.wrapHardwareBuffer()` 后显式 `HardwareBuffer.close()`；
- 镜像 `SurfaceControl` 在 addView/reparent 失败路径释放；
- 窗口尺寸读取失败时用 dp 兜底（`dimenPx()`），避免热装 APK 后 `Resources$NotFoundException` 拖垮整条 `onResume`。

### 2.5 列表 / 视图 / Fragment
- `DisplayRecyclerViewAdapter.kt`：`removeItem` 下标越界保护；`onSwiped` 用 `absoluteAdapterPosition` 并处理 `NO_POSITION`。
- `GenericMotionView.java`：未设置 consumer 时返回 `false`（不再 NPE，也不吃掉滚轮事件）。
- `AaDisplayActivityKt.kt`：`showMain` 先 `findFragmentById`；`showRecentTask` 用 `commitNow` 保证同步完成。
- `AaUiHook.kt`：多处 `findViewById(...)` 加 `?.let`，修复空指针。

### 2.6 Coolwalk / DexKit
- `CoolwalkFacetChrome.kt`：`ConstraintSet.clone` 前给所有子 View 分配 id；settle Runnable 包 `runCatching`。
- `AaCoolwalkProjectionHook.kt`：两参 `getParcelable` 加 API 33 判断。
- `AaCoolwalkAutoOpenHook.kt`：`ContextCompat.registerReceiver(..., RECEIVER_EXPORTED)`。
- `CoolwalkRailMath.kt`：`railPxRange` 保证非空；已知 rail 宽度不匹配时拒绝 2×rail 的 gap。
- `CoolwalkRailStore.kt`：`clear()` 同时清零持久化的 session 键；`read()` 包异常保护。
- `CoolwalkRailCoordinator.kt`：`maybeBeginReconnectIfNeeded` 默认 `sessionGapMs = 0`。
- `AaBtnEventHook.kt`：参数校验后再 `abortMethod()`。
- `AaPropsHook.kt`：`ConcurrentHashMap`；Cursor 每次 query 重建；DexKit `classes[0].methods`。
- `AaSignatureHook.kt`：DexKit `classes[0].methods`。
- `AaMediaPlaceholderHook` / `CoolwalkComposeConstraintsGuard` / `CoolwalkHuScreenParamsHook` / `AaDpiHook`：
  删除全部 15 处 `searchPackages = listOf("")`（DexKit 2.3.0 起不再匹配全部类）。
- `aa-display/build.gradle.kts`：`org.luckypray:dexkit:2.0.0-rc3 → 2.3.0`（native 库 16 KB 对齐）。

### 2.7 安全 / 线程
- `service/ShellManagerService.kt`：`onBind` 只允许 `SYSTEM_UID` 或模块自身 uid。
- `ui/setting/SettingsActivity.kt`：root shell 开关的 `apply()` 放到 `runIO`，失败回主线程回滚 UI。
- `ui/main/MainActivity.kt`：root 检查异步化；launcher 查询走 shell 缓存，避免主线程阻塞。
- `util/AADisplayConfig.kt`：`Properties.load` 包 `runCatching`。
- `util/RemoteConfigMirror.kt`：只有 setup 成功后才置 `installed = true`。
- `util/EyeCareSuspender.kt`：跨开机残留判断改用 `Settings.Global.BOOT_COUNT`。

---

## 3. 低风险 P1 收尾（今天最后一批）

### 3.1 AIDL 回调改 oneway
**文件**：`src/main/aidl/io/github/nitsuya/aa/display/xposed/IVirtualDisplayCreatedListener.aidl`

```aidl
interface IVirtualDisplayCreatedListener {
    oneway void onAvailableDisplay(int displayId, boolean create);
}
```

- 调用方在 system_server 主线程，同步跨进程回调会阻塞主线程；
- 客户端本来就只是记录 displayId 再 `runMain` 处理，异步语义一致；
- 实测：`IVirtualDisplayCreatedListener` 相关的
  `Outgoing transactions from this process must be FLAG_ONEWAY` 警告降为 0。
- 仍保留的 `FLAG_ONEWAY` 警告来自 `IShellManager.createVirtualDisplayBefore`（**故意同步**：before-create 脚本必须先执行完）
  以及 MIUI/framework 自身调用。

### 3.2 缺失类日志降噪
**文件**：`xposed/hook/aa/AaUiHook.kt`、`xposed/hook/aa/coolwalk/CoolwalkHookEnv.kt`

AA 17.8 删除了 `CarSystemUiControllerService`，属于预期路径。原来用 `log(tag, msg, e)` 打 E 级 + 完整
`ClassNotFoundException` 堆栈（LSPosed 还会再打一遍）。改为 `logDebug` 一行，仅 logcat debug 可见。

### 3.3 导出广播校验
**文件**：`xposed/hook/aa/coolwalk/AaCoolwalkAutoOpenHook.kt`、`ui/aa/AaDisplayActivity.java`

- 发送方：`AA_DISPLAY_SHOWN` 改为 `Intent(...).setPackage("com.google.android.projection.gearhead")`；
- 接收方：校验 `intent.getPackage()` 是否为 gearhead。
- 踩坑记录：API 34 的 `BroadcastReceiver.getSentFromPackage()` 对模块 App 自己发的这条广播**仍返回 null**，
  不能拿来做 sender 白名单（会误拒正常链路）；另外 Kotlin 里 `intent.package` 是语法错误，必须用 `getPackage()`。
- 实测：
  - 正常广播 → `AutoOpen stop retries (broadcast)` 正常出现；
  - 伪造的隐式广播 → `ignore AA_DISPLAY_SHOWN pkg=null`。

---

## 4. 新功能：在 AADisplay 内启停车机服务器

### 4.1 背景
AA 的车机服务器是 `com.google.android.projection.gearhead.companion.DeveloperHeadUnitNetworkService`：

- `exported=false`，跑在 `:projection`，`onBind()` 返回 null；
- `onCreate()` 立即 `startForeground(notification)`；
- `onStartCommand`：
  - `action == "shutdown"` → 关闭 `ServerSocket` + `stopSelfResult`；
  - 其它（含无 action）→ 起线程 `new ServerSocket(5277)`，返回 `START_STICKY`（进程被杀后系统会自动重启服务）。

### 4.2 Hook 侧
**新增文件**：`xposed/hook/HeadUnitServerBridge.kt`

- 在 gearhead **每个进程**的 `Instrumentation.callApplicationOnCreate` 时注册动态广播；
- 广播协议：
  - `io.github.nitsuya.aa.display.action.START_HEAD_UNIT_SERVER`
  - `io.github.nitsuya.aa.display.action.STOP_HEAD_UNIT_SERVER`
  - `io.github.nitsuya.aa.display.action.HEAD_UNIT_SERVER_RESULT`（结果回传，含 `stop/success/message`）
- 启动：`startForegroundService(Intent(context, 服务类))`；停止：`startService(intent.setAction("shutdown"))`；
- App 发**有序广播**（`setPackage(gearhead)`），成功后 `abortBroadcast()`，避免每个 gearhead 进程都启动一遍。

### 4.3 App 侧
**新增文件**：`util/HeadUnitServerController.kt`

- 注册结果接收器 → 发有序广播 → 2.5s 无回调则 root 兜底；
- root 兜底命令：
  - 启动：`am start-foreground-service -n <组件>`
  - 停止：`am start-foreground-service -n <组件> -a shutdown`
- 注意：普通 `am startservice` 即使用 root 也会被
  `Background start not allowed ... from uid=0 pkg=com.android.shell` 拒绝，必须用 foreground 版本
  （root 调用会被记为 `SYSTEM_UID`，放行）。

**UI**：`res/xml/pref_aadisplay_config.xml` 新增「车机服务器」分类，两个纯动作 Preference：

- `HeadUnitServerStart`：启动车机服务器
- `HeadUnitServerStop`：停止车机服务器

由 `ui/setting/SettingsActivity.kt` 绑定点击事件并 Toast 结果。主界面不再有该卡片。

### 4.4 纯命令触发（测试用）
```bash
adb shell am broadcast -a io.github.nitsuya.aa.display.action.START_HEAD_UNIT_SERVER -p com.google.android.projection.gearhead
adb shell am broadcast -a io.github.nitsuya.aa.display.action.STOP_HEAD_UNIT_SERVER  -p com.google.android.projection.gearhead
```

---

## 5. 验证记录

| 项目 | 结果 |
|---|---|
| 16 KB 对齐 | `zipalign -c -P 16` 通过；4 个 ABI 的 `libdexkit.so` `PT_LOAD p_align=16384` |
| P0 标记（安装包 dex） | `uncaught coroutine exception` / `display create timeout` / `onDestroy ignored` / `addView(controller) failed` 等均存在 |
| 协程异常 | 本轮 logcat 未捕获异常计数 = 0 |
| 建屏 | `displayProfile locked: 800*480,170`，VD 800×480 @170，launcher 正常启动 |
| 正常退出 | 关 DHU → 60s 倒计时 → `virtual display released` / `displayProfile cleared` → VD=0、模块悬浮窗=0 |
| 进程被杀 | `client process died` → **63ms** 内释放 VD；App 被拉起后能 `existing=false` 重新建屏 |
| 镜像窗居中 | y=764（(2480−952)/2），修复前 1165 |
| 控制器贴边 | 展开右缘 1116，收起 `x=1059`（露 14dp） |
| 车机服务器 | 三种场景均监听 5277：hook（gearhead 在线）、hook（重启后自动拉起）、root `start-foreground-service`（gearhead 完全没进程） |
| oneway | 本监听器相关 `FLAG_ONEWAY` 警告 = 0 |
| 日志降噪 | `AAD_AaUiHook` 的 `ClassNotFoundException` E 级堆栈 = 0 |
| 广播校验 | 正常广播正常处理；隐式伪造广播被忽略 |
| 崩溃 | crash buffer 为空，无 ANR |

---

## 6. 已知遗留 / 未做

- `IShellManager.createVirtualDisplayBefore` 仍是同步调用（`FLAG_ONEWAY` 警告的来源），
  因为 before-create 脚本必须在建屏前完成，暂不改异步。
- `AaVirtualDisplayAdapter.onDestroy` 中的 `removeTask` / `forceStopPackageAsUser` 仍在 system_server 主线程，
  属于潜在卡顿点，建议后续挪到 IO（UI 相关的 `removeView` 留在主线程）。
- `CoreManagerService.touch()` 使用 `runBlocking(Dispatchers.IO)`，是输入热路径，需实测手感后再改。
- Coolwalk rail 双轨判定、`DisplayProfileSettle` 收敛、护眼 observer 语义、RHD 布局未动
  （依赖 AA 内部行为或需要多版本矩阵测试）。
- **热装 APK 后必须重启**才能让 system_server/gearhead 用上新代码与资源；
  否则主界面会显示“需要重启生效”，严重时触发 `Resources$NotFoundException`。

---

## 7. 文件清单

### 新增
- `aa-display/src/main/java/io/github/nitsuya/aa/display/xposed/hook/HeadUnitServerBridge.kt`
- `aa-display/src/main/java/io/github/nitsuya/aa/display/util/HeadUnitServerController.kt`
- `CHANGELOG-2026-11-15.md`（本文）

### 修改（按目录）
- `aa-display/build.gradle.kts`
- `src/main/aidl/.../IVirtualDisplayCreatedListener.aidl`
- `src/main/java/io/github/nitsuya/aa/display/xposed/`
  - `CoreManagerService.kt`、`CoreManager.kt`、`HookPreferences.kt`
  - `hook/AndroidHook.kt`、`hook/AndroidAuoHook.kt`
  - `hook/aa/AaUiHook.kt`、`AaPropsHook.kt`、`AaSignatureHook.kt`、`AaBtnEventHook.kt`
  - `hook/aa/coolwalk/CoolwalkFacetChrome.kt`、`AaCoolwalkProjectionHook.kt`、
    `AaCoolwalkAutoOpenHook.kt`、`CoolwalkRailMath.kt`、`CoolwalkRailCoordinator.kt`、`CoolwalkHookEnv.kt`
- `src/main/java/io/github/nitsuya/aa/display/ui/`
  - `window/DisplayWindow.kt`、`window/DisplayRecyclerViewAdapter.kt`
  - `aa/AaVirtualDisplayAdapter.kt`、`aa/AaDisplayActivityKt.kt`、`aa/AaDisplayActivity.java`
  - `aa/view/GenericMotionView.java`
  - `main/MainActivity.kt`、`setting/SettingsActivity.kt`
- `src/main/java/io/github/nitsuya/aa/display/util/`
  - `AADisplayConfig.kt`、`CoolwalkRailStore.kt`、`RemoteConfigMirror.kt`、`EyeCareSuspender.kt`
- `src/main/java/io/github/nitsuya/aa/display/service/ShellManagerService.kt`
- `src/main/res/values/strings.xml`
- `src/main/res/xml/pref_aadisplay_config.xml`

> 注：`res/layout/activity_main.xml` 在开发过程中曾加入「车机服务器」卡片，随后按要求移入高级设置页，**净改动为无**。
