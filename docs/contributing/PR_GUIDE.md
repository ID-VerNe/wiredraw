# 怎么开 PR（PR 操作指引）

> 本文件是本仓库内所有 Agent 与协作者的 PR 规范，**必须遵照执行**。
> 配套：怎么发 issue 见 [ISSUE_GUIDE.md](ISSUE_GUIDE.md)；流程总览见 [AGENTS.md](../../AGENTS.md) §0。

## 定位：PR 是最小可审查单元

- 一 issue 一 PR：PR 是 issue 的实施载体。**合并只代表改动进了 `main`，不代表症状消失**——
  行为类 PR 用 `Refs #NN`，issue 由发现者回签后关闭。
  合并前的审批由全新上下文的 subagent 做（见「合并评审」），不需要维护者逐 PR 人工过目。
- 拆分原则：每个 PR 都能独立通过门禁、独立合并、独立回滚；拆不开说明边界没想清，回 issue 补 plan。
- 大 issue 先拆再派：**分节用 `P+字母`，优先级用 `P+数字`**。

## 关闭方式：Closes 还是 Refs

| 关键词 | 适用 PR | 关闭时机 |
|---|---|---|
| `Closes #NN` | 纯文档（`docs/`、`*.md`）、纯重构（不改外部行为） | 合并即关闭 |
| `Refs #NN` | 行为类：`fix` / `feat` / `perf`，以及任何触及运行时代码路径的 PR | 合并只进待验收队列（`needs-live-verify`），回签后才关闭 |

判据一句话：**合并后是否还需要在真机上再观察一次症状才能确认有效**——需要就是行为类。

- 同一 PR 既改文档又改行为：按行为类走。
- 多分节 issue 的每个 PR 都用 `Refs #NN`，全部分节合并后统一回签、一次关闭。

### 硬规则：正文里举例一律写 `#NN` 占位

**GitHub 的自动关闭解析器与门禁是两套规则，不要混为一谈：**

| | `scripts/check_pr_sections.py`（门禁） | GitHub 合并动作 |
|---|---|---|
| 判定对象 | 净化后的可见文本（围栏 / 行内代码 / HTML 注释 / 缩进代码块全剔除） | **PR 原始全文**，一个字符都不剔 |
| 认哪些词 | `Closes` / `Refs`（本仓库要求的标准写法） | `close` / `closed` / `fix` / `fixes` / `fixed` / `resolve` / `resolves` / `resolved`，**大小写不敏感**；关键词与 `#<数字>` 之间还允许一个冒号（`Closes: #NN`） |
| 后果 | 判红，改正文即可 | merge 瞬间**真的关掉那个 issue** |

- 正文里凡是要提到关闭关键词的**举例、模板、原始输出、规则说明**，编号一律写 `#NN` 占位
  （`Closes #NN` / `Closes: #NN`），或把关键词与编号拆开写成 `` `Closes` #N ``。
  带**具体数字编号**的 closing 短语，哪怕写在行内代码或围栏里、或写成冒号形式，
  GitHub 照样关单。
- `support` 档的 `closing_side_effects()` 逐行扫原始全文：凡带具体编号、又不在
  「本 PR 明确声明要关」集合里的 closing 关键词一律判红，并报出行号。
- **别为了贴「基线验证」的原始输出，把带具体编号的 closing 原句抄进正文**——那正是本门禁
  要拦的反例。贴命令与退出码，或把输出里的编号替换成 `#NN` 并注明替换过。
- 真实发生：某次 PR 正文用 `Refs` 关联目标 issue，但正文在两处行内代码里各举了一次
  带具体编号的 closing 例子，merge 后 3 秒目标 issue 被自动关闭，变成「先关单、后回签」。
  这是本仓库对这件事的**唯一完整叙述**；`REVIEW_DISPATCH.md` 的常见误判表只保留检查项并指回本节。

## 拆分与命名规范

| 维度 | 标识 | 语义 | 回答的问题 |
|---|---|---|---|
| 分节（步骤） | `P+字母`：`PA`/`PB`…`PZ` | 把 issue 切成有序、可独立合并的步骤；**一个分节 = 一个 PR** | 这是大 issue 的哪一步 |
| 优先级 | `P+数字`：`P0`/`P1`/`P2` | 严重度：P0 阻断（数据损坏 / 传输出错 / 主链不通）、P1 常见路径、P2 边角与增强 | 先做哪个 |

