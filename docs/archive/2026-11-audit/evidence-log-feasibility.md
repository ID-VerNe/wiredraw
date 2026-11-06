# 取证登记（A/B 部分，主代理自查）

> 检索时间：2026-11-04。所有引文均来自页面原文；"资料时间"为页面标注日期或可确认的发布时间。

## A1 USB 规格

| 项目 | 结论 | 原文/证据 | 链接 | 资料时间 | 类型 |
|---|---|---|---|---|---|
| a7C (ILCE-7C) USB | USB Type-C，SuperSpeed USB 5Gbps（USB 3.2） | "USB Type-C terminal: SuperSpeed USB 5 Gbps (USB 3.2)" | https://helpguide.sony.net/ilc/2020/v1/en/contents/TP1000156734.html | 帮助指南（相机固件 2.x 时代） | 官方明说 |
| a7C 商品规格页 | "USB Type-C® SuperSpeed USB 5 Gbps (USB 3.2) compatible"；PC Interface: Mass-storage / MTP | 同左 | https://www.sony.com/electronics/support/e-mount-body-ilce-7-series/ilce-7c/specifications | 页面未标日期（2020 上市机型） | 官方明说 |
| DPReview 交叉核对 | USB 3.2 Gen 1 (5 Gbit/sec) | 规格表 | https://www.dpreview.com/products/sony/slrs/sony_a7c/specification/ | 2020-10-27 | 第三方 |
| a7C II | SuperSpeed USB 5 Gbps（USB 3.2） | "USB Type-C®端子 ● (SuperSpeed USB 5 Gbps (USB 3.2)に準拠)" | https://www.sony.jp/ichigan/products/ILCE-7CM2/spec.html | 未标日期（2023 上市） | 官方明说 |
| a7 IV | USB 3.2 Gen 2 (10 Gbit/sec) | DPReview 对比表 | https://www.dpreview.com/products/compare/side-by-side/sony_a7iv-vs-sony_a7cii-vs-nikon_zf | 未标日期 | 第三方 |
| a7C 存储卡 | SD (UHS-I/II compliant)，单卡槽 | "Slot for SD (UHS-I/II compliant) memory card" | 同 a7C 规格页 | — | 官方明说 |

注：a7C 的 5Gbps 是"链路能力"，实际持续吞吐受机身 UHS-II 卡读取与相机 USB 实现限制（无官方实测数据）。

## A2 USB 模式

| 项目 | 结论 | 原文 | 链接 | 类型 |
|---|---|---|---|---|
| Setup 菜单 USB Connection | Auto / Mass Storage / MTP 三选一 | "Auto: Establishes a Mass Storage or MTP connection automatically..." / "Mass Storage: ..." / "MTP: ..." | https://helpguide.sony.net/ilc/2020/v1/en/contents/TP1000115335.html | 官方明说 |
| PC Remote | 独立功能：Network → [PC Remote Function]，连接方式 USB / Wi-Fi Direct / Wi-Fi 接入点 | "PC Remote Cnct Method ([USB]/[Wi-Fi Direct]/[Wi-Fi Access Point])" | https://helpguide.sony.net/ilc/2020/v1/de/contents/TP1000155771.html（英文版 TP1000156203） | 官方明说 |
| USB Streaming（UVC） | 固件 Ver.2.00 新增 | "USBストリーミング機能の追加" | http://support.d-imaging.sony.co.jp/www/cscs/firm/?mdl=ILCE-7C | 官方明说 |
| 互斥注意 | 用 USB 时须先关 Ctrl w/ Smartphone；PC Remote 走 USB 时须把 PC Remote 设 Off 才能用 MSC/MTP | "Select MENU → (Network) → [Ctrl w/ Smartphone] → [Off] beforehand. Also ... set [PC Remote] ... to [Off]." | https://helpguide.sony.net/ilc/2020/v1/en/contents/TP1000115335.html | 官方明说 |

## A3 PC Remote 落盘 RAW

