# Changelog

## v0.24#18.1-e4 (2026-11-26)

基线：`89d03f2`（tag `v0.24#17.8-e3`）。发布说明见 [docs/releases/v0.24-18.1-e4.md](docs/releases/v0.24-18.1-e4.md)。

### Added
- **AAP 直连路线**：不经过 gearhead，直接实现 AAP 客户端。支持 USB accessory、
  无线（蓝牙 RFCOMM → WPP → 车机热点 → TCP）与 debug 构建下的 TCP/localabstract 调试。
  主界面新增「投屏路线」卡片，可随时切换并显示实时连接状态。
- **Route B 显示接管**：AAP 路线由模块 App 创建虚拟屏，system_server 通过
  `ICoreManager.onExternalDisplayAttached` 接管任务托管、输入注入、镜像与手机端悬浮窗。
- 新增 `AapRoute`（默认 `legacy`）、`AapEnabled`、`AapTcpPort` 配置项。
- `NOTICE-AAP.md`：声明 AARE / aasdk / lagXkjy 的代码来源、提交与改动，
  以及可选的证书数据源 iscle/android-auto-certificates（仅运行时下载，不包含在仓库内）。

### Changed
- 版本号：
  - `versionName`: `0.24#18.1-e4`
  - `versionCode`: `3018`
- 设置页移除 AAP 分类，路线选择与启停移到主界面；主界面顺序调整为
  状态 → 投屏路线 → 常用设置 → 设备信息。
- 车机服务器入口移到高级设置，改用 signature 级权限广播。

### Fixed
- **Android Auto 18.1**：`LayoutInfo` 构造器新增参数导致 hooks 全部失效；
  改为形状无关匹配，适配 18.1 的构造器。
- 协程未捕获异常可能打死 system_server；建屏/销毁增加超时、看门狗与客户端死亡兜底。
- 旧会话的 60s 销毁倒计时会拆掉之后建立的新会话。
- 悬浮球在某些路线下被按车机 170dpi inflate，尺寸缩到 59px；改用默认屏密度后恢复。
- 重连时重复 bind TCP/localabstract 监听导致 `EADDRINUSE`；改为常驻 accept 循环。
- 投屏收尾可能 force-stop gearhead；已加入保护名单。
- `IVirtualDisplayCreatedListener.onAvailableDisplay` 改为 `oneway`，消除
  system_server 的 FLAG_ONEWAY 警告与跨进程回调阻塞。

### 通用性
- 证书从已安装的 AA APK 离线提取，按 gearhead `versionCode` 打标签；AA 升级后
  下一次 AAP 会话自动重抽，抽取失败时保留旧证书，不用自签覆盖。
- AAP 前台服务类型改为 `connectedDevice`，缺少蓝牙/USB 授权时退回 `dataSync`，
  避免 Android 15+ 的 dataSync 每日时长限制。
- AAP 视频设置合入「投屏显示」：编码（H.264 Baseline/Level 3.1 优先、自动、H.265、
  VP9、AV1）与最大分辨率；车机未宣告或本机不支持时回退 H.264，非 H.264 编码器
  启动失败自动断开并以 H.264 重连。「虚拟屏 DPI」改为两条路线通用，
  「Android Auto DPI」仍仅原版路线生效。
- 新增 AAP 证书管理界面：状态查看、从已安装 AA 重抽、APK/PEM 导入、
  在线下载、密钥库保存/切换/删除、取消固定、导出与删除。
- 本 ROM 上 `ListPreference` / `AlertDialog` 的列表项与按钮收不到点击：
  编码 / 分辨率 / 键盘位置改为独立选项页，证书页改为页内控件 + 二次确认。

## v0.24#17.2-e2 (2026-09-02)

基线：`4a9746b`（tag `v0.24.0`，`0.24#17.2-e1` / `3012`）。详见 [docs/releases/v0.24-17.2-e2.md](docs/releases/v0.24-17.2-e2.md)。

### Added
- 迁移到 libxposed 现代 Xposed API：新入口 `ModernInit`（`META-INF/xposed/java_init.list`），
  配置改读框架守护进程里的远端配置，不再依赖 others 可读的 `aadisplay_config.xml`
  （LSPosed 2.2.0 起废弃、2.3.0 计划移除）。`XposedInit` 保留为旧框架兜底，两条路径共存。
  `module.prop` 的 `targetApiVersion` 停在 101，否则框架会屏蔽 EzXHelper 依赖的
  `de.robv.android.xposed.*`。