- 正交使用：先按优先级定顺序，同优先级内按分节字母序推进。
- 分节编号 issue 内全局唯一、按序分配、不随优先级重置；分节内多项用 `P+字母+数字`（`PE4`）。
- 引用格式：`#<issue>-P<字母>[数字]`，如 `#NN-PA`。

落点全覆盖：

| 位置 | 形态 |
|---|---|
| issue 正文分节标题 | `### PA · <标题>（P1）` |
| 分支 | 多分节 `type/<issue>-p<字母>[-<数字>]-<slug>`；单分节 `type/<issue>-<slug>` |
| PR 标题 / commit | `type(scope): 摘要（issue #NN）`；多分节加「分节 PA」后缀 |

命名反模式：裸字母分节（A/B/C、「阶段一」）、`P0-R1` 式混合编号、分节编号随优先级重置、把优先级当步骤用。

## 分支

```
type/<issue 号>-<slug>                     # 如 feat/7-mtp-batch-download
type/<issue 号>-p<字母>-<slug>             # 多分节，如 feat/7-pb-progress-ui
```

- 从最新 `main` 切，**用 git worktree，不在主工作区切分支**：

  ```bash
  git worktree add ../wiredraw-<issue> -b type/<issue>-<slug> main
  ```

- `type` 与 issue 的 type 对齐（`fix` / `feat` / `docs` / `refactor` / `chore` / `test`）。
- 本仓库没有需要 junction 共享的大目录（`node_modules` 之类都没有），worktree 开箱可用。
  注意 `tools/android-mtp-probe/build/` 是 gitignore 的，在新 worktree 里重新构建即可。
- worktree 用完 `git worktree remove --force <path>`，别留在磁盘上发酵。

## 标题与 commit

- PR 标题：`type(scope): 摘要（issue #NN）`——**分支、标题、commit 三处都带 issue 号**，
  grep 任一入口都能回到 issue。
- commit 正文说清**为什么这么改**与**实测数字**，不要只列改了哪些文件。
- 一个 PR 允许多个 commit，但每个 commit 自解释，别写 `wip` / `fix`。
- **禁止 `Co-Authored-By:` 行**（`.githooks/commit-msg` 会拦）；同一钩子也校验首行格式。
- 逐个 `git add <file>`，禁止 `git add .` / `-A` / 目录批量。

## 正文分段

固定结构，**必填段缺一不可**：

| 段 | 必填 | 内容 |
|---|---|---|
| `Closes #NN` / `Refs #NN` | 是 | 按上节二选一，放正文开头或末尾 |
| `## 问题` | 是 | 复述 issue 的现象 + 根因（带 `文件:行号`） |
| `## 改动` | 是 | 按文件列改动，每个文件说清「为什么」 |
| `## 测试` / `## 验证` | 是 | 贴门禁原始输出（`python scripts/run_gates.py ci` 的逐条 PASS 行）；只写「都过了」不算 |
| `## 基线验证` | 修红门禁 / 修误报时必填 | 见下 |
| `## 门禁改动` | diff 触及门禁链时必填 | 见「门禁改动与独立复核」 |
| `## 影响面` | 是 | 行为变化、兼容性、明确不动的部分 |
| `## 验收对照` | 大 issue 拆分时必填 | 按分节逐条对照，未完成项显式列出并转新 issue |

### 基线验证是硬要求

门禁类 issue 的缺陷有两个方向，证据形态**相反**，别拿一个模板硬套另一个：

| 缺陷方向 | 未打补丁的 base（pristine worktree） | 打补丁后 |
|---|---|---|
| 修误报（门禁错杀合法正文） | 同一用例必须**红**，红的签名与 issue 症状一致 | 同一用例转**绿** |
| 修漏检（门禁放过违规正文） | 同一负样本必须**绿**（漏检可复现） | 同一负样本转**红**（拦住） |

两个方向都适用的硬要求：

- 两者输出都贴进 PR。只有「打了补丁就绿 / 红」没有「不打补丁时的反向证据」= 假绿，不予合并。
- **新增用例还要给出「用例口径」的成对输出**：同一批新用例喂给未打补丁的 base 逻辑时，
  期望红的那些必须 FAIL，打补丁后全绿——证明用例有判别力，不是靠别的原因变红。
  可以用一个外部脚本 import 两个 worktree 的 `check_body()` 分别跑同一批样本，
  把脚本路径与两条命令贴进 PR，让评审者能原样复现。

