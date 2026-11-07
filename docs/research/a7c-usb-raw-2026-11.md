# 索尼 a7C + 安卓有线连接传 RAW：可行性研究

日期：2026-11-04 ｜ 对象：ILCE-7C（a7C 一代）

---

## 0. 一句话结论

a7C 上没有任何东西需要"解锁"——它的 USB 口本来就是标准 MTP / MSC / PTP 设备，索尼甚至**免费公开了 PTP 命令集**（Camera Remote Command，ILCE-7C 在支持列表内）。
真正的缺口是：**安卓端没有一个客户端软件去说这套协议**。

**最关键的一条（第 11 节实测得出）：想拿"已经拍好的旧 RAW"就用 MTP，别用 PTP。**
相机切 `USB连接 = MTP` 后，卡里就是一个普通媒体设备，`DCIM/*.ARW` 随便读，
安卓用系统自带 `MtpDevice` API 即可，免 root、免协议逆向。PTP（PC Remote）那条路
**协议层就拒绝枚举卡内文件**（`GetStorageIDs` / `GetObjectHandles` 全 AccessDenied），
只能拿到"最新拍的那一张"。

按代价排序：

| 路线 | 做什么 | 能拿旧 RAW | 传速（实测/估） | 工作量 | 能替代官方 App |
|---|---|---|---|---|---|
| **A. MTP 直连** ⭐ | 相机切 MTP，手机当媒体设备读卡 | ✅ **已真机验证** | **21.4 MB/s**（实测） | 核心 20 行代码 | 翻卡导片可以，遥控不行 |
| **A′. MSC 直拷** | 相机切 Mass Storage，手机当 U 盘读 | ✅ | 略高于 MTP | 安卓侧要 `libaums`（仅 FAT32） | 同上 |
| **B. PC Remote + Termux/gphoto2** | 相机切 PC Remote，手机跑 PTP 客户端 | ❌ **仅最新一张** | 30–80 MB/s | 0.5–1 天 | 基本可以（边拍边传） |
| **C. 自写安卓 App（双模）** | MTP 导旧片 + PTP 遥控 | ✅ | 同上 | 2–6 周 | 完全 |

**如果只想解决"旧 RAW 传不进手机"，选 A（MTP）。** 实测已跑通
（魅族 20 Infinity / Android 15 (API 35) / USB MTP / ILCE-7C 固件 2.00）：
511 张 ARW、11.9 GB 全部可见，三张 24 MP RAW 下载后 MD5 与 PC 侧逐字节一致，
批量 21.4 MB/s（Wi-Fi PTP 只有 0.95 MB/s，约 22 倍）。
可直接用的探针工程在 `tools/android-mtp-probe/`，实测结果见第 12 节。

注意：**这台机器上没有现成 App 能读**（Flyme 文件管理器未声明 USB host），
要么装第三方 MTP 文件管理器，要么用我们的探针。

---

## 1. 你收集的这 15 个仓库，实际能干什么

逐个看过 README 与源码后分类（**没有一个**做 USB PTP）：

### 1.1 Wi-Fi JSON API 系（Camera Remote API，2014–2019 的旧协议）
`kz-remote-api`、`locana`、`sony_camera_api`、`sony-remote`、`sonypy`、`Camera-Remote-API`、`sony-camera-api`、`ofxSonyRemoteCamera`、`SonyCameraRemoteAPI`、`RemoteCamera`、`RemoteCam`

- 全部走 `http://192.168.122.1:8080/sony/{camera,avContent}` 这套 JSON 接口
- `kz-remote-api` / `locana` 是最完整的两个（SSDP 发现 + 全量 action 封装），但都是 C#/UWP，Windows 端
- **致命点：这个协议在 AP（手机直连相机）模式下只暴露 JPEG。** `getContentList` 返回的 `content.original[].stillObject` 只有 `jpeg`，`url` 指向 `/contentstransfer/orgjpeg/...`。改扩展名、拼路径都拿不到 ARW——文件根本没通过这个服务暴露。
- 社区结论一致（StackOverflow 上 2014 年问到现在）：AP 模式 = 只有 JPG；RAW 只能走 USB / PTP-IP 基础设施模式。

### 1.2 BLE 系
`alpharemote`（安卓，通知栏快门）、`furble`（ESP32 硬件遥控）、`sony_camera_ble_remote`（抓包 XML）

- 只能发按键、收极少量状态（对焦/快门/录制）。`alpharemote` README 明确写：*"There is no way to get a preview of the image, transfer the image or just get the camera's settings."*
- 对传图零帮助。但 `alpharemote` 的安卓工程（Kotlin + CompanionDeviceService）可作为路线 C 的安卓工程骨架参考。

### 1.3 桌面端
`locana`（Win10 UWP）、`RemoteCam`（Win/mac，.NET + Avalonia，Wi-Fi 走索尼 API，A7S III 实测 20fps 取景）

- 和你"手机上用"的目标不重合。`RemoteCam` 的 `native/CamBridge` 里有一套 CP/协议封装，值得读但不解决 USB。

### 1.4 空目录
`alpha-fairy` 是空的。

**结论：这批仓库能教你索尼相机的 Wi-Fi/BLE 协议长什么样，但没有一个能解决你的需求。要 USB，得换技术栈。**

---

## 2. 为什么官方 App 死活传不了 RAW（协议层原因）

