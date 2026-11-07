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
> **续传已停用（止血，issue #177）**：真机实测 `getPartialObject` 的 `offset` 不生效，
> reader 忽略偏移、从对象起点顺序给数据。把遗留 `.part` 当可信前缀续写会得到
> 「字节数对、内容错、却报 OK」的文件；满尺寸残留直接 rename 更是把残渣当成品。
> 所以现在任何既有 `.part` 一律先删除、从 0 整段重写，成功文案只承诺「尺寸核对通过，
> 内容未校验」。恢复续传前必须先解决 offset 语义或改用可校验的分段方案。
>
> PD（#50）补齐：**单文件失败同步重试 1 次（有上限，不做整轮自动重跑）**、逐文件失败固定
> 展示在 `Location details`、`P-09` 导入前空间预检（不足时禁用主 CTA + 一行 `Low space`），
> 以及 `onDestroy` 先有界 join worker 再关闭会话。见「错误与重试 / 空间预检 / onDestroy（PD）」。

> 文中 `#NN` 形式的编号指本仓库 issue，便于对照需求来源。

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
| **断点续传（`getPartialObject` offset 续读）** | ⛔ 已停用（#177 止血：真机 offset 不生效；遗留 `.part` 一律作废、从 0 重传） |
| **失败重试（单文件同步 1 次，有上限）** | ✅ 实现（PD / issue #50；`MAX_FILE_ATTEMPTS = 2`，不做整轮自动重跑） |
| **逐文件失败上报到 UI** | ✅ 实现（PD；固定进 `Location details` 失败列表，DONE 首屏受 U-47 约束不出现 `Failed N`） |
| **`P-09` 导入前空间预检** | ✅ 实现（PD；计划 + 5% 缓冲不足时禁用主 CTA + 一行 `Low space`，差额进 Details；真机未验） |
| **`onDestroy` 先 join worker 再关会话** | ✅ 实现（PD；1 s 有界等待，worker 未退出则跳过 `close()`；真机未验） |
| **已导入索引（`imported-index.txt`）** | ✅ 实现（#20；`outDir` 磁盘镜像派生，每轮导入结束原子重写；主键 `relPath + size`；**本期无消费方**，供后续 UI 单读「新片」；真机未验） |
| **多存储同名路径冲突（#54）** | ✅ 实现（`storageId` 只做判别、**不进 relPath**；同 relPath 跨存储的项显式失败、不调 transfer、不写 Journal；落盘拒绝异尺寸覆盖；**两个同名文件仍争同一路径，本期不支持同时导入**；多存储卷实际行为真机未验） |
| **落点策略（#28）** | ✅ 策略层（默认 `getFilesDir()/imported`；布局镜像 `relPath`，不重排；相册请求**显式回退**并带原因；**相册写入未实现**，权限/MIME 真机未验） |

## 类职责

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
├─ ImportedIndex     已导入索引：outDir 磁盘镜像派生（relPath + size），每轮重建    [纯 JDK]
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

纯 JDK 类的清单只有一处：`build.gradle.kts` 的 `pureJdkClasses`（主源集）与
`pureJdkDebugClasses`（`src/debug` 下的 `DemoTree` / `DemoTransfer`）。这些文件**不允许
import android.\***，这样它们能在普通 JVM 上跑 `test/SelfTest.java`。**文档不写死数量**——
以 `checkPureJdkList` 输出末行的 `N 个纯 JDK 文件，与清单一致` 为准。

这条边界是**编译期强制**的：Gradle 的 `compilePureJdkJava` 编译这批文件时**不给 android.jar**
（classpath 故意为空），所以哪个纯逻辑类偷偷 `import android.*` 会直接编译失败，而不是等真机才崩。
`pureJdkClasses` 清单还有第 0 步漂移检查兜底——`checkPureJdkList` 拿「磁盘上不 import android
的文件」与清单比对，漏列一个文件就判红（否则新加的纯逻辑类会悄悄脱离这条保护）。

## 续传已停用（止血 / issue #177）

**结论（2026-11-07 真机实测，小米 9 / Android 15 API 35 / USB MTP）**：`getPartialObject` 的
`offset` 不生效——reader 忽略偏移、从对象起点顺序给数据。把遗留 `.part` 当可信前缀 append，
会把「文件从 0 开始那一段」追加到后面：字节数对得上、MD5 错、日志却打 `[OK]`。
issue #67 在 ILCE-7C 上用探针得到的「offset 是绝对偏移」结论因此不能作为 App 契约
（探针侧另有会话在纠正，这里只按已观测到的行为止血）。

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

## 已导入索引（issue #20）

