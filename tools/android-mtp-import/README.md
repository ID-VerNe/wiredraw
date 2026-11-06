# android-mtp-import —— 导片工具（架构骨架）

把 `tools/android-mtp-probe/`（一次性验证工具）里已经跑通的 MTP 结论，
沉淀成一个**能日常用**的导片工具的骨架。

> **PA（issue #50）起，整文件传输是真的；PB 补齐暂停 / 取消 / 恢复；PC 补齐真续传。**
> `Import all N` / `Import N` 会用 `MtpDevice.importFile()` 把对象读到
> `getFilesDir()/imported/<relPath>`，逐文件校验字节数并推进进度。
> `Pause` 落在文件边界（在传的那个文件会传完），`Cancel` 会关闭正在写的 fd，尝试中断
> 当前 `importFile`：本次机型实测约 0.6 s 返回；能否打断没有跨机型保证，打不断时由
> `isCancelStuck()` 给界面出路。
>
> PC（#50）用探针 #67 钉死的 `getPartialObject` 语义做真续传：失败/断线保留按
> `relPath + size` 命名的 `.part`，下一轮按它的实际长度从绝对偏移续读（1 MiB 块，永不越界），
> 成功后才 rename 并记账；取消会删除整个半成品（从 0 重来）。
> 见「断点续传（PC）」与 issue #50 的 PD 分节（仍未做）。

## 它现在能做什么

| 能力 | 状态 |
|---|---|
| 找 Sony 机身、申请/自动获取 USB 权限 | ✅ 实现（从探针抽出，坑原样保留） |
| 建 MTP 会话、带唤醒重试拿存储 | ✅ 实现（8 次 / 3 秒，同探针） |
| 全量扫描卡内对象 → 目录树（`Catalog`） | ✅ 实现（不用 format 过滤，见下） |
| 渐进加载：首批 ARW 即出照片墙，其余后台补齐（#60） | ✅ 实现（`Catalog.snapshot()` 每 200 个对象发布一次；顶栏 2 dp 细线 + 临时 `Cancel`，主 CTA 在补齐期间禁用；真机时延**未验**） |
| 照片墙缩略图（#59） | ✅ 实现（IO 线程 `getThumbnail`：先 ARW 句柄、再同名同目录 JPG；512 px 长边解码，24 MiB LruCache 兜底，格子移出组合即 `evict`（离屏位图不常驻）；`getThumbnail` 对 ARW 的支持**真机未验**，拿不到就退回 V-19 灰块） |
| 目录树打勾、勾目录连带子孙、全选/清空 | ⛔ 已被照片墙取代（选择模型仍在 `Selection`） |
| 三屏（`library` / `transfer` / `photo`）+ 十个状态全部有界面 | ✅ 实现（Compose，`src/com/rea/mtpimport/ui/`） |
| 照片墙：ARW 按日期分组、点选、过滤抽屉 | ✅ 实现（`PhotoWall` + `UiText` 纯逻辑，JVM 自检覆盖） |
| 假数据模式（`--es demo_state <状态>`，release 编不进去） | ✅ 实现（`src/debug`，见下） |
| 勾选 → 下载清单 + 总量（`ImportPlan`） | ✅ 实现 |
| 进度数学：百分比 / 速度 / ETA | ✅ 实现（纯逻辑，JVM 自检覆盖） |
| 续传日志读写（按 `relPath` + 大小判定完成/已落盘字节） | ✅ 实现（纯逻辑，JVM 自检覆盖） |
| 线程 / 逐文件回调 | ✅ 实现 |
| **暂停（文件边界）/ 取消（关 fd 尝试中断 `importFile`）/ 恢复本轮** | ✅ 实现（PB / issue #50；2026-11-06 真机已验：`Pause`→`Resume`、`Cancel` 约 0.6 s 返回并重传；打断没有跨机型保证） |
| **取消后 / 暂停恢复前复用会话的只读探活（`getObjectInfo`）+ 取消卡死的 ERROR 出路** | ✅ 实现（二审 B-1 / 三审 B-1：取消后与 `Pause & exit` 恢复前都探活；卡死轮次放弃后拦住扫描/新导入且不覆写 journal。探活路径 2026-11-06 真机已验，卡死路径由 SelfTest 覆盖、未触发） |
| **真正搬字节：`importFile` 整文件传输 + 字节数对账** | ✅ 实现（PA / issue #50，`MtpTransfer`） |
| **断点续传（`getPartialObject` offset 续读）** | ✅ 实现（PC / issue #50；语义由 #67 探针在 ILCE-7C 上验证，App 内端到端待真机） |

## 类职责