> **勘误（2026-11-04 追加）**：本节原写"Wi-Fi 拿不到 RAW"，这只对**手机端那条路径**成立。
> a7C 的 `PC Remote` 支持 `Wi-Fi Direct / Wi-Fi Access Point`，走的是 **PTP/IP**（不是老的 JSON API），
> 并有 `RAW+J PC Save Img = RAW & JPEG` 选项——**IED 在 Wi-Fi 下确实能过 RAW**。
> 详见第 10 节。真正传不了 RAW 的只有"手机 App + Camera Remote API"这条链。

两个死结，任选其一都无解：

1. **手机端的 Wi-Fi 便携遥控（Ctrl w/ Smartphone）走的是旧的 Camera Remote API**，它的内容服务只挂 JPEG 原始文件和缩略图。RAW 永远不在可下载列表里。
   （注意：这条只约束手机 App。PC 端的 `PC Remote` 是另一套协议栈，见第 10 节。）
2. **一插 USB，相机就切走了**。USB 连上会终止 Wi-Fi 遥控会话（索尼设计如此），所以"Wi-Fi 控制 + USB 拷文件"这种组合不存在。

外加两个现实约束：
- **Creators' App 不支持 a7C 一代**（支持列表里只有 α7C II / α7CR），a7C 只能继续用 Imaging Edge Mobile。所以"换个官方新 App 就好了"这条路不存在。
- **a7C 没有 PlayMemories Camera Apps**，不能往机身里装第三方插件（老 a7 II 那批机型才可以）。传闻中的"解锁相机内 App"在 a7C 上不成立。

---

## 3. 那"解锁"到底指什么

把"锁"拆开看，其实只有一道是真的：

| 你以为是锁 | 实际状态 |
|---|---|
| 相机 USB 被锁，只认官方软件 | ❌ 不存在。a7C 是标准 PTP/MSC 设备，任何主机都能枚举 |
| 需要破解/越狱相机 | ❌ 不需要，也没人做出来过（a7C 加密签名固件） |
| 需要 root 手机 | ❌ 不需要。Android USB Host API 免 root 可用 |
| **索尼 PTP 扩展命令不公开** | ✅ **曾经是，现在不是**。2024-04 索尼免费公开 **Camera Remote Command**，PTP 命令参考 + 示例代码，ILCE-7C 明确在列，接口写的是 **USB**，且注明"Supported OS: No specific requirements"——文档里直接提到可以做到 iOS/Android 上 |

**所以这是一道"客户端缺失"的墙，不是"权限"的墙。** 有 Linux/Windows 的现成实现（gphoto2、Imaging Edge Desktop），安卓上没有，仅此而已。

参考资料入口：
- Camera Remote Toolkit（SDK + Command 免费下载）：`https://support-d-imaging.sony.co.jp/app/sdk/en/index.html`
- Camera Remote Command 说明页（日文，含 PTP 定位与安卓/iOS 可行性表述）：`https://www.sony.jp/camera-biz/command/`

---

## 4. 路线 A：MSC 直拷（零开发，建议先做）

原理：a7C 的 USB 模式里有 **Mass Storage**，此时机身就是一块 U 盘。现代安卓原生支持挂载 USB 大容量存储，文件管理器（含系统"文件"App）能直接浏览 `DCIM/100MSDCF` 里的 `.ARW`。

这不是野路子——**索尼自己的 Transfer & Tagging 官方文档就是让用户这么干的**，还给了老机型（含 a7C 这一代菜单）的确切设置步骤。

### 相机端设置（a7C 菜单名可能略有差异）

1. `MENU → 网络 → Ctrl w/ Smartphone → 关`（必须先关，否则 USB 不认）
2. `MENU → 设置 → USB → USB Connection → Mass Storage`
3. `MENU → 设置 → USB → USB LUN Setting → Single`
4. `MENU → 设置 → USB → USB Power Supply → 关`
   - 关掉是**让手机不给相机供电**，省手机电；反过来说如果你手机电量充足、想给相机续命，可以开，但会明显掉电。
5. 插线，相机屏幕提示时选 `Mass Storage`

### 手机端

- 插上后通知栏会出现"USB 驱动器 / Sony USB Drive"，点进去，或用任何支持 SAF 的文件管理器打开 `DCIM/` 复制。
- 想在手机上直接看 ARW：装 **GalleryRAW**（Play 商店，支持 OTG 直读 .ARW，读嵌入的 JPEG 预览做筛选）。这是目前最接近你要的"筛片 + 导出"体验的工具。

### 现实预期

- a7C 是 **USB 3.2 Gen1 5Gbps** 接口，卡是 UHS-I，瓶颈在卡和相机的 USB 实现上，实际多在 **30–80 MB/s**（估，取决于卡）。对比 Wi-Fi 的 1–3 MB/s，是一到两个数量级的差距。
- **≥64GB 的卡必须是 exFAT**，否则手机挂载会失败（这是索尼文档明写的条件）。
- MSC 模式下**不能拍照**。要边拍边拷，走路线 B。

---

## 5. 路线 B：PC Remote + 手机跑 PTP 客户端（性价比最高）

### 原理

`USB Connection → PC Remote`：相机进入 PTP "远程拍摄"模式，此时索尼的 PTP 扩展命令全部可用——取景、曝光参数、快门、以及**把刚拍的文件（含 ARW）从相机里取出来**。Imaging Edge Desktop 在电脑上做的就是这件事，gphoto2 也能做（`--list-files` / `--get-all-files` / `--capture-image-and-download`）。

安卓上没有现成 App，但可以在 **Termux** 里跑 gphoto2，且**不需要 root**：