- **位置**：`<filesDir>/imported/imported-index.txt`，与 `import-journal.txt` 同目录。
  `getFilesDir()` 是应用私有**持久**目录，系统「清理缓存」不会带走；「清除数据 / 卸载」会
  （那时 Journal 也一起没了）。
- **格式**：行式 UTF-8 文本，头 `# wiredraw-import index v1`，每行 `size \t relPath`
  （`relPath` 复用 `Journal.sanitize` 去掉 tab / 换行），按 `relPath` 排序。
  不采用 JSON：这里只需要逐行读，多一层转义没有收益。
- **主键 `relPath + size`**，与 Journal 的跨会话跳过、`.part` 命名同口径。
  **已知边界**：同路径同尺寸但内容变了（换卡后编号复用、相机内替换、格式化后重拍同名文件）
  分辨不出来，会被当成已导入。要真正区分内容得把整文件读一遍算哈希，本单不做。
- **派生快照，不是事件账本**：每轮导入结束（`ImportService` 的 `finally`，与 Journal 同受
  `abandoned` 保护）递归扫 `outDir` 重新生成并原子重写（同目录 `.tmp` + `ATOMIC_MOVE`）。
  **不读旧索引**——缺失 / 截断 / 乱码都等价于「还没生成」，重写即恢复；跳过判定仍由 Journal
  负责，索引不参与，所以索引丢失不会触发重传。
- **排除项**：`import-journal.txt`、索引自身与 `.tmp`、所有 `*.part`；只收普通文件且 `size > 0`。
- **消费方**：本期（#20）**没有**。后续 UI 单（首屏「新片」计数、网格已导入标记、过滤抽屉）
  在扫描完成后读它，按 `relPath` 与相机侧条目求交。真机**未验**。

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
那需要先有真机证据证明 storageId 跨会话稳定，并配套历史 Journal / 索引 / 磁盘迁移，留后续单。

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
（issue #70）：#177 已证 `getPartialObject` 的 offset 在真机不生效，「已落盘字节」不可信，
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

导入日志落在 `getFilesDir()/imported/import-journal.txt`（与已导入索引 `imported-index.txt` 同目录），
可以直接看：

```bash
adb exec-out run-as com.rea.mtpimport cat files/imported/import-journal.txt
```

## 落点策略（issue #28）

- **默认**：`getFilesDir()/imported`，不弹任何权限请求；系统「清理缓存」不会带走，「清除数据 / 卸载」会。
- **布局**：镜像相机 `relPath`，即 `<root>/<relPath>`，**不额外加日期层、不加机型层**。日期分组由相机目录自带（卡内是 `2025-06-07/` 这类目录时天然按日期落）；`relPath` 同时是 Journal / 索引主键，落点路径与识别键保持一致，不出现「识别对了、路径变了」的双份账。
- **回退可见**：请求的落点不可用时回退私有根，`DestPolicy.Destination.fallbackReason` 必须非空，`Main` 在 `onCreate` 写日志；不得只留在调试输出。
- **相册未接入**：MediaStore 权限 / `.ARW` 的 MIME / `RELATIVE_PATH` 均未真机验证；且 `.part` 在私有目录、成品在 MediaStore 属跨 provider，`File.renameTo` 不可用，要改成流式复制。所以请求相册一律回退，不写「看似可用」的代码。接入前必须先拆「状态目录（journal / index / `.part`）」与「成品根」，否则换根会丢导入记录。
- **存量兼容**：默认根不变，老文件路径与 `relPath + size` 键都不变，不会因为落点策略把老文件当冲突或重传。

## 假数据模式（不插相机也能走完 10 个状态）

只在 **debug** APK 里有（`src/debug` 源集，release 编不进去，U-54）。状态名见 `UiState.State`，
也认任务书短名 `PERMISSION` / `READY` / `EMPTY`：

```bash
adb shell am start -n com.rea.mtpimport/.Main --es demo_state IMPORTING
```

假树与真机同口径：1526 对象 / 503 文件夹 / 511 ARW / 512 JPEG / 12.75 GB（ARW 11.89 GB）；
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
2. **相机会挂起 USB**：会话结束后约 1 分钟，`getStorageIds()` 返回空、`open()` 卡 10 秒。
   `MtpSession.storageIds()` 重试 8 次 / 每次 3 秒；仍失败就提示用户拔插线
   —— 实测重试唤不醒，只有拔插有效。
3. **扫描要 59 秒**：1526 个对象 × 39 ms。所以扫描必须回调进度，且扫描结果要缓存成
   `Catalog` 复用——每次勾选都重扫的交互没法用。issue #60 起不再等全量：首批 ARW
   一出现就发布快照（之后每 200 个对象一次），照片墙先可用，剩余对象在后台静默补齐；
   补齐期间主 CTA 禁用（扫描与导入不能在同一 MTP 会话上并行发命令）。

