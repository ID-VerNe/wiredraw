# 测试（Testing）

> 门禁清单只有一份事实来源：[`scripts/gates.json`](../scripts/gates.json)；
> 执行器是 [`scripts/run_gates.py`](../scripts/run_gates.py)。本机与 CI 跑同一份清单，
> 不要在别处再写一份命令数组。

## 一、三档门禁

| 档 | 条目 | 何时跑 | 需要什么 |
|---|---|---|---|
| `ci` | 5 条 `static`：元门禁、仓库卫生、文档链接、Python 语法、敏感信息 | 每次改动后；`pre-push` 也只跑这一档 | 只要 Python |
| `manual` | 2 条：探针 APK 构建、导片工具自检 + 构建 + 验签 | 动到 `tools/android-mtp-probe/` 或 `tools/android-mtp-import/` 时 | Android SDK + JDK |
| `support` | 3 条：PR 正文分段、PR 正文与评论的敏感信息、commit message 敏感信息 | 有 PR 上下文时（CI 的 `pull_request`） | `gh` / `GH_TOKEN`；本地无 PR 时 SKIP |

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

```bash
git config core.hooksPath .githooks   # 换机器后重设一次
```

- `commit-msg`：拦 commit 文案格式与 `Co-Authored-By:`。
- `pre-push`：跑 `static` 分片。

`SKIP_GATES=1` **仅用于钩子自身故障**（例如钩子在当前 shell 下跑不起来）。它会打印醒目横幅，
并把绕过记录写入 `$(git rev-parse --git-common-dir)/skip-gates.log`（所有 worktree 共用一份）。
使用后必须在 PR 正文说明原因，并附上手动执行 `python scripts/run_gates.py ci` 的原始输出；
它不是「门禁红了赶时间」的通行证。

## 三、回归靠什么

本仓库没有通用单元测试框架。回归靠两类东西：

1. **`scripts/` 下的门禁脚本**：`check_gates_manifest.py` / `check_doc_links.py` /
   `check_sensitive.py` / `check_pr_body.py` / `check_pr_sections.py` /
   `check_commit_messages.py` / `review_prompt.py` 带 `--self-test`，改门禁先改用例；
   `check_no_vendored.py` 与 `py_compile` 没有自检入口。
   导片工具有独立的 JVM 自检（`manual` 档的 `selfTest`；项数随用例增加，
   以输出末行 `== 自检全绿：N 项 ==` 为准），
   纯逻辑类不允许 `import android.*`，由编译期强制。
2. **可重复执行的真机命令**：相机相关结论必须附带可直接粘贴的复现命令与原始输出。

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
