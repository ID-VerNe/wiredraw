# 验证环境矩阵

本仓库的每条结论都绑定一套环境。**换机型、换固件、换连接方式，结论就可能不成立**——
这张表就是用来防止把单机结论读成全平台事实的。

## 怎么读

- 单元格只有三种值：**通过 + 日期 + 证据链接** / **失败 + 错误码** / **未验证**。
- 每套环境固定标注五项：相机机型 / 固件 / 手机型号 / Android API / 连接方式；
  纯 PC 侧路线没有手机，把「手机型号」换成主机说明、Android 列写 `—`。
- 「通过」必须有可点击的证据（报告、工具 README 或 `文件:行号`）。
- 表里没出现的组合一律按「未验证」处理，**不要外推**。
- 新增一行的做法：真机复验 → 填日期与环境 → 补证据链接。没真机跑过的格子只能写「未验证」。
- 手机型号的显示名以设备 `ro.product.model` 为准；同一台机器的旧写法（如 E2 的
  `小米 9` / `Mi 9`）一律按编号读，不另立环境。
- 历史记录（`docs/research/`、`docs/archive/`、`CHANGELOG.md`）里的机型是记录当时的设备，
  是历史事实，**不随本表改名**；判断结论归属用编号，不改原文。

## 环境编号

| 编号 | 相机 | 固件 | 手机 / 主机 | Android | 连接 |
|---|---|---|---|---|---|
| E1 | ILCE-7C | 2.00 | 魅族 20 Infinity | 15 (API 35) | USB MTP |
| E2 | ILCE-7C | 2.00 | MI 9（小米 9，`cepheus`） | 15 (API 35) | USB MTP |
| E3 | ILCE-7C | 2.02 | PC（与相机同一路由器、同一网段） | — | Wi‑Fi PTP/IP |

新结论在 issue / PR 的环境行里仍按 [AGENTS.md](../AGENTS.md) §0.2 写齐五项环境
（机型 / 固件 / 手机 / Android API / 连接方式），并在其后补编号（如 `E2`）——编号只用于
归属，不替代五项。历史文档保留当时机型，归属按记录日期对应的环境读。

历史记录里的 `MI 9` / `Mi 9` / `小米 9` 都指 E2；例如 `#59` / `#61` 的机型记录按本表归属 E2。

## 一、USB MTP 路线

| 能力 | E1 | E2 | 其他机型 / 固件 |
|---|---|---|---|
| 枚举（`open` → `getStorageIds` → 全量 `getObjectHandles` → 逐个 `getObjectInfo`） | 通过 2026-11-04（[主报告 §12.2](research/a7c-usb-raw-2026-11.md) 记录 1526 个对象全覆盖） | 未验证（PC 探针只记录了抽样对象的 `getPartialObject` 语义，没有全量枚举的原始输出） | 未验证 |
| 下载与完整性（字节数 + MD5 + TIFF 文件头） | 通过 2026-11-04（[主报告 §12.3](research/a7c-usb-raw-2026-11.md)） | 通过 2026-11-06（[探针 README「PC 探针」](../tools/android-mtp-probe/README.md)） | 未验证 |
| 插线自动拉起（`ACTION_USB_DEVICE_ATTACHED` → 自动授权 → 扫描） | 通过 2026-11-05 18:48（[导片工具 README「真机待验清单」第 1 项](../tools/android-mtp-import/README.md)；这一批实测的机型 / Android / 连接方式见 [UI-SPEC §0.11 第 7 行](../tools/android-mtp-import/UI-SPEC.md)：ILCE-7C fw2.00 / 魅族 20 Infinity / Android 15 API 35 / USB MTP） | 未验证 | 未验证 |
| 冷启动检测（`autoScanOnLaunch`）+ 同一次插拔去重 | 通过 2026-11-05 18:48（同上第 2 项：广播路径「→ 开始扫描」，attach 路径「→ 扫描已在进行中」，单次会话 1526 对象；同一批次的机型出处同上格） | 未验证 | 未验证 |
| 首次系统授权（勾一次「一律打开」，之后静默） | 通过 2026-11-05 18:48（[导片工具 README「真机待验清单」第 1 项](../tools/android-mtp-import/README.md)：`permissions_manager` 只有既有 `uids`，日志无「尚无 USB 权限，发起申请」；「首次必须勾一次」这一系统约束见 [UI-SPEC §0.11 第 7 行](../tools/android-mtp-import/UI-SPEC.md)） | 未验证 | 未验证 |
| 吞吐 | 通过 2026-11-04：21.43 MB/s（73.29 MB / 3.42 s，[主报告 §12.3](research/a7c-usb-raw-2026-11.md)） | 通过 2026-11-06：22.86 MB/s（`getPartialObject` 分块；App 侧该路径已在 #177 停用，现走 `importFile` 整文件） | 未验证 |

