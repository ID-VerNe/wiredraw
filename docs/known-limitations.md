# 已知限制（Known Limitations）

> 本文只列**结论与边界**，不展开实测过程。每条限制的环境、原始输出与复现命令见
> [主报告](research/a7c-usb-raw-2026-11.md) 与各工具 README。
>
> 适用环境：ILCE-7C 固件 2.00；USB MTP 侧为 E1（魅族 20 Infinity）/ E2（MI 9），均 Android 15 (API 35)（另有标注除外）。
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
| `getPartialObject` 的 `offset` 在真机上不生效 | 传非 0 offset 仍返回对象起点数据；把它追加到 `.part` 会得到「字节数对、内容错、日志打 OK」的坏文件 | 2026-11-07 真机实测（E2 MI 9 / Android 15 / USB MTP，见 issue #177 诊断评论）。**止血方案 B：续传已停用**，遗留 `.part` 一律作废、从 0 重传。[探针 README](../tools/android-mtp-probe/README.md) 里 #67 的「绝对偏移」结论已由 #179 撤回，不可作为实现依据 |
| 完成只做尺寸核对 | 尺寸相等不代表内容正确；日志与结果文案必须写明「内容未校验」 | [MtpTransfer.java](../tools/android-mtp-import/src/com/rea/mtpimport/MtpTransfer.java) 的 `finish()`；内容摘要校验尚未实现（issue #177 的修复候选 2） |
| `ThumbnailStore.fetchFull()` 仍按 offset 分块读 | 同名 JPG 预览可能被拼成「尺寸对、内容错」的图且不报错 | [ThumbnailStore.java](../tools/android-mtp-import/src/com/rea/mtpimport/ThumbnailStore.java):294-301；导入路径已停用续传（#177），缩略图路径待改，见 issue #188 |
| `length` 超剩余 / `offset` 越界的探针结论未复测 | 不能把 #67 的边界观察当成稳定契约 | [探针 README](../tools/android-mtp-probe/README.md) 的 PC 探针节；续传停用后导入路径（`MtpTransfer`）已不再发 `getPartialObject`（缩略图路径仍发，见上一行 #188），恢复前必须重新实测 |

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
| 断点续传 | **已停用（#177）** | 真机实测 `getPartialObject` 的 offset 不生效，续传会产出坏文件；遗留 `.part` 一律作废、从 0 重传。恢复前需先解决 offset 语义或改用可校验的分段方案 |
| 其他机型 / 固件 | **未验证** | 本仓库所有真机结论都绑定 ILCE-7C 固件 2.00 / 2.02 与指定手机 |

## 相关文档

- 实测数据与复现命令：[主报告](research/a7c-usb-raw-2026-11.md)
- 只读边界与设备身份处理：[安全与隐私](safety-and-privacy.md)
- 构建与门禁：[构建](building.md)、[测试](testing.md)