```
Main                 Activity：USB 权限/attach → 扫描 → 照片墙选图 → 传输 → 进度/暂停/取消
├─ MtpSession        USB 连接 + MtpDevice 生命周期 + 唤醒重试          [android]
├─ CatalogScanner    枚举 MTP 对象 → Catalog（只读）                    [android]
├─ Catalog           只读目录树（Entry + childrenOf/descendantsOf）     [纯 JDK]
├─ Selection         勾选集合；勾目录 = 把子孙写进集合                   [纯 JDK]
├─ ImportPlan        勾选 → 去重清单 + totalBytes()                     [纯 JDK]
├─ TransferProgress  已传/总量 → 分数 / 速度 / ETA（时钟注入）          [纯 JDK]
├─ Journal           续传日志：markDone / isComplete / partPath / resumeFrom / 存取  [纯 JDK]
├─ ImportService     编排：线程、暂停（文件边界）、取消、逐文件调 transfer  [纯 JDK]
├─ CancelToken       取消令牌：置位 + 立即执行传输层的中断动作（PB）       [纯 JDK]
├─ ResumableTransfer 传输契约（整文件 / 取消 / offset 续传，语义见 #67）    [纯 JDK]
├─ MtpTransfer       真实传输：importFile（从头）/ getPartialObject（续传）
│                    → 校验字节 → rename 到 relPath；取消删本次半成品（PB/PC） [android]
├─ ThumbnailStore    照片墙缩略图：getThumbnail → 同名 JPG 回退 → 24 MiB LruCache [android]
├─ ImportListener    回调接口（全 default）                              [纯 JDK]
├─ AttachGate        插线事件准入判定：去重，同一次插拔不得开两个会话     [纯 JDK]
├─ PermGate          USB 权限等待门：每次申请重建、只认本次序号（#11）     [纯 JDK]
├─ ScanRun           一轮扫描的取消令牌：取消后迟到回调不得改界面           [纯 JDK]
├─ StorageRetry      getStorageIds 带唤醒重试：每轮判取消，最多多睡 1 个 3 秒 [纯 JDK]
├─ Log               极小日志接口，免得纯逻辑类去 import android.util.Log  [纯 JDK]
├─ UiState           10 态 → 界面映射、短名解析（§1.2）                    [纯 JDK]
├─ UiText            用户可见数字与短文案的唯一入口（C-05 / C-08 / U-45）   [纯 JDK]
├─ PhotoWall         ARW → 日期组聚合（组内 count / bytes）                 [纯 JDK]
├─ DemoBridge        假数据载荷契约（实现只在 debug 源集）                  [纯 JDK]
├─ DemoPayload       一次假数据载荷（状态 + 假树 + 假进度）                 [纯 JDK]
├─ PhotoPreview      照片详情页的预览来源（真实路径恒为 null）              [android]
├─ ui/               Compose 三屏 + 两个抽屉 + 图标 + 主题 + AppModel       [android]
└─ src/debug/        DemoTree（假树）+ DemoEntry（`--es demo_state` 入口）
                     + DemoImages（真实照片占位）+ DemoTransfer（进度模拟）  [debug 变体]
```

**Compose 只做 UI 层与状态映射**：`AttachGate` / `Catalog` / `Journal` /
`TransferProgress` / `ImportService` 保持原样；`PermGate` 只调整等待/重发实现，
序号语义不变，`Main` 只负责把系统事件翻译成 `AppModel` 的可观察属性。

标 `[纯 JDK]` 的**二十三个**文件**不允许 import android.\***，这样它们能在普通 JVM 上跑
`test/SelfTest.java`（其中 `DemoTree.java` / `DemoTransfer.java` 在 `src/debug`，用单独的清单核对）。

这条边界是**编译期强制**的：Gradle 的 `compilePureJdkJava` 编译这批文件时**不给 android.jar**
（classpath 故意为空），所以哪个纯逻辑类偷偷 `import android.*` 会直接编译失败，而不是等真机才崩。
`pureJdkClasses` 清单还有第 0 步漂移检查兜底——`checkPureJdkList` 拿「磁盘上不 import android
的文件」与清单比对，漏列一个文件就判红（否则新加的纯逻辑类会悄悄脱离这条保护）。

## 断点续传（PC / issue #50）

`getPartialObject` 的语义先由 `tools/android-mtp-probe` 在 ILCE-7C 上实测（issue #67），
结论已钉死：`offset` 是**绝对偏移**；普通 ARW / JPEG 对象都支持；1 MiB 分块拼满
24.4 MB ARW 的字节数、MD5、TIFF 文件头与 `importFile` 完全一致（22.86 MB/s）；
`length` 超剩余截断到 EOF；`offset == size` 返回 0；**`offset == size + 1` 抛 IOException
并污染整个 MTP 会话**。

对应实现：

- `resumeFrom == 0` 仍走已验的 `importFile`；
- `0 < resumeFrom < size` 走 `getPartialObject(handle, offset, min(1 MiB, size - offset), buf)`
  循环 append 到 `.part`，**永不请求 `offset > size`**；
- `resumeFrom == size` 直接把半成品 rename 落盘；
- 半成品按 `<relPath 压平>.<size>.part` 命名（`Journal.partPath`），与跳过判定的
  `relPath + size` 同口径、跨会话稳定；同路径异 size 的旧残留不会被当成本对象的前缀
  （旧文件清理由 #52 负责）；续传起点取自 `.part` 实际长度（`Journal.resumeFrom`），
  超过目标大小视为损坏、从 0 重传；
