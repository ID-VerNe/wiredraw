# android-mtp-import —— 导片工具（架构骨架）

把 `tools/android-mtp-probe/`（一次性验证工具）里已经跑通的 MTP 结论，
沉淀成一个**能日常用**的导片工具的骨架。

> **PA（issue #50）起，整文件传输是真的；PB 补齐暂停 / 取消 / 恢复；续传已停用（#177）。**
> `Import all N` / `Import N` 会用 `MtpDevice.importFile()` 把对象读到
> `getFilesDir()/imported/<relPath>`，逐文件核对字节数并推进进度。
> `Pause` 落在文件边界（在传的那个文件会传完），`Cancel` 会关闭正在写的 fd，尝试中断
> 当前 `importFile`：本次机型实测约 0.6 s 返回；能否打断没有跨机型保证，打不断时由
> `isCancelStuck()` 给界面出路。
>
> **续传已停用（止血，issue #177）**：非零 `offset` 只有在恰好等于相机内部游标时才被采纳；
> 对不上时忽略 offset、从对象头返回。续传起点与游标对不上，把遗留 `.part` 当可信前缀续写会得到
> 「字节数对、内容错、却报 OK」的文件；满尺寸残留直接 rename 更是把残渣当成品。
> 所以现在任何既有 `.part` 一律先删除、从 0 整段重写，成功文案只承诺「尺寸核对通过，
> 内容未校验」。恢复续传前必须先解决 offset 语义或改用可校验的分段方案。
>
> PD（#50）补齐：**单文件失败同步重试 1 次（有上限，不做整轮自动重跑）**、逐文件失败固定
> 展示在 `Location details`、`P-09` 导入前空间预检（不足时禁用主 CTA + 一行 `Low space`），
> 以及 `onDestroy` 先有界 join worker 再关闭会话。见「错误与重试 / 空间预检 / onDestroy（PD）」。

> 文中 `#NN` 形式的编号指本仓库 issue，便于对照需求来源。

「该读哪一节」（本文混写**现状、架构、操作与待办**，按目的取用）：

| 你要做什么 | 该读 |
|---|---|
| 先看「现在能用到什么程度」 | 「它现在能做什么」的能力表 |
| 理解代码分层与类职责 | 「类职责」（**非穷举**，纯 JDK / `import android.*` 的边界以 `gradle/self-test.gradle.kts` 的 `pureJdkClasses` 为准） |
| 构建、自检、装到手机 | 「构建与自检」→「安装运行」 |
| 验收前查某条能力验没验过 | [验证环境矩阵 §四](../../docs/verification-matrix.md)（本文件只留复现命令） |
| 想知道本轮明确不做什么 | 「待建清单」 |
| 查界面规则本身 | [UI-SPEC.md](UI-SPEC.md)（界面约束与验收断言的唯一归属） |

## 它现在能做什么