| 项目 | 结论 | 原文 | 链接 | 类型 |
|---|---|---|---|---|
| 保存目的地 | PC Only / PC+Camera / Camera Only | "Still Img. Save Dest. ([PC Only]/[PC+Camera]/[Camera Only])" | https://helpguide.sony.net/ilc/2020/v1/en/contents/TP1000156203.html | 官方明说 |
| 传输文件类型 | RAW & JPEG / JPEG Only / RAW Only | "RAW+J PC Save Img: ... ([RAW & JPEG]/[JPEG Only]/[RAW Only])"（仅当影像质量=RAW&JPEG 时可选） | 同上（德文版 TP1000155771 交叉核对） | 官方明说 |
| Imaging Edge Desktop Remote | 可在电脑端设置保存目的地/尺寸/格式，拍摄后落盘 | "In the Remote Shooting settings on the camera, you can check the destination for saving images, the size of saved images, and the format..." | https://support.d-imaging.sony.co.jp/app/imagingedge/en/instruction/4_5_remote.php | 官方明说 |

## B4 CrSDK 支持机型

| 项目 | 结论 | 原文 | 链接 |
|---|---|---|---|
| 支持列表含 ILCE-7C | "ILCE-1M2, ILCE-1, ..., ILCE-7CM2, ILCE-7C, ILCE-6700" | https://support.d-imaging.sony.co.jp/app/sdk/en/index.html | 官方明说 |

## B5 CrSDK 平台

| 项目 | 结论 | 原文 | 链接 |
|---|---|---|---|
| 支持 OS | macOS 14.1+/15.1+/26.0、Windows 11、Linux 64bit(ARMv8)/32bit(ARMv7)/64bit(x86)。无 Android/iOS | "Supported OS: macOS® ... / Windows® 11 / Linux® 64bit (ARMv8) / Linux® 32bit (ARMv7) / Linux® 64bit (x86)" | https://support.d-imaging.sony.co.jp/app/sdk/en/index.html |
| 个人不可下载 | "個人のお客様へはCamera Remote SDKを提供しておりません"（不向个人提供） | https://www.sony.jp/camera-biz/sdk/index.html |

## B6 内容传输 / RAW

| 项目 | 结论 | 原文 | 链接 | 类型 |
|---|---|---|---|---|
| SDK 1.05 起支持 ILCE-7C 的 Contents Transfer mode | "Contents Transfer mode * Compatible with ILCE-7RM4A, ILCE-7C." | https://support.d-imaging.sony.co.jp/app/sdk/en/index.html（发布历史 2021-12-07） | 官方明说 |
| 第三方对 CrSDK 封装的枚举（镜像 SDK 头文件） | FileType: Jpeg=0x0001, Raw=0x0002, RawJpeg=0x0003, RawHeif=0x0004, Heif=0x0005；ControlMode ContentsTransfer=0x00000001；回调 OnNotifyContentsTransfer(handle, filename)、OnCompleteDownload(filename, file_type) | PyPI sonyalphapy 0.1.0 包内 enums.py/_callback.py | https://pypi.org/project/sonyalphapy/ | 第三方 |
| PTP 层证据 | Sony 对象格式 0xb101 = RAW；opcode 0x9212 = SDIO_SetContentsTransferMode；事件 0xC20D = Sony_ContentsTransferEvent | libgphoto2 `camlibs/ptp2/ptp.h` 第 1587/719/1360 行 | https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.h | 第三方（逆向规范级） |

## B7 授权

| 项目 | 结论 | 原文 | 链接 |
|---|---|---|---|
| 可商用分发 | 可把库的二进制形式"不可分离地"嵌入应用并分发给第三方；可向终端用户授权使用 | "(ii) incorporate a binary form of the library file in the SOFTWARE into the APPLICATION SOFTWARE in an inseparable way and distribute the APPLICATION SOFTWARE to any third parties; and (iii) grant the third parties ... a license to copy and use the APPLICATION SOFTWARE..." | https://support.d-imaging.sony.co.jp/app/sdk/licenseagreement_d/en.html |
| 不可单独分发 SDK | "You may not share, distribute, rent, lease, sublicense, assign, transfer or sell the SOFTWARE unless expressly authorized" | 同上（REQUIREMENTS AND LIMITATIONS） | 同上 |
| 商用 FAQ | "はい、使用許諾契約書に基づき自由に販売できます"（可自由销售） | https://www.sony.jp/camera-biz/sdk/index.html |

## C 部分（待子代理结果）

- 见 `work/agent-android.md`、`work/agent-commercial.md`
