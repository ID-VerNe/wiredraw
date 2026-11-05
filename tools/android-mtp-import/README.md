# android-mtp-import —— 导片工具（架构骨架）

把 `tools/android-mtp-probe/`（一次性验证工具）里已经跑通的 MTP 结论，
沉淀成一个**能日常用**的导片工具的骨架。

> **本轮只搭框架，不实现真正的传输。** 点「开始导入」会明确停在
> `NOT_IMPLEMENTED`，进度条不会动。这是计划行为，不是 bug —— 原因见
> 「为什么不做真续传」一节。

## 它现在能做什么

| 能力 | 状态 |
|---|---|
| 找 Sony 机身、申请/自动获取 USB 权限 | ✅ 实现（从探针抽出，坑原样保留） |
| 建 MTP 会话、带唤醒重试拿存储 | ✅ 实现（8 次 / 3 秒，同探针） |
| 全量扫描卡内对象 → 目录树（`Catalog`） | ✅ 实现（不用 format 过滤，见下） |
| 目录树打勾、勾目录连带子孙、全选/清空 | ⛔ 已被照片墙取代（选择模型仍在 `Selection`） |
| 三屏（`library` / `transfer` / `photo`）+ 十个状态全部有界面 | ✅ 实现（Compose，`src/com/rea/mtpimport/ui/`） |
| 照片墙：ARW 按日期分组、点选、过滤抽屉 | ✅ 实现（`PhotoWall` + `UiText` 纯逻辑，JVM 自检覆盖） |
| 假数据模式（`--es demo_state <状态>`，release 编不进去） | ✅ 实现（`src/debug`，见下） |
| 勾选 → 下载清单 + 总量（`ImportPlan`） | ✅ 实现 |
| 进度数学：百分比 / 速度 / ETA | ✅ 实现（纯逻辑，JVM 自检覆盖） |
| 续传日志读写（句柄 → 已落盘字节） | ✅ 实现（纯逻辑，JVM 自检覆盖） |
| 线程 / 暂停 / 取消 / 逐文件回调 | ✅ 实现 |
| **真正搬字节（含 `getPartialObject` 续传）** | ❌ **仅接口 + 占位** |

## 类职责

```
Main                 Activity：USB 权限/attach → 扫描 → 勾选 → 进度条 → 开始/暂停
├─ MtpSession        USB 连接 + MtpDevice 生命周期 + 唤醒重试          [android]
├─ CatalogScanner    枚举 MTP 对象 → Catalog（只读）                    [android]
├─ Catalog           只读目录树（Entry + childrenOf/descendantsOf）     [纯 JDK]
├─ Selection         勾选集合；勾目录 = 把子孙写进集合                   [纯 JDK]
├─ ImportPlan        勾选 → 去重清单 + totalBytes()                     [纯 JDK]
├─ TransferProgress  已传/总量 → 分数 / 速度 / ETA（时钟注入）          [纯 JDK]
├─ Journal           续传日志：markDone / isComplete / partPath / 存取   [纯 JDK]
├─ ImportService     编排：线程、暂停、取消、逐文件调 transfer            [纯 JDK]
├─ ResumableTransfer 传输契约 + NotImplemented 占位                     [纯 JDK]
├─ ImportListener    回调接口（全 default）                              [纯 JDK]
├─ AttachGate        插线事件准入判定：去重，同一次插拔不得开两个会话     [纯 JDK]
├─ PermGate          USB 权限等待门：每次申请重建、只认本次序号（#11）     [纯 JDK]
├─ Log               极小日志接口，免得纯逻辑类去 import android.util.Log  [纯 JDK]
├─ UiState           10 态 → 界面映射、短名解析（§1.2）                    [纯 JDK]
├─ UiText            用户可见数字与短文案的唯一入口（C-05 / C-08 / U-45）   [纯 JDK]
├─ PhotoWall         ARW → 日期组聚合（组内 count / bytes）                 [纯 JDK]
├─ DemoBridge        假数据载荷契约（实现只在 debug 源集）                  [纯 JDK]
├─ DemoPayload       一次假数据载荷（状态 + 假树 + 假进度）                 [纯 JDK]
├─ ui/               Compose 三屏 + 两个抽屉 + 图标 + 主题 + AppModel       [android]
└─ src/debug/        DemoTree（假树）+ DemoEntry（`--es demo_state` 入口）  [debug 变体]
```

**Compose 只做 UI 层与状态映射**：`PermGate` / `AttachGate` / `Catalog` / `Journal` /
`TransferProgress` / `ImportService` 保持原样，`Main` 只负责把系统事件翻译成
`AppModel` 的可观察属性。

标 `[纯 JDK]` 的**十七个**文件**不允许 import android.\***，这样它们能在普通 JVM 上跑
`test/SelfTest.java`（其中 `DemoTree.java` 在 `src/debug`，用单独的清单核对）。

