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
| 目录树打勾、勾目录连带子孙、全选/清空 | ✅ 实现 |
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
└─ Log               极小日志接口，免得纯逻辑类去 import android.util.Log  [纯 JDK]
```

标 `[纯 JDK]` 的**九个**文件**不允许 import android.\***，这样它们能在普通 JVM 上跑
`test/SelfTest.java`。

这条边界是**编译期强制**的：`check.sh` 编译这批文件时**不给** `-classpath android.jar`，
所以哪个纯逻辑类偷偷 `import android.*` 会直接编译失败，而不是等真机才崩。
`PURE=(...)` 清单还有第 0 步漂移检查兜底——拿 `grep -L '^import android'` 的实际结果
与清单比对，漏列一个文件就判红（否则新加的纯逻辑类会悄悄脱离这条保护）。

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

一条命令验完（JVM 自检 + APK 构建 + 签名校验）：

```bash
bash tools/android-mtp-import/check.sh
```

只要跑纯逻辑、不要 Android SDK：

```bash
SKIP_BUILD=1 bash tools/android-mtp-import/check.sh
```

单独构建 APK：

```bash
bash tools/android-mtp-import/build.sh   # 产物 build/mtp-import.apk
```

签名复用 `tools/android-mtp-probe/rea-debug.keystore`（同一把 key，包名不同不冲突；
换 key 会导致覆盖安装 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。

## 安装运行

```bash
adb install -g build/mtp-import.apk
adb shell am start -n com.rea.mtpimport/.Main
adb logcat -s MTPIMPORT:*
```

相机侧：`MENU → 设置 → USB连接 → MTP`（不是 PC 遥控）。插线系统会自动拉起本 App
并自动授权（manifest 挂了 `USB_DEVICE_ATTACHED` + `res/xml/device_filter.xml`）。

续传日志落在 `getFilesDir()/import-journal.txt`，可以直接看：

```bash
adb exec-out run-as com.rea.mtpimport cat files/import-journal.txt
```

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

- [ ] 插线后 App 自动拉起、自动拿到 USB 权限（不弹窗）
- [ ] 扫描完成，文件夹/文件数与卡内一致（探针对照值：1526 对象 / 503 文件夹 / 511 ARW）
- [ ] 目录树层级正确（`DCIM/100MSDCF/...`），勾一个目录后「已勾选」计数与总量合理
- [ ] 未显示全部行时的提示文案正确（超过 `MAX_ROWS = 800` 行）
- [ ] 点「开始导入」→ 停在 `NOT_IMPLEMENTED`，日志说明原因，进度条停在 0
- [ ] 拔线重插后能重新扫描（会话重建）
- [ ] `import-journal.txt` 能写出来且格式可读（结构见 `Journal` javadoc）