- `HookPreferences`：hook 侧唯一的配置入口，来源由入口类注入（现代=远端配置，
  legacy=`XSharedPreferences`+`reload()`），解析结果缓存，取不到时回落默认值。
- `RemoteConfigMirror`：把 UI 侧写入的本地 `SharedPreferences` 同步到远端配置
  （绑定时全量补齐，之后逐 key 推送）。
- `HookTarget`：与框架无关的「被 hook 目标」描述，替换各 hook 签名里的
  `LoadPackageParam`（现代 API 的两个回调都不给进程名，legacy 的 param 又造不出来）。
- `ModuleAssets`：补齐 EzXHelper 的 `modulePath` / `moduleRes`（现代 API 没有
  `StartupParam`），懒解析 + `PathClassLoader` 兜底，失败只记日志不抛。
- `CoolwalkComposeConstraintsGuard`：Compose 约束断言守卫，见 Fixed 第一条。
- 新增配置项 `ForceAppFullscreenOnProjection`（默认 `true`）。

### Changed
- 版本号更新为：
  - `versionName`: `0.24#17.2-e2`
  - `versionCode`: `3016`
- `packaging` 不再整块排除 `META-INF/**`，改为逐项排除依赖元数据，
  保住 libxposed 要求的 `META-INF/xposed/**`。
- `AndroidManifest.xml` 补上 `android:name=".Application"`：此前该类从未注册，
  `onCreate` 不会执行，`makeReadableForHooks` / `DynamicColors` / 全局 `App` 实际都没生效过。
- 模块自身不再作为作用域分发：`xposed_scope` 删掉 `io.github.nitsuya.aa.display`，
  `XposedInit` 对应分支改为 `null`。
- `CoreManagerService.config` 改为委托 `HookPreferences`；`AaVirtualDisplayAdapter` 的
  config 形参类型放宽到 `SharedPreferences?`。
- `AndroidAuoHook` 用 `target.appInfo?.sourceDir` 并对 null 跳过（原先会 NPE）。
- 删除 `AaDisplayProcessHook` / `AaDisplayPresentationResize` 与
  `CoolwalkDrawingSpecWiden` 的模块进程侧改写路径：模块自身进程无法被 hook，
  投屏尺寸由 system_server 侧 `DisplayProfileSettle` 裁定。
- 删除 content_bounds 的无键改写路径（进程级 `Rect(int,int,int,int)` / `Rect.set` hook）：
  gearhead 自己分配的 `Rect(80, 0, 1280, 720)` 与 rail 内缩后的 content_bounds 形状完全一致，
  无法靠收紧判定区分，撑到 1280 就与只有 1200 的协商画布矛盾。改写现在只走带键路径
  （config Bundle / `Bundle.putParcelable` / `Intent.putExtra` / `ArrayMap.put`），
  且 content_bounds 只平移到原点、不再撑大：`CoolwalkRailMath.expandedBoundsWithin`
  以传入矩形的宽度为上限，画布本身仍由 `CoolwalkDrawingSpecWiden` 负责放大。

### Fixed
- 修复开启「隐藏车机 UI 控件」后 `gearhead:projection` 闪退（连 DHU / 连车即断）：
  该开关把 GhFacetBar VD 饿到 1×720、Dashboard VD 饿到 1×1 并把残留窗口宽度写 0，
  而 17.x 的车机 UI 是 Compose —— 分栏容器测量时拿「主轴可用量 − 第一组已用量」当第二组预算，
  容器只剩 0/1 px 时差值为负，撞上 `Constraints(minW, maxW, minH, maxH)` 里
  `max >= min && min >= 0` 的硬断言，在 `AndroidComposeView.dispatchDraw` 的延迟测量里抛
  `IllegalArgumentException: maxWidth must be >= than minWidth` 打死进程。
  现在 hook 那两个校验入口（17.3 有两个，一个直接比较后抛、一个先与出布尔再抛），
  参数合法立即原样放行，只把本来必定抛异常的四元组夹回合法值：负的 max 收到 0，
  min 再夹进 max 以内，等价于「这一轴没有可用空间」。不吞异常、不改任何有效约束的语义。
- 修复竖屏应用在车机横屏上被 fixed-orientation letterbox 压成居中窄条：
  hook `ActivityRecord.getOverrideOrientation()`（12/13 回退 `getOrientation(int)`），
  把投屏屏上 activity 的固定朝向改成 `SCREEN_ORIENTATION_UNSPECIFIED`。
  实测 `setIgnoreOrientationRequest` 与 `OVERRIDE_ANY_ORIENTATION` 对虚拟屏均无效。
