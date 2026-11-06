# 归档索引

一行一条：`日期 | 主题 | 结论 | 环境 | 证据（本地文件名，不入库）`。

「证据」列只写本地文件名，这些文件不随仓库分发；重新生成方法见该条说明或对应工具 README。

| 日期 | 主题 | 结论 | 环境 | 证据（本地文件名，不入库） |
|---|---|---|---|---|
| 2026-11-04 | MTP 导 RAW 打通与速率实测 | 1526 个对象 / 511 张 ARW / 11.9 GB；三张 24 MP RAW 共 73.29 MB、3.42 s，21.43 MB/s；MD5 与字节数一致 | ILCE-7C fw2.00 / 魅族 20 Infinity / Android 15 (API 35) / USB MTP | `out/mtp-report.txt`、`out/screen.png`；重跑见 `tools/android-mtp-probe/README.md` |
| 2026-11-04 | PTP/IP 直连会话（IED 协议复刻） | 相机只认当初配对的 GUID；存储枚举四个调用全部 AccessDenied，仅 `GetObjectInfo(0xFFFFC001)` 可用 | ILCE-7C fw2.02 / Wi-Fi PTP/IP，PC 与相机接同一台路由器 | `captures/init_ied.txt`、`captures/stream7.txt`、`captures/wlan.pcapng`（148 MB）；重抓见 `tools/sony-ptpip/README.md` |
| 2026-11-05 | 导片工具 UI 决策取证 | 规范表的 47 个值与页面实际值逐项一致 | 魅族 20 Infinity / Android 15 / 真机 | 本地 `/2026-11-05/` 目录（未跟踪）；说明见 `tools/android-mtp-import/UI-SPEC.md` |
| 2026-11-06 | 仓库开源前整理盘点 | 建立 10 张 issue，覆盖隐私清洗、归档规则、文档收敛与门禁 | 仓库 `main`（只读盘点，未接相机） | 无（结论直接落成 issue） |
