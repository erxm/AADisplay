# AAP 路线：第三方代码来源与许可声明

本分支 `feature/aap-route`（基线 `main@89d03f2`）新增的「不依赖 Android Auto 的 AAP 投屏路线」，
主要参考并受益于 [lagXkjy/AADisplay](https://github.com/lagXkjy/AADisplay) 的工作：AAP 直连的
整体思路、连接顺序（蓝牙 ACL → WPP → 车机热点 → TCP AAP）以及无线连接实现，都来自该分支。
本项目在此基础上按自身结构做了裁剪与最小整合。

> **适用性说明**：大多数情况下，[lagXkjy 的分支](https://github.com/lagXkjy/AADisplay)
> 功能更完整、适用性更广（含音频、HUD、无线自动发现等）。本分支面向的是
> 「保持原版 Android Auto 路线不变、以最小改动接入 AAP」的场景；如果没有与原版路线共存的需求，
> 建议优先使用 lagXkjy 的分支。

除下文列出的来源外，其余代码均为本项目自有实现。整个仓库按根目录 [LICENSE](LICENSE)
（GPL-3.0）分发。

---

## 1. 主要参考来源：lagXkjy/AADisplay

| 项 | 内容 |
|---|---|
| 仓库 | https://github.com/lagXkjy/AADisplay |
| 提交 | `1886f69e262a6cb0281231f3b5529fa8ceadad42` |
| 许可 | GPL-3.0 |
| 本地路径 | `aa-display/src/main/java/io/github/nitsuya/aa/display/aap/wireless/` |
| 关联 | 其无线 WPP/RFCOMM 流程基于 AARE 的 `wireless/*` |

**本项目参考的设计**：

- 在原有 gearhead 路线之外，单独提供 AAP 直连投屏；
- 手机作为 AAP 客户端，先经蓝牙 RFCOMM 完成 WPP 握手，再加入车机热点、建立 TCP 会话；
- USB accessory、无线与本地调试三种传输形态的组织方式；
- 监听蓝牙 ACL 变化，自动发现并连接车机。

**移植文件**（文件头已标注来源；均为 GPL-3.0）：

| 文件 | 本分支的改动 |
|---|---|
| `WirelessConnector.kt` | 移除依赖 system_server 私有扩展的 `preferCellularOverUnvalidatedWifi()` / `armCarAccessoryWifi()`，仅保留 App 侧 RFCOMM → WPP → Wi-Fi → TCP 流程 |
| `HeadUnitNetwork.kt` | 删除 minSdk 31 用不到的 Android 8/9 `joinLegacy` 路径；保留以 `WifiNetworkSpecifier` 加入车机热点的实现 |
| `WirelessAutoStarter.kt` | 原样使用（蓝牙 ACL / SDP 发现车机） |

> 该实现是「手机作为普通 Wi-Fi 客户端加入车机热点」，不涉及 Wi-Fi Direct/P2P 组网。

---

## 2. 协议栈：`vendor/aare-aap`

| 项 | 内容 |
|---|---|
| 项目 | AARE（Android Auto Reverse Engineering） |
| 仓库 | https://github.com/prototux/AARE |
| 模块 | `aap`（手机侧 AAP 协议栈） |
| 版本 | 上游 `main` @ `5ac79ba`（v1.3.0 时代），已 vendored 至 `vendor/aare-aap` |
| 许可 | GPL-3.0-or-later（`vendor/aare-aap/LICENSE`） |
| 说明 | `vendor/aare-aap/NOTICE.md`、`vendor/aare-aap/README.md` |

覆盖 TLS 与分帧（`AapSession`、`FrameIo`、`Cryptor`）、通道协商（`channels/*`）、
视频与输入通道、无线 WPP（`wireless/WirelessBootstrap`、`wireless/Wpp`）以及 protobuf 定义。

### Protobuf 定义（`aap_protobuf`）

| 项 | 内容 |
|---|---|
| 项目 | aasdk |
| 仓库 | https://github.com/opencardev/aasdk |
| 路径 | `protobuf/` |
| 版权 | Copyright (C) 2018 f1x.studio (Michal Szwaj) 与 OpenCarDev 社区 |
| 许可 | GPL-3.0-or-later |
| 说明 | 见 `vendor/aare-aap/NOTICE.md` |

构建集成：根 `settings.gradle.kts` 引入 `:aare-aap` / `:aare-proto`；
`aa-display/build.gradle.kts` 依赖 `project(":aare-aap")`，打包时排除 `**/*.proto`
源码（仅保留编译产物）。

---

## 3. 同项目参考实现（未逐行复制）

Route B 的接口与接管流程（`ICoreManager.onExternalDisplayAttached`、
`CoreManagerService.launchDisplayAttach()`、`AaVirtualDisplayAdapter.mExternalOwned`）
参考了本项目其它分支的思路，并按当前代码结构增量改写：

- 原 `onCreateDisplay` / `onDestroyDisplay` 的行为保持不变；
- 没有引入 `setPhoneWindowEnabled`、App 侧持窗或 HUD overlay 等会改变原版路线的逻辑；
- 手机端悬浮窗继续复用本仓库已有的 `DisplayWindow`。

---

## 4. 运行时下载的证书数据（非代码）

| 项 | 内容 |
|---|---|
| 仓库 | https://github.com/iscle/android-auto-certificates |
| 用途 | 「AAP 证书管理 → 从在线仓库下载」可选功能 |
| 引入方式 | **不包含在本仓库**；仅在用户点击时通过 jsDelivr / raw.githubusercontent 拉取 `latest` 下的 `certificate.pem`、`private-key.pem`、`metadata.json` |
| 校验 | X.509 解析、RSA CRT 私钥、证书/私钥模数匹配、签发者必须为 `Google Automotive Link` |

该功能只是证书**数据**的下载入口（与 AARE 的 “Download certificate” 同源），没有复制其代码；
证书材料的版权/分发条件归仓库作者与原始来源所有。不联网时可完全使用本机 AA 提取或本地导入。

---

## 5. 未使用的外部代码

本分支没有使用 hudcast 或其他未声明许可来源的代码；iscle 仓库只作为上述可选的证书数据源，
不包含任何代码。`aauto.aar`、DexKit、libxposed 等既有依赖的来源与许可见各自上游仓库，本次未改动。
