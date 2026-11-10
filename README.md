# a7C 有线传 RAW

让安卓手机通过 **USB 有线**读取索尼 a7C（ILCE-7C）存储卡里的 RAW（`.ARW`），替代只能传 JPG
的官方 App。单机实测 **21.43 MB/s**，比 Wi‑Fi 快约 22 倍。

## 能做什么，不能做什么

| 能 | 不能 |
|---|---|
| USB 有线枚举整卡、下载历史 RAW（包括旧照片） | PTP / PTP‑IP 会话只能取最新一张（`0xFFFFC001`） |
| 免 root、免协议逆向：用 Android 自带的 `android.mtp.MtpDevice` | **不免授权**：首次插线要在系统弹窗里勾一次「一律打开」，之后静默 |
| 记录完整性可校验：字节数 + MD5 + TIFF 文件头 | 不提供相机遥控 / 边拍边传（那需要写操作，探针只读） |
| 单环境实测吞吐 21.43 MB/s（三张 24 MP RAW，矩阵 E1） | 只在[验证环境矩阵](docs/verification-matrix.md)列出的 E1 / E2 验证过；其他机型与固件未验证 |

## 支持环境

| 能力 | 环境 | 状态 |
|---|---|---|
| MTP 枚举 + 下载 | ILCE-7C 固件 2.00 / 魅族 20 Infinity / Android 15 (API 35) / USB 有线（矩阵 E1） | 通过（2026-11-04，证据见[主报告](docs/research/a7c-usb-raw-2026-11.md) §12） |
| PTP‑IP 握手 + 取最新一张 | ILCE-7C / Wi‑Fi PTP‑IP（复刻 IED 协议） | 通过；同一会话下存储枚举 `AccessDenied` |
| 导片工具（照片墙 / 选择 / 进度；断点续传已停用） | 矩阵 E1 + E2（魅族 20 Infinity / MI 9，均 Android 15 API 35，USB） | 实现状态见[导片工具 README](tools/android-mtp-import/README.md)；逐条真机待验状态与证据见[验证环境矩阵 §四](docs/verification-matrix.md) |
| 安装最低版本 | Android 12（API 31）及以上 | 两个 APK 均 `minSdk = 31`、`targetSdk = 35`（[探针构建脚本](tools/android-mtp-probe/build.sh)、[导片工具构建配置](tools/android-mtp-import/build.gradle.kts)） |
| 其他机型 / 固件 | — | 未验证 |

单环境结论不外推：表里没列出的环境一律按「未验证」处理。

## 快速开始

```bash
# 1) 构建两个 APK（需要 Android SDK + JDK，细节见 docs/building.md）
#    Linux / macOS / Git Bash：
bash tools/android-mtp-probe/build.sh
bash tools/android-mtp-import/gradlew -p tools/android-mtp-import assembleDebug

# 2) 改完代码先跑门禁（本机与 CI 跑同一份清单）
python scripts/run_gates.py ci

# 3) 装到测试机
adb install -g tools/android-mtp-probe/build/mtp-probe.apk
adb install -g tools/android-mtp-import/build/outputs/apk/debug/mtp-import-debug.apk
```

**Windows PowerShell 注意**：PowerShell 里的 `bash` 落到 WSL，上面两条构建命令会失败
（WSL 内没有 Windows 的 SDK / JDK：探针报「找不到 Android SDK」，导片工具报
`JAVA_HOME is not set`）。用下面几条等价命令（本机已实测通过）：

```powershell
# 两个 APK 一起验：探针 build.sh + 导片工具自检 / 构建 / 验签
python scripts/run_gates.py manual

# 只要探针 APK：用 Git for Windows 的 bash（不是 PowerShell 里的 bash）
& "C:\Program Files\Git\bin\bash.exe" tools/android-mtp-probe/build.sh

# 只要导片工具
.\tools\android-mtp-import\gradlew.bat -p tools/android-mtp-import selfTest assembleDebug
```

相机侧：`MENU → 设置 → USB连接 → MTP`（不是 PC 遥控）。首次插线系统会弹授权，
要勾一次「一律打开」；应用侧无法绕过。三档门禁与钩子见[测试](docs/testing.md)，
环境变量与常见失败见[构建](docs/building.md)。

## 实测数据

**MTP（推荐路线）**——三张 24 MP RAW：

| 文件 | 大小 | 耗时 | 速率 |
|---|---|---|---|
| DSC02183.ARW | 24.44 MB | 1.19 s | 20.62 MB/s |
| DSC02185.ARW | 24.43 MB | 1.03 s | 23.67 MB/s |
| DSC02184.ARW | 24.42 MB | 1.03 s | 23.72 MB/s |
| 批量 | 73.29 MB | 3.42 s | **21.43 MB/s** |

完整性三重校验全过：字节数与相机报的一致、**MD5 手机侧与 PC 侧逐字节相同**、
文件头 `49 49 2A 00`（合法 TIFF/ARW）。同一台相机的 Wi‑Fi PTP 只有 0.95 MB/s。

## 为什么是 MTP，不是 PTP

