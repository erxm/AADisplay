# AADisplay

[![Fork](https://img.shields.io/badge/Fork-Nitsuya%2FAADisplay-blue?logo=github)](https://github.com/Nitsuya/AADisplay)
![Xposed Module](https://img.shields.io/badge/Xposed-Module-blue)
![Android SDK min 31](https://img.shields.io/badge/Android%20SDK-%3E%3D%2031-brightgreen?logo=android)
![Android SDK target 36](https://img.shields.io/badge/Android%20SDK-target%2036-brightgreen?logo=android)

把手机上的一块虚拟屏投到车机上，车机整屏显示投屏内容，控制则留在手机端的悬浮小窗上。
支持两条路线：**原版路线**（hook Android Auto / gearhead）与 **AAP 直连**（不依赖
Android Auto，直接实现 AAP 协议）。

本仓库是 [Nitsuya/AADisplay](https://github.com/Nitsuya/AADisplay) 的 fork，
面向 Android Auto 18.1 兼容性、TaskView 生命周期稳定性、投屏尺寸与悬浮窗尺寸的正确性。

## 投屏路线

主界面的「投屏路线」卡片可直接切换（设置页不再放路线项），并显示实时连接状态：

- **原版路线（默认）**：hook `com.google.android.projection.gearhead`，需要安装 Android Auto。
- **AAP 直连**：基于 [AARE](https://github.com/prototux/AARE) 的开源 AAP 协议栈，
  不依赖 Android Auto / gearhead；支持 USB accessory、无线（蓝牙 + 车机热点）以及
  debug 构建下的本地 TCP/localabstract 调试（配合 DHU）。

AAP 路线的思路与无线实现主要来自 [lagXkjy/AADisplay](https://github.com/lagXkjy/AADisplay)：
蓝牙 ACL → WPP → 加入车机热点 → TCP AAP。**大多数情况下 lagXkjy 的分支功能更完整、
适用性更广**；本仓库只做了与「原版路线共存」所需的最小裁剪。

在 AAP 路线下，虚拟屏由模块 App 创建（Route B），system_server 仅负责任务托管、
输入注入、镜像与手机端悬浮窗；原版路线的建屏 / 销毁路径保持不变。

### AAP 画面与证书

高级设置 →「投屏显示」中与原版参数放在一起：

- **AAP 视频编码**：默认 H.264 Baseline，优先 Level 3.1；可选自动 / H.265 / VP9 / AV1。
  车机未宣告所选编码或本机无对应编码器时回退 H.264；非 H.264 编码器启动失败会断开
  一次并以 H.264 重连。
- **AAP 最大分辨率**：在车机提供的配置里挑选不超过该值的最大分辨率（auto / 800x480 /
  1280x720 / 1920x1080）。
- **虚拟屏 DPI（通用）**：大于 50 时同时覆盖 AAP 路线的车机密度；0 / 留空时原版跟随
  手机、AAP 跟随车机。**Android Auto DPI（原版）** 仍只作用于原版路线。

证书沿用 AA 的 CarService 身份：默认从本机已安装的 Android Auto APK（base + split）
离线提取，并按 gearhead `versionCode` 打标签；**AA 升级后下一次 AAP 会话会自动重抽**，
抽取失败时保留旧证书而不是改用自签。也可在「AAP 证书」页从 APK / PEM 导入、从
[iscle/android-auto-certificates](https://github.com/iscle/android-auto-certificates)
下载、保存/切换密钥库身份或导出。

未安装 Android Auto 的手机也能走 AAP 路线（使用自签证书）；但校验证书链的车机会拒签，
此时需要导入或下载可用的 CarService 证书。

### 第三方代码与许可

本分支引用的全部第三方代码、版本提交与改动清单见 **[NOTICE-AAP.md](NOTICE-AAP.md)**，主要包括：

- [lagXkjy/AADisplay](https://github.com/lagXkjy/AADisplay) @ `1886f69`：AAP 直连思路与无线连接实现
  （蓝牙 ACL → WPP → 车机热点 → TCP AAP）的主要来源，本项目受其启发并做裁剪；
- [prototux/AARE](https://github.com/prototux/AARE)（`aap` 模块，GPL-3.0-or-later，
  pinned `main@5ac79ba`，vendored 于 [`vendor/aare-aap`](vendor/aare-aap)）；
- [opencardev/aasdk](https://github.com/opencardev/aasdk)（AAP protobuf 定义，GPL-3.0-or-later）。

本仓库整体按根目录 [LICENSE](LICENSE)（GPL-3.0）分发。

## 功能

- 两条路线：原版 Android Auto 路线与不依赖 AA 的 AAP 直连路线，主界面随时切换。
- 任意应用投到车机屏幕，按车机实际分辨率铺满，无硬编码分辨率表。
- AAP 支持 H.264 / H.265 / VP9 / AV1 与最大分辨率限制，默认 H.264 Baseline + Level 3.1。
- AAP 证书自动跟随 AA 升级重抽，也可导入、下载、切换密钥库身份。
- 手机端 MIUI 小窗形态的控制面板：可拖动、可等比缩放、长按复位，卡片外触摸穿透。
- 小窗内多任务面板，点击图标切换车机前台任务。
- 隐藏车机自带 UI 控件，回收左侧导航轨后按整块车机宽度重新申请投屏画布。
- 竖屏应用在车机上强制全屏，不被 letterbox 压成居中窄条。
- 投屏期间抑制护眼模式、修正 Gboard 悬浮键盘、可选禁用 Waze / Google 地图的车机投屏。

## 运行要求

- Android 12 及以上（SDK 31+）
- Root + LSPosed（或兼容的 Xposed 框架）
- 原版路线：需要安装 Android Auto（`com.google.android.projection.gearhead`）
- AAP 路线：不需要安装 Android Auto；校验证书链的车机需要可用的 CarService 证书

## 安装与配置

1. 从 [Releases](https://github.com/erxm/AADisplay/releases) 下载 APK 并安装。
2. 在 LSPosed 中为以下作用域启用模块（**模块自身不要勾选为作用域**）：
   - Android Auto（仅原版路线需要）
   - 系统框架（System Framework）
3. 重启设备。模块 hook 了 `system_server`，**每次更新模块后都必须重启才能生效**。
4. 打开 AADisplay：
   - 主界面「投屏路线」选择原版 / AAP，AAP 路线下可查看状态、连接/停止
   - 自动打开（Auto Open）、默认启动应用、延迟销毁时间
   - 高级设置 →「投屏显示」：虚拟屏 DPI（两条路线通用）、Android Auto DPI（原版）、
     AAP 视频编码、AAP 最大分辨率
   - 高级设置 →「AAP 证书」：查看、重抽、导入、下载、密钥库管理
   - 连接 AA 时禁用 Waze / Google 地图，隐藏车机 UI 控件，强制全屏、护眼与 Gboard 修正

## 测试环境

当前版本 `0.24#18.1-e4`（versionCode `3018`）在以下环境实测通过：

| 项目 | 版本 |
|---|---|
| 系统 | HyperOS `3.0.307.6`（Android 16） |
| Android Auto | `18.1.664444-release` |
| Xposed 框架 | LSPosed 2.2.0 |
| 车机端 | DHU（Desktop Head Unit，800x480 / 170dpi） |

本版面向 Android Auto 18.1；其他 AA 版本、机型与真实车机未做验证。
逐项验证记录见 [CHANGELOG.md](CHANGELOG.md)，各版本更新说明见 [docs/releases/](docs/releases)。

## 已知的系统限制

以下两点由系统机制导致，不是模块缺陷：

- **护眼模式**是 SurfaceFlinger 层的全局色彩矩阵，只作用于主屏后由 SF 广播到所有屏，
  没有 per-display 排除接口，投屏画面必然跟着变黄。模块默认在投屏期间临时关闭护眼、
  断开后按原值恢复；代价是投屏期间手机屏幕也暂时没有护眼效果。
- **Gboard 悬浮键盘**：Gboard 的键盘形态取自持久化设置，而设备形态按机身判断
  （投屏时仍是手机），投屏那块横屏虚拟屏因此命中「手机 + 横屏」这套键，键盘以悬浮形态打开。
  模块默认只修正 Gboard 读到的值，不写入 Gboard 设置；代价是投屏期间手机横屏也不会悬浮。

AAP 路线另外还有：

- 目前只在 DHU（TCP / localabstract）上实测，真实车机的 USB / 无线尚未验证；
  无线要求车机走标准 AA 无线流程（手机加入车机热点，不是 Wi-Fi Direct/P2P），
  首次连接可能弹出 Android 的“连接到此设备”确认框。
- 使用的 DHU 请用原版 `desktop-head-unit.exe` + `800x480 / 170dpi`。`800x533` 补丁版
  窗口比视频多 53px，底部会出现绿条，这是 DHU 自身渲染问题，不是投屏画面。
- 部分 ROM 上 `ListPreference` / `AlertDialog` 的列表项与对话框按钮收不到点击；
  本版本已把编码 / 分辨率 / 键盘位置改为独立选项页，证书页改为页内控件。

## 编译

```bash
./gradlew :aa-display:assembleDebug
./gradlew :aa-display:assembleRelease
```

## 致谢

- 上游：[Nitsuya/AADisplay](https://github.com/Nitsuya/AADisplay) →
  [Stashboy/AADisplay](https://github.com/Stashboy/AADisplay) → 本仓库。
- Coolwalk facetbar / 媒体占位屏链路来自
  [lagXkjy/AADisplay](https://github.com/lagXkjy/AADisplay/tree/AADisplay-Split-Lite)
  的 `AADisplay-Split-Lite` 分支，作者 **qiang**，按路径 cherry-pick 引入，
  每个提交保留原作者、原提交时间与原始 SHA；架构说明见
  [docs/COOLWALK_FACETBAR.md](docs/COOLWALK_FACETBAR.md)。
  其中车机侧触控接管刻意未引入 —— 本 fork 的控制方案是手机端悬浮小窗。

## 许可证

继承上游，见 [LICENSE](LICENSE)。
