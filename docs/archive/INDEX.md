# 归档索引

一行一条：`日期 | 主题 | 结论 | 环境 | 证据（本地文件名，不入库）`。

「证据」列只写本地文件名，这些文件不随仓库分发；重新生成方法见该条说明或对应工具 README。

| 日期 | 主题 | 结论 | 环境 | 证据（本地文件名，不入库） |
|---|---|---|---|---|
| 2026-11-04 | MTP 导 RAW 打通与速率实测 | 1526 个对象 / 511 张 ARW / 11.9 GB；三张 24 MP RAW 共 73.29 MB、3.42 s，21.43 MB/s；MD5 与字节数一致 | ILCE-7C fw2.00 / 魅族 20 Infinity / Android 15 (API 35) / USB MTP | `out/mtp-report.txt`、`out/screen.png`；重跑见 `tools/android-mtp-probe/README.md` |
| 2026-11-04 | PTP/IP 直连会话（IED 协议复刻） | 相机只认当初配对的 GUID；存储枚举四个调用全部 AccessDenied，仅 `GetObjectInfo(0xFFFFC001)` 可用 | ILCE-7C fw2.02 / Wi-Fi PTP/IP，PC 与相机接同一台路由器 | `captures/init_ied.txt`、`captures/stream7.txt`、`captures/wlan.pcapng`（147,927,148 字节，约 141 MiB）；重抓见 `tools/sony-ptpip/README.md` |
| 2026-11-05 | 导片工具 UI 决策取证 | 规范表的 47 个值与页面实际值逐项一致 | 魅族 20 Infinity / Android 15 / 真机 | 本地日期工作区（`2026-11-05/`，未跟踪、不入库）；素材与复核口径见本页「UI 决策取证」条 |
| 2026-11-06 | 仓库开源前整理盘点 | 建立 10 张 issue，覆盖隐私清洗、归档规则、文档收敛与门禁 | 仓库 `main`（只读盘点，未接相机） | 无（结论直接落成 issue） |
| 2026-11-07 | 对外发布就绪度复盘点 | `support` 档条数漂移已对齐（#183）；登记 `tools/pe` 入口说明（#186）、`.screenshots` 忽略（#185）、证据坐标与归档索引补录（#187） | 仓库 `main`（只读盘点，未接相机） | 无（结论直接落成 issue） |

## 2026-11-audit 逐篇说明

`docs/archive/2026-11-audit/` 是 2026-11-04 一次性审计的存档（4 md + 1 txt），当时仓库 HEAD
`23318f2`，全程未连接相机。5 个文件按「产生时间与任务 / 核心结论 / 现在是否仍成立 /
原始素材如何重新生成」逐篇记录；正文本身是历史存档，不再更新。

### `a7c-usb-raw-feasibility.md`

- **产生时间与任务**（2026-11-04）：核查「安卓能不能经 USB 从 a7C 拿 RAW」。
  官方文档优先、第三方交叉验证，每条标注 `[官方]` / `[第三方]` / `[推断]`。
- **核心结论**：官方 Transfer & Tagging（Ver.2.1 起）+ 相机 MSC 可原样传 `.arw`，属「有条件可行」；
  自研 App 调 Camera Remote SDK 不可行（SDK 无 Android 构建、只向法人提供）；
  「边拍边把 RAW 落手机」没有官方路径，PC Remote 落盘只对 PC 主机。
- **现在是否仍成立**：技术底账沿用；真机部分已被主报告
  [`../research/a7c-usb-raw-2026-11.md`](../research/a7c-usb-raw-2026-11.md)（MTP 枚举 1526 个对象 /
  21.43 MB/s）与 [`../known-limitations.md`](../known-limitations.md) 取代。
  涉及官方 App、CrSDK 与第三方软件的条目会随外部产品更新而过时，引用前重核。
- **原始素材如何重新生成**：纯官方文档核查，无仓库内工具。按正文表格里的官方链接逐条回查，
  重点核 CrSDK 的支持 OS 列表与版本历史（Contents Transfer 是否仍只标注 a7C / a7R IVA）。

### `evidence-log-feasibility.md`

- **产生时间与任务**（2026-11-04）：上一篇核查的取证登记（A/B 部分，主代理自查），
  把每条判断落成「原文 + 链接 + 资料时间 + 官方/第三方」四列。
- **核心结论**：a7C 为 USB 3.2 Gen1 5Gbps；USB Connection 为 Auto / Mass Storage / MTP 三选一；
  PC Remote 可设 `Still Img. Save Dest.` 与 `RAW+J PC Save Img`（能只落 RAW）；
  CrSDK 支持列表含 ILCE-7C，但无 Android 且不对个人；SDK 1.05 起支持 a7C 的 Contents Transfer。