- `termux-usb` 会向系统申请设备的 **文件描述符**（这是安卓唯一的免 root USB 访问途径）
- libusb 用 `libusb_wrap_sys_device(ctx, fd, &handle)` 接管这个 fd
- 已知有人成功在非 root 的安卓 7 上编译并跑通 libgphoto2 + gphoto2 命令行（见 Termux 仓库 issue #4902）。gphoto2 的 CLI 默认只走 `libusb_open()`，需要一行补丁让它接受 fd 参数（同 issue 里给了 patch 范例）

### 大致步骤（可行性验证用，不是成品）

```bash
# Termux
pkg install termux-api libusb clang make pkg-config autoconf automake libtool libjpeg-turbo
# 拉 libgphoto2 2.5.3x 源码，打 -usb-fd 补丁（改成 libusb_wrap_sys_device）
./configure --prefix=$PREFIX --without-camlibs-extra && make -j8 && make install

# 相机设为 PC Remote，插 OTG 线
termux-usb -l                       # 找到相机的 /dev/bus/usb/00x/00y
termux-usb -r /dev/bus/usb/001/002  # 弹窗授权
termux-usb -e "./gphoto2 --usb-fd --list-files" /dev/bus/usb/001/002
termux-usb -e "./gphoto2 --usb-fd --get-all-files --skip-existing \
  --filename $HOME/storage/pictures/a7c/%f.%C" /dev/bus/usb/001/002
```

### 前置条件与坑

- **相机保存目标设成 `PC+Camera`**（`MENU → 网络 → PC Remote Settings → Save Destination`）。设成 `PC Only` 时照片只存在相机内存里，必须同步取走，大 ARW 会拖慢连拍节奏；`PC+Camera` 是"卡上一份 + 可取一份"，最稳。
- 相机侧 `PC Remote` 要打开（部分机型是 `PC Remote Function → On`）。
- 手机 OTG 供电有限，长会话建议相机另接电源或用带 PD 的 OTG 分线器。
- PC Remote 期间相机不能回放、菜单受限，但**可以正常拍照**，这正是"边拍边传"要的。

---

## 6. 路线 C：自己写安卓 App（想彻底替换官方 App 才做）

### 架构

```
Kotlin App
 ├─ UsbManager / UsbDeviceConnection  (免 root)
 ├─ PTP initiator  (ISO 15740)
 │   ├─ OpenSession / GetDeviceInfo / GetObjectHandles / GetObject / GetPartialObject
 │   └─ Sony 扩展 opcode + DeviceProp 编解码
 ├─ 前台 Service + WorkManager (后台长传)
 └─ SAF / MediaStore 落盘，MediaMetadataRetriever 取预览
```

### 现成积木

| 用途 | 项目 | 说明 |
|---|---|---|
| 协议文档（权威） | **Sony Camera Remote Command** | 免费，ILCE-7C 在列，含命令参考 + 示例代码。这是你唯一的"官方许可"依据 |
| C 语言 PTP 实现 | **camlib**（petabyt） | Android/Kotlin 原生后端已工作，设计目标就是安卓 App（作者做过 CamControl）；Sony 支持在路线图上，**未完成** |
| Java 版 PTP 骨架 | **Android_USB_PTP_Lib**（terencehonles） | 2012 年的老代码，纯 Java 走 USB Host，Canon/Nikon 验证过。拿来做 initiator 的骨架很好，Sony 部分要自己写 |
| 索尼 PTP 逆向成果 | **libgphoto2 的 `camlibs/ptp2/`** | 索尼扩展 opcode、属性表、事件表都在里面，直接翻译成 Kotlin 是最快路径 |

### MVP 里程碑建议

1. **M1**：`GetDeviceInfo` 成功，认出 ILCE-7C（验证 USB 权限、端点、PTP 会话）
2. **M2**：`GetObjectHandles` 列出卡内对象，识别 ARW（`ObjectFormat` 0x3814 / 索尼自定义）
3. **M3**：`GetPartialObject` 分块下载一个大 ARW，进度条 + 断点续传
4. **M4**：拍照触发 + `ObjectAdded` 事件监听，实现"拍完自动传"
5. **M5**：取景（索尼 liveview 是私有 opcode，返回 JPEG 流，帧率约 15–30fps）——这一步最难，可以最后做

---

## 7. 三个必须知道的硬约束

1. **a7C 是 UHS-I 卡槽**，哪怕 USB 3.2，也上不了 200MB/s。想再快只能换机身（a7 IV 起 UHS-II）。
2. **MSC 与拍摄互斥，PC Remote 与 Wi-Fi 遥控互斥**。想"边拍边传"，只能 USB + PC Remote。
3. **没有任何方案能让 a7C 在 Wi-Fi 下传 RAW**，包括官方。这是协议设计如此，不是 App 偷懒。

---

## 8. 建议执行顺序

1. **今天**：路线 A 跑一遍，确认你手机能挂载 MSC、能复制 ARW、测出实际 MB/s。这一步就能立刻解决"RAW 进不了手机"。
2. **这周**：如果还需要遥控，按路线 B 在 Termux 里把 gphoto2 打通（重点验证 `--usb-fd` 补丁 + `GetObject` 能取 ARW）。
3. **想长期替代官方 App**：路线 C，从 camlib 或 `Android_USB_PTP_Lib` 起步，先做 M1–M3，把"有线批量拉 RAW"做成 App，遥控后置。

---

## 9. 补充评估：能不能用 REA 逆固件、直接给机身加功能（2026-11-04 追加）

### 9.1 结论

**能逆，但"加功能"这条路在 a7C 上不通；而且你的需求根本不需要动固件。**

不是"破解难度大"的问题，是**三件事同时不成立**：

