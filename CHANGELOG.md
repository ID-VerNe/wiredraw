# Changelog

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。
本仓库此前不发版；下面是截至 2026-11-07 的能力快照，不逐条回溯历史提交。
后续改动按 Added / Changed / Deprecated / Removed / Fixed / Security 归类记在 `Unreleased` 段。

## Unreleased

### Added

- MTP 只读链路：安卓侧枚举整卡并下载历史 RAW（ILCE-7C 固件 2.00 / 魅族 20 Infinity /
  Android 15 (API 35) / USB 有线）；三张 24 MP RAW 实测 73.29 MB / 3.42 s ≈ 21.43 MB/s，
  字节数、MD5 与 TIFF 文件头三项校验通过。
- PTP/IP 只读探针与 pcap 解析器：复刻 IED 引导序列；同一会话内只能取最新一张
  （`0xFFFFC001`），存储枚举返回 `AccessDenied`。
- 导片工具骨架（Kotlin + Compose，`minSdk 31` / `targetSdk 35`）：照片墙、选择、
  导入进度、暂停 / 取消 / 恢复；`getPartialObject` 断点续传自 #177 起停用；真机待验项见
  [导片工具 README](tools/android-mtp-import/README.md)。
- 三档门禁（`ci` / `manual` / `support`）：清单唯一事实源 `scripts/gates.json`，
  本地与 CI 跑同一份命令。

### Changed

- 首屏「新片」语义按维护者裁决降级收口为「全部可见文件」（issue #20）：相机即拍即传 NAS、
  本机不留存未导入旧片，「首屏 N 张新照片」的立单前提不成立；防重传已由 Journal
  （跨会话主键 `size + relPath`，E2 实测 671 计划跳过 664）覆盖，首屏不承诺新片数、
  CTA 恒为 `Import all N`。
- 导片工具停用 MTP 断点续传（#177）：非零 `offset` 只有在恰好等于相机内部游标时才被采纳；
  对不上时忽略 offset、从对象头返回。续传起点与游标对不上，且续读不省带宽（要先从 0 读到该
  位置），遗留 `.part` 一律作废、每次从 0 重传。完成状态只做尺寸核对（内容未校验），不再把
  「尺寸凑够」当成校验通过。证据是 R0–R3 判别与同轮 25 次顺序拼接 MD5 与整文件一致的合解
  （E2 MI 9 / Android 15 API 35 / ILCE-7C fw2.00 / USB MTP，2026-11-07，`main@261942b`）。
- 构建文档补齐 Windows PowerShell 等价命令：PowerShell 里的 `bash` 会落到 WSL，
  改用 `python scripts/run_gates.py manual`、Git for Windows 的 `bash.exe` 与 `gradlew.bat`。
- `.gitignore` 按扩展名兜底 RAW 与抓包：`*.ARW` / `*.arw` / `*.pcap` / `*.pcapng` / `*.pcap.gz`。

### Fixed

- 暂无。

### Security

- 新增 `SECURITY.md` 与 issue / PR 模板；敏感信息门禁覆盖仓库文本、PR 正文与评论、
  commit message，设备身份一律用占位符。
