# 取证笔记（审计中间产物）

日期：2026-11-04
范围：`<本机工作区绝对路径>` 下 15 个仓库，只读。

## 1. 全仓关键字检索（排除 .git、二进制）
检索式：
- `UsbManager|libusb|UsbDevice|UsbInterface|USB_HOST|android.hardware.usb|ptpip|PtpIp|PTPIP`
  - 代码命中 0 处（只有以下 2 处非代码命中）：
    - `RemoteCam/tools/make_notices.py:18`（读取 SDK 内 `libusb.zip` 许可证）
    - `RemoteCam/package-mac.sh:33`（打包 `libusb-source.zip`）
- `setContentTransferMode|getContentTransfer|ContentFile|.arw|ARW`
  - 代码命中仅：
    - `locana/Locana/Utility/ThumbnailCacheLoader.cs:99-101`（.arw 改名 .jpg 作缩略图缓存名，不解析 ARW）
  - 其余为 SSDP 字符串、README/memory 文档，无 RAW 下载实现。
- `AndroidManifest.xml` 全仓仅 1 个：`alpharemote/app/src/main/AndroidManifest.xml`。

## 2. git HEAD（最后提交）
alpha-fairy（无提交，仅 .git）
alpharemote 2026-11-02 8dffccf
Camera-Remote-API 2014-08-22 f9d068c
furble 2026-09-13 0cac22a
kz-remote-api 2019-10-04 2b4b312
locana 2017-11-15 f7c2489
ofxSonyRemoteCamera 2013-09-29 21c94f8
RemoteCam 2026-11-04 42b9130
RemoteCamera 2015-01-02 b39954f
sony_camera_api 2021-04-02 c397080
sony_camera_ble_remote 2023-04-09 3bdfeba
sony-camera-api 2013-12-16 49d6f60
sony-remote 2015-06-30 2d9d7ff
SonyCameraRemoteAPI 2014-04-15 d6b400f
sonypy 2013-12-23 8bc291f

## 3. USB 证据
- 无任何仓库直接调用 Android UsbManager / libusb / UsbDevice / PTPIP API。
- RemoteCam 间接路径（索尼 Camera Remote SDK）：
  - `RemoteCam/native/CamBridge/CamBridge.cpp:174` 注释 “Finds cameras on USB and the local network.”
  - 同文件 `:180` `SDK::EnumCameraObjects`
  - `:185` `GetConnectionTypeName()`
  - `:256-266` `cb_connect` → `SDK::Connect(info, ...)`，USB 与 IP 的 info 都走这里
  - `RemoteCam/app/CamRemote/CameraConnect.cs:105-135`：非 IP（即 USB）对象不做 SSH 指纹，直接连接
  - `RemoteCam/README.md:424` 标称连接方式为 Wi-Fi
  - `RemoteCam/README.md:439` “A wired connection could bring some of them in. It hasn't been built yet.”
  - `RemoteCam/README.md:497` SDK 不随仓库分发，需自行解压到 `vendor/CrSDK`
- RemoteCam iOS 是 PTP/IP（TCP+SSH），不是 USB：
  - `RemoteCam/app/CamRemote.iOS/Sources/CamSSH.c:16-17` 端口 64321 / 15740；`:32` `socket(AF_INET, SOCK_STREAM)`
  - `Camera.swift:1328-1332` 通过 `cam_ssh_write` 收发 PTP 包；`:973-975` OpenSession；`:1156-1161` GetObject
- 结论：USB 能力只在 SDK 封装层“存在”，仓库自身没有 USB 实现。

## 4. RAW 证据
- RemoteCam（唯一真实 RAW 传输路径，桌面端）：
  - `README.md:426` “Full size ... A full-size RAW took 4 to 12 seconds”
  - `README.md:480` “A full-size RAW can take 4 to 12 seconds to arrive”
  - `app/CamRemote/MainWindow.xaml.cs:142-146`：StillImageStoreDestination / Still_Image_Trans_Size / `RAW_J_PC_Save_Image` / FileType / `RAW_FileCompressionType`
  - `app/CamRemote/CameraService.cs:626-631` `SendsToComputer`
  - `native/CamBridge/CamBridge.cpp:345-357` `SDK::SetSaveInfo`；`:80` `OnCompleteDownload`
- kz-remote-api 只有常量与元数据，无传输实现：
  - `Project/Util/AvContentParams.cs:63-66` `ImageType.Raw = "raw"`
  - `Project/Util/AvContentStructures.cs:190-217` `ImageContent.original[]` / `stillObject`
  - `Project/Api/AvContentApiClient.cs:78-81` 仅 `getContentList`
  - 注意易误读：`CameraParams.cs:22-25` 的 `Original` 是 **postview 尺寸**；`:281` 的 `Contents Transfer` 是 **相机功能名**，都不是 RAW 传输模式。
