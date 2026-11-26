# Vendor import 分支

本仓库用独立分支归档外部参考项目，保留上游提交与作者信息，便于追溯来源。
这些分支**不会并入 main**（除已 cherry-pick/改写后进入 main 的代码外），`main` 的代码树不受影响。

| 分支 | 上游 | 固定提交 | 用途 | 许可 |
|---|---|---|---|---|
| `vendor/aap-import` | [lagXkjy/AADisplay](https://github.com/lagXkjy/AADisplay) `main` | `1886f69`（2.0稳定版） | AAP 直连思路与无线连接实现的主要参考 | GPL-3.0 |
| `vendor/aare-import` | [prototux/AARE](https://github.com/prototux/AARE) | `5ac79ba` | AAP 协议栈（`aap` 模块） | GPL-3.0-or-later |
| `vendor/aasdk-import` | [opencardev/aasdk](https://github.com/opencardev/aasdk) `newdev` | `9bf6adf` | AAP protobuf 定义的上游快照 | GPL-3.0-or-later |
| `vendor/coolwalk-import` | [lagXkjy/AADisplay](https://github.com/lagXkjy/AADisplay) `AADisplay-Split-Lite` | 分支 tip `7f28bfb`（作者 qiang） | Coolwalk facetbar 相关提交 | GPL-3.0 |

说明：

- `vendor/aare-aap/` 是 main 中实际参与编译的 AARE `aap` 模块（含 protobuf 生成件），
  来源与固定提交见 `vendor/aare-aap/README.md`、`vendor/aare-aap/NOTICE.md`。
- protobuf 定义本项目实际随 AARE 快照引入（`aap/src/main/proto/aap_protobuf`），
  `vendor/aasdk-import` 只作为上游历史归档，不保证与快照逐文件一致。
- 各外部项目的版权与许可归其原作者所有；本仓库整体仍按根目录 [LICENSE](../LICENSE)（GPL-3.0）分发。