## 真机待验清单（验收前必须在 ILCE-7C 上跑）

- [x] 插线后 App 自动拉起、自动拿到 USB 权限（不弹窗）；**首次**要在系统弹窗里勾一次「一律打开」，
      应用侧没有正规接口能绕过这一步（#17）。实测 2026-11-05 18:48：插回后 `permissions_manager`
      只有既有 `uids=10269`，日志无 `尚无 USB 权限，发起申请`
- [x] 插线或冷启动时自动开始扫描，不需要手动点 `Connect camera`；同一次插拔重复投递两次也不得开两个会话（#17）。
      实测 2026-11-05 18:48：广播路径 `→ 开始扫描`，attach intent 路径 `→ 扫描已在进行中，忽略本次重复事件`，
      单次会话扫出 `待扫描对象共 1526 个`、`扫描完成：文件夹 503 个、文件 1023 个（合计 12.75 GB，其中 ARW 11.89 GB）`
- [ ] 扫描完成，照片墙的日期组 / 照片数与卡内一致（探针对照值：1526 对象 / 503 文件夹 / 511 ARW）；
      顶栏计数胶囊 = 511，`Import all 511` 与计数胶囊同分母（U-53）
- [ ] 渐进加载（#60）：插线后**首批 ARW 出现即**看到照片墙，不必等满 1526 个对象；
      顶栏下方 2 dp 细线推进、临时 `Cancel` 可点、主 CTA 禁用；补齐完成后细线与 `Cancel` 消失、
      CTA 启用、计数胶囊从中间值变为 511；点 `Cancel` 必须丢弃部分结果回 `COLD` —— **未验**
      （2026-11-06 相机被另一会话探针占用：`ResumedActivity=com.rea.mtpprobe/.Main`，本轮未动真机）
- [ ] 渐进态取消不得误删上一轮完整结果：扫完一轮 → 拔插重扫 → 在 `SCANNING` 的
      `session.open()` / `storageIds()` 窗口点 `Cancel` → 回照片墙后 `Import all N` 仍能进
      `IMPORTING`（日志不得出现「先扫描」）—— **未验**（同上，相机被占用）
- [ ] 缩略图（#59）：照片墙格子必须显示真实缩略图，不是灰块 + 文件名；格子滚出可视区后
      位图必须被移出缓存（滚回会重新发 `getThumbnail`，属预期，不是重复请求缺陷）；
      内存里最多 24 MiB 位图（LRU 兜底）。
      ARW 句柄 `getThumbnail` 若不支持，日志出现 `ARW 缩略图失败` 但**同名 JPG** 回退成功；
      两者都没有时才退回 V-19 灰块，且**不得**崩溃 —— **未验**（2026-11-06 相机被另一会话
      探针占用：`ResumedActivity=com.rea.mtpprobe/.Main`，本轮未动真机）
- [ ] 看图页（#61）：长按任一格进入 `photo` 页，必须出现该张照片的像素（全幅 `Fit`），
      不是只有黑底 + `ARW · <size>` 胶囊；加载中与失败时才保持胶囊，**不得**出现占位块/假图；
      返回再进同一张不重复读相机（8 MiB 预览缓存）；拔线/会话挂起时退回胶囊且不崩
      —— **未验**（同上，相机被占用）
- [ ] 点格切换已选（`Import all N` ↔ `Import N`；已选 0 时主 CTA 仍可点 = 全部 ARW，见下一条），长按格子进 `photo` 页
      （有像素时全幅 `Fit`，否则单行 `ARW · <size>` 胶囊）；组头可折叠，过滤抽屉只改可见集、
      不改计数胶囊与 `Import all N` 分母
- [ ] 空勾选点主 CTA（#80）：照片墙刚出现、一格都不勾时点 `Import all 511`，必须进入 `IMPORTING`，
      日志出现 `计划：511 个文件 / 12766800000 字节`（不得再出现「没有勾选任何文件」）；计划只含 ARW、
      不含同目录 JPG（511 个而不是 1023 个）；剩余空间不足时按 `P-09` 禁用 CTA + 一行 `Low space`
      —— **未验**（本轮按任务约束未装包、未碰真机；空勾选路径此前没有单独上过真机，验收留到 #80 的真机复验）