- 修复投屏时整机软重启（`je#system_server`）两处成因：`CommonContextWrapper` 构造时
  `InitFields.modulePath` 未初始化抛 `UninitializedPropertyAccessException`；
  `onCreateDisplay` 建悬浮窗的异常从 `GlobalScope` 逃到线程默认处理器。
- `WazeHook` / `GboardImeHook` 不再各自持有 `XSharedPreferences`。

### Verification
- 构建通过：`:aa-display:assembleRelease`；装机确认 `versionCode=3016`。
- 环境：HyperOS `3.0.307.6`（Android 16）+ Android Auto `17.3.662854` + LSPosed，车机端为 DHU。
- APK 内 `META-INF/xposed/{java_init.list,module.prop,scope.list}` 与 `assets/xposed_init` 均在；
  `system` 与 gearhead 两侧现代入口均加载，`ModuleAssets` 解析出 modulePath，
  `RemoteConfigMirror` 绑定框架服务并同步 16 个 key。
- 重启后连 DHU 跑完整段会话（`HideAaUiControls` 开）：logcat 无 `maxWidth must be`、
  无 `FATAL EXCEPTION`，DHU 侧无 `Failed to read from transport`，
  `:projection` 及另外四个 gearhead 进程全程同 pid 存活。
  守卫只命中一次：`repair Constraints w[1..1]→[1..1] h[0..-5]→[0..0]`，
  附带的调用栈落在分栏容器测量 → `Constraints.copy` → `dispatchDraw` 这条预期路径上。
- 画布（未加 `CoolwalkHuScreenParamsHook` 的那一轮）：
  `displayProfile locked: 1200*720,160 (reported=1200 rail=0 full=1200)`、
  `content_bounds=Rect(0, 0 - 1200, 720)`、`pillar_width=0`、rail 宽度回收为 0。
- 投屏屏上的应用（车机启动器 / 高德）`mBounds=Rect(0, 0 - 1200, 720)`、`letterBoxed=false`。
- 右侧黑边：补上 `CoolwalkHuScreenParamsHook` 后重跑 DHU，`:car` 侧读到车机全宽 1280，
  `CoolwalkProjectionBoundsHook` / `CoolwalkDrawingSpecWiden` 把内容槽 1200 撑回 1280，
  日志确认窗口右边界不再短一条 rail；肉眼二次确认未做。
- 连续两次重启无 `system_server` 软重启。

## 0.24 (2026-08-30)

基线：`c770d65` (`0.23#17.2-r5` / `3011`)。详见 [docs/releases/v0.24.0.md](docs/releases/v0.24.0.md)。

### Added
- Coolwalk 全屏链路：折叠车机侧边栏，回收 rail 宽度后按整块车机宽度重新申请投屏画布；
  软重连后 rail 晚一拍出现 / 被饿死时会重试 settle。
  （导入自 `lagXkjy/AADisplay@AADisplay-Split-Lite`，作者 qiang，按路径 cherry-pick 保留原提交历史）
- `ICoreManager` 新增 Coolwalk rail 通道，全屏信号经 `system_server` 中转
  （gearhead 在 Android 13+ 无法可靠投递定向广播）；4 个新方法追加在接口末尾，
  既有 Stub 序号不变：`reportCoolwalkRailSnapshot` / `getCoolwalkRailSnapshot` /
  `getCoolwalkReconnectEpochMs` / `notifyCoolwalkFullBleed`。
- 手机端小窗多任务面板的图标模式（`recent_task_icon.xml`，不加载任务快照）。
- 独立的 miuix 风格设置页 `SettingsActivity`。
- 新增配置项 `HideAaUiControls`（默认 true）、`SuspendEyeCareOnProjection`（默认 true）、
  `FixGboardFloatingIme`（默认 true）。

### Changed
- 版本号更新为：
  - `versionName`: `0.24#17.2-e1`
  - `versionCode`: `3012`
- 手机端镜像窗改为 MIUI 小窗形态：`WRAP_CONTENT` 浮窗、卡片外触摸穿透、顶栏/导航条可拖动、
  右下角把手等比缩放、长按复位。缩放为两因子模型
  `mDisplayRatio = mMirrorFitRatio * mMirrorScale`，触摸转发按该比例反算坐标，
  所有缩放入口都必须走 `updateDipslaySize()`。
- `DisplayImePolicy` 默认由强制 `LOCAL(0)` 改为 `FALLBACK_DISPLAY(1)`，键盘落回手机主屏，
  不再挡掉车机半屏；取值收敛在 `0..2`。
