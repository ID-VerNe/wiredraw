# 测试（Testing）

> 门禁清单只有一份事实来源：[`scripts/gates.json`](../scripts/gates.json)；
> 执行器是 [`scripts/run_gates.py`](../scripts/run_gates.py)。本机与 CI 跑同一份清单，
> 不要在别处再写一份命令数组。

## 一、三档门禁

| 档 | 条目 | 何时跑 | 需要什么 |
|---|---|---|---|
| `ci` | `static` 档（条数以 [`scripts/gates.json`](../scripts/gates.json) 为准）：元门禁、仓库卫生、文档链接、Python 语法、敏感信息 | 每次改动后；`pre-push` 也只跑这一档 | 只要 Python |
| `manual` | 条数以 `scripts/gates.json` 为准：探针 APK 构建、导片工具自检 + 构建 + 验签 | 动到 `tools/android-mtp-probe/` 或 `tools/android-mtp-import/` 时 | Android SDK + JDK |
| `support` | 条数以 `scripts/gates.json` 为准：PR 正文分段、PR 正文与评论的敏感信息、commit message 敏感信息 | 有 PR 上下文时（CI 的 `pull_request`） | `gh` / `GH_TOKEN`；本地无 PR 时 SKIP |

```bash
python scripts/run_gates.py --list          # 看档位与执行顺序
python scripts/run_gates.py ci              # 全量（最便宜的一档，本机日常）
python scripts/run_gates.py ci --fail-fast  # 见到红就停
python scripts/run_gates.py ci --shard static
python scripts/run_gates.py manual          # 真机/工具链档（Android 构建）
python scripts/run_gates.py support         # 需要 PR 上下文的一档
```

`ci` 全绿是最低要求；**只改文档也要跑**，`check_doc_links.py` 就在里面。
`manual` 档的前置环境变量见 [building.md](building.md) 第二节。

## 二、钩子与绕过

钩子的安装、`commit-msg` / `pre-push` 职责与 `SKIP_GATES=1` 的绕过审计，统一以
[`AGENTS.md`](../AGENTS.md) §0.1 为准；本节不再复述，避免两处口径漂移。

## 三、回归靠什么

本仓库没有通用单元测试框架。回归靠两类东西：

1. **`scripts/` 下的门禁脚本**：`check_gates_manifest.py` / `check_doc_links.py` /
   `check_sensitive.py` / `check_pr_body.py` / `check_pr_sections.py` /
   `check_commit_messages.py` / `review_prompt.py` 带 `--self-test`，改门禁先改用例；
   `_common.py` 没有参数入口，**直接运行即跑自检，带任何参数一律退 2**；
   `check_no_vendored.py` 与 `py_compile` 没有自检入口。
   导片工具有独立的 JVM 自检（`manual` 档的 `selfTest`；项数随用例增加，
   以输出末行 `== 自检全绿：N 项 ==` 为准），
   纯逻辑类不允许 `import android.*`，由编译期强制。
2. **可重复执行的真机命令**：相机相关结论必须附带可直接粘贴的复现命令与原始输出。

### 退出码契约（`1` 与 `2` 必须分开）

| 码 | 含义 | 典型场景 |
|---|---|---|
| `0` | 绿：检查通过 / 自检全绿；本地无 PR 上下文时的 SKIP 也是 `0` | 门禁全绿、`--self-test` 全绿 |
| `1` | **内容不达标**：这份东西本身不合格 | 断链、敏感值命中、PR 缺必填段、超 5 MB 的被跟踪文件 |
| `2` | **环境或参数问题**：这次检查没做成 | `gh` / `git` 不可用、输出不是 JSON、文件读不到、参数形态不对 |

混用这两类会让 CI 把环境抖动记成内容问题（或反过来把漏检记成通过）。共享的机械能力
（`gh` 取数、`git ls-files -z`、命中行格式、门禁链判定、退出码常量）收口在
[`scripts/_common.py`](../scripts/_common.py)；扫什么、算不算命中仍留在各门禁脚本里。

`git ls-files` 一律带 `-z`：不带时中文文件名会被 `core.quotePath` 转义成假路径，
`check_doc_links.py` 会把**确实存在**的中文名文档报成「被跟踪 md 读不到」——这是
`core.quotePath=true` 机器上的固定假红，不是漏检（#204）。


写成用例时先写「能红」的用例，再改实现；变异必须替换断言瞄准的子串，前面加无意义前缀不算变异。
变异还原**不许用 `git checkout --`**（会把本轮全部改动冲回 HEAD），协议是
`cp file /tmp/x.bak` → 变异 → `cp` 回写。

## 四、真机证据纪律

任何涉及相机的结论都要标注环境：**机型 / 固件版本 / 手机型号 / Android API / 连接方式（USB 还是 Wi-Fi）**。
缺了就无法复现。推断要写成推断，不要写成事实。

跨环境验证状态收在[验证环境矩阵](verification-matrix.md)：每个「通过」都带日期与环境，
没跑过的组合一律写「未验证」。

## 五、门禁类改动的额外要求

diff 触及门禁执行链（`scripts/check_*.py`、`scripts/gates.json`、`scripts/run_gates.py`、
CI workflow、`.githooks/`）时，PR 必须有 `## 门禁改动` 段，并按缺陷方向给出成对证据：

- **修误报**：未打补丁的 base 上同一用例必须红，补丁后转绿；
- **修漏检**：未打补丁的 base 上同一负样本必须绿（漏检可复现），补丁后转红（拦住）。

只有「打了补丁就绿」没有反向证据 = 假绿。合并评审由全新上下文的 subagent 做，
提交者不能给自己评审。细则见 [PR_GUIDE.md](contributing/PR_GUIDE.md) 与
[REVIEW_DISPATCH.md](contributing/REVIEW_DISPATCH.md)。

## 相关文档

- 流程总览：[AGENTS.md](../AGENTS.md) §0
- 构建：[building.md](building.md)
- 发布与门禁改动：[PR_GUIDE.md](contributing/PR_GUIDE.md)
