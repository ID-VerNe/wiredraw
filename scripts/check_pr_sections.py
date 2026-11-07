#!/usr/bin/env python3
"""PR 正文的分段结构校验（`support` 档）。

只查**结构**，不查内容真假 —— 内容真假只有评审者能判（见 `docs/contributing/REVIEW_DISPATCH.md`）。
盯住三件事：

1. 必填分段齐全：`## 问题` / `## 改动` / `## 测试`（或 `## 验证`）/ `## 影响面`；
2. 有关闭关键词：`Closes #NN` 或 `Refs #NN`；
3. diff 命中门禁链时，必须有 `## 门禁改动` 段。

用法：
    python scripts/check_pr_sections.py --pr 12
    python scripts/check_pr_sections.py --body-file .tmp-body.md --files scripts/gates.json
    python scripts/check_pr_sections.py            # 无 PR 上下文 → SKIP，退出 0

无 PR 上下文时 SKIP 是设计：这条需要 GitHub 上下文，放进 `ci` 档会让本地每次
`run_gates.py ci` 都得带 `--pr`，把最便宜的一档弄脆。

但 **有 PR 上下文却取不全**（事件载荷缺 base/head、`git diff` 跑不通）时**必须红**：
那种情况下 `files` 会是空的、门禁链判定恒为否，同一份正文会因环境抖动从红变绿。

退出码：0 绿或 SKIP / 1 红 / 2 环境问题。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

from _common import ENV_FAIL, EnvProblem, gh_json, gate_hits, run

ROOT = Path(__file__).resolve().parent.parent

# (显示名, 可接受的段名候选)；每行一个「必填段」。
REQUIRED = [
    ("问题", ("问题",)),
    ("改动", ("改动",)),
    ("测试 / 验证", ("测试", "验证")),
    ("影响面", ("影响面",)),
]

# ATX 标题，按 CommonMark：最多缩进 3 个空格，`##` 后必须是**半角**空格或制表符。
# 两点都不能省：`  ## 影响面` 在 GitHub 上是标题（用 `^##` 会误红）；
# `##　影响面`（全角空格）不是标题（用 `\s+` 会误绿）。
HEADING = re.compile(r"^ {0,3}##[ \t]+(.+?)[ \t]*$", re.M)
CLOSING = re.compile(r"\b(Closes|Refs)\s+#(\d+)", re.I)
GATE_SECTION = "门禁改动"

# GitHub 的自动关闭关键词（PR 正文里出现就真的会关掉对应 issue）。
# 与 CLOSING 的区别：CLOSING 判的是「本 PR 打算怎么关联 issue」，只认 Closes/Refs
# 且要求本仓库的标准写法；这里判的是「合并这个动作会不会产生关单副作用」，
# 要覆盖 close/closed、fix/fixes/fixed、resolve/resolves/resolved 全套动词，
# 还要覆盖 GitHub 允许的冒号写法（`Closes: #N`）与任意大小写（re.I）。
#
# 关键词与编号之间只允许半角空白 + 一个可选冒号，不用 `\s`：`\s` 含换行，
# 意图层拿的是整份净化后文本，跨行匹配会把「上一行以关键词结尾、下一行以编号开头」
# 误判成关闭。逐行扫描本来不跨行，所以必须在这里钉死。
#
# 关键落差：门禁把围栏 / 行内代码 / HTML 注释里的关键词剔除后再判意图，
# GitHub **不剔除**。PR #4 正文第 54、231 行在行内代码里举了 `Closes` #3 的例子，
# 门禁判绿、GitHub 在 merge 后 3 秒关掉了 issue #3，绕过「回签通过才关单」。
GITHUB_CLOSING = re.compile(
    r"\b(?:close[sd]?|fix(?:es|ed)?|resolve[sd]?)[ \t]*:?[ \t]*#(\d+)\b", re.I
)
REF_ONLY = re.compile(r"\bRefs\s+#(\d+)", re.I)

# 围栏代码块与 HTML 注释整段剔除后再找标题与关键词。不剔的话，正文里贴一段示例
# （PR 模板、回签模板、原始输出）就能满足段检查，门禁等于可绕过。
#
# 按 CommonMark 的规则逐行扫，而不是用一条正则：
#   - 开围栏：最多缩进 3 个空格 + 3 个以上 ` 或 ~；
#   - 闭围栏：同样最多 3 空格、同一字符、**不短于**开围栏长度，其后只允许空白；
#   - 到文末仍未闭合：余下全部按代码块处理（不能让尾部内容照算）。
# 一条 `^(```|~~~).*?^\1$` 的正则会漏掉缩进围栏与「开 3 闭 4」，等于把洞留在原地。
FENCE_OPEN = re.compile(r"^ {0,3}(`{3,}|~{3,})")
INDENTED_CODE = re.compile(r"^( {4,}|\t)")
INLINE_CODE = re.compile(r"`[^`\n]*`")


def normalize_eol(text: str) -> str:
    """统一行尾为 LF。

    PR 正文的换行来自提交者的本地环境：`gh pr view --json body` 与 GitHub 事件载荷
    会把 CRLF 原样带回来（JSON 里的 `\r\n` 是转义字符，不解成行尾），而
    `Path.read_text()` 的 universal-newline 会把它翻成 LF。不归一化的话，同一份正文
    会在 `--body-file` 下绿、在 `--pr` / CI 下红——正是本仓库禁止的「因环境抖动改变结论」。
    """
    return text.replace("\r\n", "\n").replace("\r", "\n")


QUOTE_PREFIX = re.compile(r"^ {0,3}>[ \t]?")
LIST_PREFIX = re.compile(r"^ {0,3}([-*+]|\d{1,9}[.)])[ \t]+")


def strip_containers(line: str) -> str:
    """剥掉行首的引用块 / 列表项前缀，让容器里的围栏也能被认出来。

    围栏前面多一个 `> ` 或 `- `，`^ {0,3}``` 就匹配不上了：代码块里的 `Closes`
    被当成真关键词（过松），或者那个「闭围栏」被当成新的开围栏、一路吞到文末（过严）。
    两种都实测过。

    副作用是容器里的标题也会被当成顶层标题 —— 这是**有意的**：引用块里的
    `> ## 影响面` 在 GitHub 上照样渲染成一个可见的二级标题，算它存在是对的。
    """
    prev = None
    while prev != line:
        prev = line
        line = QUOTE_PREFIX.sub("", line, count=1)
        line = LIST_PREFIX.sub("", line, count=1)
    return line


def strip_markup(text: str) -> str:
    """剔除围栏代码块、缩进代码块与 HTML 注释，只留「在页面上真会渲染出来」的行。

    判定必须按 CommonMark 走，四类都要照顾到，用一条正则只能覆盖其中一类：

    1. **围栏代码块**：开围栏最多缩进 3 空格 + 3 个以上 ` 或 ~；闭围栏同字符、
       不短于开围栏、其后只允许空白；到文末未闭合则余下全部算代码。
    2. **HTML 注释**：`<!--` 即注释块开始；**到文末都没等到 `-->` 时余下全部不可见**
       （这是一份正文最彻底的「隐身」方式，页面上一个字都没有）。
       一行里可能有**多个** `<!--`，必须循环处理 —— 只处理第一个的话，
       `<!-- x --> <!--` 这种写法能把未闭合注释藏到第二个位置，绕开检查。
    3. 注释在同一行闭合时，`-->` 之后的内容仍然**不在行首**，不能当标题 ——
       用 `\\x00` 顶在它前面，让 `^ {0,3}##` 认不出来。直接丢掉整行会误伤
       `Refs #3 <!-- 备注 -->` 这种写法。
    4. **行内代码**（`...`）与**缩进代码块**（空行后 4 空格 / 制表符起始）里的
       `<!--` 是字面量，不是注释起点。反过来判会把合法正文吃掉（过严）。
    """
    kept: list[str] = []
    lines = normalize_eol(text).split("\n")
    n = len(lines)
    i = 0
    in_comment = False
    prev_blank = True  # 文首按空行算
    prev_indented = False

    while i < n:
        line = strip_containers(lines[i])

        # 1) 本来就在注释块里：丢到 `-->` 为止（没有就丢到文末）
        if in_comment:
            j = line.find("-->")
            if j < 0:
                i += 1
                prev_blank = False
                continue
            line = "\x00" + line[j + 3 :]
            in_comment = False

        # 2) 围栏代码块整段跳过
        m = FENCE_OPEN.match(line)
        if m:
            ch = m.group(1)[0]
            size = len(m.group(1))
            close = re.compile(r"^ {0,3}" + re.escape(ch) + "{" + str(size) + r",}[ \t]*$")
            i += 1
            while i < n and not close.match(strip_containers(lines[i])):
                i += 1
            i += 1  # 跳过闭围栏（若已到文末，i 越界，循环自然结束）
            prev_blank = False
            prev_indented = False
            continue

        # 3) 行内代码是字面量。不先剔，正文里引用「未闭合的 `<!--`」会把自己吞掉。
        line = INLINE_CODE.sub("", line)

        # 4) 缩进代码块（空行之后、或紧接上一行缩进）里的 `<!--` 不是注释
        if INDENTED_CODE.match(line) and (prev_blank or prev_indented):
            kept.append(line)
            prev_blank = False
            prev_indented = True
            i += 1
            continue

        # 5) 同一行可能有多个注释：闭合一个就继续找下一个
        while True:
            k = line.find("<!--")
            if k < 0:
                break
            rest = line[k + 4 :]
            j = rest.find("-->")
            if j < 0:
                in_comment = True
                line = line[:k]
                break
            line = line[:k] + "\x00" + rest[j + 3 :]

        if line.strip():
            kept.append(line)
        prev_blank = not line.strip()
        prev_indented = False
        i += 1

    return "\n".join(kept)


def strip_indented(text: str) -> str:
    """去掉 4 空格 / 制表符起始的行：那是缩进代码块，页面上的内容是代码不是关键词。

    只用于 `Closes` / `Refs` 的匹配 —— 标题正则本身已经是 `{0,3}`，不必再切一遍。
    """
    return "\n".join(ln for ln in text.split("\n") if not INDENTED_CODE.match(ln))


def headings(body: str) -> list[str]:
    return [m.group(1).strip() for m in HEADING.finditer(strip_markup(body))]


def has_section(heads: list[str], names: tuple[str, ...]) -> bool:
    """段名按前缀匹配：`## 测试（门禁原始输出）` 也算 `测试`。

    接收已算好的标题列表：调用方只剥离一次正文，比较多次复用（#207）。
    """
    for h in heads:
        for n in names:
            if h == n or h.startswith(n):
                return True
    return False


def closing_side_effects(body: str, visible: str) -> list[str]:
    """查「合并这个动作会不会真的关掉 issue」，而不只是「正文有没有写关键词」。

    两层规则不同，不能合并：
    - 意图层用 `visible`（围栏 / 注释 / 行内代码都剔除过）判断本 PR 声明要关谁；
    - 副作用层扫**原始全文**，因为这些被剔除的位置 GitHub 照样解析。

    报错给出行号，让提交者能直接定位到那句举例。
    """
    problems: list[str] = []
    intended = {m.group(1) for m in GITHUB_CLOSING.finditer(visible)}
    refs = {m.group(1) for m in REF_ONLY.finditer(visible)}

    if intended and refs:
        problems.append(
            "关闭方式冲突：可见正文里同时出现 closing 关键词与 `Refs`，"
            "按 docs/contributing/PR_GUIDE.md「关闭方式」二选一（行为类只该用 `Refs`）"
        )
    if len(intended) > 1:
        problems.append(
            "多目标关闭：可见正文里出现多个不同的 closing issue 号，"
            "一 issue 一 PR，多出来的那个该换成 `Refs`"
        )

    for lineno, line in enumerate(normalize_eol(body).split("\n"), 1):
        for m in GITHUB_CLOSING.finditer(line):
            n = m.group(1)
            if n in intended:
                continue
            problems.append(
                f"第 {lineno} 行：`{m.group(0)}` 会真的关掉 issue #{n} —— "
                "GitHub 不区分 prose / 代码围栏 / 行内代码。举例请写 `Closes #NN` 占位，"
                "或把关键词与编号拆开写成 `` `Closes` #N ``；"
                "若确为本 PR 要关的 issue，就让它单独出现在正文中"
            )
    return problems


def check_body(body: str, files: list[str]) -> list[str]:
    problems: list[str] = []
    visible = strip_indented(strip_markup(body))
    heads = headings(body)

    for label, names in REQUIRED:
        if not has_section(heads, names):
            problems.append(f"缺必填段：`## {label}`")

    if not CLOSING.search(visible):
        problems.append(
            "缺关闭关键词：需要 `Closes #NN`（纯文档 / 纯重构）或 `Refs #NN`（行为类），"
            "见 docs/contributing/PR_GUIDE.md「关闭方式：Closes 还是 Refs」"
        )

    problems.extend(closing_side_effects(body, visible))

    hits = gate_hits(files)
    if hits and not has_section(heads, (GATE_SECTION,)):
        problems.append(
            f"diff 命中门禁链（{len(hits)} 个文件：{', '.join(hits[:3])}"
            f"{' 等' if len(hits) > 3 else ''}）但正文缺 `## {GATE_SECTION}` 段"
        )

    return problems


# ---------------------------------------------------------------- 取上下文


def from_gh(pr: int) -> tuple[str, list[str]]:
    """取 PR 正文与改动文件；任何环境类失败都映射成退出 2。

    以前坏 JSON 走裸 `json.loads`，抛 `JSONDecodeError` → 退出 1，与 `check_pr_body.py`
    的退出 2 分叉；同一故障在 CI 里被记成「正文不合格」（#204）。
    """
    try:
        data = gh_json(["pr", "view", str(pr), "--json", "body,files"])
    except EnvProblem as exc:
        print(f"[红] {exc}", file=sys.stderr)
        raise SystemExit(ENV_FAIL) from exc
    if not isinstance(data, dict):
        print(f"[红] gh 返回的不是对象：{type(data).__name__}", file=sys.stderr)
        raise SystemExit(ENV_FAIL)
    files = [f.get("path", "") for f in data.get("files") or [] if f.get("path")]
    return data.get("body") or "", files


def from_event() -> tuple[str, list[str]] | None:
    """CI 里从 GitHub 的事件载荷取 PR 正文与改动文件。

    没有 PR 上下文（非 pull_request 事件）返回 None → 上层 SKIP。
    有 PR 上下文但 base/head 缺失、或 `git diff` 跑不通 → 抛 `EnvProblem` → 上层红。
    """
    path = os.environ.get("GITHUB_EVENT_PATH")
    if not path or not Path(path).is_file():
        return None
    try:
        event = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as e:
        raise EnvProblem(f"读不到事件载荷 {path}：{e}") from e
    pr = event.get("pull_request")
    if not isinstance(pr, dict):
        return None

    body = pr.get("body") or ""
    base = (pr.get("base") or {}).get("sha")
    head = (pr.get("head") or {}).get("sha")
    if not (base and head):
        raise EnvProblem(
            "事件载荷里缺 pull_request.base.sha / head.sha，取不到改动文件；"
            "此时无法判断是否命中门禁链，不能报绿"
        )

    proc = run(["git", "diff", "--name-only", f"{base}...{head}"])
    if proc.returncode != 0:
        raise EnvProblem(
            f"git diff {base}...{head} 失败（{proc.returncode}）："
            f"{(proc.stderr or proc.stdout).strip()[:200]}"
        )
    files = [x.strip() for x in proc.stdout.splitlines() if x.strip()]
    return body, files


# ---------------------------------------------------------------- 自检

# 常量钉住四条用例，不依赖磁盘上的样本文件 —— 样本一进仓库就会腐烂。
# 注意：每条样本只带**一个**缺陷。`SAMPLE_MISSING` 特意加了 `Closes`，
# 否则它会因为「缺关闭关键词」也变红 —— 那样即使断言的实现被改坏，
# 用例照样红，等于测不出东西（变异验证时真踩到了这一点）。
SAMPLE_MISSING = """## 问题