| 关卡 | a7C 实际状况 |
|---|---|
| ① 解包固件 | 官网给的 `BODYDATA.DAT` 是分块加密的（FDAT 块 + IV），头部有型号/版本校验。公开资料里没有 a7C 这批的密钥 |
| ② 反编译 | 可以做。arm64 裸机 + 部分 Linux 组件，Ghidra 能读。但几百万行、无符号表，AI 辅助也只是加速 |
| ③ 打补丁 | **这一关才是死点**：机内 updater 用 RSA-2048 验签（RSASSA-PKCS1-v1_5 + SHA-256），覆盖整个加密流。私钥在索尼手里，改一个字节就验不过 |
| ④ 装"插件" | **a7C 没有应用运行时**。PlayMemories（PMCA / SPK 包 / Android 容器）在 a7 III 之后就被砍了，a7C 没有。所以不存在"装个 App 进去"这回事——只能硬改固件里的 USB/PTP 服务代码 |

第 ④ 点最容易被忽略：你要的功能在别的机型上可能"加个插件"就行，在 a7C 上意味着**在裸机固件里手改协议栈的机器码**。

### 9.2 已知的公开成果与边界

- **PMCA-RE / OpenMemories 系列**：只覆盖有 PlayMemories 的老机型（a7 II、a6000/6300/6400、RX100 IV/V 等）。其支持矩阵明确写着新平台（CXD90045 / CXD90057 一类）"因固件加密签名机制暂不支持"。
- **A7 IV 快门角度**（2024，Reddit + SAR 报道）：个人级突破，作者声称用路径穿越漏洞拿到了完整解密固件并改了功能。但**没有公开工具链**、绑定特定固件版本、作者自己强调变砖风险极高。属于"证明理论上能行"，不是"可复现的路径"。
- **A7C II 解锁 FL2/FL3**（2026-10 中文科技媒体大量报道）：只有媒体图文，GitHub 上**没有任何可验证代码**。而且那是 a7C **II**（BIONZ XR 平台），与你的 a7C 一代不是同一套固件。
- **索尼 a7C 一代**：目前没有任何公开的固件修改成果。

一句话：**能看见，改不动；改动了也刷不进去；刷进去了也保修作废、且随时可能变砖。**

### 9.3 REA 这个工具链本身

`rea`（morluto/rea，MCP 形态的逆向编排器）能提供的是：

| 能力 | 对应 REA 模块 | 对本需求有用吗 |
|---|---|---|
| 固件区域识别/解包 | `inspect_firmware_regions` / `extract_firmware`（Binwalk 3.1 + Unblob 26.6） | 只能处理**未加密**容器。索尼的 FDAT 加密块解不开，命中率极低 |
| 原生二进制反编译 | Ghidra / IDA / Hopper 桥 | 有用，但要先有解密后的镜像 |
| Android APK 分析 | jadx / apktool / Frida | **最有用**（见 9.4） |
| 抓包 | pcap / USB 流量 | **有用**（见 9.4） |
| GDB 动态调试 | `start_gdb_session` 等 | Windows 不支持，只在 Linux 上可用 |

两个环境限制：**固件解包里程碑只在 Linux x64 验证过**，GDB 会话也只在 Linux 可用。你在 Windows 上，这部分要么 WSL，要么换机器。

### 9.4 正确的用法：逆两端，不碰机身

你的目标是"让手机能通过 USB 拿 RAW"，这件事的未知量**全在客户端侧**，不在机身固件里。机身已经会了（Imaging Edge Desktop 能取 ARW 就是证明）。所以该逆的是：

1. **Imaging Edge Desktop（Windows x64）** — 它内部调用 CrSDK 的 DLL。用 REA 的 native 分析 + 抓包，看它 USB 握手后发哪几条 opcode、按什么顺序取文件。产出：**a7C 的 PTP 时序说明书**。
   - 但先提醒：CrSDK 附带的 **Camera Remote Command 文档是免费公开的**，80% 的答案你已经有了，不用逆。
2. **Imaging Edge Mobile APK** — 用 jadx 看它为什么只提供 JPEG：是它主动过滤，还是协议层就没有。产出：**确认死结在哪一层**（我判断是协议层，`getContentList` 的 `stillObject` 只有 `jpeg`）。
3. **USB 抓包对比** — 同一台相机，分别用"官方 App + Wi-Fi"和"Imaging Edge Desktop + USB"操作，对比报文。产出：**USB 路径下取 ARW 的最小命令序列**。

这三件事**都不接触机身、不变砖、不违反保修**，而且产出直接喂给路线 C（自写安卓 App）的 M1–M3。

真正只有 RE 才能给的东西：索尼私有 liveview opcode、部分未公开 DeviceProp 编号、`PC Remote` 下的隐藏保存选项。而这些 **libgphoto2 的 `camlibs/ptp2/` 里基本都有**，读源码比逆二进制快一个数量级。

### 9.5 如果还是要动固件

成本量级：需要 ①一个未修复的固件校验漏洞（等价于 0-day）②完整的加解密逆向 ③重打包工具 ④变砖可恢复的手段（JTAG/编程器，a7C 需要拆机）。
预期产出：能改出厂参数/解锁被阉割功能；**但"新增 USB 传 RAW"这件事机身本来就会，改了也没有增量**。
结论：投入产出比极差，风险不可逆（保修作废 + 可能报废一台机身）。不建议。

---

## 10. 从本机 IED 入手：已确认的分层与可执行方案（2026-11-04 追加）

### 10.1 先确认一件事：a7C 确实能"Wi-Fi 过 RAW"

`MENU → 网络 → PC Remote Function` 在 a7C 上有：

