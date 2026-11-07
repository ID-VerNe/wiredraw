# android-mtp-probe —— 安卓经 USB/MTP 读 a7C 卡内 RAW 的验证工具

一条 20 行的 MTP 主机调用就能替代索尼官方 App 传 RAW 的过程。本工程是为了**先证明这条路真的走得通**，
再去写正式 App。已经在魅族 20 Infinity / Android 15 (API 35) + ILCE-7C 上跑通。

结论先行：**能用，21.4 MB/s，下载出来的 ARW 与相机侧字节完全一致（MD5 已核对）。**

## 它做什么

只读，不改相机任何设置：

1. 找到 Sony USB 设备（VID `0x054C`）
2. `UsbManager.openDevice()` → `MtpDevice.open()`
3. 枚举存储、统计卡内文件（按 ARW / JPEG / MP4 / 文件夹分类）
4. 挑最大的 3 张 ARW 下载，量吞吐、比大小、算 MD5、校验 TIFF 文件头
5. 对第一张已下载的 ARW 做 `getPartialObject` 语义验证（非零 offset 内容判别 / offset 与 length
   越界 / 普通对象支持面 / 顺序拼接与整文件比 MD5），见下方「PC 探针」一节
6. 全程日志到 logcat（tag `MTPPROBE`），同时写一份到 `getFilesDir()/report.txt`

## 构建（不需要 Gradle）

要求：Android SDK（`build-tools;35.0.1` + `platforms;android-35`）、JDK 21、Python 3。
脚本会自动探测工具链：SDK 按 `ANDROID_HOME` → `ANDROID_SDK_ROOT` → 常见默认路径，
JDK 按 `JAVA_HOME` → `PATH` 里的 `javac`。找不到时打印可直接复制的设置示例并以非 0 退出。
版本号写死是为了构建可复现；你装的版本不同，改脚本顶部的 `BT_VER` / `PLATFORM` 两行即可。

```bash
# Linux / macOS / Git Bash
bash build.sh
# 产物 build/mtp-probe.apk
```

Windows PowerShell 下 `bash` 会落到 WSL（WSL 内看不到 Windows 的 SDK 路径，直接以
「找不到 Android SDK」退出）。用 Git for Windows 的 bash，或在仓库根目录走门禁执行器：

```powershell
# 在探针目录（当前目录）执行，与上面的 bash build.sh 相同
& "C:\Program Files\Git\bin\bash.exe" build.sh

# 或在仓库根目录走门禁执行器
python scripts/run_gates.py manual
```

流程就是 `aapt2 compile → aapt2 link → javac → d8 → 手动塞 classes.dex → zipalign → apksigner`。
签名用的 `rea-debug.keystore` 留在工程根目录（**别删**，删了重新构建会因签名不同而 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。

## 安装运行

```bash
adb install -g build/mtp-probe.apk
adb shell am start -n com.rea.mtpprobe/.Main
adb logcat -s MTPPROBE:*
```

相机侧设置：`MENU → 设置 → USB连接 → MTP`（不是 PC遥控）。

**插上线系统会自动拉起本 App 并自动授予 USB 权限**——因为 manifest 里挂了匹配 Sony VID 的
`USB_DEVICE_ATTACHED` intent-filter（`res/xml/device_filter.xml`），之后插线不再弹权限框。
但**首次插线仍要在系统弹窗里勾一次「一律打开」**（系统层约束，应用无法绕过）。

## 取回结果

App 是 `debuggable`，所以用 `run-as` 直接读内部文件：

```bash
adb exec-out run-as com.rea.mtpprobe cat files/report.txt      > mtp-report.txt
adb exec-out run-as com.rea.mtpprobe cat files/probe_DSC02183.ARW > DSC02183.ARW
```

## 实测结果（2026-11-04）