缺 `## 测试` 与 `## 影响面`，关闭关键词是齐的，红只能来自缺段。

## 改动

样本用，证明缺段必红。

Closes #3
"""

SAMPLE_FULL = """## 问题

四段齐全、有 `Closes`，且未命中门禁链。

## 改动

`docs/contributing/REVIEW_DISPATCH.md`
- 样本用。

## 测试

```
[绿] 全部可达
```

## 影响面

仅文档。

Closes #3
"""

SAMPLE_GATE = """## 问题

四段齐全 + 门禁改动段，命中门禁链时应转绿。

## 改动

`scripts/gates.json`
- 样本用。

## 测试

```
[绿] 清单一致
```

## 门禁改动

`scripts/gates.json`
- 改前：support 为空；改后：一条。未打补丁 base 上不存在该条目。

## 影响面

仅清单。

Refs #3
"""


def self_test() -> int:
    # 围栏代码块里的标题 / 关键词不算数：否则贴一段模板就能满足检查。
    # 下面五种写法在 GitHub 上都会被渲染成代码或不可见内容，都必须不算数。
    fenced_gate = SAMPLE_FULL.replace(
        "## 影响面", "```markdown\n## 门禁改动\n\n`scripts/gates.json`\n```\n\n## 影响面"
    )
    fenced_closing = SAMPLE_FULL.replace("Closes #3", "```\nCloses #3\n```")
    indented_fence = SAMPLE_FULL.replace(
        "## 影响面", "  ```markdown\n## 门禁改动\n  ```\n\n## 影响面"
    )
    # 「开 3 闭 4」这条必须用**期望绿**来测：如果写成期望红，闭围栏识别失败时
    # 围栏会一路吃到文末、把 `## 影响面` 也吞掉，于是「缺影响面」的红和「门禁段被藏起来」
    # 的红长得一样，用例就失去了判别力（变异验证时真踩到了）。所以样本里在围栏
    # **之后**再放一段真的 `## 门禁改动`，只有「闭围栏被正确识别」才会绿。
    long_close_fence = SAMPLE_FULL.replace(
        "## 影响面",
        "```markdown\n## 门禁改动\n````\n\n## 门禁改动\n\n真的这一段。\n\n## 影响面",
    )
    html_comment = SAMPLE_FULL.replace("## 影响面", "<!--\n## 门禁改动\n-->\n\n## 影响面")
    unclosed_fence = SAMPLE_FULL + "```\n## 门禁改动\n"
    # 必填段标题只在围栏里出现：页面上是代码，不算段。这条钉住「标题只从剥离后的正文取一次」
    # （#207）；若把 `headings()` 的输入换成未剥离的正文，它会从红翻绿。
    fenced_required = SAMPLE_FULL.replace(
        "## 影响面", "```markdown\n## 影响面\n```", 1
    )
    # 同一必填段标题围栏内外并存：围栏里那份不算、围栏外那份算，结论必须是绿。
    fenced_and_real_required = SAMPLE_FULL.replace(
        "## 影响面", "```markdown\n## 影响面\n```\n\n## 影响面", 1
    )

    # 行尾与标题边界：三条输入路径（--body-file / --pr / 事件载荷）拿到的换行不一样，
    # 结论必须一样。下面四条都来自真实误判（CI 在 CRLF 正文上整段吞掉）。
    # 注释与缩进代码块：未闭合的 `<!--` 会让整份正文在页面上不可见，
    # 而 4 空格缩进的 `Closes` 在页面上是代码不是关键词。
    unclosed_comment = "<!-- 忘写结尾的注释\n" + SAMPLE_GATE
    comment_then_heading = SAMPLE_FULL.replace(
        "## 影响面", "<!-- x -->## 门禁改动\n\n## 影响面"
    )
    indented_closing = SAMPLE_FULL.replace("Closes #3", "    Closes #3")
    trailing_comment = SAMPLE_FULL.replace("Closes #3", "Closes #3 <!-- 备注 -->")
    inline_code_comment = SAMPLE_FULL.replace(
        "## 影响面", "写法是 `<!--` 但不闭合，这是字面量\n\n## 影响面"
    )
    # 同一行第二个未闭合注释：第 6 轮评审的阻塞项。
    second_comment = "<!-- x --> <!--\n" + SAMPLE_GATE
    two_closed_comments = "<!-- a --> <!-- b -->\n" + SAMPLE_GATE
    # 过严面：空行后的缩进代码块里写 `<!--`，页面上是代码不是注释。
    indented_comment = SAMPLE_FULL.replace(
        "## 影响面", "示例代码：\n\n    <!-- 这是代码\n\n## 影响面"
    )
    # 容器里的围栏：围栏前面多一个 `> ` 或 `- `，原来的行首正则就匹配不上。
    quote_fence_closing = SAMPLE_FULL.replace(
        "Closes #3", "> 模板：\n>\n> ```\n> Closes #3\n> ```"
    )
    list_fence_closing = SAMPLE_FULL.replace(
        "Closes #3", "示例：\n\n- ```\n  Closes #3\n  ```"
    )
    list_fence_gate = SAMPLE_FULL.replace(
        "## 影响面", "- ```\n  ## 门禁改动\n  ```\n\n## 影响面"
    )
    # 过严面：合法正文把命令写成列表项里的围栏，不能被吞掉。
    legal_list_fence = SAMPLE_FULL.replace(
        "```\n[绿] 全部可达\n```",
        "- 跑一下：\n\n  ```bash\n  python scripts/run_gates.py ci\n  ```\n  [绿] 全部可达",
    )

    # 关单副作用（issue #5）：门禁把围栏 / 行内代码里的关键词剔除后再判意图，
    # 但 GitHub 的自动关闭解析器**不剔除**。PR #4 就在行内代码里举了个例子，
    # merge 后 3 秒把 issue 关了。样本里的编号用字符串拼接，避免本文件自己
    # 出现裸的具体编号（贴进 PR 正文的输出会再次被本门禁扫）。
    concrete = "Closes #" + "7"
    colon_word = "Closes: #" + "7"
    fix_word = "Fixes #" + "7"
    refs_word = "Refs #" + "7"
    side_effect_inline = SAMPLE_GATE + "\n举例：`" + concrete + "` 只是说明。\n"
    side_effect_colon = SAMPLE_GATE + "\n举例：`" + colon_word + "` 只是说明。\n"
    side_effect_placeholder = SAMPLE_GATE + "\n举例：`Closes #NN` 只是说明。\n"
    side_effect_fix = SAMPLE_GATE + "\n举例：`" + fix_word + "` 只是说明。\n"
    side_effect_fence = SAMPLE_GATE + "\n```\n" + concrete + "\n```\n"
    intent_conflict = SAMPLE_FULL + "\n" + refs_word + "\n"

    cases = [
        ("缺段必红", SAMPLE_MISSING, [], 1),
        ("段齐必绿", SAMPLE_FULL, [], 0),
        ("命中门禁链但缺 `## 门禁改动` 必红", SAMPLE_FULL, ["scripts/gates.json"], 1),
        ("命中门禁链且写了 `## 门禁改动` 必绿", SAMPLE_GATE, ["scripts/gates.json"], 0),
        ("只改文档不触发门禁链要求", SAMPLE_FULL, ["docs/contributing/PR_GUIDE.md"], 0),
        ("围栏里的 `## 门禁改动` 不算数", fenced_gate, ["scripts/gates.json"], 1),
        ("必填段标题只在围栏里出现时必须红", fenced_required, [], 1),
        ("同一必填段标题围栏内外并存时算绿", fenced_and_real_required, [], 0),
        ("围栏里的 `Closes` 不算数", fenced_closing, [], 1),
        ("缩进 2 空格的围栏也算围栏", indented_fence, ["scripts/gates.json"], 1),
        ("开 3 个闭 4 个算闭合：围栏内那段不算、围栏后真的有段则绿", long_close_fence, ["scripts/gates.json"], 0),
        ("HTML 注释里的 `## 门禁改动` 不算数", html_comment, ["scripts/gates.json"], 1),
        ("未闭合的围栏吃到文末", unclosed_fence, ["scripts/gates.json"], 1),
        # CRLF：同一份正文换个行尾必须同一个结论。`gh pr view --json body` 与 CI 事件
        # 载荷都会把 `\r\n` 原样带回来，而 `--body-file` 的 universal-newline 会翻成 LF。
        ("CRLF 正文段齐必绿", SAMPLE_GATE.replace("\n", "\r\n"), ["scripts/gates.json"], 0),
        ("CRLF 正文里围栏内的 `## 门禁改动` 仍不算数",
         fenced_gate.replace("\n", "\r\n"), ["scripts/gates.json"], 1),
        ("CRLF 正文里围栏内的 `Closes` 仍不算数",
         fenced_closing.replace("\n", "\r\n"), [], 1),
        # 同样按**期望绿**断言：CRLF 下若不归一化，闭围栏的 `[ \t]*$` 认不出行尾的 `\r`，
        # 围栏会一路吃到文末，把后面的真段一起吞掉 —— 那样「期望红」的用例也会红，
        # 分辨不出是行尾没归一化还是内容确实被藏起来。
        ("CRLF 正文里开 3 闭 4 也算闭合", long_close_fence.replace("\n", "\r\n"), ["scripts/gates.json"], 0),
        # 标题边界：缩进 0–3 空格的 `##` 在 GitHub 上是标题；全角空格分隔的不是。
        ("缩进 2 空格的 `##` 仍算标题", SAMPLE_FULL.replace("## 影响面", "  ## 影响面"), [], 0),
        ("`##` 后接全角空格不算标题",
         SAMPLE_FULL.replace("## 影响面", "##\u3000影响面"), [], 1),
        ("未闭合的 `<!--` 让整份正文失效", unclosed_comment, ["scripts/gates.json"], 1),
        ("注释同行闭合后、`##` 紧跟其后不算标题", comment_then_heading, ["scripts/gates.json"], 1),
        ("4 空格缩进代码块里的 `Closes` 不算数", indented_closing, [], 1),
        ("行尾注释不影响真正的 `Closes`", trailing_comment, [], 0),
        ("行内代码里的 `<!--` 不算注释起点", inline_code_comment, [], 0),
        ("同一行第二个 `<!--` 未闭合也吃掉后文", second_comment, ["scripts/gates.json"], 1),
        ("同一行两个都闭合则不影响后面", two_closed_comments, ["scripts/gates.json"], 0),
        ("空行后缩进代码块里的 `<!--` 不是注释", indented_comment, [], 0),
        ("引用块里的围栏内的 `Closes` 不算数", quote_fence_closing, [], 1),
        ("列表项里的围栏内的 `Closes` 不算数", list_fence_closing, [], 1),
        ("列表项里的围栏内的 `## 门禁改动` 不算数", list_fence_gate, ["scripts/gates.json"], 1),
        ("合法正文把命令写在列表项围栏里不该被吞", legal_list_fence, [], 0),
        # 关单副作用：期望红的用例在「只查净化后文本」的实现下都应为绿（即用例先红）。
        ("行内代码里举例的具体编号 closing 会误关单", side_effect_inline, [], 1),
        ("冒号写法的具体编号 closing 也会误关单", side_effect_colon, [], 1),
        ("举例用 #NN 占位不会误关单", side_effect_placeholder, [], 0),
        ("纯 closing 关键词无副作用", SAMPLE_FULL, [], 0),
        ("Fixes 这类关闭动词也会误关单", side_effect_fix, [], 1),
        ("围栏里的具体编号 closing 也会误关单", side_effect_fence, [], 1),
        ("可见文本里混用 Closes 与 Refs 是意图冲突", intent_conflict, [], 1),
    ]
    checks: list[tuple[str, bool, str]] = []
    for name, body, files, expected in cases:
        got = 1 if check_body(body, files) else 0
        detail = "\n        ".join(check_body(body, files))
        checks.append((f"{name}（期望退出 {expected}，实际 {got}）", got == expected, detail))

    # 事件载荷用例：这条钉住「取不到改动文件时不许报绿」。
    # 原来的实现是 files 留空 + 照常输出绿，同一份正文会因环境抖动从红变绿。
    with tempfile.TemporaryDirectory() as tmp:
        ev = Path(tmp) / "event.json"

        def probe(payload: dict) -> str:
            ev.write_text(json.dumps(payload), encoding="utf-8")
            saved = os.environ.get("GITHUB_EVENT_PATH")
            os.environ["GITHUB_EVENT_PATH"] = str(ev)
            try:
                return "ok" if from_event() is not None else "none"
            except EnvProblem:
                return "red"
            finally:
                if saved is None:
                    os.environ.pop("GITHUB_EVENT_PATH", None)
                else:
                    os.environ["GITHUB_EVENT_PATH"] = saved

        got = probe({"pull_request": {"body": SAMPLE_FULL}})
        checks.append(
            ("事件载荷缺 base/head 时必须红（不许降级成绿）", got == "red", f"实际 {got}")
        )

        got = probe({"ref": "refs/heads/main"})
        checks.append(("非 PR 事件仍是 SKIP（返回 None）", got == "none", f"实际 {got}"))

    # 环境问题必须退 2：`gh` 正常退出但输出不是 JSON。迁移前这里裸 `json.loads`，
    # 抛 `JSONDecodeError` 以退出码 1 结束，与 `check_pr_body.py` 的退出 2 分叉（#204）。
    import _common

    original_run = _common.subprocess.run

    def fake_run(argv, **kwargs):
        return subprocess.CompletedProcess(argv, 0, "not-json", "")

    try:
        _common.subprocess.run = fake_run
        try:
            from_gh(1)
        except SystemExit as exc:
            env_exit = exc.code
        except Exception as exc:  # noqa: BLE001 - 用例要抓「抛错了类型」
            env_exit = f"异常 {type(exc).__name__}"
        else:
            env_exit = "未退出"
    finally:
        _common.subprocess.run = original_run
    checks.append(
        ("gh 输出坏 JSON 时退 2（不再退 1）", env_exit == 2, f"实际 {env_exit}")
    )

    bad = 0
    for name, ok, detail in checks:
        flag = "PASS" if ok else "FAIL"
        print(f"[{flag}] {name}")
        if not ok:
            bad += 1
            if detail:
                print(f"        {detail}")

    if bad:
        print(f"== 自检红 {bad} 项 ==", file=sys.stderr)
        return 2
    print(f"== 自检全绿：{len(checks)} 项 ==")
    return 0


# ---------------------------------------------------------------- 入口


def main() -> int:
    ap = argparse.ArgumentParser(description="校验 PR 正文的必填分段（support 档）")
    ap.add_argument("--pr", type=int, help="PR 编号（走 gh）")
    ap.add_argument("--body-file", help="本地自查：直接给正文文件")
    ap.add_argument("--files", nargs="*", default=None, help="本地自查：改动文件列表")
    ap.add_argument("--self-test", action="store_true", help="跑内置自检，不需要 gh / 网络")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    if args.body_file:
        p = Path(args.body_file)
        if not p.is_file():
            print(f"[红] 找不到 {p}", file=sys.stderr)
            return 2
        if args.files is None:
            # 不给 --files 就等于「没有改动文件」，门禁链判定会静默跳过、
            # 整份正文直接绿。必须让调用方显式声明「无改动文件」（`--files` 后不跟参数）。
            print(
                "[红] --body-file 模式必须显式给 --files（无改动文件就写 `--files` 后不跟参数）；"
                "否则「命中门禁链才要 `## 门禁改动`」会静默跳过",
                file=sys.stderr,
            )
            return 2
        body = p.read_text(encoding="utf-8", errors="replace")
        files = list(args.files)
        source = f"--body-file {args.body_file}"
    elif args.pr:
        body, files = from_gh(args.pr)
        source = f"PR #{args.pr}"
    else:
        try:
            ctx = from_event()
        except EnvProblem as e:
            print(f"[红] {e}", file=sys.stderr)
            print("     取不到上下文时不能报绿——否则同一份正文会因环境抖动从红变绿。", file=sys.stderr)
            return 2
        if ctx is None:
            print("[SKIP] 没有 PR 上下文（非 pull_request 事件且未给 --pr / --body-file）。")
            return 0
        body, files = ctx
        source = "pull_request 事件载荷"

    problems = check_body(body, files)
    if problems:
        for p in problems:
            print(f"[红] {p}", file=sys.stderr)
        print(f"     正文结构见 docs/contributing/PR_GUIDE.md「正文分段」；{source}", file=sys.stderr)
        return 1

    hits = gate_hits(files)
    gate_note = f"，门禁链 {len(hits)} 个文件已声明" if hits else ""
    print(f"[绿] {source}：必填段齐全、关闭关键词存在{gate_note}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