在 PC Remote 会话里，相机**协议层就拒绝存储枚举**（实测，非推断）：

| 调用 | 结果 |
|---|---|
| `GetStorageIDs` / `GetNumObjects` / `GetObjectHandles` / `GetStorageInfo` | 全部 `AccessDenied` |
| `GetObjectInfo(0xFFFFC001)` | OK，只能拿最新拍的那一张 |

而 `GetDeviceInfo` 明确报告相机**支持** `0x1004` / `0x1007`——不是没实现，是会话类型不给。
换成 MTP 后同一台相机的枚举全部正常：1526 个对象、511 张 ARW、11.9 GB 一览无余。
协议细节与抓包分析见[主报告](docs/research/a7c-usb-raw-2026-11.md)与
[PTP/IP 会话分析](docs/research/a7c-ptpip-session.md)。

## 已知限制

- **MTP 会话结束后相机会挂起 USB**：症状、重试次数与耗时以 [AGENTS.md](AGENTS.md) §4 为准；
  应用内重试唤不醒，必须拔插数据线。
- **别用 `getObjectHandles` 的 format 过滤**：传 `0xB101` 返回 754 个，逐个 `getObjectInfo`
  只有 511 个真是 `0xB101`；老实走全量枚举（39 ms/个）或按 `parent` 逐目录。
- **PTP 握手有配对门**：随机 UUID 一律 `Init_Fail reason=1`，只认当初配对的 GUID；
  事件通道必须先于 `OpenSession` 建立，否则一个字节都不回。
- **MSC 直拷未验证**：安卓侧要 `libaums`，且仅 FAT32。

边界与未验证项的完整清单见[已知限制](docs/known-limitations.md)。

## 仓库结构

```
AGENTS.md               协作总纲：五步流程入口、红线、已证实结论、环境坑；细则在 docs/contributing/，实测数据在 docs/research/，门禁清单在 scripts/gates.json
CONTRIBUTING.md         外部贡献者入口（只给摘要，正文以 AGENTS.md 为准）
SECURITY.md             安全报告渠道与范围（漏洞走私密报告，勿附设备身份）
CODE_OF_CONDUCT.md      行为准则（Contributor Covenant 2.1 原文）
LICENSE                 Apache-2.0
CHANGELOG.md
docs/
  building.md           构建与安装
  testing.md            三档门禁与回归约定
  architecture.md       三条链路与导片工具分层
  known-limitations.md  已知限制与未验证项
  verification-matrix.md 验证环境矩阵（哪条结论在哪套环境验过）
  safety-and-privacy.md 只读边界、设备身份与签名密钥
  contributing/         ISSUE_GUIDE / PR_GUIDE / REVIEW_DISPATCH / LOCAL_WORKSPACE
  research/             调研主报告、PTP/IP 会话分析与探索记录索引
  archive/              按时间归档的过程记录与索引
scripts/                门禁清单 gates.json、执行器 run_gates.py 与各检查脚本
tools/
  android-mtp-probe/    MTP 探针 APK（手搓构建，无 Gradle）
  android-mtp-import/   导片工具（Kotlin + Compose，Gradle wrapper）
  sony-ptpip/           PTP/IP 只读探针 + 抓包解析器
  pe/                   静态分析小工具
.github/workflows/ci.yml
```

## 证据与本地文件

原始证据（抓包、手机侧报告、截图、RAW 样本）体积大且含设备身份，**不入库**。
clone 之后本地没有这些文件是预期行为，不是仓库损坏；结论都落在
[归档索引](docs/archive/INDEX.md)，需要原始文件时按[归档说明](docs/archive/README.md)
从历史提交恢复到工作区。

## 参与贡献

- 第一次来先读 [CONTRIBUTING.md](CONTRIBUTING.md)；流程与红线以 [AGENTS.md](AGENTS.md) §0、§2 为准。
- 发 issue 看[ISSUE_GUIDE](docs/contributing/ISSUE_GUIDE.md)，开 PR 看
  [PR_GUIDE](docs/contributing/PR_GUIDE.md)，合并评审契约见
  [REVIEW_DISPATCH](docs/contributing/REVIEW_DISPATCH.md)。
- 门禁清单只有一个事实来源 [scripts/gates.json](scripts/gates.json)；
  动 Android 工具时另跑 `python scripts/run_gates.py manual`。

## 安全与许可

- **探针只读**：`tools/` 下连相机的代码不下发任何写操作（不删除、不格式化、不改设置、不触发快门）。
  边界与设备身份处理见[安全与隐私边界](docs/safety-and-privacy.md)，漏洞报告渠道见
  [SECURITY.md](SECURITY.md)。
- `tools/android-mtp-probe/rea-debug.keystore` 是**调试用**签名密钥（口令 `android`），
  只为让 `adb install -r` 可复现，不含任何真实凭据。
- 仓库不含任何第三方参考项目：早期审计过的 15 个仓库只是维护者本机的只读副本，
  不是依赖，也不被任何构建或代码引用（[本地专属目录与内部工具](docs/contributing/LOCAL_WORKSPACE.md)）。
- 许可：[Apache License 2.0](LICENSE)。
