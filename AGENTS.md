# AGENTS.md — 本项目协作须知

给在本仓库工作的任何 AI agent（以及新加入的人类）的一页式说明。改动前先读完本文，
尤其是「迭代流程」与「红线」两节。

本文件随仓库 bootstrap 提交建立。**从此之后的每一次改动都走五步流程**，包括改这份文件本身。

---

## 0. 迭代流程（先读这一节）

所有仓库改动（代码、文档、门禁、配置）走五步，缺一步即不合规。
细则：发 issue 看 [`doc/ISSUE_GUIDE.md`](doc/ISSUE_GUIDE.md)，开 PR 看 [`doc/PR_GUIDE.md`](doc/PR_GUIDE.md)。

1. **先发 issue（发现即提，不等批准）**：正文四要素齐全——现象（行为类必须带一条可直接粘贴的
   复现命令）/ 证据链（`文件:行号`）/ 影响面（P0–P2）/ 修复候选。给不出复现命令就贴 `needs-repro`。
   issue 是执行与验收的唯一依据。
2. **写 plan 追评到 issue 下面**：探索结论（带证据）+ 方案 + **明确不做** + 验证方式，
   并含一行「用 issue 的复现命令复现：成功 / 失败（贴原始输出）」。复现失败说明描述与现状不符，
   退回补充，不进实施；定稿后只追加不改口。
3. **开 worktree 改代码**：`git worktree add ../wiredraw-<issue> -b type/<issue>-<slug> main`，
   不在主工作区切分支；分支 / PR 标题 / commit **三处都带 issue 号**。
4. **开 PR**：正文分段齐全（问题 / 改动 / 测试 / 基线验证 / 门禁改动 / 影响面 / 验收对照）；
   `Closes` 只给纯文档与纯重构，**行为类用 `Refs`**。
   **合并审批权归全新上下文的 subagent**：提交者写完全部文档、门禁转绿后，
   用 `python scripts/review_prompt.py --pr <号>` 生成派发文案（**不要手写**，它默认不带 PR 正文，
   「不投喂结论」靠结构保证），原样交给只拿到 PR 编号、仓库路径与分支/标题/改动文件清单的评审会话；
   它出 `APPROVE` 就直接合并（merge commit，不 squash、不 rebase），
   不需要维护者逐 PR 人工点头。提交者不能给自己评审。契约见
   [`doc/REVIEW_DISPATCH.md`](doc/REVIEW_DISPATCH.md)。
5. **合并后对账与回签**：修复者逐项核对「自述 vs 实际」（`git status`、文件存在性、门禁原始输出），
   给行为类 issue 贴 `needs-live-verify`；由发现者或**全新上下文**的会话按 PR_GUIDE 的模板回签，
   **回签通过才关 issue**。修复者不能给自己回签。

> 这个仓库的行为类改动大多要**真机**才能验（相机 USB 行为、MTP 会话寿命、传输速率）。
> 所以第 5 步不是形式主义：合并只代表代码进了 `main`，不代表相机那头症状消失。

### 0.1 门禁清单只有一个事实来源

本地与 CI 跑同一份清单 [`scripts/gates.json`](scripts/gates.json)，执行器是
[`scripts/run_gates.py`](scripts/run_gates.py)：

```bash
python scripts/run_gates.py --list          # 看档位与执行顺序
python scripts/run_gates.py ci              # 全量（最便宜的一档，本机日常）
python scripts/run_gates.py ci --fail-fast  # 见到红就停
python scripts/run_gates.py ci --shard static
python scripts/run_gates.py manual          # 真机/工具链档（Android 构建）
python scripts/run_gates.py support         # 需要 PR 上下文的一档；本地无 PR 时 SKIP
```

- **新增门禁只改清单**，然后跑 `python scripts/check_gates_manifest.py --write` 重写下面这段；
  手改会被元门禁打红（磁盘脚本、清单、本节三者必须一致）。
- `core.hooksPath` 指向 `.githooks`：`commit-msg` 拦文案格式与 `Co-Authored-By:`，
  `pre-push` 跑 static 分片。换机器后重设一次：`git config core.hooksPath .githooks`。