## 二、Wi‑Fi PTP/IP 路线

| 能力 | E3 | 其他环境 |
|---|---|---|
| 发现与配对握手（SSDP + HTTP 64321 → `Init`） | 通过 2026-11-04（顺序见[会话分析 §2.1–§2.3](research/a7c-ptpip-session.md)；**前提**是用当初配对成功的 GUID，随机 UUID 会 `Init_Fail reason=1`，见[会话分析 §5.1 第 1 条](research/a7c-ptpip-session.md)） | 未验证 |
| `OpenSession`（事件通道先建） | 通过 2026-11-04（[会话分析 §5.1 第 2 条](research/a7c-ptpip-session.md)：少这步一个字节都不回；顺序实测见[主报告 §11.2](research/a7c-usb-raw-2026-11.md)） | 未验证 |
| 存储枚举（`GetStorageIDs` / `GetNumObjects` / `GetObjectHandles` / `GetStorageInfo`） | 失败 `AccessDenied`（2026-11-04，[会话分析 §5.2 枚举失败矩阵](research/a7c-ptpip-session.md)） | 未验证 |
| 取最新一张（`GetObjectInfo(0xFFFFC001)` + `GetPartialObject`） | 通过 2026-11-04（[会话分析 §2.5](research/a7c-ptpip-session.md) 的取文件流程；空闲时返回 OK 但 0 字节这一注意事项见 §5.3.1，不是通过证据） | 未验证 |
| 取历史对象（任意旧句柄） | 失败 `AccessDenied`（同上：枚举被拒，拿不到历史句柄） | 未验证 |
| 吞吐 | 通过 2026-11-04：0.95 MB/s（143 MB / 150 s，含实时取景；[会话分析 §1](research/a7c-ptpip-session.md)） | 未验证 |

> 这张表里的「失败」不是代码缺陷，而是**会话类型本身的限制**：PC Remote 会话下相机只放行
> `0xFFFFC001`，存储枚举一律 `AccessDenied`。处置方式见[已知限制](known-limitations.md)。

## 三、`getPartialObject` 的 offset 语义（USB MTP）

这一节不并进「一、USB MTP 路线」的能力表：它不是「能不能传」，而是「非零 `offset` 的请求
返回的字节到底从哪里开始」。这条语义直接决定断点续传能不能用，所以单列。

| 能力 | E1 | E2 | 其他机型 / 固件 |
|---|---|---|---|
| 非零 `offset` 的内容起点（R0–R3 判别） | 未验证 | 失败（不能把 `offset` 当绝对偏移用：**非零 `offset` 只有在恰好等于相机内部游标时才被采纳；对不上时忽略 offset、从对象头返回**。R1 @2 MiB、R2 @8 KiB 的判定行 `实际内容位置=0`，**无错误码**；同轮 25 次顺序拼接 MD5 与整文件一致。2026-11-07，[探针 README「PC 探针」](../tools/android-mtp-probe/README.md)） | 未验证 |

> 这里的「失败」指**不能把 `offset` 当绝对偏移用**，不是传输失败。精确语义：非零 `offset`
> 只有在恰好等于相机内部游标时才被采纳；对不上时忽略 offset、从对象头返回。这里的「游标」是
> **相机侧读流的位置**（相机在本次会话里维护，不是客户端变量；是否按对象独立维护未验证）。
> 实际后果：从 0 起逐块顺读永远对（此时每步 `offset` 恰好等于游标，字节数 + MD5 一致）；
> 跳读与续传一定错；而且**续读不省带宽**——要让 `offset` 对上相机游标，得先把前一段从 0
> 读一遍。所以断点续传不成立，#177 的处置是停用续传、整文件重发。

### 会话时长维度

