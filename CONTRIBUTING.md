# 贡献指南（CONTRIBUTING）

> 本文件面向**外部贡献者**，只提供入口与摘要。
> 协作规则的唯一事实源是 [AGENTS.md](AGENTS.md)：两者冲突时以 AGENTS.md 为准，
> 本文件不复述规则原文，避免两处漂移。

## 这个项目是什么

让安卓手机通过 **USB 有线**连接索尼 a7C（ILCE-7C），把存储卡里的 RAW（`.ARW`）传到手机。
仓库里放的是**真机实测**结论与配套工具：MTP 探针、PTP/IP 只读探针、抓包解析器，
以及正在建设的导片工具。

先读 [README.md](README.md) 的一页结论，再看
[RESEARCH-android-usb-raw-a7c.md](RESEARCH-android-usb-raw-a7c.md) 的完整实测记录。

## 需要什么环境

| 用途 | 依赖 |
|---|---|
| Python 工具与门禁 | Python 3，**只用标准库**，不引入第三方包 |
| 探针 / 导片工具构建（`manual` 档） | Android SDK + JDK；导片工具用仓库自带的 Gradle wrapper |
| 真机验证 | ILCE-7C 固件 2.00 + 魅族 20 Infinity / Android 15 (API 35)，USB MTP 连接 |

没有真机也能做文档、门禁与静态分析；但任何涉及相机的结论都必须标注机型、固件、
手机与 Android 版本、连接方式（USB 还是 Wi-Fi），否则别人无法复现。

## 怎么跑门禁

门禁清单只有一个事实来源 [scripts/gates.json](scripts/gates.json)，执行器是
[scripts/run_gates.py](scripts/run_gates.py)，本机与 CI 跑同一份：

```bash
python scripts/run_gates.py --list          # 看档位与顺序
python scripts/run_gates.py ci              # 最便宜的全量档，改完就跑
python scripts/run_gates.py ci --fail-fast  # 见到红就停
python scripts/run_gates.py manual          # Android 构建档（需要 SDK + JDK）
```

装一次 git 钩子（`commit-msg` 校验提交信息格式，`pre-push` 跑 static 分片）：

```bash
git config core.hooksPath .githooks
```

新增门禁只改 `scripts/gates.json`，然后跑
`python scripts/check_gates_manifest.py --write` 同步 AGENTS.md 的生成段。

另有 `support` 档（`python scripts/run_gates.py support`）：它校验需要 PR 上下文的项，
本地没有 PR 上下文时会 `SKIP`，属正常。

## issue 与 PR 的最小要求

完整细则见 [doc/ISSUE_GUIDE.md](doc/ISSUE_GUIDE.md) 与
[doc/PR_GUIDE.md](doc/PR_GUIDE.md)；流程总览见 [AGENTS.md](AGENTS.md) §0。最小要求：

1. **先发 issue**：现象（行为类必须带一条可直接粘贴的复现命令）/ 证据链（`文件:行号`）/
   影响面（P0–P2）/ 修复候选，四要素齐全。
2. **plan 追评到 issue**：探索结论 + 方案 + **明确不做** + 验证方式，
   并回填一行「用 issue 的复现命令复现：成功 / 失败」。
3. **用 worktree 开发**，不在主工作区切分支：
   `git worktree add ../wiredraw-<issue> -b type/<issue>-<slug> main`。
4. **分支、PR 标题、commit 三处都带 issue 号**；commit 首行
   `type(scope): 中文摘要（issue #NN）`，禁止 `Co-Authored-By`。
5. **PR 正文分段齐全**：`## 问题` / `## 改动` / `## 测试`（贴门禁原始输出）/
   `## 影响面`，必要时补 `## 基线验证` / `## 门禁改动` / `## 验收对照`。
6. **关闭方式**：纯文档 / 纯重构用 `Closes #NN`；行为类用 `Refs #NN`，
   合并后在真机回签，症状消失才关单。
7. **逐个 `git add <file>`**，禁止 `git add .` / `-A`。

## 代码与文档风格

- **探针只读**：`tools/` 下连相机的代码不得下发任何写操作（删除、格式化、改设置、
  触发快门都不行）。需要写操作先开 issue 讨论，不在 PR 里顺手加。
- Python 工具只用标准库；脚本必须能通过 `py_compile`。
- 注释与 README、CONTRIBUTING 用中文；命令、协议名、代码标识保留英文原文。
- 文档里的相对链接必须可达（`check_doc_links.py` 会校验），断了按门禁红处理。
- 大文件不入库：RAW 样本、原始抓包、构建产物一律 gitignore；
  15 个第三方参考仓库也不入库。
- 文档里贴数字必须带单位与口径；涉及相机的结论必须标注环境。

## AI 协作说明

本仓库大量改动由 AI agent 完成，因此流程里有几道**结构性**约束：

- [AGENTS.md](AGENTS.md) 是协作规则的唯一事实源；本文件只是入口摘要。
- **提交者不能给自己评审**：PR 合并前由全新上下文的 subagent 做合并评审，
  契约见 [doc/REVIEW_DISPATCH.md](doc/REVIEW_DISPATCH.md)；
  合并后的真机回签同样由发现者或全新上下文的会话执行。
- **完成声明以磁盘为准**：任何「已完成」都要能用 `git status`、文件存在性与
  门禁原始输出复核，不采信转述。

## 许可证

本项目以 [Apache License 2.0](LICENSE) 授权。提交贡献即表示同意按该许可证分发。
