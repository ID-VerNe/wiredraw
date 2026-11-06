# 索尼相机远程控制仓库审计：安卓 USB 直连 a7C 传 RAW 的技术底账

- 审计日期：2026-11-04
- 审计目录：`<本机工作区绝对路径>`
- 审计方式：只读检查 README、构建文件、源码与 git HEAD；未编译、未连接相机、未下载 SDK。
- 结论口径：文中每条判断都给出仓库内文件与行号；无法从仓库证实的标注“未验证”。

---

## 一、结论（先回答问题）

**不能直接靠这批代码实现“安卓手机 USB 有线直连 a7C 并传输 RAW”。**

1. **没有任何一个仓库实现 USB 主机传输。** 全仓检索 `UsbManager`、`libusb`、`UsbDevice`、`UsbInterface`、`USB_HOST`、`android.hardware.usb`、`PtpIp/PTPIP`，源码命中 0 处；只有 `RemoteCam` 的两处打包脚本提到 SDK 附带的 `libusb` 许可证/源码，不是传输代码。
2. **10 个仓库是纯 Wi-Fi 方案。** 全部走 Sony Camera Remote API（HTTP/JSON + SSDP `ScalarWebAPI:1`），物理链路是 Wi-Fi，不可能通过 USB 直接与 a7C 通信。
3. **3 个仓库是蓝牙方案。** `alpharemote`（安卓 BLE 遥控）、`furble`（ESP32 BLE 快门）、`sony_camera_ble_remote`（BLE 抓包宏），只做快门/参数，不做图像传输。
4. **唯一同时具备“USB 可能性”和“RAW 传输”的是 `RemoteCam`，但它是桌面（Windows/macOS）方案，不是安卓方案。**
   - 它封装 Sony Camera Remote SDK v2.02.00（README:432），由 SDK 枚举 USB/网络相机（`CamBridge.cpp:174-195`），并由 SDK 把 RAW 存到电脑（`CamBridge.cpp:345-357`、`MainWindow.xaml.cs:142-146`、README:426/480）。
   - 但 README 明确把连接方式写成 Wi-Fi（README:424），并把 a7C 列入“Wi-Fi 连不上”，同时写明“有线连接还没做”（README:439）；SDK 也不随仓库分发（README:497）。
   - 仓库内没有 Android 工程、JNI 层或 SDK 的 Android 构建产物，无法把它搬到手机上。
5. **可复用的只有“零件级”内容：**
   - `alpharemote` 的安卓工程骨架（BLE/前台服务/通知，可改成 USB 前台服务）；
   - `RemoteCam` iOS 端手写的 PTP 事务层，作为 PTP 协议参考（它跑在 TCP/SSH 上，不是 USB）；
   - `RemoteCam` 的 SDK 属性常量（`RAW_J_PC_Save_Image`、`Still_Image_Trans_Size`、`FileType`、`RAW_FileCompressionType`），证明 a7 系列在 SDK 语义上如何区分 JPEG/RAW；
   - `kz-remote-api`/`locana` 的 Wi-Fi 内容模型，仅用于理解 Sony 返回的 `original[]`/`stillObject` 结构。

**要实现目标，必须新增安卓端 USB Host + PTP 传输层（或使用官方支持安卓的 SDK，但本批仓库中没有）。**

---

## 二、技术底账表