R0–R3 只在 E2 的**新会话**跑过一轮（2026-11-07，`main` @ `261942b`）。同一套判别按会话时长
分档的记录如下；「通过」只表示**判别实验已跑完、结论有证据**，不代表 offset 可用——E2 的结论
正是「`offset` 只在恰好等于相机游标时才被采纳」。

| 会话已持续 | E1 | E2 |
|---|---|---|
| 0 分钟（新会话，`OpenSession` 后立即跑） | 未验证 | 通过 2026-11-07（判别完成：非零 `offset` 只在恰好等于相机游标时才被采纳、对不上时从对象头返回；证据见上表链接） |
| 5 分钟 | 未验证 | 未验证 |
| 10 分钟 | 未验证 | 未验证 |
| 20 分钟 | 未验证 | 未验证 |

- **未验证不是通过**，更不是「已确认安全」：它只表示没有实测记录。任何「长会话下 `offset`
  可以当绝对偏移用」或「长会话下续传可以恢复」的推断都没有依据。
- 未验证的原因是**成本与决策**：每格约 25 分钟实测（四档 × 两台手机），而 #177 的处置
  （停用续传、整文件重发）不依赖长会话结论。**不是「验不出来」。**
- 何时会重新需要它们：**恢复断点续传**、改动分块 / offset 策略，或需要用长会话游标行为解释
  某次传输产物时，必须重新实测；通过之前一律按「未验证」引用。

E2 的两条基础证据要**合解**才得到上面的语义：R0–R3（#179）判别非零 `offset` 的内容起点；
#67 的 25 次顺序拼接（字节数 + MD5 与整文件一致）证明从 0 逐块顺读这条路径完整。
单看 R0–R3 会误读成「每次都从 0 返回」，单看顺序拼接也推不出「非零 offset 何时被采纳」。

## 四、发布前真机待验清单（2026-11-08 快照）

> 数据来源：逐条 `gh issue view` 的正文与全部评论 + `gh issue list --state open --label needs-live-verify`，
> 2026-11-08 快照。**这张表是账本**：已关闭的条目保留行、改状态并写关闭日期，不删行。
> 状态词只在本节使用（上面三张能力表仍按「怎么读」的三值口径）：
>
> | 状态 | 含义 |
> |---|---|
> | 通过 | 该行判据已满足；证据类型（真机 / 静态 / 算术）写在「依据」列 |
> | 未通过 | 跑过判据但结果不满足，或该行的功能尚未落地 |
> | 未验证 | 仍有验收点没跑，或没有可执行证据 |
> | 环境不支持 | 本环境无法构造该条件，不是没排期，也不是代码缺陷 |
> | 前提不满足 | 原 issue 的前提在本轮不成立，已改用替代构造或留待重做 |
> | 未复现 | 按原步骤没触发目标路径，不能算症状消失 |
>
> 已关闭条目的环境统一为 E2（ILCE-7C fw 2.00 / MI 9 / Android 15 API 35 / USB MTP），
> 另有标注除外；E1 本轮未验，不外推。历史 25 条的口径见 #159，本表已按本轮结论重写。

### 4.1 已关闭（回签通过）