```
设备: Sony Corporation / ILCE-7C / ver=2.0 / 支持 24 个操作
存储 0x00010001 "Storage Media"  容量 59.4 GB  可用 46.5 GB
对象总数 = 1526
分类: 文件夹=503  ARW=511 (合计 11.9 GB)  JPEG=511  其它=1 (C0001.MP4 128.01 MB)

[1/3] DSC02183.ARW  24.44 MB / 1.19 秒 = 20.62 MB/s  大小✅一致
[2/3] DSC02185.ARW  24.43 MB / 1.03 秒 = 23.67 MB/s  大小✅一致
[3/3] DSC02184.ARW  24.42 MB / 1.03 秒 = 23.72 MB/s  大小✅一致
批量合计: 73.29 MB / 3.42 秒 = 21.43 MB/s
MD5 = 060f09ec9eb37139060a9f49c52c98a5   （与 PC 侧 pull 下来后一致）
文件头: 49 49 2A 00 ...  ✅ 合法 ARW(TIFF)
```

对比：Wi-Fi PTP/IP 走 IED 那套只有 **0.95 MB/s**，USB MTP 快约 **22 倍**。

## PC 探针（`getPartialObject`，2026-11-06；非零 offset 判别 2026-11-07 / issue #179）

环境：11-06 首轮为 E2 MI 9 / Android 15 (API 35) / ILCE-7C 2.00 / USB MTP。

> **11-06 的「`offset` 是绝对偏移」结论已撤回（#177 / #179）。** 那一轮的用例只覆盖
> `offset=0` 连读与「从 0 逐块递增」，这两类读法对「忽略 offset、按游标顺序推进」和
> 「每次从 0 返回」都没有判别力；#177 的真机续传产物（尺寸正确但尾部等于对象头）就是被这套
> 用例放过去的。当前语义以本机新跑的 **R0–R3** 为准。

运行方式：照常构建、安装、启动探针（见上文），跑完取回 `report.txt`：

```powershell
adb exec-out run-as com.rea.mtpprobe cat files/report.txt > <本机报告路径>
```

在报告里搜 `非零 offset 内容判别`，能看到四条逐项结果与一行判定。

### 判别用例（R0–R3，只读）

真值是本轮 `importFile` 整文件下载的落盘产物（GetObject 路径，与 `getPartialObject` 不同，
且 MD5 已与 PC 侧 pull 结果核对），不是被测实现自己拼出来的块。

| 条目 | 请求 | 作用 |
|---|---|---|
| `R0 头部参照` | `offset=0`，1 MiB | 确认对象头与基准一致，建立后续位置参照 |
| `R1 前向跳块` | `offset=2 MiB`，64 KiB | 遵守 offset 时位置必须是 2 MiB；只读到 1 MiB 无法区分「绝对偏移」与「游标顺序推进」 |
| `R2 回跳` | `offset=8 KiB`，64 KiB | 明显小于游标；忽略 offset 的实现在这里必然拿不到 `[8 KiB, 8 KiB+64 KiB)` |
| `R3 复位` | `offset=0`，64 KiB | 非零读之后再请求 0，看是否回到对象头 |

每条都先在请求 offset 处逐字节比对；不命中则在基准文件里扫描该片段的首个出现位置，
所以输出是「请求 offset → 实际内容位置」，而不是只有一个 pass/fail。
这四条固定排在该对象的第一条 partial 读，避免其它读推进/扰动游标。

### 怎么读判定行

| 判定 | 含义 |
|---|---|
| `命中请求 offset（真绝对偏移）` | 四条内容都落在各自请求的 offset；只有这种情况才允许把 offset 当绝对偏移用 |
| `按游标顺序推进（忽略 offset）` | 每次返回的字节从上一段末尾继续，请求 offset 被忽略 |
| `每次从 0 返回（offset 被完全忽略）` | 每次都回到对象头。**这是四条单请求的分类名，不是完整语义**——合解见下一节 |
| `混合/未识别` | 不成上述三种模式，按上面逐条位置定位 |
| `无法判定：…` | 有请求异常 / 返回 0 / 返回字节在基准中找不到；先排除会话与基准问题 |

### 合解：`offset` 只在等于游标时被采纳

单看判定行会把「每次从 0 返回」误读成完整语义。把 R0–R3 与同轮 25 次顺序拼接（MD5 与整文件
一致）合起来，才能得到精确结论：