- `PC Remote Cnct Method`：**USB / Wi-Fi Direct / Wi-Fi Access Point**
- `Still Img. Save Dest.`：PC Only / PC+Camera / Camera Only
- `RAW+J PC Save Img`：**RAW & JPEG / JPEG Only / RAW Only**
- `PC Save Image Size`：Original / 2M

也就是说，`PC Remote` 这条线在 Wi-Fi 下用的是 **PTP/IP（TCP 15740）**，不是老的 JSON `Camera Remote API`，因此 RAW 完全可以直接过。之前"Wi-Fi 只能传 JPG"的结论只适用于手机端那条 `Ctrl w/ Smartphone` 链路。

**这反过来说明一个更重要的事：PTP 协议本身与传输层无关。** 同一套命令走 USB、走 Wi-Fi 都一样，这正是安卓端的实现捷径。

### 10.2 IED 安装目录给出的现成架构图

```
C:\Program Files\Sony\Imaging Edge\
  LjCore.dll                 PTP 编解码核心（C++ 类接口，导出仅 LjCore / LjCommandData，符号已剥）
  LjAdapter\
    Lja_PTP_USB.dll          USB 传输（libusb → Driver\amd64\libusbK.sys）
    Lja_PTP_IP.dll           Wi-Fi 传输（PTP/IP，TCP 15740，另带 libssh2）
    Lja_PTP_WIA.dll          Windows WIA 路径
    libusb-1.0.dll
  Driver\amd64\libusbK.sys   相机被 libusbK 接管 → 所以资源管理器里没有"图像设备"
  Strings.xml                设备属性词表（RAW_AND_JPEG / RAW_FILE_TYPE / PC Save Image Size…）
```

适配层是 `sony_lajiao::IDeviceConnection`（内部代号"辣椒"，符号里还留着
`C:\Jenkins_git\IE\SharedLibraries\LaJiao\...` 的 PDB 路径），导出
`EnumCameraObjects / Connect / CancelConnection / GetCameraObjectInfofromDetector / GetConnectionTypeName`。

**结论：一套 PTP 核心 + 可插拔 transport。安卓端照这个结构写就对了**，
而且意味着**先做 PTP/IP 比先做 USB 便宜得多**（纯 TCP socket，不要 USB 权限、不要 USB Host 那套描述符/端点/超时处理）。

### 10.3 三条入手路径（按成本排序）

| # | 做什么 | 成本 | 产出 |
|---|---|---|---|
| A | **tshark 抓 PTP/IP**（Wi-Fi Direct） | 1 小时 | 完整事务流水，含取 ARW 的 opcode 序列与真实字节数 |
| B | USBPcap 抓 USB 侧 | 2 小时 | 确认 USB 与 IP 的 opcode 序列是否一致（通常只差封装） |
| C | API Monitor 挂 `Lja_PTP_*.dll` 的三个导出 | 半天 | 确认 IED 选了哪条 transport、参数怎么传 |

A 是唯一必要的，且是纯被动观察：不碰固件、不改相机、不影响保修。
工具已经放在 `tools/sony-ptpip/`：

- `README.md`：相机设置、抓包命令、注意事项
- `parse_ptpip.py`：把 pcap 翻译成 `REQ/RSP/DATA` 流水，自动算出每个对象的字节数（据此区分 ARW 与 JPEG）

判别技巧：`DATA start ... total=` 的数值就是文件大小。24MP 未压缩 ARW ≈ 48–50 MB，
JPEG 几 MB，一眼就能确认 IED 取的是哪种。

### 10.4 对原方案的修正

原第 5、6 节的路线需要调换顺序：

- **新路线 B′（推荐先做）：PTP/IP 安卓客户端**。Wi-Fi 5GHz 下估 5–15 MB/s，
  比旧 JSON API 的 1–3 MB/s 高一个档，且**能拿 RAW**，实现难度最低。
- **路线 C 的 USB transport 后置**：等 PTP 编解码逻辑跑通，再把 transport 换成 USB Host 即可，
  代码结构完全对应 IED 的 `LjAdapter`。
- 纯速度考虑仍以 MSC 直拷（路线 A）最快，但它不能边拍边传。

### 10.5 一个容易踩的坑

`PC Save Image Size = 2M` 时，RAW 会被直接丢弃并在 IED 里提示
"RAW 格式的图像将不传输，因为 [电脑保存影像尺寸] 设置为 [2M]"。
这条文案就写在 `Strings.xml` 里，说明是客户端逻辑而非相机限制。
自己写客户端时同样必须显式请求 Original 尺寸。

---

### 10.6 实测结果（2026-11-04 晚，已抓包验证）

用 a7C + 家庭路由器（Wi-Fi Access Point 模式）+ IED 实测一遍，148 MB 抓包解析完毕。
详细流水见 `docs/research/a7c-ptpip-session.md`。三条重要修正：

1. **a7C 不走 SSH。** 全流程 0 个 22 端口连接，`DigitalImagingDesc.xml` 里没有
   `X_SSH_Support` 字段。10.3 里从 `Lja_PTP_IP.dll` 推出的 libssh2 隧道只适用于其他机型。
   **安卓端不需要 libssh2、不需要复现 pairing 逻辑**，纯 TCP + PTP/IP 即可。
2. **协议是标准 PTP/IP，TCP 15740**，发现走 UPnP/SSDP + HTTP 64321
   （`/dd.xml`、`/DigitalImagingDesc.xml`，后者含 `X_PTP_PairingNecessity=Necessary`、PTP 3.00）。