| 优先级 | issue | 待验点 | 状态 | 关闭日期 | 依据（结论 + 证据类型） |
|---|---|---|---|---|---|
| P0 | #177 | 续传停用后整文件重发，无静默损坏 | 通过 | 2026-11-08 | 真机：预置 1,048,576 字节 `.part` 后重导，日志「已清理无主半成品：1 个」，成品 24,800,000 字节、MD5 `7d4cf952…` 与完整导入基准逐字节一致。**判据已改**：原引用的 `2026-10-08/DSC04541.ARW` 已不在卡上、原 MD5 真值失效，改用同链路完整导入成品作基准（记录见 #177 的回签评论）。 |
| P1 | #44 | 4 处视觉偏差 | 通过 | 2026-11-08 | 真机：P1-1 传输态断线可见（拔线切 `Disconnected`、插回自动回在线）。**两处降级**：P1-3 环轨道对比度是「实现 + 算术」证据（`outline` 白底 4.47:1），本轮未做真机取色；`V-26` 三处复位只有 `USB_ATTACHED` 被真机覆盖，`IMPORT_START` / `DONE` / `RESUME(lost=true)` 只有自检。复审留出的 #265 / #266 / #267 / #268 / #269 是独立 bug 单，不改变本行判据。 |
| P1 | #83 | filter 语义与像素优先 JPG | 通过 | 2026-11-08 | 真机：`All` 档导出 671 个 JPG / 941 MB、ARW 0 个；滤波只筛选、所见即所得、JPG 优先三条成立。**口径已改**：三档字节表以 `DemoTree.java` 源码推导为准，`RAW` 档为 12,766,800,000 字节（11.89 GB），回签首评的「1.00 GB」实为 `Jun 7` 的 RAW 载荷（记录见 #83 的数字更正评论）。 |
| P1 | #209 | 完成 / 跳过判据 | 通过 | 2026-11-08 | 真机：全量重导 `完成 0 / 跳过 671 / 失败 0`，逐条 `[Skipped] … Skipped existing`；`SkipRule` 是唯一判据。 |
| P1 | #214 | `visibleUnits` 唯一派生 | 通过 | 2026-11-08 | 静态：`AppModel.kt` 的 `visibleUnits` 是唯一派生源，CTA / 已选数 / 组头都由它重建（`git grep` 核对，无真机像素证据）。 |
| P1 | #215 | 设计常量 | 通过 | 2026-11-08 | 静态：`git grep -E "[0-9]+(dp|sp)"` 在 `ui/` 下零匹配，尺寸全部走具名常量。 |
| P2 | #53 | `Skipped` 进 `Details` | 通过 | 2026-11-08 | 真机：DONE 态点 `Location` 打开抽屉，含 `Skipped 671 existing` 与逐文件明细；主视觉 DONE 不含该句。 |
| P2 | #57 | 暂停后改勾选 | 通过 | 2026-11-08 | 真机：暂停轮改勾选 19 → 17，日志「暂停轮次的清单已变化（旧 19 个 / 新 17 个）：取消旧轮次，按当前勾选重新开始」。 |
| P2 | #64 | 视图关闭后全失败静默 | 通过 | 2026-11-08 | 真机：关视图后最终 UI dump 含 `导入失败，请拔插数据线` / `Reconnect`，错误未被吞掉。 |
| P2 | #65 | 取消日志语气 | 通过 | 2026-11-08 | 真机 + 静态：旧串「正在传的文件会被中断」零命中；新串「已请求取消（若正在传文件，会尝试中断；能否打断无跨机型保证）」出现。「打不断」分支仍无真机证据，但不是本行要改的字符串。 |
| P2 | #116 | 自检 / README / `ERROR` 文案 | 通过 | 2026-11-08 | 静态：`SelfTest` 7 条挂起用例且条件互异；README 写 60 s 口径；`ERROR` 主文案为「相机连接已断开，请拔插数据线」+ `Reconnect`。 |
| P2 | #124 | 暂停注释与预检顺序 | 通过 | 2026-11-08 | 静态 + 单机：`precheckSpace(plan)` 在暂停轮取消之前，`KEEP_PAUSED` 保留旧暂停轮；低空间竞态在 E2（可用约 171 GB）仍构造不出来。 |
| P2 | #217 | 抽屉 / 按钮抽离 | 通过 | 2026-11-08 | 静态：两个抽屉共用 `DrawerSkeleton`，图标统一走 `Glyph`，次按钮复用 `SecondaryButton`；无真机布局观感证据。 |
| P2 | #221 | 缩略图链与短读 | 通过 | 2026-11-08 | 静态：`fetchFull` 走 `RangeRead.read(entry.size, 1 << 20, …)`，`decode` + `commit` 单点。设备短读 / 超量返回的真机边界仍未覆盖，相关残余风险见 #188。 |

### 4.2 仍 open（缺哪个验收点）

