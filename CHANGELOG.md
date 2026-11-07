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

- 导片工具停用 MTP 断点续传（#177）：真机实测 `getPartialObject` 的 `offset` 不生效，
  遗留 `.part` 一律作废、每次从 0 重传；完成状态只做尺寸核对（内容未校验），不再把
  「尺寸凑够」当成校验通过。
- 构建文档补齐 Windows PowerShell 等价命令：PowerShell 里的 `bash` 会落到 WSL，
  改用 `python scripts/run_gates.py manual`、Git for Windows 的 `bash.exe` 与 `gradlew.bat`。
- `.gitignore` 按扩展名兜底 RAW 与抓包：`*.ARW` / `*.arw` / `*.pcap` / `*.pcapng` / `*.pcap.gz`。

### Fixed

- 暂无。

### Security

- 新增 `SECURITY.md` 与 issue / PR 模板；敏感信息门禁覆盖仓库文本、PR 正文与评论、
  commit message，设备身份一律用占位符。