3. **取文件用 `GetPartialObject`(0x101B)，每次 1 MB**，offset 作为入参 → 断点续传免费。
   实测 ARW ≈ 23.6 MB（压缩 RAW）、JPG ≈ 0.6–0.9 MB，文件名 `DSC0000x.ARW`。
   实时取景是 `GetObject(0xFFFFC002)` 轮询；新拍文件句柄固定为 `0xFFFFC001`。
   事件走**第二条 PTP/IP 连接**（`Init_Event_Request`），索尼私有事件码 `0xC2xx`。

实测速度 ≈ 0.95 MB/s（含取景并发）。所以：**协议已经全部摸清，安卓端可以直接照着实现；
速度仍受 Wi-Fi 限制，要快还得换 USB transport，但载荷完全一样。**

---

## 11. 决定性实验：能不能下载"已经拍了的老照片"（2026-11-04 深夜）

10.6 那套流程只能拿**新拍的**照片——因为 IED 从头到尾只用伪句柄 `0xFFFFC001`
指向"最后一张"。用户真正要的是**卡里已有的旧 RAW**。这一节是直接写探针连相机做出来的结论，
不是推测。

工具：`tools/sony-ptpip/probe.py`（纯 socket、只读操作、不发任何写命令）。

### 11.1 相机的握手有"配对"这道门

这一条是踩了一晚上才确认的：

| 发送的 GUID | 结果 |
|---|---|
| 我随机生成的 UUID | `Init_Fail` **type=5 reason=1**（被拒），连试 6 次、间隔 4 秒，全部被拒 |
| IED 当初配对用的 GUID `<已配对的 GUID>` | 立刻 `Init_Command_Ack`，相机报 `ILCE-7C` |

**相机把 GUID 当身份记着，不是随便谁都能连。** 想出 `<已配对的 GUID>` 是因为
抓包里 IED 发的就是这个（它是 IED 自己 UUID 截断到 16 字符的结果）。
真机部署时这个值要改成"本机 IED 首次配对时用过的 GUID"，或者想办法让相机重新配对。

握手 payload 格式（44 字节，抓包帧 322 实测）：

```
+0   16B  GUID 的 ASCII 文本（必须 16 字节，相机按 16 字节对齐去解析后面的名字）
+16  nB  发送方名称 UTF-16LE（含 \0\0 结尾）
+?   4B  版本 u32 = 0x00010000
```
**注意没有 PTP 规范里的 1 字节长度前缀**——加上去相机会直接断连。

### 11.2 事件通道必须先建，否则 OpenSession 永远超时

之前一直卡在"握手成功但 `OpenSession` 没有任何响应，只超时"。原因找到了：
IED 的真实顺序是（帧 319→331 的时间戳）：

```
t=124.23  stream7 SYN              ← 命令通道
t=124.33  Init_Command_Request
t=126.23  Init_Command_Ack          （相机想了 2 秒）
t=126.22  stream8 SYN              ← 事件通道，第二条 TCP
t=126.24  Init_Event_Request        body = 4 字节 connection number
t=126.24  Init_Event_Ack
t=126.25  OpenSession(1)  ← 到这里才发
```

补上事件通道 + 把 `dataPhase` 位照抄 IED 的 **1**（不是 0）之后：

```
[1] 命令通道握手 OK → 相机 'ILCE-7C'  connection=859057508 (0x33342D64)
[2] 事件通道已建立（第二条 TCP，body=connection number）
[3] OpenSession(1) → OK
```

**`OpenSession` 不响应 ≠ 被拒绝，而是它在等事件通道注册完成。** 这个坑文档里没有。

### 11.3 但枚举卡内文件被硬性拒绝 —— 这就是终点

补上索尼私有引导序列（`SDIOConnect` 1/2/3 + `GetExtDeviceInfo[300]` ×2，
返回的 8 字节数据与抓包里 IED 完全一致，都是全零，说明引导本身成功）：

```
SDIOConnect[1, 0, 0] → OK data=8B
SDIOConnect[2, 0, 0] → OK data=8B
GetDeviceInfo[0]     → OK data=303B
GetExtDeviceInfo[300]→ OK data=260B
SDIOConnect[3, 0, 0] → OK data=8B
GetExtDeviceInfo[300]→ OK data=260B
```

然后逐条试枚举路径，**全部 AccessDenied**：

| 调用 | 结果 |
|---|---|
| `GetStorageIDs` | **AccessDenied** |
| `GetNumObjects(storage=0xFFFFFFFF)` | **AccessDenied** |
| `GetObjectHandles(storage=0x0)` | **AccessDenied** |
| `GetObjectHandles(storage=0xFFFFFFFF)` | **AccessDenied** |
| `GetStorageInfo(0x00010001)` | **AccessDenied** |
| `GetObjectInfo(0xFFFFC001)` | OK（能拿到 ObjectInfo，但只有"最新一张"） |

注意 `GetDeviceInfo` 明确报告相机**支持** `0x1004` 和 `0x1007`
（32 个 opcode 里有），所以不是"没实现"，是**当前会话类型不允许调用**。

**结论：PC Remote 这一种 PTP 会话在协议层就不开放存储枚举。**
IED 从来没用过 `GetObjectHandles`，它只需要 `0xFFFFC001` 就够了——
所以"索尼官方不给传老照片"不是 App 的偷懒，是它走的那条会话本身没有这个能力。

顺带修掉一个旧 bug：`ObjectInfo` 的宽高偏移之前写成 28/32，正确是 **26/30**。
文件名长度字节在 **52**、文件名从 **53** 开始（按 PTP 规范累加应该是 48，
索尼多塞了 4 字节，两处独立验证都指向 52）。布局：