| 优先级 | issue | 待验点 | 状态 | 已验部分 / 缺哪个验收点 | 复现动作（可粘贴） |
|---|---|---|---|---|---|
| P0 | #50 | 真实 MTP 导入落盘与统计 | 未验证 | 已验：`2026-11-07/DSC05053.JPG` 落盘 1,056,849 字节，手机侧与取回侧 MD5 均 `8fedf390…`；journal / index 已落账（**当时索引仍在写**，2026-11-08 已按 #20 裁决 B 停写）；`.part` 零残留；全量 `完成 0 / 跳过 671 / 失败 0`；拔线一轮 `完成 14 / 跳过 180 / 失败 477`。缺：PB 取消卡住时的重试与进度反馈、拔线后 `Details` 是否固定列 `Failed:` 明细、低空间预检拒绝与提示、worker 与 `onDestroy` 的 join。 | `adb shell am start -n com.rea.mtpimport/.Main` → 等扫描完 → 点 `Import all N` → `adb exec-out run-as com.rea.mtpimport cat files/imported/import-journal.txt` |
| P1 | #20 | 已导入索引与新片计数 | 未通过 | 写方已按裁决 B 停写：`ImportService` 轮末不再重建索引，`ImportedIndex` 与 `pureJdkClasses` 清单项已删除。读方 / 首屏「新片」计数未落地，issue 保持 OPEN；恢复时读方与写方同 PR 交付，并由界面真正用上。设备上残留的 `imported-index.txt` 属停写前普通文件，清理与跳过都不受影响。 | 无需真机复验；恢复时按「读方 + 写方同 PR」的验收口径复验首屏「新片」计数 |
| P1 | #40 | Compose 界面骨架与 demo 入口 | 未验证 | 已验：`CATALOG_READY` 一态（顶栏 512、组头 `Jun 7 · 43 · 74.0 MB`、CTA `Import all 512`）。缺：三屏 + 10 态截图、`U-44` / `U-45` / `U-47` / `U-51` / `U-52` / `U-53` / `U-54` 逐条对照，以及真实 MTP 的取消 / 竞态 / 120 s 权限窗口 / 挂起重连。 | `adb shell am start -n com.rea.mtpimport/.Main --es demo_state CATALOG_READY` → `adb exec-out uiautomator dump /dev/tty`；再逐态截图 |
| P1 | #52 | `.part` 残留堆积 | 前提不满足 | 原前提「格式化卡」未执行，改用植入 3 个 `.part`：下一轮日志「已清理无主半成品：3 个」，`.part` 清零。缺：`ScanResult.complete == false` 时「不清理并留计数」分支。原「`relPath + size` 合法 `.part` 保留」判据已随 #177 废止（`PartResidue.keepNames()` 恒返回空集），不再是待验点。 | 导入中拔线 → `adb exec-out run-as com.rea.mtpimport ls -l files/imported`；另造 `scan.complete == false` 看是否保留计数 |
| P1 | #60 | 冷启动 57 s 才出照片墙 | 未验证 | 已验：冷启动 `TotalTime 677 ms`，3 s 内见首格与主 CTA，全量扫描 75 s。缺：滚到未加载组是否出格 / 滚出是否取消加载、取消 / 拔线 / USB 挂起三条路径不崩、全量补齐后的计数与容量一致性。 | `adb logcat -c` → `adb shell am force-stop com.rea.mtpimport` → `adb shell am start -n com.rea.mtpimport/.Main` → 等 70 s → `adb logcat -d -s MTPIMPORT:*` |
| P1 | #63 | 传输中挂起把 `Import` 变死键 | 未复现 | 传输中拔线后 `importFile` 立即返回 false，477 个失败在 14 ms 内跑完，整轮 `完成 14 / 跳过 180 / 失败 477`；全量日志「挂起 / 失步 / 探活」零命中，**挂起判定未被调用**。缺：「未点取消即挂起」的可复现构造，不能算症状消失。 | 传输中拔线或让相机休眠 → 点 `Import` → 看是否无响应 |
| P1 | #205 | 构建常量收口 | 未验证 | 已验：`build.gradle.kts` 单点定义 `compileSdk = 35` / `minSdk = 31` / `targetSdk = 35`。缺：「没有全局 python 的机器上普通提交被拒」与「正常机器不受影响」两项；`build.sh` 与 `build.gradle.kts` 的 SDK 路径 / 签名口令跨文件收口仍未做。 | `git grep -n -E "compileSdk|targetSdk|minSdk" -- tools/android-mtp-import`；在无全局 python 的机器上提交一次 |
| P2 | #28 | 落点决策落地 | 未通过 | 已验：默认私有落点与 `relPath` 镜像布局在 E2 成立（`Location details` 指向 `files/imported`）。缺：「用户可选落点」——UI 没有选择器，`Main` 恒传 `Kind.PRIVATE`；相册权限 / `.ARW` MIME / 跨 provider 复制按 #28 自述本期不接。 | 真机导入后 `adb exec-out run-as com.rea.mtpimport ls -l files/imported`；看 `Location details` |
| P2 | #54 | 多存储同名 `relPath` | 环境不支持 | E2 只有一块内部存储，无法构造双存储同名争路径；单存储回归与异尺寸拒绝覆盖已在 2026-11-07 通过。 | 机身与卡放同名目录 / 同名文件 → 导入 → `adb exec-out run-as com.rea.mtpimport ls -l files/imported`（本环境不可执行） |
| P2 | #96 | 纯 JPG 首屏渐进发布 | 环境不支持 | 静态成立（`Catalog.photoFiles` 按 `isRaw() \|\| isJpeg()` 计数，`hasPhotoFile()` 以此为门槛）；没有纯 JPG 卡环境，运行时行为未验。 | 相机只留 JPG → `adb shell am start -n com.rea.mtpimport/.Main` → 看照片墙是否在扫描进度走完前出现（本环境不可执行） |
| P2 | #127 | `Skipped` 文案与早退 | 未验证 | 代码级成立（`ImportRoundListener` 复用 `UiText.skippedLine` 且 `skipped == 0` 时不打；`ImportEntry.begin` 先 `resetSkipped()`）。缺：真机「先跑出 `Skipped N existing` → 失步进 `ERROR` → 打开 `Details` 确认不残留」。 | 真机重复导入到 `ERROR` → 打开 `Details` → `adb exec-out uiautomator dump /dev/tty` |