| 能力 | 状态 |
|---|---|
| 找 Sony 机身、申请/自动获取 USB 权限 | ✅ 实现（从探针抽出，坑原样保留） |
| 建 MTP 会话、带唤醒重试拿存储 | ✅ 实现（8 次 / 3 秒，同探针） |
| 全量扫描卡内对象 → 目录树（`Catalog`） | ✅ 实现（不用 format 过滤，见下） |
| 渐进加载：首批 ARW 即出照片墙，其余后台补齐（#60） | ✅ 实现（`Catalog.snapshot()` 每 200 个对象发布一次；顶栏 2 dp 细线 + 临时 `Cancel`，主 CTA 在补齐期间禁用；真机时延**未验**） |
| 照片墙缩略图（#59） | ✅ 实现（IO 线程 `getThumbnail`：先 ARW 句柄、再同名同目录 JPG；512 px 长边解码，24 MiB LruCache 兜底，格子移出组合即 `evict`（离屏位图不常驻）；`getThumbnail` 对 ARW 的支持**真机未验**，拿不到就退回 V-19 灰块） |
| 看图页预览（#61） | ✅ 实现（`PhotoScreen` 异步走 `ThumbnailStore.loadPreview`，同一只读链路；加载/失败保持 U-51 胶囊；8 MiB 独立预览缓存，连续打开同一张不重复读相机；真实路径与网格共用 512 px 长边解码上限（`MAX_EDGE`），故 ≤512 px；demo 分支不降采样（素材最长边 ≤1440）；`getThumbnail` 源分辨率仍**真机未验**） |
| 目录树打勾、勾目录连带子孙、全选/清空 | ⛔ 已被照片墙取代（选择模型仍在 `Selection`） |
| 三屏（`library` / `transfer` / `photo`）+ 十个状态全部有界面 | ✅ 实现（Compose，`src/com/rea/mtpimport/ui/`） |
| 照片墙：ARW 按日期分组、点选、过滤抽屉 | ✅ 实现（`PhotoWall` + `UiText` 纯逻辑，JVM 自检覆盖） |
| 日期组一键全选（#62） | ✅ 实现（组头整行长按 ≥500 ms：组内未全选→全选、已全选→全不选；折叠组自动展开；复用 `V-11` 与 `Import N`，无新增常驻控件；自检 8 项） |
| 假数据模式（`--es demo_state <状态>`，release 编不进去） | ✅ 实现（`src/debug`，见下） |
| 勾选 → 下载清单 + 总量（`ImportPlan`） | ✅ 实现（空勾选等价于全选：只收照片墙的 ARW，同名 JPG / 顶层非 ARW 不进计划，issue #80） |
| 进度数学：百分比 / 速度 / ETA | ✅ 实现（纯逻辑，JVM 自检覆盖） |
| 导入完成日志读写（按 `relPath` + 大小判定完成） | ✅ 实现（纯逻辑，JVM 自检覆盖；#177 起不再记录「已落盘字节」） |
| 线程 / 逐文件回调 | ✅ 实现 |
| **暂停（文件边界）/ 取消（关 fd 尝试中断 `importFile`）/ 恢复本轮** | ✅ 实现（PB / issue #50；2026-11-06 真机已验：`Pause`→`Resume`、`Cancel` 约 0.6 s 返回并重传；打断没有跨机型保证。issue #57 补：恢复前按 `relPath + size` 比较暂停轮次的清单，计划变了就取消旧轮次、按新计划重开——真机未验） |
| **取消后 / 暂停恢复前复用会话的只读探活（`getObjectInfo`）+ 取消卡死的 ERROR 出路** | ✅ 实现（二审 B-1 / 三审 B-1：取消后与 `Pause & exit` 恢复前都探活；卡死轮次放弃后拦住扫描/新导入且不覆写 journal。探活路径 2026-11-06 真机已验，卡死路径由 SelfTest 覆盖、未触发） |
| **真正搬字节：`importFile` 整文件传输 + 字节数对账** | ✅ 实现（PA / issue #50，`MtpTransfer`） |
| **断点续传（`getPartialObject` offset 续读）** | ⛔ 已停用（#177 止血：非零 offset 只在恰好等于相机游标时才被采纳，续读不省带宽；遗留 `.part` 一律作废、从 0 重传） |
| **失败重试（单文件同步 1 次，有上限）** | ✅ 实现（PD / issue #50；`MAX_FILE_ATTEMPTS = 2`，不做整轮自动重跑） |
| **逐文件失败上报到 UI** | ✅ 实现（PD；固定进 `Location details` 失败列表，DONE 首屏受 U-47 约束不出现 `Failed N`） |
| **`P-09` 导入前空间预检** | ✅ 实现（PD；计划 + 5% 缓冲不足时禁用主 CTA + 一行 `Low space`，差额进 Details；真机未验） |
| **`onDestroy` 先 join worker 再关会话** | ✅ 实现（PD；1 s 有界等待，worker 未退出则跳过 `close()`；真机未验） |
| **已导入索引（`imported-index.txt`）** | ⛔ 已停写（#20 裁决 B；曾实现为 `outDir` 磁盘镜像派生、每轮原子重写，但全仓无读方，`ImportedIndex` 与写方已删除；恢复需读方与写方同 PR 交付） |
| **多存储同名路径冲突（#54）** | ✅ 实现（`storageId` 只做判别、**不进 relPath**；同 relPath 跨存储的项显式失败、不调 transfer、不写 Journal；落盘拒绝异尺寸覆盖；**两个同名文件仍争同一路径，本期不支持同时导入**；多存储卷实际行为真机未验） |
| **落点策略（#28）** | ✅ 策略层（默认 `getFilesDir()/imported`；布局镜像 `relPath`，不重排；相册请求**显式回退**并带原因；**相册写入未实现**，权限/MIME 真机未验） |

## 类职责

> **非穷举**，只给职责视角；`[android]` / `[纯 JDK]` 标签以 `gradle/self-test.gradle.kts` 的
> `pureJdkClasses` 与 `checkPureJdkList` 为准，新类漏标时以构建门禁的红为准，不以本树为准。