这条边界是**编译期强制**的：Gradle 的 `compilePureJdkJava` 编译这批文件时**不给 android.jar**
（classpath 故意为空），所以哪个纯逻辑类偷偷 `import android.*` 会直接编译失败，而不是等真机才崩。
`pureJdkClasses` 清单还有第 0 步漂移检查兜底——`checkPureJdkList` 拿「磁盘上不 import android
的文件」与清单比对，漏列一个文件就判红（否则新加的纯逻辑类会悄悄脱离这条保护）。

## 为什么不做真续传

`MtpDevice.getPartialObject(handle, offset, size, buffer)` 对 a7C 的 `0xB101` 对象
是否支持 offset 续读、超界时是报 `InvalidParameter` 还是静默少传，**本机一次都没验证过**。
探针用的是 `importFile()`（`MtpDevice` 里没有 offset 语义）。

写一个「看起来能续传」的实现比不写危险得多：它会让所有人以为断点续传已经可用，
而真机上可能写出一堆截断的 ARW —— 而且 MD5 要等到很久以后才会对不上。

真实现要做的三件事已写在 `ResumableTransfer` 的 javadoc 里：跨会话按 `size + relPath`
重新拿到新句柄、从 `resumeFrom` 循环 `getPartialObject`、落盘字节与
`getCompressedSizeLong()` 对账。

## 构建与自检

构建体系：Gradle `8.9` + AGP `8.7.3` + Kotlin `2.0.21` + Compose 编译器插件 `2.0.21`
（Compose 运行库 BOM `2024.12.01`）。工具链由 `gradle/wrapper/` 锁定，**不要**用本机全局
安装的 Gradle。首次构建要联网下载 Gradle 依赖；本机代理链路会间歇断开，失败时原样重跑即可。

一条命令验完（JVM 自检 + APK 构建）：

```bash
bash tools/android-mtp-import/gradlew -p tools/android-mtp-import selfTest assembleDebug
```

只跑纯逻辑自检（仍需能定位 Android SDK —— 配置阶段即校验；80 项断言）：

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
`DONE` 下返回键直接退出——这三种只能重新 `am start`，不要当成「抽屉里随便来回切」。

## 沿用探针的三条实测结论

1. **format 过滤不可信**：`getObjectHandles(sid, 0xB101, 0)` 返回 754 个而全量只有
   511 个真 ARW（探针 README 坑 2）。`CatalogScanner` 一律全量枚举 + 逐个
   `getObjectInfo()` 自己分类，不做这个「优化」。
2. **相机会挂起 USB**：会话结束后约 1 分钟，`getStorageIds()` 返回空、`open()` 卡 10 秒。
   `MtpSession.storageIds()` 重试 8 次 / 每次 3 秒；仍失败就提示用户拔插线
   —— 实测重试唤不醒，只有拔插有效。
3. **扫描要 59 秒**：1526 个对象 × 39 ms。所以扫描必须回调进度，且扫描结果要缓存成
   `Catalog` 复用——每次勾选都重扫的交互没法用。

## 真机待验清单（验收前必须在 ILCE-7C 上跑）

- [x] 插线后 App 自动拉起、自动拿到 USB 权限（不弹窗）；**首次**要在系统弹窗里勾一次「一律打开」，
      应用侧没有正规接口能绕过这一步（#17）。实测 2026-11-05 18:48：插回后 `permissions_manager`
      只有既有 `uids=10269`，日志无 `尚无 USB 权限，发起申请`
- [x] 插线或冷启动时自动开始扫描，不需要点「重新检测」；同一次插拔重复投递两次也不得开两个会话（#17）。
      实测 2026-11-05 18:48：广播路径 `→ 开始扫描`，attach intent 路径 `→ 扫描已在进行中，忽略本次重复事件`，
      单次会话扫出 `待扫描对象共 1526 个`、`扫描完成：文件夹 503 个、文件 1023 个（合计 12.75 GB，其中 ARW 11.89 GB）`
- [ ] 扫描完成，文件夹/文件数与卡内一致（探针对照值：1526 对象 / 503 文件夹 / 511 ARW）
- [ ] 目录树层级正确（`DCIM/100MSDCF/...`），勾一个目录后「已勾选」计数与总量合理
- [ ] 未显示全部行时的提示文案正确（超过 `MAX_ROWS = 800` 行）
- [ ] 点「开始导入」→ 停在 `NOT_IMPLEMENTED`，日志说明原因，进度条停在 0
- [ ] 拔线重插后能重新扫描（会话重建）
- [ ] 连续两次「重新检测」并在两次系统弹窗都点**拒绝**：第二次必须等回调和提示
      `USB 权限未授予 · 点「重新检测」重新申请`，**不得**出现 `openDevice() 返回 null`
      （#11：2026-11-05 用同源、不同包名的副本验过；原包要验得先让系统清掉它的 USB 授权，
      目前只在拔插后才会清，故留给下次真机）
- [ ] `import-journal.txt` 能写出来且格式可读（结构见 `Journal` javadoc）