- **现在是否仍成立**：属「引文快照」，官方页面改版即需重核；结论已被上一篇与主报告吸收。
- **原始素材如何重新生成**：按表内链接逐条打开核对；无仓库内工具，也不含原始抓包。

### `evidence-notes-audit.md`

- **产生时间与任务**（2026-11-04）：15 个第三方参考仓库的只读检索中间产物——
  关键字全仓检索、HEAD 快照、USB / RAW / Android 三类证据摘录。
- **核心结论**：USB 相关关键字（`UsbManager` / `libusb` / `UsbDevice` / `PtpIp` 等）代码命中 0；
  仅 `RemoteCam` 经 Sony SDK 间接具备 USB 可能；RAW 传输只有 `RemoteCam` 桌面端；
  安卓工程只有 `alpharemote`，且是 BLE 不是 USB。
- **现在是否仍成立**：是 2026-11-04 的快照，上游仓库会变；工程结论「必须新写安卓 USB 传输层」
  已由本仓库自己实现并实测（[`../../tools/android-mtp-probe/`](../../tools/android-mtp-probe/)，
  数据见主报告与本页首条）。
- **原始素材如何重新生成**：把 15 个仓库 clone 到本地后，在各自根目录执行只读检索：

  ```powershell
  git grep -n -E "UsbManager|libusb|UsbDevice|UsbInterface|USB_HOST|android.hardware.usb|ptpip|PtpIp|PTPIP" -- .
  git log -1 --format="%ad %h" --date=short
  ```

  这些目录不得 `git add`：`.gitignore` 的忽略规则与 `check_no_vendored.py` 兜底。

### `sony-usb-raw-repo-audit.md`

- **产生时间与任务**（2026-11-04）：15 仓库审计的汇总——
  「能不能直接拿这批开源代码实现安卓 USB 直连 a7C 传 RAW」，逐条给 `文件:行号`。
- **核心结论**：不能。10 个纯 Wi-Fi、3 个 BLE，唯一同时具备「USB 可能性 + RAW 传输」的是桌面端
  `RemoteCam`（依赖专有 CrSDK，且 README 明说有线连接尚未实现）。
  可复用的只有零件级：安卓壳、PTP 事务参考、SDK 属性语义、Wi-Fi 内容模型。
- **现在是否仍成立**：对「这批仓库」的判断成立；「必须新写 USB Host + PTP 层」已由
  [`../../tools/android-mtp-probe/`](../../tools/android-mtp-probe/) 走通（MTP 枚举 1526 个对象 /
  511 张 ARW），边界另见 [`../known-limitations.md`](../known-limitations.md)，不再是待定项。
- **原始素材如何重新生成**：同上一条的只读检索；逐条行号证据需本地副本，本地没有副本时按
  [`../contributing/LOCAL_WORKSPACE.md`](../contributing/LOCAL_WORKSPACE.md) 自行 clone 上游。

### `repos-git-meta.txt`

- **产生时间与任务**（2026-11-04）：15 个本地仓库的 HEAD 快照（日期 + 摘要 + 短 hash），
  给审计结论定时间锚点。
- **核心结论**：最后提交从 2013-09-29 到 2026-11-04（取各仓库 HEAD 的 author date）；`alpha-fairy` 无提交；
  多数为浅克隆，不能据此判断上游完整维护历史。
- **现在是否仍成立**：只是当时快照，不代表上游现状；引用时必须标明快照日期。
- **原始素材如何重新生成**：在本地 15 个仓库目录逐个执行，汇总成同格式的纯文本清单：

  ```powershell
  git log -1 --format="%ad | %s | %h" --date=short
  ```

## UI 决策取证（2026-11-05）

素材是导片工具定稿设计包：`nordic_precision/DESIGN.md` 与三页 `code.html` 的 `colors` 配置。
本地取证目录不入库（`.gitignore` 的日期工作区规则），仓库内无法直接复核文件本身。

复核方式：按设计工具重新导出定稿包，**以三页 `code.html` 的 `"colors"` 配置（页面实际值）为比对基线**，
与 [`../../tools/android-mtp-import/UI-SPEC.md`](../../tools/android-mtp-import/UI-SPEC.md)
3.3 节的 47 个值逐项比对（当时结论为逐项一致）。`DESIGN.md` 与表中的差异
（如 `primary` / `tertiary` 定稿为纯黑、本版落 `#1d1d1f`）按 UI-SPEC §3.3「落地口径」表记录的
五处漂移处理，**不作为比对基线**。规范实现以 UI-SPEC 的表为准，不依赖本地目录。