| 仓库 | 协议 / 传输 | 是否有 USB 直连代码 | 是否有 RAW/ARW 路径 | 平台 / 语言 | 最后提交 | 对本目标价值 |
|---|---|---|---|---|---|---|
| alpha-fairy | 无代码（仅 `.git`） | 无 | 无 | — | 无提交 | 0 |
| alpharemote | BLE（Sony 蓝牙遥控 GATT） | 无 | 无 | Android / Kotlin，compileSdk 37 | 2026-11-02 | 仅安卓工程骨架 |
| Camera-Remote-API | Wi-Fi HTTP/JSON（硬编码 192.168.122.1:8080 / 10.0.0.1:10000）<!-- allow-sensitive: 相机 AP 的公开默认网关，非本机内网 --> | 无 | 无（通用 JSON 调试器，无 content 流程） | Web / JS | 2014-08-22 | 历史参考 |
| furble | BLE（ESP32 硬件快门） | 无 | 无 | ESP32 / C++ | 2026-09-13 | 与本目标无关 |
| kz-remote-api | Wi-Fi HTTP/JSON + SSDP | 无 | 仅常量/元数据，无下载实现 | Windows/UWP / C# | 2019-10-04 | 协议与数据模型参考 |
| locana | Wi-Fi HTTP/JSON + SSDP | 无 | 只取 JPEG original；`.arw` 仅作缩略图改名 | Windows 10 UWP / C# | 2017-11-15 | Wi-Fi 客户端参考 |
| ofxSonyRemoteCamera | Wi-Fi HTTP/JSON | 无 | 无 | C++ / openFrameworks | 2013-09-29 | 历史参考 |
| **RemoteCam** | 桌面：Sony Camera Remote SDK（Wi-Fi，可枚举 USB）；iOS：SSH + PTP/IP | **间接**：SDK `EnumCameraObjects` / `Connect`，无自有 USB 代码 | **有**：SDK `SetSaveInfo` + `RAW_J_PC_Save_Image`，实测传 RAW | Windows/macOS（.NET 10 + C++）；iOS Swift | 2026-11-04 | **唯一接近，但非安卓、需专有 SDK** |
| RemoteCamera | Wi-Fi HTTP/JSON（IP 写死 QX100） | 无 | 无 | Node/JS | 2015-01-02 | 历史参考 |
| sony_camera_api | Wi-Fi HTTP/JSON + SSDP | 无 | `getContentList` 元数据，无传输 | Python 2/3 | 2021-04-02 | 快速探测接口 |
| sony_camera_ble_remote | BLE 抓包宏（XML） | 无 | 无 | XML | 2023-04-09 | BLE 协议笔记 |
| sony-camera-api | Wi-Fi HTTP/JSON（resty） | 无 | 无 | Shell/Python | 2013-12-16 | 历史参考 |
| sony-remote | Wi-Fi HTTP/JSON | 无 | 无 | Python 2 + OpenCV | 2015-06-30 | 历史参考 |
| SonyCameraRemoteAPI | Wi-Fi HTTP/JSON + SSDP | 无 | 无（`actTakePicture` 返回 JPG URL） | iOS / Objective-C | 2014-04-15 | 历史参考 |
| sonypy | Wi-Fi HTTP/JSON + SSDP | 无 | 无（`actTakePicture` 返回 JPG） | Python 2 | 2013-12-23 | 历史参考 |

> “最后提交”取各仓库当前 HEAD 的 author date；这些仓库多为浅克隆，不能据此判断完整维护历史。

---

## 三、逐项证据

### 3.1 连接协议：Wi-Fi、SDK、PTP/IP、BLE 各是谁

**Wi-Fi（Sony Camera Remote API，HTTP/JSON + SSDP）**

- `Camera-Remote-API/webapp/cameraApp.js:2-3`：
  `http://192.168.122.1:8080/sony`、`http://10.0.0.1:10000/sony`。<!-- allow-sensitive: 相机 AP 的公开默认网关，非本机内网 -->
- `RemoteCamera/README.md:35`：IP/端口写死给 DSC-QX100。
- `sony-remote/sony.py:30`：`http://192.168.122.1:8080/sony/camera`。<!-- allow-sensitive: 相机 AP 的公开默认网关，非本机内网 -->
- `sony-camera-api/camera.sh:7,43`：`resty http://10.0.0.1:10000`，`POST /sony/camera`。<!-- allow-sensitive: 相机 AP 的公开默认网关，非本机内网 -->
- `ofxSonyRemoteCamera/src/ofxSonyRemoteCamera.cpp:184-193`：`actTakePicture` → `httpPost`。
- `sony_camera_api/src/pysony.py:21-30`：SSDP `239.255.255.250:1900`，`ST: urn:schemas-sony-com:service:ScalarWebAPI:1`。
- `SonyCameraRemoteAPI/SonyCameraRemoteAPIClient.m:178-196`：同样的 SSDP M-SEARCH。
- `locana/README.md:1-6`：Windows 10 客户端；`RemoteApiContentsLoader.cs` 走同样的内容接口。
- `kz-remote-api/README.md:4-5`：Sony Camera Remote API beta v2.3.0 的 C# 封装。

