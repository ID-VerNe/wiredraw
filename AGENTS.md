# AGENTS.md — 本项目协作须知

给在本仓库工作的任何 AI agent（以及新加入的人类）的**协作总纲**。本文篇幅较长（按节查阅，
不必一次读完），改动前至少读完「迭代流程」与「红线」两节。

本文件随仓库 bootstrap 提交建立。**从此之后的每一次改动都走五步流程**，包括改这份文件本身。

「该读哪一节」：

| 你是谁 / 要做什么 | 先读 |
|---|---|
| 第一次进入仓库的 agent 或人 | §0 迭代流程 → §2 红线 |
| 要改代码 | §2 红线 → §3「单一归属」（新代码往哪儿写） |
| 遇到相机 / 传输问题 | §3「现状：已经证实的事」→ §4 环境坑 |
| 要改门禁或加门禁 | §0.1（清单唯一事实源在 `scripts/gates.json`） |
| 要发 issue / 开 PR / 做合并评审 | §0 五步 → `docs/contributing/` 四件细则 |
| 同一件事看到两种说法 | §3「事实类型 → 唯一权威」表 |
| 要写 commit / 构建产物 | §5 |

---

## 0. 迭代流程（先读这一节）

所有仓库改动（代码、文档、门禁、配置）走五步，缺一步即不合规。
细则：发 issue 看 [`docs/contributing/ISSUE_GUIDE.md`](docs/contributing/ISSUE_GUIDE.md)，开 PR 看 [`docs/contributing/PR_GUIDE.md`](docs/contributing/PR_GUIDE.md)。

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
   **正文里举例一律写 `#NN` 占位**，不写具体编号——GitHub 对 PR 原始全文跑自动关闭解析，
   行内代码 / 围栏里的 closing 关键词加真实编号也会真的关掉 issue（见
   [`docs/contributing/PR_GUIDE.md`](docs/contributing/PR_GUIDE.md)「硬规则」）。
   **合并审批权归全新上下文的 subagent**：提交者写完全部文档、门禁转绿后，
   用 `python scripts/review_prompt.py --pr <号>` 生成派发文案（**不要手写**，它默认不带 PR 正文，
   「不投喂结论」靠结构保证），原样交给只拿到 PR 编号、仓库路径与分支/标题/改动文件清单的评审会话；
   它出 `APPROVE` 就直接合并（merge commit，不 squash、不 rebase），
   不需要维护者逐 PR 人工点头。提交者不能给自己评审。契约见
   [`docs/contributing/REVIEW_DISPATCH.md`](docs/contributing/REVIEW_DISPATCH.md)。
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
python scripts/run_gates.py ci --prepush    # pre-push 钩子口径：按 gates.json 的 prePush 标记取分片
python scripts/run_gates.py manual          # 真机/工具链档（Android 构建）
python scripts/run_gates.py support         # 需要 PR 上下文的一档；本地无 PR 时 SKIP
```

- **新增门禁只改清单**，然后跑 `python scripts/check_gates_manifest.py --write` 重写下面这段；
  手改会被元门禁打红（磁盘脚本、清单、本节三者必须一致）。
- `core.hooksPath` 指向 `.githooks`：`commit-msg` 拦文案格式与 `Co-Authored-By:`，
  并调 `scripts/check_commit_messages.py` 扫 commit message 里的敏感值；
  `pre-push` 跑 static 分片。换机器后重设一次：`git config core.hooksPath .githooks`。
- `pre-push` 的 `SKIP_GATES=1` **仅用于钩子自身故障**（例如钩子在当前 shell 下跑不起来），
  它会打印醒目横幅但跳过 static 门禁。使用后必须在 PR 正文说明原因，并附上手动执行
  `python scripts/run_gates.py ci` 的原始输出；它不是「门禁红了赶时间」的通行证。
  绕过会写入 `$(git rev-parse --git-common-dir)/skip-gates.log`（所有 worktree 共用一份），用于事后审计。
- Android 构建门禁在 `manual` 档，CI 跑不了（需要 Android SDK + JDK）。

<!-- gates:ci:begin -->
> 本节由 `python scripts/check_gates_manifest.py --write` 从 `scripts/gates.json` 生成，**不要手改**；
> 顺序即本机全量执行顺序。

| # | 分片 | 门禁 | 命令 | 防什么 |
|---|---|---|---|---|
| 1 | `static` | `check_gates_manifest.py` | `python scripts/check_gates_manifest.py` | 元门禁：scripts/gates.json、AGENTS.md 生成段、磁盘脚本三者零漂移；手改 AGENTS.md 的门禁表格即红。cmd 参数也不许降级：禁用参数表的唯一来源是 scripts/check_gates_manifest.py 的 FORBIDDEN_FLAGS（本清单不再枚举），flags.require 声明的参数必须在场，脚本 --help 不认的参数即红（#131、#173、#203） |
| 2 | `static` | `_common.py` | `python scripts/_common.py` | 门禁共享层自检：`gate_hits` 新旧路径逐项等价、共享层自身命中门禁链、`GATE_TRIGGERS` 与 `FROZEN_GATE_TRIGGERS` 零漂移、退出码常量 0/1/2（#288）。各门禁的 `--self-test` 不覆盖共享层，必须由本档位显式执行，否则判定表被悄悄扩展只有绿灯（#283） |
| 3 | `static` | `check_no_vendored.py` | `python scripts/check_no_vendored.py` | 15 个第三方参考仓库一个都不许被跟踪，且 .gitignore 的忽略规则必须还在；顺带拦下 >5 MB 的被跟踪文件（原始大抓包就是靠这条挡在外面的，实测体积见 [`docs/archive/INDEX.md`](docs/archive/INDEX.md)） |
| 4 | `static` | `check_doc_links.py` | `python scripts/check_doc_links.py` | 所有被跟踪 md 的相对链接必须可达。这套协作靠文档当记忆，断链等于记忆断了 |
| 5 | `static` | `check_line_refs.py` | `python scripts/check_line_refs.py` | 被跟踪文件（md/java/kt/kts/py，含代码注释）不许写物理行号（AGENTS.md §2 第 10 条，规则原在 UI-SPEC 只覆盖自身，#252 上收为全仓）：行号随上方插行漂移，而文档与注释都没有编译门禁能发现。扩展名白名单避开主机端口与章节号；md 跳围栏代码块（issue/PR 模板里的行号示例属一次性上下文）与 docs/archive/ 冻结证据（失效是预期），代码类型不剥围栏；门禁自身的测试夹具按精确路径排除（夹具必须字面含违规形态才能验证判红），不做值级白名单；裸冒号数字误报不可控，不进这条门禁 |
| 6 | `static` | `py_compile` | `python -m compileall -q scripts tools` | scripts/ 与 tools/ 下所有 Python 必须能编译。探针是「只读连相机」的工具，语法崩在真机上才发现代价最高 |
| 7 | `static` | `check_sensitive.py` | `python scripts/check_sensitive.py --check` | 敏感信息门禁：相机 GUID 及其 hex/14 位去横线/4-4-4-2 重分组/Base64/URL 编码、序列号、内网 IP、私人路径、私有仓库标识、通用密钥形态。命中即阻断（#110）。相机 AP 公开示例网关与 Android debug keystore 公开口令走脚本内 ALLOWLIST，只按「类别 + 精确值」放行，不做网段/通配；其余行内豁免必须带具体理由。--fix 才改写，--warn 只用于排查基线 |

共 7 个 CI 门禁，分 1 个分片（static 7）；manual 2、support 3 见 `scripts/gates.json`。
<!-- gates:ci:end -->

### 0.2 证据与验证纪律

- **无证据不修复**：任何 finding 必须带可直接粘贴的 `文件:行号` 或可复现命令才进入修复；
  没有证据的视为虚构。
- **完成声明以磁盘为准**：任何「已完成」都要独立用 `git status`、文件存在性、门禁原始输出复核，
  不采信转述。
- **真机结论必须标注环境**：机型 / 固件版本 / 手机型号 / Android API / 连接方式（USB 还是 Wi-Fi）。
  本项目的每一条结论都强依赖这些，缺了就无法复现。
- **结论归属到环境编号**：真机结论除五项环境外，还要能对上
  [`docs/verification-matrix.md`](docs/verification-matrix.md) 的 E 编号；矩阵里没有的组合按「未验证」处理。
- **红了先归因**：用 pristine HEAD 的 worktree 对照跑一遍，分清代码问题还是环境抖动，再动手。
- **PR 正文与评论也在脱敏扫描范围内**：`support` 档的
  [`scripts/check_pr_body.py`](scripts/check_pr_body.py) 会扫 PR 正文与评论，
  且外部输入**不认行内豁免**；本机路径、内网地址、口令、序列号、配对 GUID 一律用
  `<本机 Android SDK 路径>` / `<相机IP>` / `<PAIRED_GUID>` 这类占位。
  提交前可本地预检：`python scripts/check_pr_body.py --body-file <正文文件>`。

---

## 1. 项目身份

| 项 | 值 |
|---|---|
| 产品名 | 索尼 a7C 经 USB 有线传 RAW 到安卓（先打通 MTP 导片，后续加 PTP 遥控） |
| 仓库 | `ID-VerNe/wiredraw` |
| 目录名 | `sony-camera-remote-repos`（**与仓库名不一致，别被目录名误导**） |
| 技术栈 | Python 3（工具脚本，**只用标准库**）+ Java / Android（探针 APK，手搓构建）+ Kotlin / Android（导片工具，Gradle + Compose 编译器） |
| 包管理器 | Python 侧仍零依赖；Android 导片工具用 Gradle（Kotlin + Compose），探针 APK 仍手搓 |
| 主开发线 | `main`（不再直接提交，见 §0） |
| 目标设备 | ILCE-7C，固件 2.00 |
| 测试机 | E1 魅族 20 Infinity / E2 MI 9（小米 9）；均 Android 15 (API 35)，见[验证环境矩阵](docs/verification-matrix.md) |

## 2. 红线（违反任何一条都不算完成任务）

1. **不留红 commit**。改动完成即跑 `python scripts/run_gates.py ci`；动到
   `tools/android-mtp-probe/` 时另跑 `python scripts/run_gates.py manual`。
2. **探针只读**。`tools/` 下连相机的代码**不得下发任何写操作**——不删除、不格式化、
   不改相机设置、不触发快门。只允许 OpenSession / 枚举 / 取值这类只读 opcode。
   要加写操作，先开 issue 讨论并把结论落进该 issue，不在 PR 里顺手加。
3. **大文件不进版本控制**。RAW 样本（24 MB/张）、原始抓包（实测体积见 [`docs/archive/INDEX.md`](docs/archive/INDEX.md)）、构建产物一律 gitignore。
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
10. **不写物理行号**（全仓规则，`check_line_refs.py` 兜底；原规则只覆盖 `UI-SPEC.md`
    自身，见 #252）：被跟踪文件（`.md` / `.java` / `.kt` / `.kts` / `.py`，含 README 类与代码注释）
    **不得**用「文件名 / 扩展名 + 冒号 + 数字」引用代码或文档位置。引用一律写「文件名 +
    该文件里可唯一 `grep` 的字符串」或「类名 + 方法名」；引用规范条目用编号（`V-16` / `C-08`），
    引用实测数据用章节号 + 数值。锚点串必须能在目标文件里 `grep` 到且能唯一定位，串被改名时
    改锚点而不是退回写行号。例外：`docs/archive/` 是当时证据的冻结副本，不改——它引用的是
    本地不入库的第三方仓库，本来不可达，坐标失效是预期；issue / PR 正文属一次性上下文，
    可写行号，但要绑定当时的分支或 commit（文档里展示这类正文的围栏示例随此豁免）。
    不带文件名的裸冒号数字进不了门禁（与时刻、编号无法区分），靠评审把关。

## 3. 现状：已经证实的事（别再重推一遍）

这一节是花了一整晚真机实测换来的，**动相关代码前先读**。细节与原始输出见
[`docs/research/a7c-usb-raw-2026-11.md`](docs/research/a7c-usb-raw-2026-11.md) 第 11、12 节。

| 结论 | 证据 |
|---|---|
| 拿旧 RAW 必须走 **MTP**，PTP 不行 | PTP PC Remote 会话下 `GetStorageIDs`/`GetObjectHandles`/`GetNumObjects`/`GetStorageInfo` **全部 AccessDenied**，只有 `GetObjectInfo(0xFFFFC001)` 通 |
| MTP 会话下枚举完全正常 | 1526 个对象 / 511 张 ARW / 11.9 GB，同平台同相机 |
| MTP 传输速率 | 21.43 MB/s（三张 24 MP RAW，73.29 MB / 3.42 s）；Wi-Fi PTP 只有 0.95 MB/s |
| MTP 传输完整性 | 字节数一致 + MD5 手机侧与 PC 侧完全相同 + 文件头 `49 49 2A 00` 合法 TIFF |
| `getObjectHandles` 的 format 过滤**不可信** | 传 `0xB101` 返回 754 个，逐个 `getObjectInfo` 只有 511 个真是 `0xB101`，差集对不上账 |
| PTP 握手要 GUID 配对 | 随机 UUID → `Init_Fail reason=1`；只认当初配对的 `<已配对的 GUID>`（真实值是配对凭据，不入库） |
| PTP 事件通道必须先于 `OpenSession` | 少这步 `OpenSession` 一个字节都不回，只超时（不是被拒） |

### 单一归属（新代码往哪儿写）

| 关注点 | 唯一归属 |
|---|---|
| 门禁清单 / 执行器 | `scripts/gates.json` + `scripts/run_gates.py`（**不要在别处再写一份命令数组**） |
| 迭代流程细则 | `docs/contributing/ISSUE_GUIDE.md` / `docs/contributing/PR_GUIDE.md` |
| 合并评审（派发 + 评审清单） | `docs/contributing/REVIEW_DISPATCH.md`（派发文案用 `scripts/review_prompt.py` 生成，**不要手写**） |
| MTP 枚举与下载 | `tools/android-mtp-probe/src/com/rea/mtpprobe/Main.java` |
| APK 构建流程（探针） | `tools/android-mtp-probe/build.sh`（**keystore 必须在工程根目录**，见 §4） |
| APK 构建流程（导片工具） | `tools/android-mtp-import/build.gradle.kts`（Android/Kotlin/依赖）+ `tools/android-mtp-import/gradle/*.gradle.kts`（纯 JDK 自检、验签、假数据、`local.properties` 解析）+ `gradle/wrapper/`（工具链版本锁在 wrapper 里，别用全局 Gradle） |
| PTP/IP 连相机 | `tools/sony-ptpip/probe.py` |
| pcap 解析 | `tools/sony-ptpip/parse_ptpip.py` |
| 全部实测数据与历史 | `docs/research/a7c-usb-raw-2026-11.md` |

### 事实类型 → 唯一权威（冲突时先看这张表）

「单一归属」回答「新代码往哪儿写」，下表回答「同一件事的**说法**听谁的」。别处复述时只给摘要 + 链接；
单说「唯一事实源」时必须带作用域（如「协作规则的唯一事实源」「门禁清单的唯一事实源」），
**不许无限定地指代整份 `AGENTS.md`**。

| 事实类型 | 唯一权威 | 别处怎么引 |
|---|---|---|
| 协作规则、红线、五步流程入口 | `AGENTS.md` §0 / §2 | `CONTRIBUTING.md` 只给对外最小版 |
| 流程细则（issue / PR / 评审 / 本地目录） | `docs/contributing/ISSUE_GUIDE.md`、`PR_GUIDE.md`、`REVIEW_DISPATCH.md`、`LOCAL_WORKSPACE.md` | `AGENTS.md` 只给入口与硬规则 |
| 门禁清单、档位与条数 | `scripts/gates.json`（§0.1 生成段由它渲染） | 文档里**不写条数**，只给指针 |
| 钩子安装、绕过与审计 | `AGENTS.md` §0.1 | `docs/testing.md` 只讲回归口径 |
| 实测数据与历史结论 | `docs/research/*`（归档在 `docs/archive/*`） | 只加口径标注，不改当时判断 |
| 容量与耗时数字口径 | `tools/android-mtp-import/UI-SPEC.md` §4.2 | 假树值必须显式标注 |
| 验证环境与待验清单 | `docs/verification-matrix.md`（矩阵）+ `docs/known-limitations.md`（快照） | 单点快照只留一处 |
| 文档与代码注释里引用坐标的写法 | `AGENTS.md` §2 第 10 条 | `UI-SPEC` §10 第 8 条只给指针；门禁是 `scripts/check_line_refs.py` |
| 合并评审的派发与检查项 | `docs/contributing/REVIEW_DISPATCH.md`（文案由 `scripts/review_prompt.py` 生成） | 不要手写派发文案 |
| 自动关单事故的完整叙述 | `docs/contributing/PR_GUIDE.md` 的「硬规则」节 | `REVIEW_DISPATCH` 只留检查项 + 指针 |

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
- **keystore 必须与构建目录分离**（2026-11-04 踩过）：`rea-debug.keystore` 在
  `tools/android-mtp-probe/` 工程根目录，**不在**任何 `build/` 下。签名一变，`adb install -r`
  直接 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。探针的 `build.sh` 与导片工具的 Gradle
  签名配置都指向这把 key，`rm -rf build` / `gradle clean` 都碰不到它。
- **取手机上的文件用 `run-as`**：APK 是 `debuggable`，`adb exec-out run-as <pkg> cat files/x > x`
  比 pull 可靠（`/sdcard/Android/data` 在 Android 11+ 连 shell 都进不去）。
- **Windows 下 `javac` 的失败退出码会被管道吞掉**：`javac ... | grep -v xxx || true` 会让
  编译失败静默通过、`build/classes` 为空却继续往下跑。构建脚本里一律先落日志再判退出码。
- **git 提交在 Windows 下会有 `LF will be replaced by CRLF` 警告**，属正常（`autocrlf`）；
  仓库内存的是 LF。
- **跑 `manual` 档前建议先设 `ANDROID_HOME` / `JAVA_HOME`**：两条构建链都会回退到默认路径——
  探针 `build.sh` 走 `ANDROID_HOME` → `ANDROID_SDK_ROOT` → 常见默认路径，JDK 再回退到
  `PATH` 里的 `javac`；导片工具 Gradle 走 `local.properties` → 环境变量 →
  `%LOCALAPPDATA%\Android\Sdk`。**默认路径也找不到时**才在定位 SDK 阶段直接失败退出，
  不是代码问题；设置口径与完整探测顺序见 [`docs/building.md`](docs/building.md) 第二节。

## 5. Commit 与发布

### Commit message

- 格式 `type(scope): 中文摘要（issue #NN）` + 正文。
  **三处带 issue 号**：分支名、PR 标题、commit。
- 正文说清**为什么这么改**与**实测数字**，不要只列改了哪些文件。
- `type` 取 `feat` / `fix` / `docs` / `refactor` / `chore` / `test` / `perf`。
- 一个 PR 允许多个 commit，但每个 commit 自解释，不写 `wip` / `fix`。
- 提交前跑 `python scripts/run_gates.py ci`，push 前确认 `git status` 干净。

### 发布

本仓库暂不发版。探针 APK 就地构建（Linux / macOS / Git Bash 用
`bash tools/android-mtp-probe/build.sh`；Windows PowerShell 用
`& "C:\Program Files\Git\bin\bash.exe" tools/android-mtp-probe/build.sh`
或 `python scripts/run_gates.py manual`——PowerShell 的 `bash` 会落到 WSL，跑不通），
产物 `build/mtp-probe.apk`，通过 `adb install -g build/mtp-probe.apk` 装到测试机。

## 6. 文档索引

| 文件 | 内容 |
|---|---|
| `README.md` | 对外门面：结论速览、实测数据、三个坑、仓库结构 |
| `CONTRIBUTING.md` | 外部贡献入口：项目是什么、环境要求、门禁怎么跑；规则冲突以 AGENTS.md 为准 |
| `CHANGELOG.md` | 变更记录：Keep a Changelog 格式，未发版前记在 `Unreleased` |
| `SECURITY.md` | 安全报告渠道与范围：私密报告、勿附设备身份、只读边界 |
| `CODE_OF_CONDUCT.md` | 行为准则：Contributor Covenant 2.1 官方英文原文，未改写条款 |
| `docs/known-limitations.md` | 已知限制：会话挂起、format 过滤、PTP 会话边界、首次授权、未验证项 |
| `docs/verification-matrix.md` | 验证环境矩阵：每条能力 × 环境编号的通过 / 失败 / 未验证，防止单机结论外推 |
| `docs/safety-and-privacy.md` | 安全与隐私边界：只读 opcode、设备身份占位、调试密钥 |
| `docs/building.md` | 构建：SDK / JDK 要求、`manual` 档前置环境变量、两种 APK 的产物与安装 |
| `docs/testing.md` | 测试：三档门禁、钩子与绕过审计、无单测框架下的回归口径 |
| `docs/architecture.md` | 架构：三条链路的模块边界、导片工具分层与门禁层 |
| `docs/research/a7c-usb-raw-2026-11.md` | **主报告**，12 节，全部实测与踩坑记录 |
| `docs/contributing/ISSUE_GUIDE.md` | 怎么发 issue：正文四要素、复现命令与 `needs-repro`、关闭方式、模板 |
| `docs/contributing/PR_GUIDE.md` | 怎么开 PR：拆分命名、正文分段、基线验证、门禁改动、合并条件、对账与回签 |
| `docs/contributing/REVIEW_DISPATCH.md` | 合并评审契约：派发方式、评审者必做清单、结论格式、常见误判 |
| `docs/contributing/LOCAL_WORKSPACE.md` | 本地专属目录与内部工具：哪些目录只留本地、15 个参考仓库的来历、证据存放与占位约定 |
| `scripts/review_prompt.py` | 生成合并评审的派发文案（默认不含 PR 正文，`--self-test` 自检） |
| `scripts/check_pr_sections.py` | PR 正文分段结构校验（`support` 档，CI 在 `pull_request` 上跑） |
| `scripts/check_pr_body.py` | PR 正文与评论的敏感信息校验：复用 `check_sensitive.py` 的规则，不认行内豁免（`support` 档，评论走 gh）；`--all` 手动盘点全部历史 PR（只读、不进 CI） |
| `scripts/check_commit_messages.py` | commit message 的敏感信息门禁：`support` 档扫 PR 的 `base..head` 新增提交，本地 `.githooks/commit-msg` 调 `--message-file` 先拦 |
| `scripts/gates.json` | 门禁清单**唯一事实来源**（§0.1 生成段由它渲染） |
| `scripts/check_gates_manifest.py` | 元门禁：清单 / AGENTS.md 生成段 / 磁盘脚本零漂移；cmd 参数不许降级（`--self-test` 跑回归用例） |
| `scripts/check_line_refs.py` | 全仓被跟踪文件（md/java/kt/kts/py）物理行号禁令的门禁（规则在 `AGENTS.md` §2 第 10 条） |
| `tools/android-mtp-probe/README.md` | MTP 探针的用法、实测结果与三个坑 |
| `tools/android-mtp-import/README.md` | 导片工具骨架：类职责、实现状态表、为什么不做真续传、真机待验清单 |
| `tools/android-mtp-import/UI-SPEC.md` | 导片工具界面规范（约束式）：两条主任务与任务流验收、状态机、布局安全区、视觉/文案基线、§8 全部 `U-xx` 断言 + `T-01`～`T-06` |
| `tools/sony-ptpip/README.md` | PTP/IP 抓包与探针的操作手册 |
| `tools/pe/README.md` | PE 检查器：只读边界、子命令与输出形态、样本不入库（子命令清单以 `python tools/pe/pe_inspect.py --help` 输出为准） |
| `docs/archive/INDEX.md` | 归档区索引：逐条时间线、环境、结论与本地证据文件名（原始证据不入库） |
| `docs/archive/README.md` | 归档区使用说明：只收 `.md` / `.txt` 文本记录、原始证据不入库、clone 后本地无证据是预期 |
| `docs/research/README.md` | 探索记录索引与结论时效：逐篇标注记录日期、环境与当时的 HEAD |
| `docs/research/a7c-ptpip-session.md` | 抓包会话逐条分析 + 直连探针实测 |
| `docs/research/lja-ptp-ip-static-analysis.md` | `Lja_PTP_IP.dll` 静态分析结论 |
| `docs/archive/2026-11-audit/` | 早期可行性核查与 15 个仓库审计的产出（存档，不再更新） |

## 7. 工作习惯

- 改前先读相关文件的现状与注释——本仓库注释密度高，多数「为什么」都写在代码旁边，别靠猜。
- 任何涉及相机的结论，**标注环境与复现命令**；没上过真机的推断要写成推断，不要写成事实。
  本项目早期就因为从 DLL 推断「走 SSH 隧道」而误判过一次（a7C 实际是裸 PTP/IP）。
- 修 bug 前先确认测试网：没有能红的用例就先补，再修。
- 文档里贴数字必须带单位与口径（MB/s 是 MiB/s 还是 MB/s、是否含文件系统写入）。