- 失败 / 断线保留 `.part`，取消删除整个半成品（取消 = 从 0 重来，与 PB 一致）；
- 结束时字节数必须等于相机侧 `ImportPlan.Item.size`，否则 FAILED 且保留 `.part`。

PD（错误与重试、空间预检、`onDestroy` 竞态）仍未做。

## 构建与自检

构建体系：Gradle `8.9` + AGP `8.7.3` + Kotlin `2.0.21` + Compose 编译器插件 `2.0.21`
（Compose 运行库 BOM `2024.12.01`）。工具链由 `gradle/wrapper/` 锁定，**不要**用本机全局
安装的 Gradle。首次构建要联网下载 Gradle 依赖；本机代理链路会间歇断开，失败时原样重跑即可。

一条命令验完（JVM 自检 + APK 构建）：

```bash
bash tools/android-mtp-import/gradlew -p tools/android-mtp-import selfTest assembleDebug
```

只跑纯逻辑自检（仍需能定位 Android SDK —— 配置阶段即校验；265 项断言）：

```bash
bash tools/android-mtp-import/gradlew -p tools/android-mtp-import selfTest
```

单独构建 APK：

```bash
bash tools/android-mtp-import/gradlew -p tools/android-mtp-import assembleDebug
# 产物 tools/android-mtp-import/build/outputs/apk/debug/mtp-import-debug.apk
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

续传日志落在 `getFilesDir()/import-journal.txt`，可以直接看：

```bash
adb exec-out run-as com.rea.mtpimport cat files/import-journal.txt
```

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
看到图）；`photo` 页仍是 #46 的 `PhotoPreview`。

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
      顶栏徽标 = 511，`Import all 511` 与徽标同分母（U-53）
- [ ] 渐进加载（#60）：插线后**首批 ARW 出现即**看到照片墙，不必等满 1526 个对象；
      顶栏下方 2 dp 细线推进、临时 `Cancel` 可点、主 CTA 禁用；补齐完成后细线与 `Cancel` 消失、
      CTA 启用、徽标从中间值变为 511；点 `Cancel` 必须丢弃部分结果回 `COLD` —— **未验**
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
- [ ] 点格切换已选（`Import all N` ↔ `Import N`；已选 0 时主 CTA 仍可点），长按格子进 `photo` 页
      （只显示单行 `ARW · <size>` 胶囊）；组头可折叠，过滤抽屉只改可见集、不改徽标与 `Import all N` 分母
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
- [x] 导入中点 `Cancel`：当前 `importFile` 在本次机型约 0.6 s 返回（不是等整张传完；
      回到照片墙；再次导入必须重传被取消的那个文件（`import-journal.txt` 里不得留下它）
      —— 2026-11-06 实测：`DSC02707.ARW` 取消后 `已取消（未传满，半成品已删除）`、
      `[CANCELLED]`、无 `[OK]`；随后同一会话的下一轮把它正常传完并记账，`.part` 无残留
- [x] 取消后同一会话继续导入是否可用（pfd 关闭中断原生读，MTP 会话可能失步）：
      下一轮开始前会用只读 `getObjectInfo(handle)` 探活，失败必须落 `ERROR`（不是整轮全失败才发现）
      —— 2026-11-06 实测：取消后立即再导入，探活通过、无 `ERROR`，继续落盘 7 张
- [ ] 真续传端到端（PC / issue #50）：导入中断线 / 杀进程，`files/imported/` 留下
      `<relPath 压平>.<size>.part`；
      重新插线再点 `Import N`，日志必须出现「发现半成品：… 已有 N 字节，从断点续传」，
      最终字节数 == 相机侧 `size`、文件头 `49 49 2A 00`、MD5 与整文件结果一致；不得重传已落盘前缀
      —— **未验**（分块语义由 #67 探针实测，App 内编排与恢复窗口待真机）
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
- [ ] `onDestroy` 与扫描线程并发关会话的原生竞态：`Main.onDestroy` 仍在 UI 线程
      `session.close()` 且不 `cancel` `scanRun`，可能与扫描线程正在跑的 `getStorageIds` /
      `getObjectInfo` 相撞（`main` 既有行为，本轮不改）——**未验**
- [ ] `Main.awaitPermission` 的「判取消」与 `Main.requestPermission` 非原子，
      取消后仍可能弹一次系统权限框——**未验**
- [ ] 连续两次申请 USB 权限并在两次系统弹窗都点**拒绝**：第二次必须等回调/超时后落到 `ERROR`
      并提示 `未获得 USB 权限`，**不得**出现 `openDevice() 返回 null`
      （#11：2026-11-05 用同源、不同包名的副本验过；原包要验得先让系统清掉它的 USB 授权，
      目前只在拔插后才会清，故留给下次真机）
- [ ] `P-09` 导入前空间预检：**未验**（本轮只登记，实现在 PD 分节，该分支当前不可达）
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