**Sony Camera Remote SDK（原生 C++ 共享库）**

- `RemoteCam/README.md:432`：`Built on | Sony's Camera Remote SDK v2.02.00`。
- `RemoteCam/native/CamBridge/CamBridge.cpp:180`：`SDK::EnumCameraObjects`。
- 同文件 `:239-240`：`SDK::CreateCameraObjectInfoEthernetConnection`（IP 方案）。
- 同文件 `:264`：`SDK::Connect(info, ...)`，USB 与 IP 的 info 都走同一入口。
- 这是 C++ 库封装，应用层通过 P/Invoke 调用，不是 Android 代码。

**PTP/IP（仅 RemoteCam 的 iPhone 端，跑在 TCP/SSH 上）**

- `RemoteCam/README.md:540`：iPhone 端通过 SSH 和 PTP/IP 直连相机。
- `RemoteCam/app/CamRemote.iOS/Sources/CamSSH.c:16-17`：远端端口 `64321`（Sony PTP/IP）、命令端口 `15740`；`:32` 使用 `socket(AF_INET, SOCK_STREAM)`。
- `RemoteCam/app/CamRemote.iOS/Sources/Camera.swift:973-975`：`0x1001/0x1002` 会话握手；`:1328-1333` 通过 `cam_ssh_write` 收发 PTP 包；`:1156-1161` 用 `0x1008/0x1009` 读取相机待取照片对象（只记录字节数，用于清空待取队列，不保存文件）。
- 这条链路依赖 SSH 隧道和网络，**不是 USB**。

**BLE**

- `alpharemote/README.md:2,18`：安卓蓝牙遥控。
- `alpharemote/app/src/main/AndroidManifest.xml:4-12`：只声明 companion device、`BLUETOOTH_CONNECT`、前台服务/通知权限。
- `furble/README.md:13-16`：使用相机原生 BLE 接口，ESP32/PlatformIO。
- `sony_camera_ble_remote/Record.xml:2-10`：服务 `8000ff00-...`，特征 `ff01/ff02`，写 `0x010f/0x010e`。

**空仓库**

- `alpha-fairy` 只有 `.git`，没有工作树文件，也没有提交。

### 3.2 是否有任何代码真正走 USB 有线路径

**没有仓库自己实现 USB 传输。** 全仓代码检索 `UsbManager`、`libusb`、`UsbDevice`、`UsbInterface`、`USB_HOST`、`android.hardware.usb`、`PtpIp/PTPIP` 均为 0 命中。

唯一“可能走 USB”的路径在 `RemoteCam`，但它是**索尼 SDK 内部行为**，不是本仓库代码：

- `RemoteCam/native/CamBridge/CamBridge.cpp:174-195`：注释写 “Finds cameras on USB and the local network.”，随后调用 `SDK::EnumCameraObjects`，并用 `GetConnectionTypeName()` 读取连接类型。
- `RemoteCam/native/CamBridge/CamBridge.cpp:256-266`：`cb_connect` 对任意枚举到的 info 调用 `SDK::Connect`；USB 对象与 IP 对象共用此入口。
- `RemoteCam/app/CamRemote/CameraConnect.cs:105-135`：选中的相机若不是 IP（即 SDK 报 USB），就不做 SSH 指纹，直接 `ConnectAsync`。
- SDK 的 USB 后端不在仓库内：`RemoteCam/README.md:497` 要求自行把 SDK 解压到 `vendor/CrSDK`；`RemoteCam/tools/make_notices.py:18` 与 `package-mac.sh:33` 只打包/声明 SDK 附带的 `libusb`。
- 但产品口径是 Wi-Fi：`README.md:424`（Connection = Wi-Fi）、`README.md:9`（“with no cables”）、`README.md:439`（a7C 等机型“有线连接还没做”）。

因此准确表述是：**`RemoteCam` 的代码路径允许 SDK 枚举 USB 并连接，但没有仓库级的 USB 实现，也没有产品化/验证证据。**

其余仓库的“USB”只出现在 README 对比表（如 `RemoteCam/README.md:446` 提到富士 SDK 支持 USB），与本项目代码无关。

