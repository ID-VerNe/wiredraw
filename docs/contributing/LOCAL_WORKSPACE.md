# 本地专属目录与内部工具

> 第一次 clone 本仓库时，你会看到文档里提到、但仓库里没有原始文件的目录（设计稿、日期工作区、
> 原始抓包等）。原始文件是维护者本机的过程产物，**不是依赖**：缺了它们，构建、门禁与真机验证
> 都照常能跑，只是无法复现当时的历史证据。
>
> 本页只回答「是什么、在哪、要不要进版本库、没有会怎样」。规则的唯一事实源仍是
> [AGENTS.md](../../AGENTS.md) §0/§2 与 [`.gitignore`](../../.gitignore)；若本页与它们不一致，
> 以它们为准。

## 只留本地的目录

| 目录 / 文件 | 进版本库 | 是什么 | 没有它会怎样 |
|---|---|---|---|
| `.design/` | 否 | UI 设计工具导出的 ZIP（日间/夜间主题、参考稿） | 不影响构建；界面约束写在 [`UI-SPEC.md`](../../tools/android-mtp-import/UI-SPEC.md) |
| `.screenshots/` | 否 | 真机截图与界面比对图，可能带设备身份与相机界面信息 | 无影响；需要时按工具 README 重新截图 |
| `YYYY-MM-DD/` | 否 | 按日期建立的会话工作区：真机截图、评审派发、基线输出、诊断日志等中间产物 | 不影响；有价值的结论已整理进 `docs/archive/` 与 `docs/research/` |
| `.tmp/` | 否 | 仓库内的临时目录兜底 | 无影响；worktree 约定放在仓库外，见下文 |
| `.workbuddy/`、`.handoffs/` | 否 | 本地过程记忆与会话交接文档，含会话上下文 | 无影响；它们每天新增，对协作者没有复用价值 |
| `captures/`、`out/` | 否 | 抓包派生的可读流水、手机侧报告与截图、RAW 样本 | 需要时按工具 README 重新生成；结论见 `docs/research/` 与归档区 |
| `tools/android-mtp-probe/build/`、`tools/android-mtp-import/build/` | 否 | APK、class、Gradle 产物 | 重新构建即可 |

具体忽略规则以 [`.gitignore`](../../.gitignore) 为准（它按类别分段并写明理由）。
`git check-ignore -v <path>` 只回答「当前规则是否已覆盖」；**没命中不等于可以提交**——
规则可能滞后于本表的约定，仍按「只留本地」处理。

## 15 个第三方参考仓库

项目启动时，维护者在本地 clone 了 15 个与索尼相机控制相关的开源仓库，只做**只读审计**，
用来判断「官方 App 能不能传 RAW」「PTP/IP 怎么握手」。关于它们，记住四件事：

- **不是依赖**。没有任何构建脚本、门禁或运行时代码引用这些目录；删掉它们不影响仓库任何功能。
- **结论已归档**。逐条判断与当时的 HEAD 快照在
  [`docs/archive/2026-11-audit/`](../archive/2026-11-audit/)，不再需要本地副本。
- **规则是为了防复发**。`.gitignore` 挡不住显式 `git add`，一次 `git add .` 就会把这些目录
  吸进暂存区，所以忽略规则与门禁必须同时保留。
- **名单不由本页复述**。作为规则判据的名单只出现在两处：`.gitignore` 的忽略规则，以及
  [`scripts/check_no_vendored.py`](../../scripts/check_no_vendored.py) 的 `VENDORED` 常量；
  历史快照与逐条判断见归档。`VENDORED` 是门禁自己的判据，**不能改成从 `.gitignore` 反推**——
  否则删掉规则的同时也删掉了判据。

想自己复核这些仓库，按归档里记录的上游仓库名去找；不要把它们提交进本仓库。

## 证据与抓包的存放约定

- **原始证据只留本地**：pcapng、logcat、dump、截图、APK、RAW 都不入库。
- **仓库只收文本结论**：长期有效的进 [`docs/research/`](../research/)，
  一次性的过程记录进 [`docs/archive/`](../archive/)。
- **重新生成的方法写在工具 README 里**：MTP 报告见
  [`tools/android-mtp-probe/README.md`](../../tools/android-mtp-probe/README.md)，
  PTP/IP 抓包见 [`tools/sony-ptpip/README.md`](../../tools/sony-ptpip/README.md)。
- **为什么这么定**：单次抓包可达上百 MB（超过 GitHub 单文件上限）、一张 RAW 24 MB，
  而且抓包与截图可能带设备身份。门禁 `check_no_vendored.py` 会拦下任何 >5 MB 的被跟踪文件。

## 内部协作脚本

- **`scripts/review_prompt.py`** —— 合并评审的派发工具：读 PR 元信息，生成一段交给
  「全新上下文评审者」的文案，默认**不输出 PR 正文**，避免把提交者的结论喂给评审者。
  它依赖 `gh` 登录与仓库读权限；**外部贡献者不需要运行它**，评审契约见
  [`REVIEW_DISPATCH.md`](REVIEW_DISPATCH.md)。
- **`.githooks/`** —— 入库的 git 钩子，但不会自动生效。装一次：
  `git config core.hooksPath .githooks`。职责与绕过口径见 [AGENTS.md](../../AGENTS.md) §0.1。
  不装也能提交，只是 `commit-msg` 的格式检查与
  `pre-push` 的 static 分片会被跳过，问题会推迟到 PR 阶段才暴露。
- **worktree** —— 约定放在仓库外：`git worktree add ../wiredraw-<issue> -b <branch> main`；
  万一放进了仓库内，就落在 `.tmp/`。用完 `git worktree remove --force`，别留在磁盘上发酵。

## 隐私与占位

- 文档与示例里**不写**内网地址、相机配对 GUID、真机序列号，以及含用户名的本机绝对路径。
- 需要举例时统一用占位符：`<owner>/<repo>`、`<camera-guid>`、`<serial>`、`/path/to/...`。
- 唯一入库的密钥是调试用的 [`rea-debug.keystore`](../../tools/android-mtp-probe/rea-debug.keystore)
  （口令 `android`）。它只为让 `adb install -r` 可复现，不含真实凭据；正式签名不要入库。