- locana 明确只取 JPEG：
  - `Playback/Loader/RemoteApiContentsLoader.cs:226-235` 仅在 `original.Type == ImageType.Jpeg` 时取 URL
  - `Pages/ContentsGridPage.xaml.cs:1096-1100` 下载该 OriginalUrl（JPEG）
  - `Utility/ThumbnailCacheLoader.cs:99-101` .arw 仅改名为 .jpg 作缩略图缓存
- pysony：
  - `src/pysony.py:642-645` `getContentList`，无 content transfer 方法
  - `src/api_list.py:43-48` 只列 getContentList/deleteContent 等
- 其余 Wi-Fi 客户端只做 `actTakePicture`（返回 JPEG URL），无 content 列表/下载：
  - `sonypy/sonypy/camera.py:76-81`
  - `SonyCameraRemoteAPI/README.md:30-32`
  - `ofxSonyRemoteCamera/src/ofxSonyRemoteCamera.cpp:186-200`

## 5. Android 证据
- 仅 `alpharemote`：
  - `AndroidManifest.xml:4-12` 只有 companion device + BLUETOOTH_CONNECT + 前台服务/通知权限；无 `android.hardware.usb.host`、无 USB 权限。
  - `app/build.gradle.kts:11-16` compileSdk 37 / minSdk 31 / targetSdk 37。
  - `README.md:48` “There is no way to get a preview of the image, transfer the image...”

## 6. 协议入口
- Wi-Fi HTTP/JSON：
  - `Camera-Remote-API/webapp/cameraApp.js:2-3` 192.168.122.1:8080 / 10.0.0.1:10000 <!-- allow-sensitive: 第三方仓库里相机 AP 的公开默认网关，非本机内网 -->
  - `RemoteCamera/server/routes/camera.js:4` 10.0.0.1:10000 <!-- allow-sensitive: 第三方仓库里相机 AP 的公开默认网关，非本机内网 -->
  - `sony-remote/sony.py:30` 192.168.122.1:8080/sony/camera <!-- allow-sensitive: 第三方仓库里相机 AP 的公开默认网关，非本机内网 -->
  - `sony-camera-api/camera.sh:6-7,43` resty POST /sony/camera
  - `ofxSonyRemoteCamera/src/ofxSonyRemoteCamera.cpp:8,28-45`
  - `sony_camera_api/src/pysony.py:23` SSDP ScalarWebAPI；`:221` 默认 10.0.0.1:10000 <!-- allow-sensitive: 第三方仓库里相机 AP 的公开默认网关，非本机内网 -->
  - `sonypy/sonypy/camera.py:23-32` requests POST endpoint
  - `SonyCameraRemoteAPI/SonyCameraRemoteAPIClient.m:178` SSDP M-SEARCH
  - `kz-remote-api/README.md:4-6`；`locana/README.md:4`
- BLE：
  - `alpharemote/README.md:2,18`
  - `furble/README.md:13-16`；`platformio.ini:8-14` espressif32
  - `sony_camera_ble_remote/Record.xml:2-10` BLE service/characteristic + 0x010f/0x010e
  - `sony_camera_ble_remote/README.md:38` 逆向来源

## 7. 构建/依赖
- RemoteCam：`.NET 10 WPF`（`app/CamRemote/CamRemote.csproj:5-7`）、CMake/C++、iOS 需 Xcode；**必须自备索尼 SDK**（README:497），本仓库不能直接编译。
- locana：VS2017 + 6 个 submodule（README:16-26，`modules/` 已存在）。
- kz-remote-api：.NET Framework 4.5 / UWP / WP8（`Project/KzRemoteApiDesktop.csproj:12`），Json.NET（README:22）。
- RemoteCamera：express 3.4.8、grunt 0.4、node>=0.8（package.json:5-49），2014 年依赖。
- sonypy：Python 2.7（setup.py:11-12）。
- sony-remote：Python 2.7 + OpenCV（README:18-20）。
- sony_camera_api：Python 2/3（setup.py:41-42）。
- Camera-Remote-API：纯 Chrome Web App，无构建链。
- SonyCameraRemoteAPI：CocoaPods（README:12）。
- ofxSonyRemoteCamera：openFrameworks addon（install.xml）。
- sony-camera-api：resty 子模块（README:15-19）。
- alpharemote：Gradle + Kotlin，compileSdk 37。
- furble：PlatformIO espressif32 6.12.0。
- 以上均**未实际执行编译**。