```
Main                 Activity：USB 权限/attach → 扫描 → 照片墙选图 → 传输 → 进度/暂停/取消
├─ MtpSession        USB 连接 + MtpDevice 生命周期 + 唤醒重试          [android]
├─ CatalogScanner    枚举 MTP 对象 → Catalog（只读）                    [android]
├─ Catalog           只读目录树（Entry + childrenOf/descendantsOf）     [纯 JDK]
├─ Selection         勾选集合；勾目录 = 把子孙写进集合                   [纯 JDK]
├─ ImportPlan        勾选 → 去重清单 + totalBytes()                     [纯 JDK]
├─ TransferProgress  已传/总量 → 分数 / 速度 / ETA（时钟注入）          [纯 JDK]
├─ Journal           导入完成日志：markDone / isComplete / partPath / 存取（#177 删 resumeFrom） [纯 JDK]
├─ PartResidue       无主 .part 清理：保留集恒空（#177 续传停用），枚举成功才清（#52） [纯 JDK]
├─ DestPolicy        落盘冲突策略：relPath 解析 + 同名异尺寸拒绝覆盖（#54）；落点根（#28）    [纯 JDK]
├─ SpaceCheck        空间预检：计划 + 5% 缓冲、够不够、Details 差额（P-09）    [纯 JDK]
├─ ImportGate        主 CTA 准入判定：后台扫描 + 空间不足两条件（N-1）         [纯 JDK]
├─ ImportService     编排：线程、暂停（文件边界）、取消、重试、逐文件调 transfer  [纯 JDK]
├─ CancelToken       取消令牌：置位 + 立即执行传输层的中断动作（PB）       [纯 JDK]
├─ ResumableTransfer 传输契约（整文件 / 取消；`resumeFrom` 恒为 0，#177）   [纯 JDK]
├─ MtpTransfer       真实传输：importFile 从 0 整段写（#177 不再发 getPartialObject）
│                    → 核对字节 → rename 到 relPath；取消删本次半成品（PB） [android]
├─ ThumbnailStore    缩略图/看图页预览：getThumbnail → 同名 JPG；网格 24 MiB + 预览 8 MiB [android]
├─ ImportListener    回调接口（全 default）                              [纯 JDK]
├─ AttachGate        插线事件准入判定：去重，同一次插拔不得开两个会话     [纯 JDK]
├─ PermGate          USB 权限等待门：每次申请重建、只认本次序号（#11）     [纯 JDK]
├─ ScanResult        一次扫描的结果：目录 + 完整性标志 + 丢弃计数（#52）     [纯 JDK]
├─ ScanRun           一轮扫描的取消令牌：取消后迟到回调不得改界面           [纯 JDK]
├─ StorageRetry      getStorageIds 带唤醒重试：每轮判取消，最多多睡 1 个 3 秒 [纯 JDK]
├─ Log               极小日志接口，免得纯逻辑类去 import android.util.Log  [纯 JDK]
├─ BackNav           系统返回键路由：浮层 → 照片页 → 传输/终态 → 启动态（#48）  [纯 JDK]
├─ UiState           10 态 → 界面映射、短名解析（§1.2）                    [纯 JDK]
├─ FinishOutcome     整轮结束去向：全失败优先于「视图已关闭」（issue #64）  [纯 JDK]
├─ UiText            用户可见数字与短文案的唯一入口（C-05 / C-08 / U-45）   [纯 JDK]
├─ TopBarSpec        顶栏常驻 + 计数胶囊条件（仅 CATALOG_READY 且计数非零，V-27） [纯 JDK]
├─ ColorTokens       UI-SPEC 颜色 token 数值 + 对比度/胶囊派生（issue #78）  [纯 JDK]
├─ PhotoWall         ARW → 日期组聚合（组内 count / bytes）                 [纯 JDK]
├─ DemoBridge        假数据载荷契约（实现只在 debug 源集）                  [纯 JDK]
├─ DemoRun           demo 传输模拟的 main 侧接口（实现只在 debug 源集，#46）   [纯 JDK]
├─ DemoPayload       一次假数据载荷（状态 + 假树 + 假进度）                 [纯 JDK]
├─ PhotoPreview      demo 素材契约：由 ThumbnailStore 供照片墙与看图页（真实路径不注入） [android]
├─ ui/               Compose 三屏 + 两个抽屉 + 图标 + 主题 + AppModel       [android]
└─ src/debug/        DemoTree（假树）+ DemoTransfer（进度模拟）           [debug 变体 · 纯 JDK]
                     DemoEntry（`--es demo_state` 入口）                  [debug 变体 · Android]
                     DemoImages（真实照片占位）                          [debug 变体 · Android]
```

**Compose 只做 UI 层与状态映射**：`AttachGate` / `Catalog` / `Journal` /
`TransferProgress` / `ImportService` 保持原样；`PermGate` 只调整等待/重发实现，
序号语义不变，`Main` 只负责把系统事件翻译成 `AppModel` 的可观察属性。