- [ ] 日期组一键全选（#62）：组头整行长按 500 ms → 本组全部 ARW 进入 `V-11` 已选态，CTA 变为
      `Import <组内张数>`；再长按 → 全不选、CTA 回 `Import all 511`；部分选中时长按**补齐**（不反选）；
      折叠组长按后自动展开；`uiautomator dump` 里组头高度 ≥48 dp 且带 `long-clickable`，
      组头文字不新增、网格内无 per-cell 标记 —— **未验**（同上，设备被另一会话占用）
- [x] 点 `Import all N` → 进入 `IMPORTING`，真实传输落盘到 `files/imported/<relPath>`，每个文件字节数
      与相机侧 `ImportPlan.Item.size` 一致（PA / issue #50）
      —— 2026-11-06 实测：`DSC04541.ARW` 25393920 字节，落盘一致，文件头 `49 49 2a 00`
- [x] 同一批照片再点一次 `Import all N`：日志必须出现 `Skipped N existing`，不重复搬字节（PA / issue #50）
      —— 2026-11-06 实测：`Skipped 1 existing`（4 张里 3 张新传、1 张跳过）
- [x] 导入中（第 1 张还没传完）点 `Pause`：当前这张必须传完并落盘，进度停在文件边界，后续文件不启动；
      点 `Resume` 从下一个文件继续（PB / issue #50）
      —— 2026-11-06 实测：58%（33/57）时界面切到 `Resume`/`Cancel`；恢复后日志出现
      `继续已暂停的一轮`，从下一张 `DSC02705` 继续，`Skipped 30 existing`
- [x] `Pause & exit` 回照片墙后，已完成的文件不再重传，再点 `Import all N` 会继续被暂停的那轮；
      期间不得被迟到的进度回调拽回 `IMPORTING`（#48 / PB）
      —— 2026-11-06 实测：`Pause & exit` 后回照片墙，再点 `Import` 继续同一轮，未回跳 `IMPORTING`
      —— 2026-11-06 三审 B-1 复核：`Pause & exit` 后停 **90 秒**再点 `Import`，恢复前只读探活通过
      （日志无「恢复前探活失败」），`继续已暂停的一轮`，剩余 2 张继续落盘，`完成 3 / 跳过 0 / 失败 0`
      （ILCE-7C 2.00 / MI 9 / Android 15 API 35 / USB MTP）
- [ ] `Pause & exit` 回照片墙后**改勾选 / filter / 档位**再点 `Import N`：必须按新计划重开一轮
      （日志 `暂停轮次的清单已变化（旧 N 个 / 新 M 个）：取消旧轮次，按当前勾选重新开始`），
      不得把新勾选静默忽略、也不得继续跑旧清单；已完成文件仍由 Journal 跳过（#57）
      —— **未验**（相机已断开，判定的纯逻辑由 SelfTest 覆盖）
- [x] 导入中点 `Cancel`：当前 `importFile` 在本次机型约 0.6 s 返回（不是等整张传完；
      回到照片墙；再次导入必须重传被取消的那个文件（`import-journal.txt` 里不得留下它）
      —— 2026-11-06 实测：`DSC02707.ARW` 取消后 `已取消（未传满，半成品已删除）`、
      `[CANCELLED]`、无 `[OK]`；随后同一会话的下一轮把它正常传完并记账，`.part` 无残留
- [x] 取消后同一会话继续导入是否可用（pfd 关闭中断原生读，MTP 会话可能失步）：
      下一轮开始前会用只读 `getObjectInfo(handle)` 探活，失败必须落 `ERROR`（不是整轮全失败才发现）
      —— 2026-11-06 实测：取消后立即再导入，探活通过、无 `ERROR`，继续落盘 7 张
- [ ] 续传停用止血（#177）：导入中断线 / 杀进程，`files/imported/` 留下
      `<relPath 压平>.<size>.part` 残留；重新插线再点 `Import N`，日志必须出现
      「作废旧半成品（… 续传已停用，从 0 重传）」，且成品 MD5 与整文件传输一致；
      **不得**出现「发现半成品：… 从断点续传」，也不得把满尺寸残渣直接 rename 当成品
      —— **未验**（本轮设备被另一会话独占；控制流由 SelfTest 的「忽略 offset」假 reader 覆盖）
- [ ] 若 `pfd.close()` 打不断原生调用（`MtpTransfer` 未验项）：取消宽限 3 秒后必须判定「卡死」，
      点 `Import` 落 `ERROR`（`相机连接可能已失步，请拔插数据线`）；旧 worker 未退出前
      `Connect camera` / 新导入都必须保持 `ERROR`（不得 `session.close()` 撞上原生调用），
      且旧轮迟到 `finally` 不得覆写 `import-journal.txt`
      —— **未验**（2026-11-06 实测 pfd 关闭约 0.6 s 就返回，没造出卡死条件；放弃逻辑由 SelfTest 覆盖）