- Android 构建门禁在 `manual` 档，CI 跑不了（需要 Android SDK + JDK）。

<!-- gates:ci:begin -->
> 本节由 `python scripts/check_gates_manifest.py --write` 从 `scripts/gates.json` 生成，**不要手改**；
> 顺序即本机全量执行顺序。

| # | 分片 | 门禁 | 防什么 |
|---|---|---|---|
| 1 | `static` | `check_gates_manifest.py` | 元门禁：scripts/gates.json、AGENTS.md 生成段、磁盘脚本三者零漂移；手改 AGENTS.md 的门禁表格即红 |
| 2 | `static` | `check_no_vendored.py` | 15 个第三方参考仓库一个都不许被跟踪，且 .gitignore 的忽略规则必须还在；顺带拦下 >5 MB 的被跟踪文件（142 MB 抓包就是这样挡在外面的） |
| 3 | `static` | `check_doc_links.py` | 所有被跟踪 md 的相对链接必须可达。这套协作靠文档当记忆，断链等于记忆断了 |
| 4 | `static` | `py_compile` | scripts/ 与 tools/ 下所有 Python 必须能编译。探针是「只读连相机」的工具，语法崩在真机上才发现代价最高 |

共 4 个 CI 门禁，分 1 个分片（static 4）；manual 1、support 1 见 `scripts/gates.json`。
<!-- gates:ci:end -->

### 0.2 证据与验证纪律

- **无证据不修复**：任何 finding 必须带可直接粘贴的 `文件:行号` 或可复现命令才进入修复；
  没有证据的视为虚构。
- **完成声明以磁盘为准**：任何「已完成」都要独立用 `git status`、文件存在性、门禁原始输出复核，
  不采信转述。
- **真机结论必须标注环境**：机型 / 固件版本 / 手机型号 / Android API / 连接方式（USB 还是 Wi-Fi）。
  本项目的每一条结论都强依赖这些，缺了就无法复现。
- **红了先归因**：用 pristine HEAD 的 worktree 对照跑一遍，分清代码问题还是环境抖动，再动手。

---

## 1. 项目身份

| 项 | 值 |
|---|---|
| 产品名 | 索尼 a7C 经 USB 有线传 RAW 到安卓（先打通 MTP 导片，后续加 PTP 遥控） |
| 仓库 | `ID-VerNe/wiredraw`（private） |
| 目录名 | `sony-camera-remote-repos`（**与仓库名不一致，别被目录名误导**） |
| 技术栈 | Python 3（工具脚本，**只用标准库**）+ Java / Android（探针 APK，手搓构建） |
| 包管理器 | 无。不引入 pip / npm / gradle |
| 主开发线 | `main`（不再直接提交，见 §0） |
| 目标设备 | ILCE-7C，固件 2.00 |
| 测试机 | 魅族 20 Infinity / Android 15 (API 35) |

## 2. 红线（违反任何一条都不算完成任务）

1. **不留红 commit**。改动完成即跑 `python scripts/run_gates.py ci`；动到
   `tools/android-mtp-probe/` 时另跑 `python scripts/run_gates.py manual`。
2. **探针只读**。`tools/` 下连相机的代码**不得下发任何写操作**——不删除、不格式化、
   不改相机设置、不触发快门。只允许 OpenSession / 枚举 / 取值这类只读 opcode。
   要加写操作，先开会话讨论并落进 issue，不在 PR 里顺手加。
3. **大文件不进版本控制**。RAW 样本（24 MB/张）、原始抓包（142 MB）、构建产物一律 gitignore。
   由 `check_no_vendored.py` 兜底（>5 MB 的被跟踪文件即红）。
4. **15 个第三方参考仓库不入库**。它们在本地磁盘上随你参考，但 `git ls-files` 里出现即红。
5. **逐个 `git add <file>`**，禁止 `git add .` / `-A` / 目录批量。
6. **禁止 `Co-Authored-By:` 行**，`.githooks/commit-msg` 会直接拦。
7. **不在主工作区切分支**，用 `git worktree`；用完 `git worktree remove --force`。
8. **不新建仓库内 `CLAUDE.md`**（本文件是唯一协作说明）。
9. **测试方法论（先写测试，后改代码）**：
   - 先写用例 → 故意改坏关键逻辑（**变异**）验证用例能抓到 → 还原 → 才准动实现；
   - **变异必须替换断言瞄准的子串**，在前面加无意义前缀是无效变异；
   - 变异还原**不许用 `git checkout --`**：对未提交的跟踪文件 checkout 会把本轮全部改动
     冲回 HEAD，而 `git diff --stat` 还显示为空、看不出异常。协议是
     `cp file /tmp/x.bak` → 变异 → `cp` 回写。