### 3.3 是否有获取 RAW（ARW）/原始文件的代码路径

**有，且只有 `RemoteCam` 是真正把 RAW 传到电脑的：**

- `RemoteCam/app/CamRemote/MainWindow.xaml.cs:142-146`：
  - `StillImageStoreDestination`（存电脑/卡/两者）
  - `Still_Image_Trans_Size`（电脑副本尺寸）
  - `RAW_J_PC_Save_Image`（RAW/JPEG 送电脑）
  - `FileType`、`RAW_FileCompressionType`（文件格式与 RAW 压缩类型）
- `RemoteCam/app/CamRemote/CameraService.cs:626-631`：`SendsToComputer` 判定与 RAW/尺寸属性的生效条件。
- `RemoteCam/native/CamBridge/CamBridge.cpp:345-357`：`SDK::SetSaveInfo` 指定保存目录；`:80` `OnCompleteDownload` 通知下载完成。
- `RemoteCam/README.md:426`：`Photos to your computer | Full size, or a small JPEG. A full-size RAW took 4 to 12 seconds...`
- `RemoteCam/README.md:480`：`A full-size RAW can take 4 to 12 seconds to arrive...`
- 结论：这是 **Sony SDK 的“拍摄后由相机推送/下载到电脑”路径**，不是 Remote API 的 `getContent`，也不是安卓可用的实现。

**其他仓库只有常量/元数据，没有 RAW 下载实现：**

- `kz-remote-api/Project/Util/AvContentParams.cs:63-69`：`ImageType.Jpeg/Raw/Mpo`，`Raw = "raw"` 只是字符串常量。
- `kz-remote-api/Project/Util/AvContentStructures.cs:190-217`：`ImageContent.original[]`，元素含 `url/fileName/stillObject`。
- `kz-remote-api/Project/Api/AvContentApiClient.cs:81`：只序列化 `getContentList`，仓库没有下载文件的代码。
- 易误读点：
  - `CameraParams.cs:22-25` 的 `Original` 是 **postview 尺寸**（`"Original"`/`"2M"`），不是 RAW 传输模式。
  - `CameraParams.cs:278-282` 的 `ContentTransfer = "Contents Transfer"` 是 **相机功能名**，不是 `setContentTransferMode`。
- 全仓检索 `setContentTransferMode` / `getContentTransfer` / `ContentFile`：0 命中。

**`locana` 明确只处理 JPEG：**

- `locana/Locana/Playback/Loader/RemoteApiContentsLoader.cs:226-235`：静态图只在 `original.Type == ImageType.Jpeg` 时保存 `OriginalUrl`；RAW 类型被跳过。
- `locana/Locana/Pages/ContentsGridPage.xaml.cs:1096-1100`：实际下载的就是这个 `OriginalUrl`（因此是 JPEG）。
- `locana/Locana/Utility/ThumbnailCacheLoader.cs:99-101`：`.arw` 仅把扩展名改成 `.jpg` 作为缩略图缓存文件名，不解析 ARW。

**`sony_camera_api`：**

- `sony_camera_api/src/pysony.py:642-645` 有 `getContentList`；`src/api_list.py:43-48` 只列内容列表/删除等，没有 `setContentTransferMode`。
- 因此它只能拿到 `original[]` 的 URL 元数据，仓库内没有 RAW 下载实现。

**其余老客户端只拍 JPEG：**

- `sonypy/sonypy/camera.py:80` 只有 `actTakePicture`，返回 JPG URL。
- `SonyCameraRemoteAPI/README.md:30`、`SonyCameraRemoteAPI/SonyCameraRemoteAPI/ViewController.m:78`：同样只调 `actTakePicture`。
- `ofxSonyRemoteCamera/src/ofxSonyRemoteCamera.cpp:184-193`：同样只调 `actTakePicture`。

### 3.4 是否有 Android 平台实现

**只有 `alpharemote` 是安卓工程，而且它是 BLE，不是 USB。**

- `alpharemote/app/build.gradle.kts:11-16`：`compileSdk = 37`、`minSdk = 31`、`targetSdk = 37`，Kotlin + Android Gradle Plugin。
- `alpharemote/app/src/main/AndroidManifest.xml:4-12`：
  - `android.software.companion_device_setup`
  - `BLUETOOTH_CONNECT`
  - `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`
  - 前台服务/通知/WAKE_LOCK
  - **没有** `android.hardware.usb.host`，**没有** USB device attached 权限/过滤器。
