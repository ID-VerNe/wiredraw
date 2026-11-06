# a7C 有线传 RAW —— 实测底账与工具

目标：让安卓手机通过 **USB 有线** 连接索尼 a7C（ILCE-7C），把卡里的 **RAW（.ARW）** 传到手机，
替代只能传 JPG 的官方 App，并且比 Wi-Fi 快一个数量级。

本仓库不是调研汇总，是**在真机上一条条跑出来的结论**。每个"能用/不能用"都有对应的抓包、
探针代码或日志支撑。

---

## 一句话结论

**要拿旧 RAW 就用 MTP，不要碰 PTP。**

相机 `MENU → 设置 → USB连接 → MTP`，手机当 USB 主机，用 Android 自带的
`android.mtp.MtpDevice` 直接读 `DCIM/*.ARW`。免 root、免协议逆向、免权限弹窗。

| 路线 | 能拿旧 RAW | 实测速率 | 状态 |
|---|---|---|---|
| **MTP 直连** | ✅ | **21.4 MB/s** | ✅ 已在魅族 20 / Android 15 + ILCE-7C 跑通 |
| PTP/IP（IED 那套） | ❌ 只有最新一张 | 0.95 MB/s | ✅ 已逆向并复现，但方向不对 |
| MSC 直拷 | ✅ | 略高于 MTP | 未验证（安卓需 `libaums`，仅 FAT32） |

11.9 GB 全卡导出：Wi-Fi PTP 约 3.5 小时，USB MTP 约 9.5 分钟。

---

## 为什么 PTP 拿不到旧照片

在 PC Remote 会话里，相机**协议层就拒绝存储枚举**（实测，非推断）：

| 调用 | 结果 |
|---|---|
| `GetStorageIDs` | AccessDenied |
| `GetNumObjects` | AccessDenied |
| `GetObjectHandles` | AccessDenied |
| `GetStorageInfo` | AccessDenied |
| `GetObjectInfo(0xFFFFC001)` | OK（只能拿"最新拍的那一张"） |

而 `GetDeviceInfo` 明确报告相机**支持** `0x1004`/`0x1007`。所以不是没实现，是会话类型不给。
IED 从来没用过 `GetObjectHandles` —— 它只需要 `0xFFFFC001`，所以索尼官方不给传老照片
不是 App 偷懒，是那条会话本来就没这个能力。

换成 MTP 模式后，同一台相机的 `GetStorageIDs` / `GetObjectHandles` **全部正常**，
卡内 1526 个对象（511 张 ARW / 11.9 GB）一览无余。这条对照是整个项目的转折点。

---

## 实测数据

**MTP（推荐路线）** — 三张 24 MP RAW：

| 文件 | 大小 | 耗时 | 速率 |
|---|---|---|---|
| DSC02183.ARW | 24.44 MB | 1.19 s | 20.62 MB/s |
| DSC02185.ARW | 24.43 MB | 1.03 s | 23.67 MB/s |
| DSC02184.ARW | 24.42 MB | 1.03 s | 23.72 MB/s |
| 批量 | 73.29 MB | 3.42 s | **21.43 MB/s** |

完整性三重校验全过：字节数与相机报的一致、**MD5 手机侧与 PC 侧逐字节相同**、
文件头 `49 49 2A 00`（合法 TIFF/ARW）。

---

## 仓库结构

```
README.md                            对外门面（就是本文件）
AGENTS.md                            ★ 协作须知：五步流程、红线、已证实结论、环境坑
docs/
  contributing/
    ISSUE_GUIDE.md                   怎么发 issue（四要素、needs-repro、回签）
    PR_GUIDE.md                      怎么开 PR（分段、基线验证、合并评审、对账）
  research/
    README.md                        探索记录索引与结论时效
    a7c-usb-raw-2026-11.md           主报告（12 节，含全部实测与踩坑记录）
    a7c-ptpip-session.md             抓包会话逐条分析 + 直连探针实测
    lja-ptp-ip-static-analysis.md    Lja_PTP_IP.dll 静态分析结论
  archive/
    2026-11-audit/                   早期可行性核查与仓库审计的存档
scripts/
  gates.json                         门禁清单，唯一事实来源
  run_gates.py                       门禁执行器（本机与 CI 跑同一份）
  check_gates_manifest.py            元门禁：清单 ↔ AGENTS.md 生成段零漂移
  check_no_vendored.py               第三方参考库 / 大文件不许入库
  check_doc_links.py                 文档相对链接必须可达
tools/
  android-mtp-probe/                 ★ MTP 探针 APK 工程（手搓构建，无 Gradle）
  sony-ptpip/                        PTP/IP 直连相机探针 + 抓包解析器
  pe/                                Lja_PTP_IP.dll 静态分析工具
captures/                            抓包派生的可读流水（本地证据，未随仓库分发）
out/                                 手机侧报告与截图（本地证据，未随仓库分发）
```