## 3. 现状：已经证实的事（别再重推一遍）

这一节是花了一整晚真机实测换来的，**动相关代码前先读**。细节与原始输出见
[`RESEARCH-android-usb-raw-a7c.md`](RESEARCH-android-usb-raw-a7c.md) 第 11、12 节。

| 结论 | 证据 |
|---|---|
| 拿旧 RAW 必须走 **MTP**，PTP 不行 | PTP PC Remote 会话下 `GetStorageIDs`/`GetObjectHandles`/`GetNumObjects`/`GetStorageInfo` **全部 AccessDenied**，只有 `GetObjectInfo(0xFFFFC001)` 通 |
| MTP 会话下枚举完全正常 | 1526 个对象 / 511 张 ARW / 11.9 GB，同平台同相机 |
| MTP 传输速率 | 21.43 MB/s（三张 24 MP RAW，73.29 MB / 3.42 s）；Wi-Fi PTP 只有 0.95 MB/s |
| MTP 传输完整性 | 字节数一致 + MD5 手机侧与 PC 侧完全相同 + 文件头 `49 49 2A 00` 合法 TIFF |
| `getObjectHandles` 的 format 过滤**不可信** | 传 `0xB101` 返回 754 个，逐个 `getObjectInfo` 只有 511 个真是 `0xB101`，差集对不上账 |
| PTP 握手要 GUID 配对 | 随机 UUID → `Init_Fail reason=1`；只认当初配对的 `dd4b11b9-280d-43` |
| PTP 事件通道必须先于 `OpenSession` | 少这步 `OpenSession` 一个字节都不回，只超时（不是被拒） |

### 单一归属（新代码往哪儿写）

| 关注点 | 唯一归属 |
|---|---|
| 门禁清单 / 执行器 | `scripts/gates.json` + `scripts/run_gates.py`（**不要在别处再写一份命令数组**） |
| 迭代流程细则 | `doc/ISSUE_GUIDE.md` / `doc/PR_GUIDE.md` |
| 合并评审（派发 + 评审清单） | `doc/REVIEW_DISPATCH.md`（派发文案用 `scripts/review_prompt.py` 生成，**不要手写**） |
| MTP 枚举与下载 | `tools/android-mtp-probe/src/com/rea/mtpprobe/Main.java` |
| APK 构建流程 | `tools/android-mtp-probe/build.sh`（**keystore 必须在工程根目录**，见 §4） |
| PTP/IP 连相机 | `tools/sony-ptpip/probe.py` |
| pcap 解析 | `tools/sony-ptpip/parse_ptpip.py` |
| 全部实测数据与历史 | `RESEARCH-android-usb-raw-a7c.md` |

## 4. 环境坑（每个都真实踩过）

- **相机会在 MTP 会话结束后约 1 分钟挂起 USB**（2026-11-04 实测）：
  `MtpDevice.open()` 卡 10 秒才返回、`getStorageIds()` 一直返回空。
  应用内重试（8 次 / 约 70 秒）**唤不醒**，**必须拔插数据线**。
  写 App 时要保持会话常开，或明确提示用户重插。
- **`0xFFFFC001` 空闲时返回 OK 但 0 字节**：相机当前会话没拍过照时，
  `GetObjectInfo(0xFFFFC001)` 返回 `OK` 而不是错误码，数据长度为 0。
  客户端**不能只看返回码**，必须判长度。
- **ObjectInfo 字段偏移**：`26` = ImagePixWidth、`30` = ImagePixHeight；
  文件名长度字节在 `52`、名字从 `53` 开始（按 PTP 规范累加应是 48，索尼多塞 4 字节）。
  写成 28/32/49 会解析出空名字 —— 抓包与实时探针两处交叉验证过。