- `alpharemote/README.md:48`：明确没有预览和图像传输功能。
- 全仓 `AndroidManifest.xml` 只有这一个。

其余仓库：Windows（`locana`、`kz-remote-api`、`RemoteCam` 桌面）、macOS/iOS（`RemoteCam` iPhone、`SonyCameraRemoteAPI`）、ESP32（`furble`）、Web（`Camera-Remote-API`、`RemoteCamera`）、Python/Shell。

### 3.5 活跃度、语言、依赖、能否编译

| 仓库 | 语言 | 关键依赖/工程 | 能否直接编译（未实测，按构建文件判断） |
|---|---|---|---|
| alpharemote | Kotlin | Gradle，compileSdk 37 / minSdk 31 | 需要较新的 Android SDK 37；工程完整 |
| furble | C++ | PlatformIO + espressif32 6.12.0 | 可编译（需联网拉平台包） |
| RemoteCam | C#/C++/Swift | .NET 10 WPF（`CamRemote.csproj:5-7`）、CMake、Xcode；**必须自备 Sony SDK**（README:497） | **不能直接编译**（SDK 不在仓库） |
| locana | C#/XAML | VS2017，submodule（README:16-26） | 需 VS2017 + 初始化子模块；老 UWP 工具链 |
| kz-remote-api | C# | .NET 4.5 / UWP / WP8，Json.NET（README:17-22） | 老框架，现代 VS 可能缺 UWP/WP8 目标 |
| RemoteCamera | JS/CoffeeScript | express 3.4.8（`package.json:5`）、grunt 0.4（`:10`） | 2014 年依赖，现代 Node 下大概率失败 |
| sony_camera_api | Python | 纯标准库 + pip（setup.py:41-42 支持 Py2/Py3） | 可运行（Python 3） |
| sonypy | Python 2 | requests（setup.py） | Py2 专用，现代环境需改 |
| sony-remote | Python 2 | OpenCV（README:18-20） | Py2 + 老 OpenCV |
| Camera-Remote-API | JS/HTML | Chrome Web App，无构建链 | 作为网页直接跑 |
| SonyCameraRemoteAPI | Objective-C | CocoaPods（README:12） | 需 Xcode |
| ofxSonyRemoteCamera | C++ | openFrameworks addon（install.xml） | 需老版 openFrameworks |
| sony-camera-api | Shell/Python | resty 子模块（README:15-19） | 需初始化 resty |
| sony_camera_ble_remote | XML | nRF Connect 宏 | 无需编译 |
| alpha-fairy | — | — | 空仓库 |

**逆向/抓包产物识别：**

- `sony_camera_ble_remote/README.md:38` 明确是对 Sony BLE 遥控的逆向记录，`Record.xml:2-10` 是抓包得到的 GATT 写值。
- `RemoteCam/app/CamRemote.iOS/Sources/Camera.swift:664` 注释写明 Sony 未公开该 PTP 操作码、参考 libgphoto2 命名（`0xD2CA`/`0xD2E2`，用于格式化存储卡），属于逆向实现。
- 其余 Wi-Fi 客户端基本是官方 Camera Remote API 的示例/封装。

---

## 四、可复用性判断（按目标拆解）

### 可直接复用

1. **`alpharemote` 的安卓壳**
   - 可复用 Gradle/Kotlin 工程、前台服务、通知、设置页、BLE 权限处理。
   - 不能复用其传输层：它没有 USB，也没有图像下载（README:48）。
2. **`RemoteCam` 的 SDK 属性语义**
   - `RAW_J_PC_Save_Image`、`Still_Image_Trans_Size`、`FileType`、`RAW_FileCompressionType` 可用来确认 a7 系列在“RAW/JPEG 保存”上的状态机。
   - 只能用于理解，不能直接搬到 Android（SDK 是桌面 C++ 库）。

### 只能当协议参考