纯 JDK 类的清单只有一处：`gradle/self-test.gradle.kts` 的 `pureJdkClasses`（主源集）与
`pureJdkDebugClasses`（`src/debug` 下的 `DemoTree` / `DemoTransfer`）。这些文件**不允许
import android.\***，这样它们能在普通 JVM 上跑 `test/SelfTest.java`。**文档不写死数量**——
以 `checkPureJdkList` 输出末行的 `N 个纯 JDK 文件，与清单一致` 为准。

这条边界是**编译期强制**的：Gradle 的 `compilePureJdkJava` 编译这批文件时**不给 android.jar**
（classpath 故意为空），所以哪个纯逻辑类偷偷 `import android.*` 会直接编译失败，而不是等真机才崩。
`pureJdkClasses` 清单还有第 0 步漂移检查兜底——`checkPureJdkList` 拿「磁盘上不 import android
的文件」与清单比对，漏列一个文件就判红（否则新加的纯逻辑类会悄悄脱离这条保护）。

## 续传已停用（止血 / issue #177）

**结论（2026-11-07 真机实测，小米 9 / Android 15 API 35 / USB MTP）**：**非零 `offset` 只有在
恰好等于相机内部游标时才被采纳；对不上时忽略 offset、从对象头返回。**「游标」是相机侧读流的
位置（相机在本次会话里维护，不是客户端变量；是否按对象独立维护未验证）。续传起点与游标对不上，
把遗留 `.part` 当可信前缀 append，会把「文件从 0 开始那一段」追加到后面：字节数对得上、MD5 错、
日志却打 `[OK]`。顺读永远对、跳读 / 续传一定错，且**续读不省带宽**（要先从 0 读到该位置）。
证据是 R0–R3 判别（`实际内容位置=0`）与同轮 25 次顺序拼接 MD5 与整文件一致的合解
（`main@261942b`，见[探针 README](../android-mtp-probe/README.md) 的 PC 探针节）。
issue #67 在 ILCE-7C 上得到的「offset 是绝对偏移」结论不能作为 App 契约。

当期策略：**不给续传留任何捷径**。

- `ImportService` 的 `resumeFrom` 恒为 0，不再按 `.part` 实际长度推导；每次重试也从 0 重写，
  避免失败轮次写下的残余长度被当成新起点、把损坏放大。
- `MtpTransfer` 只走 `importFile` 整文件路径，**不再发 `getPartialObject`**；
  已删除 `append()` 与 `from == size` 直通（后者等于「尺寸凑够就 rename 当成品」）。
- 任何既有 `.part` 一律视为不可信：`MtpTransfer.whole()` 入口先删除，删不掉直接 FAILED
  （不继续写）；`PartResidue.keepNames()` 保留集恒空，完整枚举后顶层 `.part` 全清
  （#52 的清理机制保留）。
- `.part` 仍按 `<relPath 压平>.<size>.part` 命名（`Journal.partPath`），但它只是临时文件名，
  不再承载「已传前缀」语义。
- 失败 / 断线保留 `.part`（下一轮从 0 重写）；取消删除整个半成品（取消 = 从 0 重来，与 PB 一致）。
- 结束时字节数必须等于相机侧 `ImportPlan.Item.size`，否则 FAILED 且保留 `.part`。
- **完成只做尺寸核对，不做内容校验**：成功文案固定为「尺寸核对通过（内容未校验）」；
  目标已存在同尺寸文件时按 `SKIPPED` 记并注明「仅尺寸核对，内容未校验」，不再记 `OK`。
  Journal 说完成也不再单独作数——`ImportService` 必须再确认成品存在且长度吻合才跳过，
  否则记一行日志并重新导入。
- 无主 `.part` 清理（#52）的开关仍是「本次枚举是否完整成功」（`ScanResult.complete`），
  不是「清单是否为空」：枚举失败 / 取消时一律不清；清理前双闸确认没有活动轮次；
  删除失败只记日志、不阻断本轮。

## 已导入索引（issue #20，裁决 B：已停写）

- **现状**：`imported-index.txt` 曾由 `ImportedIndex` 每轮从磁盘镜像重建，但全仓**没有读方**
  （UI 首屏「新片」计数、网格已导入标记都尚未落地）。按 #20 裁决 B **先停写**：
  `ImportService` 的轮末不再重建索引，`ImportedIndex` 类与 `pureJdkClasses` 清单项一并删除。
- **恢复条件**：读方与写方必须**同一个 PR 交付**，并由界面真正用上；没有读方就不写。
  恢复时按当时的 Journal / 落盘口径重新设计，不要照抄历史实现。
- **位置与残留**：索引与 Journal 同目录（`<filesDir>/imported/`）。停写前设备上可能残留
  `imported-index.txt`，历史原子重写也可能留下 `.tmp`。三者互不派生——Journal 负责跳过判定、
  索引只是历史镜像、`.tmp` 是残留临时文件；半成品清理只动 `*.part`，以上都不自动删除，
  用户可自行清理。