- `VoiceAssistShell` 默认值改为小爱语音助手（与 nitsuya 上游一致）。
- `XposedInit` 分发调整：自身进程挂 `AaDisplayProcessHook`；Gboard 进程只挂
  `GboardImeHook`，不再套用会把 `status_bar_height` 归零的 `OtherHook`。
- 主界面卡片化重排，开关移入设置页。

### Fixed
- 修复小窗多任务面板溢出卡片（「错位」）：`ll_recent_task` 原为 `match_parent` 而父
  `FrameLayout` 是 `wrap_content`，wrap 测量阶段 9:16 快照缩略图把条目撑得比车机画面还高。
  改为图标网格 + 运行时按 `sv_mirror` 实际像素写入面板尺寸与列数，并以
  `withSnapshot = false` 省掉逐任务的 `getTaskSnapshot` IPC。车机端快照模式不变。
- 修复投屏画面跟随 MIUI/HyperOS 护眼模式变黄：护眼是 SF 层全局
  `colorTransformMatrix`，无 per-display 排除接口，投屏期间临时关闭并在断开后按原值恢复。
- 修复投屏里 Gboard 以悬浮键盘打开：只改 Gboard 读到的键盘形态，不写 Gboard 设置。
- 修复 LSPosed 未激活时 `PreferenceManager` 内部抛 `SecurityException` 无法就地 catch：
  `resolveModeForHooks()` 先探测可用 mode 再交给 `PreferenceManager`。
- 修复连接 / 重连时闪媒体占位屏（随 Coolwalk 链路导入）。

### Removed
- 删除随 Coolwalk 目录导入的 `AaCoolwalkHuTouchHook.kt`：本分支不接管车机侧触控，
  控制走手机端小窗，留着是死代码。参考实现见 `reference/AADisplay-Split-Lite`。

### Verification
- 构建通过：`:aa-display:assembleDebug`
- 测试环境：HyperOS `3.0.307.6` + Android Auto `17.2` + LSPosed，车机端为 DHU
- 安装并重启测试机（`system_server` 侧改动需重启生效）
- DHU 实机验证：小窗拖动 / 缩放 / 长按复位正常；多任务面板在 100% 与缩小状态下均完整
  落在车机画面区域内，列数在 3 / 2 之间自适应；点击图标能切换车机前台任务；
  logcat 无 `InflateException` / `FATAL EXCEPTION` / `system_server` 异常。

## 0.23.6 (2026-07-26)

### Changed
- Production version updated to:
  - `versionName`: `0.23#17.2-r5`
  - `versionCode`: `3011`
- AADisplay now creates its virtual display with the active Android Auto `TextureView` surface immediately instead of creating a surface-less display and attaching later.
- Default launcher startup now resolves from installed home launchers at runtime when no explicit launch package is configured, avoiding stale hard-coded package defaults.

### Fixed
- Fixed blank AADisplay rendering on Android Auto `17.2` by preserving the `TextureView` surface, attaching it during virtual-display creation, and reattaching it on reconnect.
- Prevented duplicate virtual display creation during rapid Android Auto lifecycle callbacks.
- Made hook preference access resilient under LSPosed by committing preferences synchronously and verifying hook-readable preference files.
- Android Auto hooks now continue with safe defaults if preference access is temporarily unavailable instead of aborting all hook setup.

### Verification
- Built successfully:
  - `:aa-display:assembleRelease`
- Installed and reboot-tested on the connected device.
- Live Android Auto validation confirmed:
  - AADisplay side control panel works
  - on-device Android Auto controls work
  - AADisplay opens and renders launcher content
  - backend virtual display and AA-hosted display both showed the same rendered content

## 0.23.4 (2026-05-23)

### Added
- Separate production toggle for Google Maps-on-phone while Android Auto remains connected:
  - new persisted setting `DisableGoogleMapsOnAa`
  - component-level enable/disable manager for Maps projection services + ghost activity

### Changed
- Production version updated to:
  - `versionName`: `0.23#16.8-r1`
  - `versionCode`: `3006`
- Waze component toggle execution now uses the same success gating strategy as Google Maps (all component operations must succeed before returning success).

### Fixed
- Android Auto UI/resource hook stability:
  - `AaUiHook` now skips optional hooks when required resources are missing instead of asserting/crashing.
  - projection decoration constructor hook now uses compatible constructor matching rather than hard-coded constructor lookup.
- Gearhead phenotype property hook robustness:
  - `AaPropsHook` now resolves string fields dynamically to reduce obfuscation fragility.

### Cleanup
- Removed dead/unused code and assets not used by current production flow:
  - removed `AaControlService`, `AaCarService`, `SettingsActivity`, legacy `LauncherHook`, deprecated pref XML, and orphaned resources.
