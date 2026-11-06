# 索尼 a7C（ILCE-7C）USB 有线直传 RAW 到安卓：可行性核查

> 核查日期：2026-11-04｜核查方式：官方文档优先，第三方资料交叉验证，明确标注推断
> 标注约定：**[官方]** = 厂商/平台官方文档原话；**[第三方]** = 第三方实测、源码或商店页面；**[推断]** = 由证据推导，无直接原文

## 直接回答

**能做到，但"替代 Imaging Edge Mobile"这个前提本身是错的**：a7C 的 Imaging Edge Mobile 确实只能 Wi‑Fi + JPEG（RAW 会被转成 JPEG），但索尼另一款官方 App **Transfer & Tagging** 在安卓上明确支持 a7C 的 USB 大容量存储导入，且从 Ver.2.1 起支持 `.arw` 原样传输到手机。因此"安卓 + USB-C 线 + a7C + 官方免费 App 直传 RAW"是**有条件可行**的，不是不可行。

真正的卡点不在 USB 硬件（a7C 是 USB 3.2 Gen1 5Gbps，链路绰绰有余），而在三处：① 官方 Camera Remote SDK **没有 Android 版本**，且只对法人提供，自研 App 路线被堵死；② 安卓系统**没有系统级的 MTP/PTP 文件浏览界面**，第三方 App 必须自己实现协议；③ MSC 路线依赖手机支持 USB Host + Mass Storage Class 以及 exFAT，且相机在 MSC 模式下不能同时拍摄。

如果目标是"边拍边把 RAW 自动落到手机"，官方免费路径**做不到**（T&T 的 USB MSC 导入期间不能拍摄）；这种"拍摄同时落盘"的能力只有 PC 端 Imaging Edge Desktop / CrSDK 具备。安卓上目前只有付费第三方 App（Monitor+）提供"有线访问相机内容 + RAW 处理"，但逐机型 RAW 落盘能力官方未逐条写明。

## 结论分级

| 路径 | 分级 | 卡点 |
|---|---|---|
| 官方 Transfer & Tagging + a7C MSC + 安卓 USB-C 传 `.arw` | **有条件可行** | 需 Sony 账号；需手机支持 USB Host/MSC；≥64GB 卡须 exFAT；导入期间不能拍；官方未逐条写明"USB MSC + RAW"组合 |
| 安卓第三方 App（Monitor+）有线读取/保存 RAW | **有条件可行**（厂商声明级） | 付费 $18.99；a7C 的内容访问仅限有线；逐机型 RAW 保存未逐条列明 |
| 自研安卓 App 调 Sony Camera Remote SDK | **不可行** | SDK 无 Android 构建、无官方支持；仅法人可下载；需自行实现 USB/PTP |
| 自研安卓 App 直接实现 PTP（不用 SDK） | **技术可行、工程成本高** | 无官方文档保障；需处理 Sony 私有 opcode/事件；安卓需 USB 权限与自写 bulk 传输 |
| 依赖 Imaging Edge Mobile 传 RAW | **不可行** | 仅 Wi‑Fi；RAW 发送时被转 JPEG |
| 边拍边把 RAW 自动落手机（官方方案） | **不可行** | a7C 的 PC Remote/USB 落盘只对 PC 主机；CrSDK 无 Android |

---

## A. a7C 的 USB 硬件与协议能力

### A1. USB 接口规格：5Gbps，与 a7C II 同级，低于 a7 IV

**[官方]** 索尼 a7C 帮助指南"规格"页原文：