- **`build.sh` 的 `rm -rf build` 会杀掉 keystore**（2026-11-04 踩过）：
  签名一变，`adb install -r` 直接 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
  所以 `rea-debug.keystore` 放在**工程根目录**，别挪回 `build/`。
- **取手机上的文件用 `run-as`**：APK 是 `debuggable`，`adb exec-out run-as <pkg> cat files/x > x`
  比 pull 可靠（`/sdcard/Android/data` 在 Android 11+ 连 shell 都进不去）。
- **Windows 下 `javac` 的失败退出码会被管道吞掉**：`javac ... | grep -v xxx || true` 会让
  编译失败静默通过、`build/classes` 为空却继续往下跑。构建脚本里一律先落日志再判退出码。
- **git 提交在 Windows 下会有 `LF will be replaced by CRLF` 警告**，属正常（`autocrlf`）；
  仓库内存的是 LF。

## 5. Commit 与发布

### Commit message

- 格式 `type(scope): 中文摘要（issue #NN）` + 正文。
  **三处带 issue 号**：分支名、PR 标题、commit。
- 正文说清**为什么这么改**与**实测数字**，不要只列改了哪些文件。
- `type` 取 `feat` / `fix` / `docs` / `refactor` / `chore` / `test` / `perf`。
- 一个 PR 允许多个 commit，但每个 commit 自解释，不写 `wip` / `fix`。
- 提交前跑 `python scripts/run_gates.py ci`，push 前确认 `git status` 干净。

### 发布

本仓库暂不发版。探针 APK 就地构建
（`bash tools/android-mtp-probe/build.sh` → `build/mtp-probe.apk`），通过
`adb install -g build/mtp-probe.apk` 装到测试机。

## 6. 文档索引

| 文件 | 内容 |
|---|---|
| `README.md` | 对外门面：结论速览、实测数据、三个坑、仓库结构 |
| `RESEARCH-android-usb-raw-a7c.md` | **主报告**，12 节，全部实测与踩坑记录 |
| `doc/ISSUE_GUIDE.md` | 怎么发 issue：正文四要素、复现命令与 `needs-repro`、关闭方式、模板 |
| `doc/PR_GUIDE.md` | 怎么开 PR：拆分命名、正文分段、基线验证、门禁改动、合并条件、对账与回签 |
| `doc/REVIEW_DISPATCH.md` | 合并评审契约：派发方式、评审者必做清单、结论格式、常见误判 |
| `scripts/review_prompt.py` | 生成合并评审的派发文案（默认不含 PR 正文，`--self-test` 自检） |
| `scripts/check_pr_sections.py` | PR 正文分段结构校验（`support` 档，CI 在 `pull_request` 上跑） |
| `scripts/gates.json` | 门禁清单**唯一事实来源**（§0.1 生成段由它渲染） |
| `scripts/check_gates_manifest.py` | 元门禁：清单 / AGENTS.md 生成段 / 磁盘脚本零漂移 |
| `tools/android-mtp-probe/README.md` | MTP 探针的用法、实测结果与三个坑 |
| `tools/sony-ptpip/README.md` | PTP/IP 抓包与探针的操作手册 |
| `tools/sony-ptpip/FINDINGS-a7c-ptpip-session.md` | 抓包会话逐条分析 + 直连探针实测 |
| `tools/pe/FINDINGS-Lja_PTP_IP.md` | `Lja_PTP_IP.dll` 静态分析结论 |
| `docs/history/` | 早期可行性核查与 15 个仓库审计的产出（存档，不再更新） |

## 7. 工作习惯

- 改前先读相关文件的现状与注释——本仓库注释密度高，多数「为什么」都写在代码旁边，别靠猜。
- 任何涉及相机的结论，**标注环境与复现命令**；没上过真机的推断要写成推断，不要写成事实。
  本项目早期就因为从 DLL 推断「走 SSH 隧道」而误判过一次（a7C 实际是裸 PTP/IP）。
- 修 bug 前先确认测试网：没有能红的用例就先补，再修。
- 文档里贴数字必须带单位与口径（MB/s 是 MiB/s 还是 MB/s、是否含文件系统写入）。