- **历史格式（仅供排查存量文件）**：行式 UTF-8，头 `# wiredraw-import index v1`，每行
  `size \t relPath`，按 `relPath` 排序；只收普通文件且 `size > 0`，排除 `import-journal.txt`、
  自身与 `.tmp`、所有 `*.part`。
- **已知边界（仍然成立）**：同路径同尺寸但内容变了（换卡后编号复用、相机内替换、格式化后
  重拍同名文件）分辨不出来，会被当成已导入；跳过判定由 Journal 负责，与索引是否存在无关。

## 多存储同名路径（issue #54）

`CatalogScanner` 会把所有 storage 的条目合进一个 `Catalog`，而 `relPath` 只沿 parent 链算，
没有 storage 维度。于是同时插两张卡、或卡内目录重名时，两个不同文件会有**同一个 `relPath`**。

本期策略（B 方案：`storageId` 只做判别，**不进 relPath**）：

- `Catalog.Entry` 记下 `storageId`；`Catalog.storageConflicts()` 把「同 relPath、不同 storage」的文件暴露出来。
- `ImportPlan` 把这些项标成 `conflict`；`ImportService` 在 Journal 判定**之前**直接计入失败并写日志，
  **不调 transfer、不写 Journal**——否则同尺寸的第二项会被当成「已完成」静默跳过，用户看到的是「一切顺利」。
- `DestPolicy` 在落盘前判目标：不存在 → 正常写；同名同尺寸 → 不覆盖、按 `SKIPPED` 记
  （仅尺寸核对，内容未校验 #177，不再记 `OK`）；**同名异尺寸 → 失败并保留旧文件**，不删除。

**边界**：本方案**不修「两个同名文件仍争同一落盘路径」**——本期不支持把两份同名文件同时导入；
它保证的是**不覆盖已存在的文件、不把冲突项记成完成**。同路径同尺寸但内容不同，仍然分辨不出来
（与 #20 的已知边界同源），这一点没有解决。真要同时导入，得把 storage 维度写进 relPath，
那需要先有真机证据证明 storageId 跨会话稳定，并配套历史 Journal / 磁盘迁移，留后续单。

**真机未验**：ILCE-7C 单存储时不会触发；多存储卷的实际 `storageId` 行为（同卡拔插是否同值、
两张卡各自的值、handle 是否设备级唯一）仍是假设，合并后按 `needs-live-verify` 验。

## 错误与重试 / 空间预检 / onDestroy（PD / issue #50）

- **单文件失败重试**：`ImportService.MAX_FILE_ATTEMPTS = 2`，即首次 + 1 次同步重试；
  每次尝试都从 0 重写（#177：不再按 `.part` 实际长度重算起点，避免把损坏放大）。
  重试耗尽后 `failed++`，note 带「已尝试 N 次」；**不做整轮自动重跑**，用户再点 Import 时
  Journal 跳过已完成（且成品必须在磁盘上）、失败的 `.part` 从 0 重传。
  取消优先：已取消就不再重试，也不把用户取消计入失败。
- **逐文件失败上报**：每次 FAILED 都进 `AppModel.failedLines`（最多 20 条），固定展示在
  `Location details` 抽屉，不会被 `logLines.takeLast(8)` 挤掉；DONE 首屏受 U-47 约束
  **不出现** `Failed N`。所有文件都失败仍走现有 `ERROR` 路径。
- **无进展挂起（#63）**：`ImportService.isStalled()` 在「worker 仍活着、**没取消、没暂停**、
  且距最后一次进入一次文件传输（整文件 `importFile`）已超过 60 s」
  （`NO_ACTIVITY_GRACE_NANOS`）时成立。
  `Main.abandonStuckService()` 把它与「取消卡住」共用同一条放弃出路，但返回成因、屏幕文案分开：
  挂起落 `ERROR`「相机长时间无响应，请拔插数据线」，取消卡住仍是「相机连接可能已失步，
  请拔插数据线」（issue #116）。于是相机休眠 / USB 掉线导致的挂起轮次不再让
  `CATALOG_READY` 下的 `Import` 变成死键（该态按 UI-SPEC §1.3 没有 Cancel 入口）。
  只按文件边界计时；`ResumableTransfer` 虽已有 #70 的块级进度回调，但它不参与挂起判定；
  暂停中不算挂起（`Pause & exit` 后可以停任意久）。真机**未验**。
- **`P-09` 空间预检**：`SpaceCheck.requiredBytes = 计划 + ceil(5%)`；`Main` 用
  `StatFs(getFilesDir())` 取可用字节。照片墙出现、勾选变化、回到照片墙（DONE / ERROR /
  取消返回）时各刷新一次，`doStart` 启动前再判一次；不足时主 CTA 禁用、上方一行
  `Low space`（12 sp），差额（`Need X GB, free Y GB`）只进 Details。`StatFs` 取数异常按
  「不拦截」处理，预检不能反过来阻断导入。
  计划为空（没勾任何照片）时不拦：点击本来也不会启动导入。