- [ ] 暂停恰好落在最后一个文件、且用户已 `Pause & exit`：worker 在文件边界正常结束，
      `onFinished(cancelled=false)` 不得把停在照片墙的界面拽回 `DONE` —— **未验**（时窗太短，三次尝试都没卡进这个边界；逻辑由 `model.getTransferViewClosed()` 分支覆盖）
- [ ] 3 秒宽限是按「与扫描重试 3 秒口径一致」取的，不是实测推导：**慢取消**（原生调用超过 3 秒才返回）
      会被误判成「取消卡死」，后果是落 `ERROR` 并要求拔插数据线（正常约 0.6 s，未造出慢取消）——**未验**
- [ ] 拔线重插后能重新扫描（会话重建）
- [ ] `SCANNING` 点 `Cancel` 必须停在 `COLD`（已有照片墙则回 `CATALOG_READY`），且此后再点
      `Connect camera` 能重新开始扫描，**不得**出现「扫描已在进行中，忽略本次重复事件」（U-06 / P-02）
- [ ] `Cancel` 后**立刻**点 `Connect camera` 只在扫描线程退出后才成立：`MtpDevice.open()` 可阻塞约 10 秒
      （相机会在 MTP 会话结束后约 1 分钟挂起 USB），这个窗口内仍可能拿到「扫描已在进行中」——**未验**
- [ ] `onDestroy` 的竞态（PD / issue #50）：先 `cancel` 传输与扫描，再 1 s 有界 join；
      全部退出才 `session.close()`，否则跳过并记日志（1 s 预算）——**未验**（逻辑由 `ImportService.awaitWorker`
      的 SelfTest 覆盖，真机尚未造出「取消打不断原生调用」窗口）
- [ ] `Main.awaitPermission` 的「判取消」与 `Main.requestPermission` 非原子，
      取消后仍可能弹一次系统权限框——**未验**
- [ ] 连续两次申请 USB 权限并在两次系统弹窗都点**拒绝**：第二次必须等回调/超时后落到 `ERROR`
      并提示 `未获得 USB 权限`，**不得**出现 `openDevice() 返回 null`
      （#11：2026-11-05 用同源、不同包名的副本验过；原包要验得先让系统清掉它的 USB 授权，
      目前只在拔插后才会清，故留给下次真机）
- [ ] `P-09` 导入前空间预检（PD / issue #50）：目标剩余空间 < 计划 + 5% 缓冲时主 CTA 必须禁用、
      上方出现一行 `Low space`、差额进 `Location details`；空间恢复（删文件）后勾选变化必须重新放行
      —— **未验**（逻辑由 SelfTest 覆盖；真机未造出低空间条件）
- [ ] 逐文件失败上报（PD / issue #50）：制造一个单文件失败（拔线/相机休眠），`Location details`
      必须固定显示 `Failed: <relPath> · <原因>`，不被后续日志挤掉；DONE 首屏不得出现 `Failed N`
      —— **未验**
- [ ] 单文件重试（PD / issue #50）：首次 `importFile` 失败后必须自动重试 1 次（重试也从 0 重写，#177），
      重试成功仍记完成；连续失败只累计 `failed`，不得无限重试或整轮自动重跑
      —— **未验**（SelfTest 覆盖「首次失败→第二次成功」与「全失败恰好 2 次」）
- [ ] `import-journal.txt` 能写出来且格式可读（结构见 `Journal` javadoc）

> **放弃卡死轮次的代价（三审 B-2）**：`ImportService.run()` 只在开始时 `loadJournal()` 一次，
> `markDone` 只改内存，`saveJournal()` 在 `finally` 整文件覆写。被放弃的轮次跳过保存，意味着
> **本轮所有已完成文件的记录都会丢**，下次会全部重传（不会产生假完成，但代价比「记录已在
> 旧日志里」大）。
>
> **异常处置（三审建议）**：`orphanService` 的 worker 不退出时，界面唯一出路是 `Connect camera`
> 重扫（会保持 `ERROR`，不会死键）；物理拔插数据线是唯一恢复手段。若拔插后仍不退出，只能杀进程重启。

> 已删除的旧条目（「目录树层级 + 已勾选计数」「`MAX_ROWS = 800` 未显示提示」「点『开始导入』」）
> 属于本轮前的目录树界面，已被照片墙取代，不再适用。

## 待建清单（本轮不做，后续 issue 排期）

- `DONE` 的 `Open` 只记日志（`Main.onOpen`），不跳系统相册；需另接 `ACTION_VIEW` / 相册 intent。