```
0 StorageID u32 | 4 ObjectFormat u16 | 6 ProtectionStatus u16
8 ObjectCompressedSize u32 | 12 ThumbFormat u16 | 14 ThumbCompressedSize u32
18 ThumbPixWidth u32 | 22 ThumbPixHeight u32
26 ImagePixWidth u32 | 30 ImagePixHeight u32
34 ParentObject u32 | 38 AssociationType u16 | 40 AssociationDesc u32
44 SequenceNumber u32 | 48（索尼 4 字节填充）| 52 文件名长度 u8 | 53 文件名 UTF-16LE
```

还有一个坑：相机**空闲时**（本次会话没拍过照）`GetObjectInfo(0xFFFFC001)`
返回 `OK` 但数据长度为 0，不是错误码。写客户端不能只看返回码，必须判长度。

### 11.4 那旧照片到底怎么拿？—— 换模式，不要换协议

索尼官方手册（ILCE-7C 帮助指南）写明 `MENU → 设置 → USB连接` 有四个选项：

| 模式 | 相机变成什么 | 能读旧 RAW 吗 | 安卓可用性 |
|---|---|---|---|
| **PC遥控 (PC Remote)** | PTP 设备，仅暴露待拍/新拍 | **不能**（11.3 已验证） | 要自己写 PTP 客户端 |
| **MTP** | MTP 媒体设备，`DCIM/100MSDCF/*.ARW` 是普通文件 | **能** | **Android 原生 `MtpDevice` API，免 root** |
| **海量存储器 (MSC)** | U 盘（FAT32/exFAT） | **能** | Android 无原生挂载，需 `libaums`（仅 FAT32） |
| 自动 | Windows 给 MTP，其它看情况 | 能 | 不可控，建议显式设 |

所以正确的结论是：

- **要传"已经拍好的"照片 → 用 MTP，别用 PTP。** 相机在 MTP 模式下把卡挂成媒体设备，
  ARW 就是一个普通文件，安卓用系统自带的 `android.mtp.MtpDevice`
  （`UsbManager` → `ACTION_USB_DEVICE_ATTACHED` → `openDevice()` → `getObjectList()`）直接读，
  不需要 root、不需要反协议、不需要任何索尼私有序列。市面上任何支持 MTP 的文件管理器
  （Total Commander + MTP 插件、USB Media Explorer 等）装上就能直接拷。
- **要"边拍边传" → 用 PTP/IP**（第 10 节、11.2–11.3 节已验：可握手并取 `0xFFFFC001`；
  同一会话下存储枚举 `AccessDenied`），但只能拿最新一张。
- **两者互斥**：`USB连接` 同一时刻只能一个模式。所以一个完整的替代 App 应该做成
  双模：MTP 模式负责翻卡导旧片，PC Remote 模式负责拍摄+即时回传。

这也解释了为什么用户觉得"官方 App 很废"——官方把这两件事拆在
Creators' App（MTP/JSON）和 IED（PTP）两个软件里，而 a7C 一代连 Creators' App 都不支持。

### 11.5 速度预期

- Wi-Fi PTP/IP 实测 ≈ **0.95 MB/s**（24MP 压缩 RAW 约 23.6 MB，一张要 ~25 秒）
- USB MTP：a7C 的 USB-C 为 USB 3.2 Gen1，实际瓶颈在机身读卡与 MTP 协议，
  通常落在 **20–40 MB/s** 量级（一张 RAW 不到 1 秒），比 Wi-Fi 高一个数量级以上
- USB MSC 若卡是 FAT32 会更快一点，但安卓侧要自己实现 SCSI/FAT 解析，不划算

---

## 12. MTP 路线实机验证：全部跑通（2026-11-04 22:00，真机 + ADB）

第 11.4 节的判断在**真机**上验证完毕。测试条件：魅族 20 Infinity / Android 15 (API 35)，
OTG 直连 a7C，相机 `USB连接 = MTP`。探针工程见 `tools/android-mtp-probe/`。

### 12.1 USB 枚举

手机侧 `dumpsys usb` 与 App 内 `UsbDevice` 均确认：

```
VID=0x054C (Sony)  PID=0x0D2A  product_name=ILCE-7C  iSerial=<机身序列号>
接口 class=6 / subclass=1 / protocol=1   ← Still Image (PTP/MTP)
端点  0x81 bulk IN、0x02 bulk OUT、0x83 interrupt IN   ← 标准 MTP 三端点
data_role=host，current_mode=dfp
```

系统声明了 `android.hardware.usb.host` 特性，手机可以当 USB 主机。

### 12.2 会话与容量

```
MtpDevice.open() 成功
设备: Sony Corporation / ILCE-7C / ver=2.0 / 支持 24 个操作
存储 0x00010001 "Storage Media"  容量 59.4 GB  可用 46.5 GB
对象总数 = 1526
```

**关键：MTP 会话里 `GetStorageIDs` / `GetObjectHandles` 全部正常**，
与第 11.3 节 PTP PC Remote 会话的 AccessDenied 形成对照 —— 同一台相机，
换模式就通了。这一条直接证实了"不是相机不给，是会话类型不给"。

卡内构成（逐个 `getObjectInfo` 统计，1526 个全覆盖）：

| 类型 | 数量 | 说明 |
|---|---|---|
| 文件夹 | 503 | |
| **ARW** | **511** | 合计 **11.9 GB**，6000×4000 |
| JPEG | 511 | 3008×2000（机内缩略尺寸） |
| MP4 | 1 | `C0001.MP4`，128.01 MB，fmt=0xB982 |

### 12.3 速度与完整性（三张 24 MP RAW）

用 `MtpDevice.importFile()`（系统自带的 MTP 分块导入，等价于任何 App 的做法）：