`## 基线验证` 里两种口径要分开写、标明方向，别把「负样本 base 绿」当成「基线红」。

### 门禁改动与独立复核

门禁是这套协作里唯一的第三方校验，**被测方却可以在同一个 PR 里改门禁**。
所以 diff 触及门禁执行链时，验收标准加两条。

触发范围（任一命中）：`scripts/check_*.py`、`scripts/gates.json`、`scripts/run_gates.py`、
`.github/workflows/ci.yml`、`.githooks/` 下全部钩子。

1. **正文必须有 `## 门禁改动` 段**，逐个文件交代：断言改了什么（改前/改后）、
   为什么必须改（原始症状如何要求这条改动，不能是「为了变绿」）、
   三种组合的红绿证据（未打补丁 base / 打补丁 / 只改门禁——**只改门禁必须仍红在原症状上**）、
   是否同步跑了 `check_gates_manifest.py --write`。
2. **独立复核**：由**不同上下文**的复核会话只读 PR diff + 本地复跑门禁，
   结论（`APPROVE` / `REQUEST_CHANGES`）与证据写进 `## 门禁改动` 段**末尾**。
   修复者不能给自己复核。

清单至少覆盖：门禁是否仍能钉住原症状（而不是改成「怎么跑都绿」）；是否删弱或跳过了断言、
放宽了阈值；基线证据是否按缺陷方向成对且与症状签名一致；是否漏改清单 / 生成段。

## 开 PR 前自查

1. `python scripts/run_gates.py ci` 全绿；只改文档时也要跑（`check_doc_links.py` 就在里面）。
2. 动过 `tools/android-mtp-probe/` 时另跑 `python scripts/run_gates.py manual`（需要 Android SDK + JDK）。
3. 涉及相机的改动，**贴真机原始输出**：`adb logcat -s MTPPROBE:*` 或 `probe.py` 的完整输出。
4. 门禁红了先归因：用 pristine HEAD 的 worktree 对照跑一遍，分清代码问题与环境抖动，再动手。
5. 确认分支基于最新 `main`：`git fetch origin main` 后无落后。
6. push 前确认 `git status` 干净。
7. 正文里没有**带具体编号的 closing 关键词**：举例一律 `#NN` 占位。
   `python scripts/check_pr_sections.py --body-file <正文文件> --files <改动文件...>`
   能在本机提前复现 `support` 档的结论；省略 `--files` 等于宣称没有改动文件，门禁链会静默跳过。
8. commit message 也扫敏感值（`support` 档 `check_commit_messages.py`）：本地 `commit-msg`
   钩子会先拦；钩子被绕过时用 `python scripts/check_commit_messages.py --range origin/main..HEAD` 自查。

## 合并条件（全部满足才合）

1. **门禁全绿**：`python scripts/run_gates.py ci` 通过；CI（GitHub Actions）同份清单也绿。
2. **无冲突**：GitHub 显示 `MERGEABLE` / `CLEAN`；有冲突不强行合——
   在 worktree 里 `git merge origin/main` 解决，重跑门禁后再推。
3. 适用时有 `## 基线验证` 段。
4. **合并评审结论为 `APPROVE`**：由全新上下文的 subagent 出具，见下节。
5. **合完立刻核对目标 issue 没被意外关闭**：贴合并前后时间线，确认关单发生在回签之后，
   而不是 merge 后几秒：

   ```bash
   gh pr view <N> --json mergedAt -q .mergedAt
   gh api repos/{owner}/{repo}/issues/<N>/timeline --paginate \
     -q '.[] | select(.event=="closed") | .created_at'
   ```

   行为类 PR 若发现 `closed` 紧跟 `mergedAt`，说明正文里混进了带具体编号的 closing 关键词，
   先补回签再重开 issue 并在回签里说明，不要当作正常关闭放行。

合并方式跟随仓库现状：**merge commit**（`gh pr merge <n> --merge`），不 squash、不 rebase。

### 合并评审

提交者写完全部文档、门禁转绿之后，**开辟一个全新上下文的 subagent 做合并评审**，
由它决定合不合；维护者不再逐 PR 人工确认。