- **`onDestroy` 竞态**：先 `cancel()` 传输与扫描，再在 1 s 总预算内 join
  `service` / `orphanService` / `scanThread`；全部退出才 `session.close()`，仍有 worker 活着
  则跳过关闭并记一行日志（不在 UI 线程撞原生传输）。跳过 `close()` 时会话保持打开：若进程
  随后被杀，相机可能按 `AGENTS.md` §4 挂起、需要拔插数据线。以上仍属**真机未验**。

## 文件内进度（issue #70）

`ResumableTransfer` 新增 `LongConsumer progress` 参数；#177 续传停用后只剩整文件
`importFile` 路径，它没有字节级回调，仍在文件边界一次推进（块级回调接口保留，当前实现不调用）。
`TransferProgress` 把「已完成文件字节」与「当前文件字节」分开记：

- `beginFile(alreadyOnDisk, size)` 进入一次尝试时**重设**当前文件（不是累加）——
  同一文件失败后重试会再调一次，累加会把磁盘上同一段字节记两遍（重试后数字跳到 80 而不是 40）；
- `commitFile(size)` 成功时按对账大小只记一次已完成字节，块级上报在此清零；
- `rollbackFile()` 失败 / 取消时丢掉当前文件的进度，已完成文件不动。

`bytesDone()` 是两者之和并钳在计划总量，只供日志、`DONE` 结算与自检使用。**字节数与速率不上屏**
（issue #70）：#177 已证 `getPartialObject` 的非零 offset 只在恰好等于相机游标时才被采纳
（对不上时从对象头返回），「已落盘字节」不可信，
任何字节级进度显示都会撒谎。界面进度环只按「已完成文件数 / 总数」——环内百分比与环下 `a/b` 同源
（`UI-SPEC.md` `U-45` / `P-03`）；`AppModel` 不保留字节状态，`updateProgress` / `showImport`
只收文件计数。失败回退让计数**往回走**属预期行为（失败的文件没有被传完），不是进度抖动。真机**未验**。

## 构建与自检

构建体系：Gradle `8.9` + AGP `8.7.3` + Kotlin `2.0.21` + Compose 编译器插件 `2.0.21`
（Compose 运行库 BOM `2024.12.01`）。工具链由 `gradle/wrapper/` 锁定，**不要**用本机全局
安装的 Gradle。首次构建要联网下载 Gradle 依赖；本机代理链路会间歇断开，失败时原样重跑即可。

一条命令验完（JVM 自检 + APK 构建）：

```bash
# Linux / macOS / Git Bash
bash tools/android-mtp-import/gradlew -p tools/android-mtp-import selfTest assembleDebug
```

Windows PowerShell 用 `.bat` 包装脚本（WSL 里没有 Windows 的 `JAVA_HOME` 与 SDK，
`gradlew` 会报 `JAVA_HOME is not set`）：

```powershell
.\tools\android-mtp-import\gradlew.bat -p tools/android-mtp-import selfTest assembleDebug
```

只跑纯逻辑自检（仍需能定位 Android SDK —— 配置阶段即校验）。项数随用例增加，
以输出末行 `== 自检全绿：N 项 ==` 为准（文档不写死项数，免得随用例增加而漂移）：

```bash
# Linux / macOS / Git Bash
bash tools/android-mtp-import/gradlew -p tools/android-mtp-import selfTest
# Windows PowerShell
.\tools\android-mtp-import\gradlew.bat -p tools/android-mtp-import selfTest
```

单独构建 APK：

```bash
# Linux / macOS / Git Bash
bash tools/android-mtp-import/gradlew -p tools/android-mtp-import assembleDebug
# 产物 tools/android-mtp-import/build/outputs/apk/debug/mtp-import-debug.apk
# Windows PowerShell
.\tools\android-mtp-import\gradlew.bat -p tools/android-mtp-import assembleDebug
```

环境要求：JDK 17+（本机用 Temurin 21）、Android SDK（platform 35 + build-tools 35）。
SDK 定位顺序是 `local.properties` 的 `sdk.dir` → `ANDROID_HOME` / `ANDROID_SDK_ROOT` →
`%LOCALAPPDATA%\Android\Sdk`；三者都没有时 `settings.gradle.kts` 会写一份被 gitignore 的
`local.properties`，仍找不到就报错退出。

签名复用 `tools/android-mtp-probe/rea-debug.keystore`（同一把 key，包名不同不冲突；
换 key 会导致覆盖安装 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。