| # | 文件 | 大小 | 耗时 | 速率 | 大小校验 |
|---|---|---|---|---|---|
| 1 | DSC02183.ARW | 24.44 MB | 1.19 s | **20.62 MB/s** | ✅ 一致 |
| 2 | DSC02185.ARW | 24.43 MB | 1.03 s | **23.67 MB/s** | ✅ 一致 |
| 3 | DSC02184.ARW | 24.42 MB | 1.03 s | **23.72 MB/s** | ✅ 一致 |
| — | 批量合计 | 73.29 MB | 3.42 s | **21.43 MB/s** | |

完整性三重校验，全部通过：

1. **字节数**：`getCompressedSizeLong()` 报的值与实际落盘字节数逐字节相等
2. **MD5**：手机侧算的与 pull 到 PC 后算的完全一致
   - DSC02183 → `060f09ec9eb37139060a9f49c52c98a5`
   - DSC02184 → `3fdcd4b85589010f113b4077490d3d37`
   - DSC02185 → `dc8a9b9310b619140f6cc42f5717903b`
3. **文件头**：`49 49 2A 00 08 00 00 00 16 00 FE 00 04 00 01 00` → `II*\0`，合法 TIFF/ARW 容器

**速度对比：Wi-Fi PTP 实测 0.95 MB/s → USB MTP 21.4 MB/s，约 22 倍。**
11.9 GB 全卡导出：Wi-Fi 约 3.5 小时，USB MTP 约 9.5 分钟。

### 12.4 枚举性能：一条必须避开的坑

| 方式 | 耗时 | 结果 |
|---|---|---|
| `getObjectHandles(sid, 0, 0)` + 逐个 `getObjectInfo` | **≈ 59 秒**（1526 个，约 39 ms/个） | 结果准确，511 张 ARW 全部正确 |
| `getObjectHandles(sid, 0xB101, 0)`（相机侧按格式过滤） | **0.17 秒** | 返回 754 个，**数字不可信** |

format 过滤看起来很美（0.17 秒 vs 59 秒），但实测对不上：

- 返回 754 个句柄，且全部包含在全量 1526 个之内（0 个越界）
- 抽样 42 个，`getObjectInfo` 全部报 `0xB101`
- 但全量逐个统计只有 **511** 个是 `0xB101`
- 差集：全量里有 772 个不在过滤结果里，可 503 文件夹 + 511 JPEG + 1 MP4 = 1015，**对不上账**

结论：**索尼对 `GetObjectHandles` 的 format 参数的解释与 PTP 规范不同（疑似返回重复/关联句柄），不要依赖它。**
生产实现应走 `getObjectHandles(sid, 0, 0)` + 逐个 `getObjectInfo`（全量枚举路径已验；
format 过滤不可信），
需要更快就按 `parent` 逐目录枚举（`getObjectHandles(sid, 0, dcimHandle)`），
而不是用 format 过滤。

### 12.5 两个实际约束

**① 相机会在会话结束后约 1 分钟挂起 USB。**
表现为 `MtpDevice.open()` 拖 10 秒才返回、`getStorageIds()` 一直返回空。
我加了 8 次重试（每次间隔 3 秒、共约 70 秒）**依然唤不醒**，
必须**拔插 USB 线**才能恢复。重试口径以 [`AGENTS.md`](../../AGENTS.md) §4 为准；本段保留当时记录的间隔与总时长。

- 写 App 时要**一直保持会话不关**（别做完一次就 `close()`）
- 或者在界面里明确提示"重插数据线"
- 相机端可延长自动关机时间以减少触发

**② 这台机器上没有"现成 App"可用。**
Flyme 自带文件管理器（`com.meizu.filemanager`）的 manifest 里**没有声明 USB host / MTP**，
所以它看不到相机。想零开发直接用，得另外装一个支持 MTP 主机的第三方文件管理器；
否则就是自己写 —— 而这件事真的很小，见下。

### 12.6 一个 App 需要多少代码

本次探针的完整实现（枚举 + 分类统计 + 下载 + MD5 + 速度）约 300 行 Java，
核心只有这么几行：

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

**免 root、免 USB 权限弹窗**：在 manifest 里挂一个匹配 Sony VID 的
`USB_DEVICE_ATTACHED` intent-filter（`res/xml/device_filter.xml`），
系统就会在插线时自动授予权限并拉起 App —— 实测权限回调直接 `granted=true`，无弹窗。
这体验比官方 App 还顺。

---

## 附：本仓库清单速查

| 仓库 | 协议 | 平台 | 对本需求价值 |
|---|---|---|---|
| kz-remote-api | Wi-Fi JSON API | C# / Win | 协议封装最全，可读；不能传 RAW |
| locana | Wi-Fi JSON API | Win10 UWP | 同上，工程量大，参考 UI/流程 |
| sony_camera_api (pysony) | Wi-Fi JSON API | Python | 快速探接口，`api_list.py` 有全集 |
| sony-camera-api / sony-remote / sonypy | Wi-Fi JSON API | Python/Shell | 同上，更早期 |
| Camera-Remote-API / RemoteCamera / ofxSonyRemoteCamera / SonyCameraRemoteAPI | Wi-Fi JSON API | Web / oF / ObjC | 历史参考 |
| RemoteCam | Wi-Fi JSON API | Win/mac | 桌面取景做得好，USB 无关 |
| alpharemote | BLE | **Android** | 安卓工程骨架可参考，BLE 不管传图 |
| furble | BLE | ESP32 | 无关 |
| sony_camera_ble_remote | BLE 抓包 | - | 协议笔记 |
| alpha-fairy | - | - | 空目录 |