> "**USB Type-C terminal**: SuperSpeed USB 5 Gbps (USB 3.2)"
> —— [ILCE-7C Help Guide: Specifications](https://helpguide.sony.net/ilc/2020/v1/en/contents/TP1000156734.html)（页面未标日期）

**[官方]** 索尼美国支持站规格页同样写 "USB Type-C® SuperSpeed USB 5 Gbps (USB 3.2) compatible"，并注明 "PC Interface: Mass-storage / MTP" —— [Sony Support: ILCE-7C Specifications](https://www.sony.com/electronics/support/e-mount-body-ilce-7-series/ilce-7c/specifications)。

**[第三方]** DPReview 规格表记作 "USB 3.2 Gen 1 (5 Gbit/sec)" —— [DPReview: Sony a7C specifications](https://www.dpreview.com/products/sony/slrs/sony_a7c/specification/)（评测发布 2020-10-27）。

对比机型：

| 机型 | USB | 证据 | 类型 |
|---|---|---|---|
| a7C（ILCE-7C） | USB 3.2 Gen 1，5Gbps | 索尼帮助指南/支持站 | 官方 |
| a7C II（ILCE-7CM2） | "SuperSpeed USB 5 Gbps (USB 3.2) に準拠" | [α7C II 主な仕様（索尼日本）](https://www.sony.jp/ichigan/products/ILCE-7CM2/spec.html) | 官方 |
| a7 IV（ILCE-7M4） | USB 3.2 Gen 2，10Gbps | [DPReview 三机对比表](https://www.dpreview.com/products/compare/side-by-side/sony_a7iv-vs-sony_a7cii-vs-nikon_zf) | 第三方 |

**对 RAW 单文件传输意味着什么（[推断]，附算式）**：5Gbps 链路理论带宽 625MB/s；a7C 单张 24MP ARW 约 25–50MB（无损压缩/未压缩不同），链路本身远不是瓶颈。**[第三方]** a7C 没有可信的 USB 实测 MB/s 数据；同代机型从卡经 USB 拷出的持续速度普遍只有几十 MB/s 量级，瓶颈在相机端读卡与 USB 实现，而非 5Gbps 这个数字。a7 IV 的 10Gbps 对大文件/视频更有意义，对单张 RAW 差别不大。

补充：**[官方]** a7C 卡槽为 "SD cards (UHS-I and UHS-II compatible)"，单卡槽（同规格页），所以"从卡读到 USB"这一环不是低速 UHS-I 卡槽。

### A2. 支持哪些 USB 模式、菜单怎么切

**[官方]** a7C 的 USB 模式分两处设置，不是一个大菜单：

1. **Setup → [USB Connection]**：`Auto` / `Mass Storage` / `MTP`
   > "Auto: Establishes a Mass Storage or MTP connection automatically…"
   > "Mass Storage: Establishes a Mass Storage connection…"
   > "MTP: Establishes an MTP connection…"
   —— [ILCE-7C Help Guide: USB Connection](https://helpguide.sony.net/ilc/2020/v1/en/contents/TP1000115335.html)

2. **Network → [PC Remote Function]**：开关 + `PC Remote Cnct Method`（`USB` / `Wi‑Fi Direct` / `Wi‑Fi Access Point`）
   —— [ILCE-7C Help Guide: PC Remote Function](https://helpguide.sony.net/ilc/2020/v1/en/contents/TP1000156203.html)（德文版同页 TP1000155771 交叉核对）

3. **USB Streaming（UVC 摄像头/直播）**：a7C 出厂固件没有，**[官方]** 由固件更新加入：
   > "USBストリーミング機能の追加"（新增 USB 流媒体功能）
   —— [ILCE-7C 本体软件更新 Ver.2.00](http://support.d-imaging.sony.co.jp/www/cscs/firm/?mdl=ILCE-7C)

**[官方]** 还有一个容易踩的互斥条件：用 MSC/MTP 前要先关手机遥控，PC Remote 走 USB 时还要把 PC Remote 设为 Off，否则 USB Connection 菜单不生效（同上 USB Connection 页）。

结论：a7C 没有"单独选 PTP"的菜单。**MSC 与 MTP 是媒体传输模式，PC Remote 是 PTP 私有扩展的遥控模式，USB Streaming 是 UVC 视频类**，四者互斥切换。

### A3. PC Remote 能否边拍边把 RAW 落到主机

**能，而且 a7C 菜单里就有"只落 RAW"选项。** 这是本次核查里最明确的官方结论之一。

**[官方]** a7C 的 PC Remote 菜单项：

> "**Still Img. Save Dest.**: Sets whether to save still images to both the camera and the computer during PC Remote shooting. ([PC Only]/[PC+Camera]/[Camera Only])"
> "**RAW+J PC Save Img**: Selects the file type for images to be transferred to the computer when [Still Img. Save Dest.] is set to [PC+Camera]. ([RAW & JPEG]/[JPEG Only]/[RAW Only])"
> "**PC Save Image Size**: … ([Original]/[2M])"
> —— [ILCE-7C Help Guide: Operating the camera from a computer (PC Remote Function)](https://helpguide.sony.net/ilc/2020/v1/en/contents/TP1000156203.html)

注意两个细节：
- `RAW+J PC Save Img` **只有在相机影像质量设为 RAW & JPEG 时才出现**（德文版同页明确："kann nur dann gewählt werden, wenn [Dateiformat] auf [RAW & JPEG] eingestellt wird"）。若机身设为"仅 RAW"，走的是另一条路径，仍可 PC Only 保存。
- `PC Save Image Size: Original/2M` 里的 `Original` 指的是 **JPEG 原尺寸 vs 2M 缩略**，不是"RAW 原始文件"的开关；RAW 是否传输由 `RAW+J PC Save Img` 决定。容易把两个"Original"混淆。

**[官方]** PC 端 Imaging Edge Desktop (Remote) 侧：
> "In the Remote Shooting settings on the camera, you can check the destination for saving images, the size of saved images, and the format in which images are saved…"
> —— [Imaging Edge Desktop: Remote Shooting](https://support.d-imaging.sony.co.jp/app/imagingedge/en/instruction/4_5_remote.php)

即：**a7C + USB + Imaging Edge Desktop 可以做到"拍摄同时 RAW 落电脑"**，这是官方支持的桌面工作流。但这条链路的主机必须是 Windows/macOS PC，不是安卓手机。

---

## B. Sony Camera Remote SDK（CrSDK）

### B4. 是否支持 ILCE-7C

**[官方]** 支持，且是明确列名：

> "ILCE-1M2, ILCE-1, ILCE-9M3, ILCE-9M2, ILCE-7RM6, ILCE-7RM5, ILCE-7RM4A, ILCE-7RM4, ILCE-7CR, ILCE-7SM3, ILCE-7M5, ILCE-7M4, ILCE-7CM2, **ILCE-7C**, ILCE-6700"
> —— [Camera Remote SDK 官方页](https://support.d-imaging.sony.co.jp/app/sdk/en/index.html)（当前版本页）

同页还注明"使用前需把相机升级到最新固件"。

### B5. 有没有 Android 版本

**没有。官方支持的操作系统列表里没有 Android，也没有 iOS。**

**[官方]** 原文：
> "**Supported OS**: macOS® 14.1 - / macOS® 15.1 - / macOS® 26.0 - / Windows® 11 / Linux® 64bit (ARMv8) / Linux® 32bit (ARMv7) / Linux® 64bit (x86)"
> —— [Camera Remote SDK 官方页](https://support.d-imaging.sony.co.jp/app/sdk/en/index.html)

三点补充：

1. **Linux ARM 版 ≠ Android**。Linux ARMv7/ARMv8 是给树莓派一类嵌入式主机的，构建依赖 glibc、libudev、libusb。**[第三方]** CrSDK 的 USB 适配层是 `libCr_PTP_USB`，Linux 侧构建文档明确要求 `libudev-dev`，运行时加载 `libusb-1.0.0`；macOS 侧对应 `libCr_PTP_USB.dylib` —— [crsdk/alpha-sdk-api: SDK_SETUP.md](https://raw.githubusercontent.com/crsdk/alpha-sdk-api/HEAD/docs/SDK_SETUP.md)、[BUILDING_BINARIES.md](https://raw.githubusercontent.com/crsdk/alpha-sdk-api/HEAD/docs/BUILDING_BINARIES.md)（2026 仓库快照）。安卓上没有 glibc/libudev，官方不提供 Android ABI 的 `libCr_Core.so`。
2. **安卓 USB 权限本身不是障碍**。**[官方]** Android 的 USB Host API 允许应用枚举设备、申请用户授权、做 bulk 传输，不需要 root —— [Android Developers: USB host overview](https://developer.android.com/develop/connectivity/usb/host)。真正缺的是 SDK 的 Android 构建与官方支持。
3. **SDK 不对个人开放**。**[官方]** 索尼日本 FAQ：
   > "個人ユーザーはCamera Remote SDKをダウンロード可能でしょうか？ … 個人のお客様へはCamera Remote SDKを提供しておりません。"
   （个人用户不能下载；不向个人提供）
   —— [Camera Remote SDK（法人向け）](https://www.sony.jp/camera-biz/sdk/index.html)

### B6. SDK 能不能读相机上的 RAW

**能，但要分清"拍摄时落盘"和"读卡上已有文件"两条 API 路径。** 官方原话只在发布说明层面，API Reference 本身随 SDK 压缩包分发、不公开挂网，下面按证据强度分层给。

**(a) [官方] 内容传输模式对 a7C 开放**
> "Camera Remote SDK Ver. 1.05.00 has been released. … Added some commands as following. … **Contents Transfer mode** \*2 … \*2 Compatible with **ILCE-7RM4A, ILCE-7C**."
> —— [Camera Remote SDK 官方页（版本历史区）](https://support.d-imaging.sony.co.jp/app/sdk/en/index.html)（Ver.1.05.00 发布于 2021-12-07）

这条直接回答"SDK 能否从相机读文件"：a7C 支持 SDK 的 Contents Transfer 模式。

**(b) [第三方] CrSDK 头文件枚举（经 Python 封装镜像）**
`sonyalphapy`（CrSDK v1 的 Python 绑定，PyPI 0.1.0，2026-03-03 发布）包内枚举与回调镜像了 SDK 定义：
- 文件类型：`Jpeg = 0x0001`、`Raw = 0x0002`、`RawJpeg = 0x0003`、`RawHeif = 0x0004`、`Heif = 0x0005`
- 控制模式：`ContentsTransfer = 0x00000001`
- 回调：`OnNotifyContentsTransfer(handle, filename)`、`OnCompleteDownload(filename, file_type)`
- 相机属性：`RAW_J_PC_Save_Image = 0x0507`（对应机身的 `RAW+J PC Save Img`）
—— [PyPI: sonyalphapy](https://pypi.org/project/sonyalphapy/)（本地包内 `sonyalphapy/enums.py`、`_callback.py` 已解包核对）

即 SDK 的"整体文件下载"接口按文件类型区分，RAW 是独立类型，不是只给 JPEG。

**(c) [第三方·逆向规范级] PTP 层的 Sony 私有定义**
libgphoto2 的 PTP 头文件（master 分支，2026-11-04 快照）：
- `PTP_OC_SONY_SDIO_SetContentsTransferMode = 0x9212`
- `PTP_OF_SONY_RAW = 0xb101`（Sony RAW 对象格式）
- `PTP_EC_SONY_ContentsTransferEvent = 0xC20D`
—— [libgphoto2 camlibs/ptp2/ptp.h](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.h)

这说明"切内容传输模式 → 枚举 → 取对象"是 Sony 相机通用的 PTP 行为，RAW 有独立对象格式码，而不是靠扩展名猜。**[推断]** 因此 CrSDK 的 Contents Transfer 在 a7C 上取 ARW 应有协议基础；但具体 API 名称/参数需以随 SDK 分发的 API Reference 为准（该文档未公开，本次无法直接引用页码）。

**(d) 关于 "Original"**
`Original` 在 a7C 菜单里是 **PC Save Image Size** 的选项（原尺寸 JPEG / 2M JPEG），不是"原始 RAW 文件"开关；RAW 走 `RAW+J PC Save Img → [RAW Only]` 或 `Still Img. Save Dest. → [PC Only]`。CrSDK 侧同样有"保存尺寸 Original/2M"的概念，只作用于 JPEG。**把 `Original` 理解成"取 RAW 原文件"是常见误读。**

### B7. 授权与分发：能不能上架

**能商用、能上架，但不能把 SDK 本身当商品分发，且只有法人能拿到 SDK。**

**[官方]** 许可协议授予条款：
> "(ii) incorporate a binary form of the library file in the SOFTWARE into the APPLICATION SOFTWARE in an inseparable way and distribute the APPLICATION SOFTWARE to any third parties; and
> (iii) grant the third parties … a license to copy and use the APPLICATION SOFTWARE, solely for the purposes to remotely control or use the DEVICE in a normal usage."
> —— [Camera Remote SDK License Agreement](https://support.d-imaging.sony.co.jp/app/sdk/licenseagreement_d/en.html)

即：**把库以二进制形式不可分离地嵌进你的 App，可以分发给第三方/终端用户**。同时协议禁止单独再分发 SDK（不得 share/distribute/sublicense/transfer 软件本体）。

**[官方]** 商用 FAQ 直接回答：
> "このSDKを使用して開発したアプリケーションを販売することは可能でしょうか？ はい、使用許諾契約書に基づき自由に販売できます。"
> （可以销售，依许可协议可自由销售）
> —— [Camera Remote SDK（法人向け）](https://www.sony.jp/camera-biz/sdk/index.html)

**[第三方]** 实践侧的注意：SDK 压缩包本身不能进公开仓库/公开 Release，每个构建者必须自己接受 EULA 下载。某开源封装项目明确写 "**Do not redistribute the Sony SDK**" —— [crsdk/alpha-sdk-api: BUILDING_BINARIES.md](https://raw.githubusercontent.com/crsdk/alpha-sdk-api/HEAD/docs/BUILDING_BINARIES.md)。这不与"嵌入 App 分发"矛盾：限制的是 SDK 本体，不是你的 App。

对个人开发者的现实门槛：**拿不到 SDK**（官方只对法人发放），所以"自己写个安卓 App 调 CrSDK"这条路在第一道门就被挡住。

---

## C. 安卓端的现实路径

### C8. 安卓 USB Host 能不能以 MTP/MSC 身份读 a7C 的 ARW

分两个协议看，结论不同：

**MSC（相机切 Mass Storage）——系统级可挂载，可行**

**[官方]** AOSP 存储文档：
> "Android 6.0 supports portable storage devices which are only connected to the device for a short period of time, like USB flash drives."
> —— [AOSP: Traditional storage](https://source.android.com/docs/core/storage/traditional)（Last updated 2026-10-09）

相机切 MSC 后对手机就是一个 U 盘，系统存储框架能识别。 **[官方]** 索尼自己的 Transfer & Tagging 手册也把前提写死：
> "支持 USB 主机功能、USB‑C® 连接器和大容量存储设备类的智能手机或平板电脑…… 容量为 64 GB 或更大的卡必须为 exFAT 格式。"
> —— [Transfer & Tagging: 2.2 通过 USB 大容量存储连接导入影像](https://support.d-imaging.sony.co.jp/app/transfer/zh-hans/instruction/22_usb.php)

且手册第 4 步直接写"选择相机中的存储卡 **[Sony U 盘]**"，说明系统确实把它当 U 盘挂载。**这是"官方明说"级别的可行证据。**

风险点：exFAT 支持取决于机型。**[官方]** AOSP 内核文档注明 exFAT "supported in kernel 5.10 and later" —— [AOSP: Android kernel file system support](https://source.android.com/docs/core/architecture/android-kernel-file-system-support)（2026-10-09）；老机型/部分 OEM 可能不识别 exFAT，需要 FAT32 卡或第三方挂载工具（如 MLUSB Mounter，**[第三方]**）。

**MTP/PTP（相机切 MTP 或 PC Remote）——有 API，无系统 UI，需第三方 App**

**[官方]** Android 确实提供了 host 端 MTP 类：
> "This class represents an MTP or PTP device connected on the USB host bus."
> —— [Android API: android.mtp.MtpDevice](https://developer.android.com/reference/android/mtp/MtpDevice)

但要澄清：AOSP 的 `MtpService`/`MtpDatabase`/`MtpServer` 是**设备端**（把手机当 MTP 设备给电脑看），不是给用户浏览相机的文件管理器。**[第三方]** 安全研究文档也印证 "USBManager starts MtpService When connected to host" —— [Black Hat Asia 2025 论文 PDF](https://i.blackhat.com/Asia-25/Asia-25-Draschbacher-Watch-Your-Phone.pdf)（2025）。所以插上相机不会自动弹出"浏览相机文件"，必须装 App 或自己写。

**RAW 在 MTP/PTP 层有没有限制？没有实质限制。** 未知扩展名会落到 `0x3000`（Undefined Non-Image Object / WPD_OBJECT_FORMAT_UNSPECIFIED），仍可传输：
- **[规范/官方]** `PTPObjectFormatCodeUndefinedNonImageObject = 0x3000` —— [Apple PTPEnums.h](https://developer.apple.com/library/archive/samplecode/PTPPassThrough/Listings/PTPEnums_h.html)；`FORMAT_UNDEFINED = 12288` —— [Android MtpConstants](https://emanual.github.io/Android-docs/reference/android/mtp/MtpConstants.html)
- **[官方]** 微软 WPD 文档："WPD_OBJECT_FORMAT_UNSPECIFIED … used for objects that cannot be specified by the other defined … format codes" —— [Microsoft Learn](https://learn.microsoft.com/en-us/previous-versions/ms739469(v=vs.85))（2008-03-07）
- **[官方]** 索尼自家文档里也有走 MTP 传 ARW 的实例："select [Auto], [Mass Storage], or [MTP] … transfer the four RAW images (file extension: ARW) to the computer" —— [Pixel Shift Multi Shooting 支持页](http://support.d-imaging.sony.co.jp/support/ilc/psms/ilce7rm3/en/index.html)

**公开的安卓实测/项目（[第三方]）**：
- [geekxingyun/android-otg-usb-mtp-ptp-solution](https://github.com/geekxingyun/android-otg-usb-mtp-ptp-solution)：作者称"MTP 模式访问单反相机照片并复制到手机存储（测试通过）"，PTP "有同行测试成功"；未指明 Sony/ARW。
- [jongan69/expo-sony-camera](https://github.com/jongan69/expo-sony-camera/blob/main/docs/SUPPORT_MATRIX.md)：安卓 USB PTP2 控制 ILCE-7M3，标注 "Experimental"，媒体浏览仅 "Captured JPEG only"，尚不能批量导 ARW。
- **Termux + gphoto2、XDA/Reddit 上"Sony OTG 传 ARW"的可靠成功帖：未找到。** 不编造。

### C9. 商业方案对照（重点：安卓端谁能传 RAW）

| 方案 | 平台 | 连接 | RAW 能力 | 价格 | 证据类型/链接 |
|---|---|---|---|---|---|
| **Sony Transfer & Tagging** | Android / iOS | **USB MSC**、Wi‑Fi FTP、USB 网络共享（a7C 不支持） | **支持 `.arw`（Ver.2.1+），仅传输（不可 IPTC 编辑）**；a7C 的 USB 大容量导入 ✔ | 免费（需 Sony 账号） | **[官方]** [支持机型表](https://support.d-imaging.sony.co.jp/app/transfer/en/devices/cameras.php)、[文件类型](https://support.d-imaging.sony.co.jp/app/transfer/zh-hans/)（页面更新 2025-10-14） |
| **Monitor+** | **Android** | 有线（a7C 的内容访问仅限有线）、Wi‑Fi 控制 | "Camera Content Access: Yes[4]"；更新日志 "Added camera content access feature"、"Fixed raw photo saving issue"、"Added support for RAW photo viewing" | Pro **$18.99** | **[第三方]** [兼容表](https://monitorplus.cc/docs/general/camera-compatibility/)、[Android 更新日志](https://monitorplus.cc/docs/general/changelog/android/)、[商店](https://monitorplus.cc/shop/) |
| Imaging Edge Mobile | Android / iOS | **仅 Wi‑Fi** | **不支持**：a7C 帮助页"RAW images are converted to JPEG format when they are sent" | 免费 | **[官方]** [a7C 帮助页](https://helpguide.sony.net/ilc/2020/v1/en/contents/TP1000139936.html)、[IEM FAQ](https://www.sony.com/electronics/support/articles/00020268)（FAQ 更新 2026-02-20） |
| CamRanger 2 | iOS / Android | 相机 USB→CamRanger→自有 Wi‑Fi | a7C 兼容；但**选 RAW 只下载/显示其内嵌 JPEG**，不传 `.arw` | **$369–457.97** | **[第三方]** [a7C 兼容页](https://camranger.com/knowledge-base/sony-a7c/)、[iOS 手册](https://camranger.com/knowledge-base/camranger-2-user-manual-for-ios/) |
| Cascable Studio | **iOS only** | **USB + Wi‑Fi** | "Storage Access: Browse your camera's storage, previewing and copying images"；"RAW Images: Access and work with RAW images" | 订阅/买断（约 $7.99/月、$99.99） | **[第三方]** [a7C 兼容页](https://compatibility.cascable.se/sony/a7c/) |
| Shutter+ | **iOS only**（注意不是安卓） | Wi‑Fi + USB | "Transfer full resolution JPEG / Transfer RAW files" | €3.99/月、€69 买断 | **[第三方]** [a7C 页面](https://shutter.dev/docs/sony-a7c) |

要点：
1. **安卓端目前唯一"官方且免费、明确支持 a7C + `.arw`"的方案是 Transfer & Tagging**。这是本次核查最重要的纠偏。
2. 安卓端付费第三方里，Monitor+ 是唯一明确声称"a7C 有线内容访问 + RAW"的产品，但官方兼容表按能力列，未逐机型写"能把 ARW 存到手机相册"，属"厂商声明 + 更新日志"级证据。
3. **CamRanger 2 是最贵的，却不传 ARW 本体**，只给 RAW 内嵌 JPEG，性价比最差。
4. Cascable、Shutter+ 都是 iOS，不能用于安卓。用户问的"Shutter（Android）"实际产品是 iOS 的 Shutter+。

### C10. 若 USB 路线受限，替代方案的真实上限

**Wi‑Fi 规格（[官方]，注意地区版本差异）**

a7C 有两个硬件型号，无线能力不同：
- **WW850128**："IEEE 802.11 a/b/g/n/ac"，"Frequency band: **2.4 GHz / 5 GHz**"
- **WW384063**："IEEE 802.11 b/g/n"，"Frequency band: **2.4 GHz**"（无 5GHz）
—— [ILCE-7C Help Guide: Specifications](https://helpguide.sony.net/ilc/2020/v1/en/contents/TP1000156734.html)

即：**是否支持 5GHz 取决于销售地区版本**，不能一概而论"a7C 只有 2.4GHz"。判断方法：看相机底部的 WW 编号（WW850128 = 双频；WW384063 = 仅 2.4GHz）。本次未逐一核实中国行货对应哪个编号，购买前应以机身铭牌为准。

**实测速度**：**[第三方]** 找不到 a7C 专属的可信 MB/s 数据。可参考同代机型（a7R IV）实测约 3.44–5.09 MB/s —— [DPReview 论坛帖](https://www.dpreview.com/forums/threads/a7r-iv-wifi-and-usb-transfer-speeds.4430574/)；a7C 用户反馈"5GHz 明显更快"但无数字 —— [DPReview 讨论帖](https://www.dpreview.com/forums/threads/smartphone-wifi-remote-control-of-the-a7c-is-baaaad.4605022/)。**[推断]** 以 3–5MB/s 计，一张 40MB ARW 约 8–13 秒，连拍几十张就是分钟级，不适合大批量。

**FTP 传输是否支持 RAW（[官方]）**：支持，且有 RAW 目标选项。
> "Setting the RAW+JPEG image transfer target: JPEG Only / RAW Only / RAW & JPEG"
> "This guide explains how to transfer images to an **FTP server**"
—— [a7C FTP Transfer 指南（官方 PDF）](https://helpguide.sony.net/di/ftp/v1/en/print.pdf)

但要看清：
- 目标是 **FTP 服务器**，不是"直接发给手机相册"。手机要当 FTP 服务器，就得靠 Transfer & Tagging（**[官方]** "Transfer & Tagging (works as an FTP server)"，[2.1 使用 FTP 导入](https://support.d-imaging.sony.co.jp/app/transfer/zh-hans/instruction/21_ftp.php)）。
- a7C **不支持 USB 网络共享导入**（T&T 支持表中该项为 "―"），只有 Wi‑Fi/热点；a7C 也没有有线 LAN 口。
- **[官方]** 帮助页还写明用蓝牙写入 FTP 设置时 `RAW+J/H Transfer Target` 会自动变为 `JPEG & HEIF`（同上 2.1 页），这意味着走 T&T 的 FTP 自动配置时默认只传 JPEG/HEIF，要传 RAW 得手动改相机设置。

**务实做法的真实上限**：
1. **拍 RAW+JPG，JPG 即时走 Wi‑Fi/IEM 当预览，RAW 事后读卡器**——这是最省事、最可靠的组合；上限是"没有即时 RAW"，但预览与选片不受影响。
2. **RAW 要进手机，用 T&T + USB MSC**：一次导一批，不是实时。官方支持、免费，是当前最优解。
3. **要"边拍边 RAW 落盘"，只能回到 PC**：a7C 的 PC Remote + Imaging Edge Desktop 或 CrSDK（Windows/macOS/Linux）。安卓无官方等价物。

---

## 卡点总结（按"硬件 / 协议 / SDK 授权 / 系统 API"分类）

| 类别 | 具体卡点 | 是否可绕过 |
|---|---|---|
| 硬件 | a7C 只有 5Gbps USB，但这不是瓶颈；真正的限制是相机端读卡与 USB 实现速度，且 MSC 模式下不能拍摄 | 不可绕过（物理限制），但对单张 RAW 影响小 |
| 协议 | a7C 没有"纯 PTP 媒体模式"菜单；MTP/MSC/PC Remote/UVC 四者互斥，切换要改多处设置 | 可绕过：按用途切模式 |
| 协议 | 要"边拍边落 RAW"必须走 PC Remote 私有 PTP，且主机需实现 Sony 扩展命令 | 安卓无现成实现，需自研 |
| SDK 授权 | CrSDK 只给法人、无 Android 版本、库需 libusb/glibc | 个人开发者基本不可用；可改用裸 PTP 自研 |
| 系统 API | 安卓无系统级 MTP/PTP 文件浏览 UI；MSC 依赖 OEM 的 exFAT/UMS 支持 | 用 T&T / Monitor+ 等 App；或换 FAT32 卡 |
| App 能力 | Imaging Edge Mobile 只 Wi‑Fi + JPEG；CamRanger 2 只给 RAW 内嵌 JPEG | 改用 Transfer & Tagging（免费）或 Monitor+/Cascable(iOS)/Shutter+(iOS) |

**一句话结论**：**"安卓 USB 直传 a7C 的 RAW"不是伪需求，官方免费 App（Transfer & Tagging）+ 相机 MSC 模式就能做到，属于"有条件可行"；但"边拍边自动落 RAW 到手机"在安卓上没有官方路径，要么退回 PC（Imaging Edge Desktop/CrSDK），要么接受"拍 RAW+JPG、JPG 即时预览、RAW 批量导"的折中。**

---

## 来源清单

### 索尼官方
1. [ILCE-7C Help Guide: Specifications](https://helpguide.sony.net/ilc/2020/v1/en/contents/TP1000156734.html)（未标日期；USB 5Gbps、Wi‑Fi 双版本、UHS‑II）
2. [Sony Support: ILCE-7C Specifications](https://www.sony.com/electronics/support/e-mount-body-ilce-7-series/ilce-7c/specifications)（未标日期）
3. [ILCE-7C Help Guide: USB Connection](https://helpguide.sony.net/ilc/2020/v1/en/contents/TP1000115335.html)（未标日期）
4. [ILCE-7C Help Guide: PC Remote Function](https://helpguide.sony.net/ilc/2020/v1/en/contents/TP1000156203.html)（未标日期；德文同页 TP1000155771）
5. [ILCE-7C 固件 Ver.2.00（USB Streaming）](http://support.d-imaging.sony.co.jp/www/cscs/firm/?mdl=ILCE-7C)
6. [Imaging Edge Desktop: Remote Shooting](https://support.d-imaging.sony.co.jp/app/imagingedge/en/instruction/4_5_remote.php)（未标日期）
7. [Camera Remote SDK 官方页（支持机型/OS/版本历史）](https://support.d-imaging.sony.co.jp/app/sdk/en/index.html)（Ver.1.05.00 发布 2021-12-07；页面为当前版）
8. [Camera Remote SDK License Agreement](https://support.d-imaging.sony.co.jp/app/sdk/licenseagreement_d/en.html)（未标日期）
9. [Camera Remote SDK（法人向け）FAQ](https://www.sony.jp/camera-biz/sdk/index.html)（未标日期）
10. [α7C II 主な仕様](https://www.sony.jp/ichigan/products/ILCE-7CM2/spec.html)（未标日期）
11. [Transfer & Tagging: 支持机型表](https://support.d-imaging.sony.co.jp/app/transfer/en/devices/cameras.php)（内容更新至 2025-10-14）
12. [Transfer & Tagging: 支持的文件类型（中文首页）](https://support.d-imaging.sony.co.jp/app/transfer/zh-hans/)（更新记录 2025-10-14，Ver.2.4）
13. [Transfer & Tagging: 2.2 USB 大容量存储导入](https://support.d-imaging.sony.co.jp/app/transfer/zh-hans/instruction/22_usb.php)（未标日期）
14. [Transfer & Tagging: 2.1 使用 FTP 导入](https://support.d-imaging.sony.co.jp/app/transfer/zh-hans/instruction/21_ftp.php)（未标日期）
15. [ILCE-7C Help Guide: Send to Smartphone Func.](https://helpguide.sony.net/ilc/2020/v1/en/contents/TP1000139936.html)（RAW 转 JPEG 原话）
16. [Sony 支持: Imaging Edge Mobile 文件格式 FAQ](https://www.sony.com/electronics/support/articles/00020268)（FAQ 更新 2026-02-20）
17. [a7C FTP Transfer 指南 PDF](https://helpguide.sony.net/di/ftp/v1/en/print.pdf)（未标日期；RAW Only/RAW & JPEG 选项）
18. [Pixel Shift Multi Shooting（MTP 传 ARW 实例）](http://support.d-imaging.sony.co.jp/support/ilc/psms/ilce7rm3/en/index.html)（未标日期）

### 平台/协议官方
19. [AOSP: Traditional storage](https://source.android.com/docs/core/storage/traditional)（Last updated 2026-10-09）
20. [AOSP: Android kernel file system support](https://source.android.com/docs/core/architecture/android-kernel-file-system-support)（2026-10-09）
21. [Android Developers: USB host overview](https://developer.android.com/develop/connectivity/usb/host)（未标日期）
22. [Android API: android.mtp.MtpDevice](https://developer.android.com/reference/android/mtp/MtpDevice)（未标日期）
23. [Microsoft Learn: WPD_OBJECT_FORMAT_UNSPECIFIED](https://learn.microsoft.com/en-us/previous-versions/ms739469(v=vs.85))（2008-03-07）

### 第三方（实测/源码/商店）
24. [DPReview: Sony a7C specifications](https://www.dpreview.com/products/sony/slrs/sony_a7c/specification/)（2020-10-27）
25. [DPReview: a7 IV vs a7C II 对比表](https://www.dpreview.com/products/compare/side-by-side/sony_a7iv-vs-sony_a7cii-vs-nikon_zf)（未标日期；a7 IV 10Gbps）
26. [libgphoto2: camlibs/ptp2/ptp.h](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.h)（master，2026-11-04 快照）
27. [PyPI: sonyalphapy（CrSDK 枚举镜像）](https://pypi.org/project/sonyalphapy/)（0.1.0，2026-03-03）
28. [crsdk/alpha-sdk-api: SDK_SETUP.md](https://raw.githubusercontent.com/crsdk/alpha-sdk-api/HEAD/docs/SDK_SETUP.md) / [BUILDING_BINARIES.md](https://raw.githubusercontent.com/crsdk/alpha-sdk-api/HEAD/docs/BUILDING_BINARIES.md)（2026 仓库快照）
29. [Monitor+ 兼容表](https://monitorplus.cc/docs/general/camera-compatibility/)、[Android 更新日志](https://monitorplus.cc/docs/general/changelog/android/)、[商店 $18.99](https://monitorplus.cc/shop/)（2026-11-04 访问）
30. [Cascable: Sony α7C 兼容页](https://compatibility.cascable.se/sony/a7c/)（2026-11-04 访问）
31. [Shutter+: Sony a7C 页面](https://shutter.dev/docs/sony-a7c)（2026-11-04 访问；iOS）
32. [CamRanger: Sony A7c 兼容页](https://camranger.com/knowledge-base/sony-a7c/)、[iOS 手册（RAW 只显示内嵌 JPEG）](https://camranger.com/knowledge-base/camranger-2-user-manual-for-ios/)（2026-11-04 访问）
33. [geekxingyun/android-otg-usb-mtp-ptp-solution](https://github.com/geekxingyun/android-otg-usb-mtp-ptp-solution)（未标日期）
34. [jongan69/expo-sony-camera: SUPPORT_MATRIX](https://github.com/jongan69/expo-sony-camera/blob/main/docs/SUPPORT_MATRIX.md)（未标日期）
35. [Black Hat Asia 2025: Android MTP 设备端分析 PDF](https://i.blackhat.com/Asia-25/Asia-25-Draschbacher-Watch-Your-Phone.pdf)（2025）