## 怎么协作

本仓库按 [AGENTS.md](AGENTS.md) 的五步流程走：**issue → plan → worktree → PR → 对账回签**。
改动前先读 [AGENTS.md](AGENTS.md) §0 与 §2（红线）。

```bash
python scripts/run_gates.py --list      # 看门禁清单
python scripts/run_gates.py ci          # 跑全量门禁（改完就跑）
python scripts/run_gates.py manual      # Android 构建档（需要 SDK + JDK）
```

门禁清单在 `scripts/gates.json`，改完跑 `python scripts/check_gates_manifest.py --write`
同步 `AGENTS.md` 的生成段。`git config core.hooksPath .githooks` 装一次钩子。

### 主要工具

- **`tools/android-mtp-probe/`** —— 一条 20 行的 MTP 调用就能替代官方 App 传 RAW 的验证工具。
  `bash build.sh` 出 APK，插线自动拉起。README 里有完整用法与三个坑。
- **`tools/sony-ptpip/probe.py`** —— 直连相机 TCP 15740 的只读探针。
  复刻了 IED 的握手（GUID 配对 + 事件通道先建），并逐条验证了枚举被拒的事实。
- **`tools/sony-ptpip/parse_ptpip.py`** —— 把 pcap 翻译成 `REQ/RSP/DATA` 事务流水。

---

## 三个必须知道的坑

1. **相机会在 MTP 会话结束后约 1 分钟挂起 USB。** `open()` 卡 10 秒、`getStorageIds()` 返回空，
   重试 70 秒也唤不醒，**必须拔插数据线**。正式 App 要么保持会话常开，要么提示重插。
2. **别用 `getObjectHandles` 的 format 过滤。** 传 `0xB101` 0.17 秒就返回 754 个"ARW"，
   但逐个 `getObjectInfo` 只有 511 个真是 `0xB101`，数字对不上账（疑似返回重复句柄）。
   老实走全量枚举（39 ms/个），或按 `parent` 逐目录。
3. **PTP 握手有配对门。** 随机 UUID 一律 `Init_Fail reason=1`，只有当初配对成功的 GUID 才放行；
   而且**事件通道必须先于 `OpenSession` 建立**，否则 `OpenSession` 一个字节都不回，只超时。

---

## 下一步

- [ ] 把 MTP 探针扩成可用的导片工具（选目录、批量、断点续传、进度条）
- [ ] 双模正式 App：MTP 导旧片 + PTP/IP 遥控拍摄
- [ ] 验证 MSC 模式（需确认卡是否 FAT32）

---

## 说明

- 本仓库不含任何第三方参考项目。早期审计过的 15 个开源仓库是维护者本机的只读副本，
  不是依赖，也不被任何构建或代码引用；来历与忽略规则见
  [本地专属目录与内部工具](docs/contributing/LOCAL_WORKSPACE.md)。
- `tools/android-mtp-probe/rea-debug.keystore` 是**调试用**签名密钥（口令 `android`），
  仅为让 `adb install -r` 可复现，不含任何真实凭据。
- `captures/*.pcapng`（体积实测见 `docs/archive/INDEX.md`）、`captures/*.txt`、`out/*` 与 `out/*.ARW`（各 24 MB）均为本地证据，
  未随仓库分发；结论归档在 `docs/archive/INDEX.md`，可按工具 README 重新生成。

---

## License

本项目以 [Apache License 2.0](LICENSE) 授权。