1. **`RemoteCam` iPhone 的 PTP 层**
   - `CamSSH.c:16-17` 的 64321/15740 端口、`Camera.swift:973-975` 的 `0x1001/0x1002` 会话握手、`:1156-1161` 的 `0x1008/0x1009` 对象读取，展示了 PTP 事务顺序。
   - 但它的传输是 PTP/IP over TCP/SSH（`CamSSH.c:32`），不是 USB PTP；移植到安卓仍需自己实现 USB 端点发现、PTP over USB 的批量传输和 Sony 厂商操作码。
2. **`kz-remote-api` / `locana` 的内容模型**
   - `original[]`、`stillObject`、`ImageType.Jpeg/Raw` 可用于理解 Wi-Fi API 的返回结构。
   - 它们不提供 USB 传输，也不下载 RAW。

### 基本无价值

- `Camera-Remote-API`、`RemoteCamera`、`ofxSonyRemoteCamera`、`sony-camera-api`、`sony-remote`、`SonyCameraRemoteAPI`、`sonypy`：2013-2015 年的 Wi-Fi 示例，只有 `actTakePicture`/liveview。
- `furble`、`sony_camera_ble_remote`：BLE 快门，与 RAW/USB 无关。
- `alpha-fairy`：空仓库。

---

## 五、未验证项与风险

1. **a7C（ILCE-7C）在 Sony Camera Remote SDK 中的支持情况：未验证。**
   - `RemoteCam/native/CamBridge/CamBridge.cpp:211-227` 的型号表只有 `ILCE-7CM2`、`ILCE-7CR`，没有 `ILCE-7C`；未知型号回退到 `ILCE_7SM3`（`:227`）。
   - 即便走 SDK 的 USB 枚举，也不能从仓库确认 a7C 一代可用。
2. **Sony Camera Remote SDK 是否可用于 Android：未验证。**
   - 仓库没有 Android JNI/`.so`/Gradle 集成；README 只给 Windows/macOS 构建说明（README:497-523）。
3. **a7C 在 USB PTP 模式下是否暴露 ARW 对象、是否需要 PC Remote 模式：未验证。**
   - 本批仓库没有 USB PTP 实现，无法从代码推断；需要在真机上用 USB 抓包/PTP 工具验证。
4. **编译结论均未实测。** 上表“能否直接编译”是按构建文件和 README 推断，未执行 Gradle/CMake/VS/PIO 构建。
5. **浅克隆。** 各仓库 HEAD 多为单提交浅克隆，“最后提交”只能说明当前快照时间，不代表上游完整历史。

---

## 六、一句话回答

**这批代码能给你“安卓 BLE 遥控壳”“Wi-Fi 遥控与内容模型”“PTP 事务参考”和“桌面 SDK 的 RAW 语义”，但不能给你任何安卓 USB 传输实现；要实现安卓 USB 直连 a7C 传 RAW，必须新写 USB Host + PTP 层，`RemoteCam` 只能作为协议与属性语义的参考，不能作为可移植底座。**

---

## 附：关键证据文件索引

- `RemoteCam/native/CamBridge/CamBridge.cpp`（USB 枚举/连接、SetSaveInfo、型号表）
- `RemoteCam/app/CamRemote/CameraConnect.cs`（非 IP/USB 连接分支）
- `RemoteCam/app/CamRemote/MainWindow.xaml.cs`（RAW/JPEG 保存属性）
- `RemoteCam/app/CamRemote.iOS/Sources/CamSSH.c`（PTP/IP over SSH）
- `RemoteCam/app/CamRemote.iOS/Sources/Camera.swift`（PTP 会话握手/GetObject 对象读取）
- `RemoteCam/README.md`（SDK 版本、Wi-Fi、RAW 实测、有线未实现）
- `alpharemote/app/src/main/AndroidManifest.xml`（安卓权限，无 USB）
- `locana/Locana/Playback/Loader/RemoteApiContentsLoader.cs`（只取 JPEG original）
- `kz-remote-api/Project/Util/AvContentParams.cs`（`ImageType.Raw` 常量）
- `kz-remote-api/Project/Util/AvContentStructures.cs`（`original[]` 模型）
- `sony_camera_api/src/pysony.py`（SSDP + `getContentList`）
- `SonyCameraRemoteAPI/SonyCameraRemoteAPI/SonyCameraRemoteAPIClient.m`（SSDP）
