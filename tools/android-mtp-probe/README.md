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
5. 对第一张已下载的 ARW 做 `getPartialObject` 语义验证（offset / length 越界 / 普通对象支持面 /
   分块拼接与整文件比 MD5），见下方「PC 探针」一节
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
& "C:\Program Files\Git\bin\bash.exe" tools/android-mtp-probe/build.sh
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

## PC 探针（`getPartialObject`，2026-11-06）

环境：小米 9 / Android 15 (API 35) / ILCE-7C 2.00 / USB MTP（issue #67，#50 的 PC 分节）。

实测结论：

- `offset` 是**绝对偏移**：`offset=0` 连读两次拿到相同字节，且与整文件头部一致。
- 抽样的 4 张 ARW + 1 张 JPEG 首块均成功（**不能推及所有普通对象**；`0xFFFFC001` 未测）。
- 24 次 1 MiB + 末次 457,472 字节（共 25 次调用）拼满 24.4 MB 的 ARW，字节数与 MD5
  和 `importFile` 结果完全一致，文件头是合法 TIFF；吞吐 22.86 MB/s，与 `importFile` 基本同速。
- `length` 超过剩余字节会**截断到 EOF**（剩余 100 字节就返回 100），不抛异常。
- `offset == size` 返回 0；`offset == size + 1` 抛 `IOException: Failed to read data.`。
  **越界后会话是否进入错误态只有首轮观察，本轮未复测 / 未验证**：越界测试固定排在最后，抛异常后
  没有任何后续读取，本轮也**未做会话探活**；该结论留到真机收尾批次复测。
  `offset = size + 1` 是探针有意为之的最后一步，正常续传实现绝不能发出这种请求。
- 因此续传请求必须满足 `0 <= offset <= size`，且 `length <= size - offset`；
  分块不要小于 1 MiB（64 KiB 小块单次只有 0.38–0.43 MB/s）。

## 三个坑（都踩过）

1. **相机会在会话结束后约 1 分钟挂起 USB。**
   症状：`MtpDevice.open()` 卡 10 秒，`getStorageIds()` 一直返回空。
   8 次重试（约 70 秒）唤不醒，**必须拔插 USB 线**。
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