## 安装运行

```bash
adb install -g tools/android-mtp-import/build/outputs/apk/debug/mtp-import-debug.apk
adb shell am start -n com.rea.mtpimport/.Main
adb logcat -s MTPIMPORT:*
```

相机侧：`MENU → 设置 → USB连接 → MTP`（不是 PC 遥控）。插线自动拉起与自动授权
都是**有条件的**：拉起依赖本包是该设备的默认处理者（`adb shell dumpsys usb` 的
`device_preferences` 指向 `com.rea.mtpimport`）并已选「始终打开」；授权只在本包
**已有该设备授权记录**时静默通过，首次或无授权记录时系统仍会弹「是否允许访问 MTP」，
必须点「允许」（manifest 挂了 `USB_DEVICE_ATTACHED` + `res/xml/device_filter.xml`）。

导入日志落在 `getFilesDir()/imported/import-journal.txt`，
可以直接看：

```bash
adb exec-out run-as com.rea.mtpimport cat files/imported/import-journal.txt
```

## 落点策略（issue #28）

- **默认**：`getFilesDir()/imported`，不弹任何权限请求；系统「清理缓存」不会带走，「清除数据 / 卸载」会。
- **布局**：镜像相机 `relPath`，即 `<root>/<relPath>`，**不额外加日期层、不加机型层**。日期分组由相机目录自带（卡内是 `2025-06-07/` 这类目录时天然按日期落）；`relPath` 同时是 Journal 主键，落点路径与识别键保持一致，不出现「识别对了、路径变了」的双份账。
- **回退可见**：请求的落点不可用时回退私有根，`DestPolicy.Destination.fallbackReason` 必须非空，`Main` 在 `onCreate` 写日志；不得只留在调试输出。
- **相册未接入**：MediaStore 权限 / `.ARW` 的 MIME / `RELATIVE_PATH` 均未真机验证；且 `.part` 在私有目录、成品在 MediaStore 属跨 provider，`File.renameTo` 不可用，要改成流式复制。所以请求相册一律回退，不写「看似可用」的代码。接入前必须先拆「状态目录（journal / `.part`）」与「成品根」，否则换根会丢导入记录。
- **存量兼容**：默认根不变，老文件路径与 `relPath + size` 键都不变，不会因为落点策略把老文件当冲突或重传。

## 假数据模式（不插相机也能走完 10 个状态）

只在 **debug** APK 里有（`src/debug` 源集，release 编不进去，U-54）。状态名见 `UiState.State`，
也认任务书短名 `PERMISSION` / `READY` / `EMPTY`：

```bash
adb shell am start -n com.rea.mtpimport/.Main --es demo_state IMPORTING
```

假树与真机对象总数同为 1526，但构成不同：真机 503 文件夹 + 511 ARW + 511 JPEG + 1 MP4；
假树 503 文件夹 + 511 ARW + 512 JPEG（无 MP4）。假树体积 12.75 GB（ARW 11.89 GB）；
`IMPORTING` / `PAUSED` 冻结在 `12/36`（33%），`DONE` 是 `36 · 858 MB`。
P-03 只规定速率与 ETA **允许**出现在 `Location details` 抽屉里、不得进主视觉；本轮没有调用点
（`UiText.rate` / `UiText.eta` 只被自检覆盖，界面与 `Main` 都不调），所以主视觉与抽屉里都不出现。
可达范围：`am start --es demo_state <状态>` 能直达全部 10 态；应用内从照片墙的 `Details` 抽屉
可以切到任一态，但 `COLD` / `EMPTY_CATALOG` 切过去后假树被清掉、没有回照片墙的路，
这两种只能重新 `am start`，不要当成「抽屉里随便来回切」；`DONE` 及其它非主页终态下
返回键回照片墙（issue #48）。
另：`CATALOG_READY` 点 `Import all` 现在会真实进入 `IMPORTING` 并每 250 ms 推进一张，
约 9 秒落到 `DONE`；`Pause` / `Resume` / `Cancel` 都能点按到达（issue #46）。
`--es demo_state IMPORTING/PAUSED` 仍是冻结的 `12/36`，那是冷启动直达，不是模拟进度。

### demo 照片占位（issue #46）

