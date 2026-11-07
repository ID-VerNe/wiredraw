# 验证环境矩阵

本仓库的每条结论都绑定一套环境。**换机型、换固件、换连接方式，结论就可能不成立**——
这张表就是用来防止把单机结论读成全平台事实的。

## 怎么读

- 单元格只有三种值：**通过 + 日期 + 证据链接** / **失败 + 错误码** / **未验证**。
- 每套环境固定标注五项：相机机型 / 固件 / 手机型号 / Android API / 连接方式；
  纯 PC 侧路线没有手机，把「手机型号」换成主机说明、Android 列写 `—`。
- 「通过」必须有可点击的证据（报告、工具 README 或 `文件:行号`）。
- 表里没出现的组合一律按「未验证」处理，**不要外推**。
- 新增一行的做法：真机复验 → 填日期与环境 → 补证据链接。没真机跑过的格子只能写「未验证」。
- 手机型号的显示名以设备 `ro.product.model` 为准；同一台机器的旧写法（如 E2 的
  `小米 9` / `Mi 9`）一律按编号读，不另立环境。
- 历史记录（`docs/research/`、`docs/archive/`、`CHANGELOG.md`）里的机型是记录当时的设备，
  是历史事实，**不随本表改名**；判断结论归属用编号，不改原文。

## 环境编号

| 编号 | 相机 | 固件 | 手机 / 主机 | Android | 连接 |
|---|---|---|---|---|---|
| E1 | ILCE-7C | 2.00 | 魅族 20 Infinity | 15 (API 35) | USB MTP |
| E2 | ILCE-7C | 2.00 | MI 9（小米 9，`cepheus`） | 15 (API 35) | USB MTP |
| E3 | ILCE-7C | 2.02 | PC（与相机同一路由器、同一网段） | — | Wi‑Fi PTP/IP |

新结论在 issue / PR 的环境行里仍按 [AGENTS.md](../AGENTS.md) §0.2 写齐五项环境
（机型 / 固件 / 手机 / Android API / 连接方式），并在其后补编号（如 `E2`）——编号只用于
归属，不替代五项。历史文档保留当时机型，归属按记录日期对应的环境读。

历史记录里的 `MI 9` / `Mi 9` / `小米 9` 都指 E2；例如 `#59` / `#61` 的机型记录按本表归属 E2。

## 一、USB MTP 路线

| 能力 | E1 | E2 | 其他机型 / 固件 |
|---|---|---|---|
| 枚举（`open` → `getStorageIds` → 全量 `getObjectHandles` → 逐个 `getObjectInfo`） | 通过 2026-11-04（[主报告 §12.2](research/a7c-usb-raw-2026-11.md)：1526 个对象全覆盖） | 未验证（PC 探针只记录了抽样对象的 `getPartialObject` 语义，没有全量枚举的原始输出） | 未验证 |
| 下载与完整性（字节数 + MD5 + TIFF 文件头） | 通过 2026-11-04（[主报告 §12.3](research/a7c-usb-raw-2026-11.md)） | 通过 2026-11-06（[探针 README「PC 探针」](../tools/android-mtp-probe/README.md)） | 未验证 |
| 插线自动拉起（`ACTION_USB_DEVICE_ATTACHED` → 自动授权 → 扫描） | 通过 2026-11-05 18:48（[导片工具 README「真机待验清单」第 1 项](../tools/android-mtp-import/README.md)；这一批实测的机型 / Android / 连接方式见 [UI-SPEC §0.11 第 7 行](../tools/android-mtp-import/UI-SPEC.md)：ILCE-7C fw2.00 / 魅族 20 Infinity / Android 15 API 35 / USB MTP） | 未验证 | 未验证 |
| 冷启动检测（`autoScanOnLaunch`）+ 同一次插拔去重 | 通过 2026-11-05 18:48（同上第 2 项：广播路径「→ 开始扫描」，attach 路径「→ 扫描已在进行中」，单次会话 1526 对象；同一批次的机型出处同上格） | 未验证 | 未验证 |
| 首次系统授权（勾一次「一律打开」，之后静默） | 通过 2026-11-05 18:48（[导片工具 README「真机待验清单」第 1 项](../tools/android-mtp-import/README.md)：`permissions_manager` 只有既有 `uids`，日志无「尚无 USB 权限，发起申请」；「首次必须勾一次」这一系统约束见 [UI-SPEC §0.11 第 7 行](../tools/android-mtp-import/UI-SPEC.md)） | 未验证 | 未验证 |
| 吞吐 | 通过 2026-11-04：21.43 MB/s（73.29 MB / 3.42 s，[主报告 §12.3](research/a7c-usb-raw-2026-11.md)） | 通过 2026-11-06：22.86 MB/s（`getPartialObject` 分块；App 侧该路径已在 #177 停用，现走 `importFile` 整文件） | 未验证 |

## 二、Wi‑Fi PTP/IP 路线

| 能力 | E3 | 其他环境 |
|---|---|---|
| 发现与配对握手（SSDP + HTTP 64321 → `Init`） | 通过 2026-11-04（顺序见[会话分析 §2.1–§2.3](research/a7c-ptpip-session.md)；**前提**是用当初配对成功的 GUID，随机 UUID 会 `Init_Fail reason=1`，见[会话分析 §5.1 第 1 条](research/a7c-ptpip-session.md)） | 未验证 |
| `OpenSession`（事件通道先建） | 通过 2026-11-04（[会话分析 §5.1 第 2 条](research/a7c-ptpip-session.md)：少这步一个字节都不回；顺序实测见[主报告 §11.2](research/a7c-usb-raw-2026-11.md)） | 未验证 |
| 存储枚举（`GetStorageIDs` / `GetNumObjects` / `GetObjectHandles` / `GetStorageInfo`） | 失败 `AccessDenied`（2026-11-04，[会话分析 §5.2 枚举失败矩阵](research/a7c-ptpip-session.md)） | 未验证 |
| 取最新一张（`GetObjectInfo(0xFFFFC001)` + `GetPartialObject`） | 通过 2026-11-04（[会话分析 §2.5](research/a7c-ptpip-session.md) 的取文件流程；空闲时返回 OK 但 0 字节这一注意事项见 §5.3.1，不是通过证据） | 未验证 |
| 取历史对象（任意旧句柄） | 失败 `AccessDenied`（同上：枚举被拒，拿不到历史句柄） | 未验证 |
| 吞吐 | 通过 2026-11-04：0.95 MB/s（143 MB / 150 s，含实时取景；[会话分析 §1](research/a7c-ptpip-session.md)） | 未验证 |

> 这张表里的「失败」不是代码缺陷，而是**会话类型本身的限制**：PC Remote 会话下相机只放行
> `0xFFFFC001`，存储枚举一律 `AccessDenied`。处置方式见[已知限制](known-limitations.md)。

## 与其他文档的关系

- 门禁的档位与钩子：[测试](testing.md)。
- 结论与边界（不展开实测过程）：[已知限制](known-limitations.md)。
- 原始过程与证据：[主报告](research/a7c-usb-raw-2026-11.md)、
  [PTP/IP 会话分析](research/a7c-ptpip-session.md)。
