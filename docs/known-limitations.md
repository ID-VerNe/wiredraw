# 已知限制（Known Limitations）

> 本文只列**结论与边界**，不展开实测过程。每条限制的环境、原始输出与复现命令见
> [主报告](research/a7c-usb-raw-2026-11.md) 与各工具 README。
>
> 适用环境：ILCE-7C 固件 2.00、魅族 20 Infinity / Android 15 (API 35)、USB MTP（另有标注除外）。
> 换机型、换固件后结论可能不再成立；跨环境验证状态见[验证环境矩阵](verification-matrix.md)，
> 矩阵里没列出的组合一律按「未验证」处理，不要外推。

## 一、MTP 路线

| 限制 | 后果 | 证据 / 处置 |
|---|---|---|
| 会话结束后约 1 分钟挂起 USB | `MtpDevice.open()` 卡约 10 秒才返回，`getStorageIds()` 一直返回空；应用内重试 8 次（约 70 秒）唤不醒 | [AGENTS.md](../AGENTS.md) §4 与[探针 README](../tools/android-mtp-probe/README.md) 坑 1。**拔插数据线**才能恢复；正式应用要么保持会话常开，要么提示用户重插 |
| `getObjectHandles` 的 format 过滤不可信 | 传 `0xB101` 返回 754 个，逐个 `getObjectInfo` 只有 511 个真是 `0xB101`，差集对不上账 | [主报告](research/a7c-usb-raw-2026-11.md) §12.4。不用 format 过滤；走全量枚举（约 39 ms/个，1526 个约 59 秒）或按 `parent` 逐目录 |
| 首次插线需要用户授权 | 系统会弹「是否允许访问 MTP」；首次还要勾一次「一律打开」 | [导片工具 README](../tools/android-mtp-import/README.md) 的安装运行节。应用侧没有正规接口能绕过，只能引导用户操作 |
| 扫描是串行的 | 扫描与导入不能在同一 MTP 会话上并行发命令 | [ImportGate.java](../tools/android-mtp-import/src/com/rea/mtpimport/ImportGate.java):17 与[导片工具 README](../tools/android-mtp-import/README.md):251。导入前必须等扫描结束；渐进加载期间主操作要禁用 |
| 单机结论 | 枚举数 1526 个对象 / 511 张 ARW / 11.9 GB 是 ILCE-7C 该卡的单次实测 | [探针 README](../tools/android-mtp-probe/README.md) 的实测结果节。不能当成所有机身、所有卡的固定值 |

## 二、传输语义

| 限制 | 后果 | 证据 / 处置 |
|---|---|---|
| `getPartialObject` 的 `offset` 是绝对偏移 | 续传必须按 `.part` 的实际长度从绝对偏移续读 | [探针 README](../tools/android-mtp-probe/README.md) 的「PC 探针（getPartialObject）」节 |
| `length` 超过剩余字节会截断到 EOF | 不抛异常，必须靠字节数对账判断是否读完 | 同上（同一节记录「剩余多少就返回多少」） |
| `offset == size + 1` 会抛 `IOException` | 越界请求会打断读取；越界后会话是否进入错误态**只有首轮观察、尚未复测** | 同上（同一节的越界观察）。正常续传实现绝不能发出 `offset > size` 的请求 |
| 分块不宜过小 | 64 KiB 小块单次只有约 0.38–0.43 MB/s | 同上（同一节的吞吐对照）。分块建议不小于 1 MiB |

## 三、PTP / PTP-IP 路线

| 限制 | 后果 | 证据 |
|---|---|---|
| PC Remote 会话下存储枚举被拒 | `GetStorageIDs` / `GetObjectHandles` / `GetNumObjects` / `GetStorageInfo` **全部 AccessDenied** | 该会话只能拿 `GetObjectInfo(0xFFFFC001)` 指向的最新一张 |
| `0xFFFFC001` 空闲时返回 OK 但 0 字节 | 只看返回码会把「没有新照片」误判成成功 | 必须判数据长度 |
| 握手必须使用当初配对成功的 GUID | 随机 UUID 一律 `Init_Fail reason=1` | 配对凭据不入库，见[安全与隐私](safety-and-privacy.md) |
| 事件通道必须先于 `OpenSession` | 少这步 `OpenSession` 一个字节都不回，只超时（不是被拒） | [PTP/IP 工具 README](../tools/sony-ptpip/README.md) |
| Wi-Fi PTP 速率低 | 该环境实测约 0.95 MB/s（含实时取景的单次测量，不是上限） | 对比 USB MTP 约 21.43 MB/s；换固件或路由器需重测 |

## 四、未验证的能力

| 能力 | 状态 | 说明 |
|---|---|---|
| MSC 直拷 | **未验证** | 安卓侧需要额外库，且只适用于 FAT32 卡；不要当成可用方案 |
| `getThumbnail` 对 ARW | **真机未验** | 拿不到时回退同名 JPG，再退回灰块；不得崩溃 |
| 断点续传端到端 | **App 内待真机** | 分块语义已由探针实测，App 编排与恢复窗口未验 |
| 其他机型 / 固件 | **未验证** | 本仓库所有真机结论都绑定 ILCE-7C 固件 2.00 / 2.02 与指定手机 |

## 相关文档

- 实测数据与复现命令：[主报告](research/a7c-usb-raw-2026-11.md)
- 只读边界与设备身份处理：[安全与隐私](safety-and-privacy.md)
- 构建与门禁：[构建](building.md)、[测试](testing.md)