**契约与清单的唯一归属是 [REVIEW_DISPATCH.md](REVIEW_DISPATCH.md)**（职责边界、
评审者必做清单、结论格式、常见误判）。本节只留操作口径：

```bash
python scripts/review_prompt.py --pr <PR 号>       # 生成派发文案，原样交给新会话
python scripts/review_prompt.py --self-test        # 自检
```

- 派发文案**不要手写**：手写一定会夹带提交者的叙述，而这个缺陷事后无法检查。
  脚本默认**不输出 PR 正文**，只给「自己去 `gh pr view <N>` 读」的指令 ——
  「不投喂结论」是结构性保证，不是自觉。
- 评审者只拿到 PR 编号、仓库路径，以及分支 / 标题 / 作者 / 改动文件清单这类**元信息**
  （这些不含提交者的判断）；**不提供转述、不回答「你跑绿了吗」**。
  正文与 diff 由评审者自己去读。
- 结论 `APPROVE` → 提交者直接合并；`REQUEST_CHANGES` → 按最小修法改，
  改完**重新派一轮全新上下文**的评审，不能拿上一轮意见当本轮结论。
- 正文分段与 `Closes`/`Refs` 这类结构问题由 `scripts/check_pr_sections.py`（`support` 档，
  CI 在 `pull_request` 上跑）兜底；它**不判内容真假**，真假只有评审者能判。

评审通过后由提交者合并，完成「合并后：对账与回签」的自查部分，
再另派一个全新上下文的会话做回签。

> **`git push` 网络受限时**：改用 SSH remote
> （`git push git@github.com:<owner>/<repo>.git <branch>`，`<owner>/<repo>` 即本仓库），
> `gh` 命令照常可用。

## 合并后：对账与回签

对账是修复者自查，回签是发现者验收，两者都要做：

- **自述 vs 实际逐项核对（修复者）**：PR 说做了的每一条，合并后独立复核
  （`git status`、文件存在性、门禁原始输出）。「完成声明以磁盘为准」，不采信任何转述。
- **欠账转新 issue**：对账发现的缺口逐条开新 issue 跟进。合并不算完，对账才算。
- **行为类 issue 回签（发现者 / 全新上下文验证会话）**：修复者合并后给 issue 贴
  `needs-live-verify`；回签者按下表贴评论，结论为「症状消失」才摘标签并关闭。
  仍复现则摘标签、留 issue 并补证据。**回签者不能是本次 PR 的作者。**
- 同根因复发时开第二代修复并互相引用，每代都留能红的用例作墓碑。

### 回签模板

贴到 issue 评论里，字段缺一不可：

- **环境**：`main` @ `<sha>`，ILCE-7C fw2.00 / E1 魅族 20 Infinity 或 E2 MI 9 / Android 15 / USB MTP，`<时间>`
- **复现命令**：优先原样复用 issue「现象」段里的那条
- **期望输出**：修复后应看到什么
- **实际输出**：粘贴原始输出，不要只写「正常」
- **真机证据**：`adb logcat` 关键行 / 截图路径 / 传输速率与 MD5
- **结论**：症状消失 / 仍复现（仍复现要贴差异）

## 模板（可直接抄）

```markdown
## 问题

<issue 现象复述 + 根因，带 `文件:行号`>

## 改动

`<文件>`
- <改动点 + 为什么>

## 测试

```
<python scripts/run_gates.py ci 的逐条 PASS 行>
```

## 基线验证

用例口径（新增用例的成对输出 + 可原样执行的脚本路径/命令）：
  未打补丁 base：<红 N 项>   打补丁后：<全绿 M 项>
负样本口径（标明方向）：
  修误报：base <红，贴签名> → 补丁后 <绿>
  修漏检：base <绿，漏检可复现> → 补丁后 <红，拦住>

## 门禁改动

仅当 diff 触及门禁链时保留；逐个文件写清改了什么、为什么必须改、三种组合的红绿证据。

**独立复核**：<APPROVE / REQUEST_CHANGES> + 复核证据（必须放在本段最末）

## 影响面

<行为变化 / 兼容性 / 不动的部分>

## 验收对照

- [x] <issue 验收项 1>
- [ ] <未完成项，转 #NN>

Closes #NN
```

行为类 PR 把末行换成 `Refs #NN`，合并后按「合并后：对账与回签」等发现者回签。