**统计（2026-11-08 快照）**：已关闭 14 行，全部为 `通过`，其中只有静态证据的 5 行（#214 / #215 / #116 / #217 / #221）、
含 `V-26` 降级的 1 行（#44）。仍 open 11 行：`未验证` 5（#50 / #40 / #60 / #205 / #127）、`未通过` 2（#20 / #28）、
`环境不支持` 2（#54 / #96）、`前提不满足` 1（#52）、`未复现` 1（#63）。
`needs-live-verify` 的 open issue 以 `gh issue list --state open --label needs-live-verify` 为准；
关闭的 issue 仍可能带这个标签，状态判断以 `state` 为准。

**怎么用这张表**：open 行按「缺哪个验收点」补测；每验完一条，先把能力级结论按「怎么读」回填到上面三张能力表并贴环境编号，
再改本行状态与依据。状态从 `通过` 改回其他值、或从其他值改成 `通过`，都要带一条新的 issue 评论作指针。

### 附：离线审计回归（不带 `needs-live-verify`，不占真机额度）

2026-11-07 已全部离线回签并摘标签；其中三条已关闭，两条仍 open 且写明剩余项。

| 优先级 | issue | 待验点 | 状态 | 关闭情况 / 剩余项 |
|---|---|---|---|---|
| P1 | #204 | 门禁共享层 | 通过 | 仍 open：`_common.py` 已落地、`GATE_TRIGGERS` 单点、坏 JSON 退 2、`ci` 全绿；正文修复候选 2 未全覆盖（`check_sensitive.py` / `check_gates_manifest.py` / `run_gates.py` 未迁移、`review_prompt.py` 不在 `GATE_TRIGGERS`），构建链常量归代码线。 |
| P2 | #207 | 单文件过载 | 通过 | 仍 open：三份长文都有读者地图、链接全可达；剩余项是 `build.gradle.kts` 的 EXIF / 假数据 / 纯 JDK 计数拆分，留给代码侧。 |
| P2 | #224 | opcode / RESP 表 | 通过 | 已关闭 2026-11-08：`0x101B` / `0x100B` / `0xA003` 与帧类型 1..14 均有用例。 |
| P2 | #225 | 离线解析门槛与 GUID | 通过 | 已关闭 2026-11-08：三条 CLI 复现从抛栈改为可读提示 + 退出 2，17 项离线用例全过。 |
| P2 | #226 | 死字段与 `pe_inspect` | 通过 | 已关闭 2026-11-08：`permAnswered` / `BULK` 零命中，PE 4 项用例全过，两条构建链成功。 |

## 与其他文档的关系

- 门禁的档位与钩子：[测试](testing.md)。
- 结论与边界（不展开实测过程）：[已知限制](known-limitations.md)。
- 原始过程与证据：[主报告](research/a7c-usb-raw-2026-11.md)、
  [PTP/IP 会话分析](research/a7c-ptpip-session.md)。