> **非零 `offset` 只有在恰好等于相机内部游标时才被采纳；对不上时忽略 offset、从对象头返回。**

- 「游标」是**相机侧读流的位置**（相机在本次会话里维护，不是客户端变量；是否按对象独立维护
  未验证）。
- 实际后果：从 0 起逐块顺读**永远对**（此时每步 `offset` 恰好等于游标）；**跳读 / 续传一定错**；
  而且**续读不省带宽**——要让 `offset` 对上相机游标，得先把前一段从 0 读一遍。
- 证据（环境与 HEAD 快照见[已知限制](../../docs/known-limitations.md) 第二节的验证快照）：
  R1 @2 MiB、R2 @8 KiB 判定行 `实际内容位置=0`（#179）；25 次顺序拼接的字节数与 MD5 与整文件
  一致（#67）。

其余既有结论（仍沿用 11-06 记录，未在 #179 复测）：

- 抽样的 4 张 ARW + 1 张 JPEG 首块均成功（**不能推及所有普通对象**；`0xFFFFC001` 未测）。
- 顺序拼接（从 0 逐块递增，共 25 次调用）拼满 24.4 MB 的 ARW，字节数与 MD5 和 `importFile`
  结果一致、文件头是合法 TIFF，吞吐 22.86 MB/s。**它只证明「从 0 顺读」这条路径完整**；
  与 R0–R3 合解才得到上面的 offset 语义，单独一条都不够。
- `length` 超过剩余字节会**截断到 EOF**（剩余 100 字节就返回 100），不抛异常。
- `offset == size` 返回 0；`offset == size + 1` 抛 `IOException: Failed to read data.`。
  **越界后会话是否进入错误态只有首轮观察，本轮未复测 / 未验证**：越界测试固定排在最后，抛异常后
  没有任何后续读取，本轮也**未做会话探活**；该结论留到真机收尾批次复测。
  `offset = size + 1` 是探针有意为之的最后一步，正常续传实现绝不能发出这种请求。
- 分块不要小于 1 MiB（64 KiB 小块单次只有 0.38–0.43 MB/s）。

## 三个坑（都踩过）

1. **相机会在会话结束后约 1 分钟挂起 USB。**
   症状：`MtpDevice.open()` 卡 10 秒，`getStorageIds()` 一直返回空。
   8 次重试仍唤不醒（口径与证据见 [`AGENTS.md`](../../AGENTS.md) §4），**必须拔插 USB 线**。
   正式 App 里要么保持会话常开，要么提示用户重插。

2. **`getObjectHandles(storageId, 0xB101, 0)` 的 format 过滤不可信。**
   0.17 秒就返回 754 个"ARW"，但全量逐个 `getObjectInfo` 只有 511 个是 `0xB101`，
   数字对不上账（疑似返回重复/关联句柄）。**别用 format 过滤**，
   用 `getObjectHandles(sid, 0, 0)` + 逐个 `getObjectInfo`（约 39 ms/个，1526 个要 59 秒），
   或者按 `parent` 逐目录枚举。

3. **别指望现成 App。** Flyme 自带文件管理器没声明 USB host，看不到相机。
   要么装第三方 MTP 文件管理器，要么就用这个探针。

## 正式 App 的最小骨架

```java
UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
UsbDevice dev = /* VID == 0x054C */;
UsbDeviceConnection conn = um.openDevice(dev);
MtpDevice mtp = new MtpDevice(dev);
mtp.open(conn);

int[] sids = mtp.getStorageIds();
int[] handles = mtp.getObjectHandles(sids[0], 0, 0);
for (int h : handles) {
    MtpObjectInfo oi = mtp.getObjectInfo(h);
    if (oi.getFormat() == 0xB101)            // ARW
        mtp.importFile(h, "/sdcard/DCIM/out/" + oi.getName());
}
```

免 root。USB 权限靠 manifest 的 `USB_DEVICE_ATTACHED` intent-filter + `res/xml/device_filter.xml`
自动授予（探针就是这么做的），但**首次插线仍要在系统弹窗里勾一次「一律打开」**，之后静默。
就这样 —— 不需要反协议、不需要 PTP、不需要索尼私有序列。
