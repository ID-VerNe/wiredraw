# 探索记录（Research Notes）

> **这些是探索过程的记录，不是产品文档。** 它们回答的是「当时为什么这么判断」，
> 不是「现在该怎么做」。实现与协作规范在 [`docs/contributing/`](../contributing/) 与
> [`AGENTS.md`](../../AGENTS.md)；对外结论速览在 [`README.md`](../../README.md)。

本目录的每篇记录都绑定当时的**机身固件、Android 版本、工具版本与仓库 HEAD**。
换机型、换固件、换 IED 版本之后，结论可能不再成立——引用前先按文中标注的环境与
复现命令重新验一遍，不要把这里的数字当成永久事实。

## 目录与结论时效

> 下表「环境」列记录的是**当时那台设备**，是历史事实，不随当前测试机集合改名；
> 当前可用的测试机与结论归属见[验证环境矩阵](../verification-matrix.md)。

| 文档 | 记录日期 | 环境 / 对象 | 当时的 HEAD | 时效说明 |
|---|---|---|---|---|
| [a7c-usb-raw-2026-11.md](a7c-usb-raw-2026-11.md) | 2026-11-04 | ILCE-7C 固件 2.00，魅族 20 Infinity / Android 15 (API 35)，USB MTP；Wi-Fi 侧为固件 2.02 | `c6da83a` | 主报告。MTP 枚举、传输速率与完整性、PTP 会话限制等真机结论在同机型同固件下可直接沿用；涉及官方 App、Camera Remote SDK 与第三方软件的部分会随外部产品更新而过时 |
| [a7c-ptpip-session.md](a7c-ptpip-session.md) | 2026-11-04（21:16–21:19 抓包） | ILCE-7C 固件 2.02，Wi-Fi AP 模式，相机与 PC 同一路由器 | `c6da83a` | PTP/IP 会话逐条分析。0.95 MB/s 是该环境（含实时取景）的单次实测，不能当成 Wi-Fi 上限；换固件或换路由器后需重抓 |
| [lja-ptp-ip-static-analysis.md](lja-ptp-ip-static-analysis.md) | 2026-11-04 | Imaging Edge Desktop 1.2.02.08050 的 `Lja_PTP_IP.dll`（文件时间 2025-06-12） | `c6da83a` | 纯静态分析，**未连相机**。换 IED 版本后导出表与推断都可能变化，需重新跑 `tools/pe/pe_inspect.py` |
| [2026-11-audit/](../archive/2026-11-audit/)（4 md + 1 txt） | 2026-11-04 | 官方文档核查 + 15 个第三方参考仓库只读审计，未连相机 | `23318f2` | 历史存档，**不再更新**。用于追溯当时的技术底账与结论来源 |

## 阅读顺序

1. 想快速知道「能不能做、走哪条路」：先读主报告 `a7c-usb-raw-2026-11.md`。
2. 要碰 PTP/IP、握手配对或抓包解析：读 `a7c-ptpip-session.md`。
3. 要解释「为什么早期结论说走 SSH 隧道」：读 `lja-ptp-ip-static-analysis.md`，再看主报告第 10.6 节的修正。
4. 要追溯 15 个参考仓库的分类与逐条判断：读 `../archive/2026-11-audit/`。