- Removed obsolete global build-feature flag from `gradle.properties` (now explicitly configured in module build config).

### Evidence / Validation
- Built successfully:
  - `:aa-display:assembleRelease`
  - `:aa-display:lintDebug`
- Verified on connected device app targets:
  - Android Auto `16.8.661854-release`
  - Google Maps `26.20.01.913318892`
  - Waze `5.18.5.6`
- Waze and Maps toggles validated as separate independent controls in app flow.

## 0.23.3 (2026-05-05)

### Added
- Smart-sidebar style controller flow:
  - floating controller now starts minimized by default
  - tap edge handle to open, tap close to hide, drag and snap left/right edge

### Changed
- Keyboard routing on virtual display now enforces local IME policy (`DisplayImePolicy=0`) for on-screen input on AADisplay side instead of fallback-to-phone behavior.

### Fixed
- Removed the non-functional disconnect-type (`A`) button from the floating controller.
- Disconnect countdown now replaces the monitor button slot directly during disconnect flow for a cleaner 2-control vertical stack.

### Verification
- `:aa-display:assembleDebug` passed after controller + IME policy updates.
- Installed successfully to test device for live verification.

## 0.23.2 (2026-05-05)

### Changed
- Release build signing now falls back to debug signing when `KEY_ANDROID` is not present, so maintainers can still produce a release artifact locally without exposing private signing credentials.

### Fixed
- Prevented DHU disconnect teardown from forcing the phone back to launcher/home when AADisplay session closes on the virtual-display home screen.
- Teardown package-stop logic now excludes:
  - AADisplay itself
  - configured launch/home package
  - current foreground package on the primary phone display

### Verification
- Reproduced issue with config where `LauncherPackage` and `HomePackage` were both launcher-based, then validated corrected behavior after patch.
- `:aa-display:assembleDebug` succeeds with this change.
- Installed and prepared for live device retest flow.

## 0.23.1 (2026-05-04)

### Added
- In-app GitHub menu now points to the production fork: `https://github.com/Stashboy/AADisplay`.

### Changed
- Internal AADisplay behavior now syncs `HomePackage` to `LauncherPackage` so task-view home behavior is consistent with selected launch package.
- Sidebar Back key handling now uses Android virtual-display key event semantics (`FLAG_FROM_SYSTEM | FLAG_VIRTUAL_HARD_KEY`) for more reliable back navigation.

### Fixed
- Sidebar Back now works reliably in task-view sessions.
- Backing out to AADisplay home now clears pinned PiP state using the same cleanup strategy as Home (with front-home checks).

### Cleanup / Optimization (Evidence-Based)
- Fixed a real API-compatibility risk in `AADisplayConfig`: replaced Java Stream `.toList()` usage (API 34+) with Kotlin collection operators (safe for min SDK 31).
- Added missing `super.onDestroy()` in `AaControlService` to resolve lifecycle correctness lint error.
- Removed unused settings warning view/resources and unused string bloat in main settings screen.
- Internationalization cleanup for top-right menu title (`GitHub` moved to string resource).

### Verification
- `:aa-display:assembleDebug` succeeds after all changes.
- `:aa-display:lintDebug` re-run confirms resolved issues for:
  - `MissingSuperCall` (AaControlService)
  - `NewApi` (AADisplayConfig stream/toList path)
  - `HardcodedText` (`menu_main.xml` GitHub title)
- Runtime verification completed against Android Auto `16.6` behavior in both DHU and real head unit sessions.

## 0.23.0 (2026-05-04)

### Added
- Dynamic TaskView sizing lock to stabilize UI rendering across different head unit dimensions.
- Better teardown behavior for TaskView sessions to reduce lingering app states on disconnect/exit.
- Android Auto 16.6 hook compatibility updates.

### Changed
- Simplified settings UI to a single screen with only production-relevant options:
  - Auto Open
  - Default Launch Package
  - Delay Destroy Time
- Default GitHub menu flow retained from the main screen (settings gear flow removed).
- Production version string updated to `0.23#16.6-r1`.

### Fixed
- Kernel panic scenario during specific TaskView lifecycle transitions.
- Home/exit transition handling that previously left abnormal PiP-like states.
- Inconsistent YouTube rendering outcomes caused by unstable virtual display dimensions.

### Security / Privacy
- Removed repository-tracked signing key material from source control.
- Added keystore patterns to `.gitignore`.
- Removed machine-specific Gradle JDK path from tracked config.

### Compatibility
- Validated behavior against Android Auto 16.6 and device-side testing with real head unit workflow.