`photo` 页在 demo 模式下渲染真实照片：素材由 `generateDemoPhotos` 在 debug 构建时从
`local.properties` 的 `demo.photo.dir` / `demo.photo.files`（也可用 `-Pdemo.photo.dir` 覆盖）
读取，按最长边 ≤1440、JPEG 质量 80 降采样到 `src/debug/assets/demo/photo_XX.jpg`，
EXIF Orientation 会归一化。**个人照片不入库，产物 gitignore**；目录缺失 / 未配置时任务打印
`skip` 并继续，APK 里没有素材，`photo` 页退回 `ARW · <size>` 胶囊。ARW 不进 APK，
假树仍保留 ARW 条目与真实大小。真实 MTP 路径不走这条链路：自 #59 起**照片墙**改由相机
`getThumbnail` 取图（demo 模式下 `ThumbnailStore` 直接用上面的 demo 素材，照片墙格子也能
看到图）；`photo` 页与照片墙共用 `ThumbnailStore`：真实路径走 `getThumbnail`（#61），
demo 走同一条 `loadPreview` 的 demo 分支取整图。

## 沿用探针的三条实测结论

1. **format 过滤不可信**：`getObjectHandles(sid, 0xB101, 0)` 返回 754 个而全量只有
   511 个真 ARW（探针 README 坑 2）。`CatalogScanner` 一律全量枚举 + 逐个
   `getObjectInfo()` 自己分类，不做这个「优化」。
2. **相机会挂起 USB**（现象、重试次数与实测结论以 `AGENTS.md` §4 为准）：`MtpSession.storageIds()`
   做唤醒重试，仍失败就提示用户拔插线。本文件不再复制实测数字。
3. **扫描要 59 秒**：1526 个对象 × 39 ms。所以扫描必须回调进度，且扫描结果要缓存成
   `Catalog` 复用——每次勾选都重扫的交互没法用。issue #60 起不再等全量：首批 ARW
   一出现就发布快照（之后每 200 个对象一次），照片墙先可用，剩余对象在后台静默补齐；
   补齐期间主 CTA 禁用（扫描与导入不能在同一 MTP 会话上并行发命令）。

## 真机验收状态（2026-11-06 快照，已停更）

> **逐条待验状态、证据与复现动作见[验证环境矩阵 §四](../../docs/verification-matrix.md)。**
> 本节曾是逐条打勾的验收台账，2026-11-06 之后停更：相机被另一会话的探针占用
> （`ResumedActivity=com.rea.mtpprobe/.Main`），后续条目没有再动真机。31 条勾选记录已按
> `AGENTS.md` §3「单点快照只留一处」删除，本文件不再维护第二份清单——两处各写一份时，
> 同一能力会同时出现「通过」与「未验」两种相反结论。

停更前的两轮实测留下这些本工具专有数值（仍被 #50 / #177 等回签引用）：

- 单张 ARW 落盘 `25,393,920` 字节、与相机侧 `ImportPlan.Item.size` 一致，文件头 `49 49 2a 00`
- 同一批照片再导一次打 `Skipped 1 existing`，不重复搬字节
- 暂停落在 58%（33/57）时界面切 `Resume` / `Cancel`，恢复后从下一张 `DSC02705` 继续
- `Cancel` 后本次机型约 0.6 s 从 `importFile` 返回（不等整张传完），`.part` 无残留
- `Pause & exit` 后停 90 秒再点 `Import`：只读探活通过，`继续已暂停的一轮`，剩余 2 张继续落盘

本工具特有的复现动作（矩阵 §四 4.2 的「复现动作」列给的是通用入口，下面是导片工具专有命令）：

```bash
# 冷启动 + 扫描（约 70 s 出全量）
adb logcat -c && adb shell am force-stop com.rea.mtpimport && adb shell am start -n com.rea.mtpimport/.Main && adb logcat -d -s MTPIMPORT:*
# 落盘结果与跨会话账面
adb exec-out run-as com.rea.mtpimport ls -l files/imported
adb exec-out run-as com.rea.mtpimport cat files/imported/import-journal.txt
# DONE / ERROR 态的 Location details（逐文件失败、低空间差额）
adb exec-out uiautomator dump /dev/tty
# 拔线重扫（会话重建）
adb shell am force-stop com.rea.mtpimport && adb shell am start -n com.rea.mtpimport/.Main
```

> **放弃卡死轮次的代价（三审 B-2）**：`ImportService.run()` 只在开始时 `loadJournal()` 一次，
> `markDone` 只改内存，`saveJournal()` 在 `finally` 整文件覆写。被放弃的轮次跳过保存，意味着
> **本轮所有已完成文件的记录都会丢**，下次会全部重传（不会产生假完成，但代价比「记录已在
> 旧日志里」大）。
>
> **异常处置（三审建议）**：`orphanService` 的 worker 不退出时，界面唯一出路是 `Connect camera`
> 重扫（会保持 `ERROR`，不会死键）；物理拔插数据线是唯一恢复手段。若拔插后仍不退出，只能杀进程重启。

## 待建清单（本轮不做，后续 issue 排期）

- `DONE` 的 `Open` 只记日志（`Main.onOpen`），不跳系统相册；需另接 `ACTION_VIEW` / 相册 intent。
