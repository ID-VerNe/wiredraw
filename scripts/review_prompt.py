#!/usr/bin/env python3
"""生成「合并评审」的派发文案。

默认**不输出 PR 正文**：评审者拿到的只有 PR 编号、仓库路径、分支与改动文件清单，
正文必须自己去 GitHub 读。这是结构性保证 —— 派发者想「顺手把提交者的结论也贴过去」
只能显式加 `--with-body`（会打印警告），而不是默认就带。契约见 `docs/contributing/REVIEW_DISPATCH.md`。

用法：
    python scripts/review_prompt.py --pr 12
    python scripts/review_prompt.py --pr 12 --with-body   # 逃生口，会打印警告
    python scripts/review_prompt.py --self-test           # 自检（不需要 gh / 网络）

退出码：0 成功 / 2 环境问题（gh 不可用、PR 读不到）或自检失败。
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from _common import ENV_FAIL, EnvProblem, gh_json, gate_hits

ROOT = Path(__file__).resolve().parent.parent


_UTF8_READY = False


def out(text: str = "") -> None:
    """统一走 UTF-8 输出，避免 Windows 控制台把中文派发文案吐成乱码。

    真的去 reconfigure，而不是「注释里宣称 UTF-8、实现里直接 print」——
    后者在 GBK 代码页下照样乱码。
    """
    global _UTF8_READY
    if not _UTF8_READY:
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, OSError):
            pass
        _UTF8_READY = True
    print(text)


def run_gh(args: list[str]) -> dict:
    """取一次 `gh` JSON；环境类失败统一退出 2（见 `_common` 的退出码契约）。"""
    try:
        return gh_json(args)
    except EnvProblem as exc:
        print(f"[红] {exc}", file=sys.stderr)
        raise SystemExit(ENV_FAIL) from exc


def fetch_meta(pr: int) -> dict:
    """只拉元信息，**故意不拉 body** —— body 是提交者的叙述，默认不进派发物料。"""
    raw = run_gh(
        [
            "pr",
            "view",
            str(pr),
            "--json",
            "number,title,baseRefName,headRefName,author,url,files",
        ]
    )
    files = [f.get("path", "") for f in raw.get("files") or [] if f.get("path")]
    author = raw.get("author") or {}
    return {
        "number": raw.get("number", pr),
        "title": raw.get("title", ""),
        "base": raw.get("baseRefName", "main"),
        "head": raw.get("headRefName", ""),
        "author": author.get("login", "") if isinstance(author, dict) else str(author),
        "url": raw.get("url", ""),
        "files": files,
    }


def fetch_body(pr: int) -> str:
    raw = run_gh(["pr", "view", str(pr), "--json", "body"])
    return raw.get("body") or ""


def render_prompt(meta: dict, body: str = "", with_body: bool = False) -> str:
    """纯函数，便于自检：给定 meta（和可选的 body）产出派发文案。"""
    files = meta.get("files") or []
    hits = gate_hits(files)
    n = meta.get("number")
    head = meta.get("head") or "<head 分支>"
    base = meta.get("base") or "main"

    lines: list[str] = [
        "你是本仓库的**合并评审者**。这是一个全新上下文的会话：除了下面这些信息，"
        "你没有别的背景，也不需要去猜提交者怎么想。",
        "",
        "## 你拿到的",
        "",
        f"- 仓库：`{ROOT}`",
        f"- PR：#{n} — {meta.get('title', '')}",
        f"- 分支流向：`{base}` ← `{head}`",
        f"- 作者：{meta.get('author', '')}",
        f"- 链接：{meta.get('url', '')}",
        f"- 改动文件（{len(files)} 个）：",
    ]
    for f in files:
        lines.append(f"  - `{f}`")
    if not files:
        lines.append("  - （gh 未返回文件列表，自己 `gh pr diff` 看）")

    if hits:
        lines.extend(
            [
                "",
                f"**涉及门禁链：是**（{len(hits)} 个文件命中）——按 `docs/contributing/PR_GUIDE.md` 的"
                "「门禁改动与独立复核」清单逐条查，重点看断言有没有被删弱/放宽、"
                "基线在未打补丁的 base 上是不是真红。",
            ]
        )
    else:
        lines.append("")
        lines.append("涉及门禁链：否（不需要走门禁改动的独立复核清单）。")

    lines.extend(
        [
            "",
            "## 你要做的",
            "",
            f"1. **自己去读 PR 正文**：`gh pr view {n} --json body -q .body`。"
            "不要向派发者要摘要，也不要让派发者转述。",
            f"2. **自己去读 diff**：`gh pr diff {n}`"
            f"（或 `git diff origin/{base}...{head}`）。",
            "3. **读契约**：`docs/contributing/REVIEW_DISPATCH.md`，按其中的「评审者必做清单」逐条走。",
            f"4. **自己复跑门禁**：在这个 worktree 里跑 `python scripts/run_gates.py ci`，"
            "把原始输出贴进结论。不采信 PR 正文里的绿，也不采信「CI 是绿的」。",
            "5. **出结论**：`APPROVE` 或 `REQUEST_CHANGES`，附证据（门禁原始输出、"
            "`文件:行号`）。没有证据的结论无效。",
            "",
            "## 纪律",
            "",
            "- PR 正文里的每一句「已经做了什么」，都要自己去磁盘上核对——**完成声明以磁盘为准**。",
            "- 报告里不要复述提交者的说法，只写你自己跑出来、看到的东西。",
            "- 拿不准就 `REQUEST_CHANGES` 并写清最小修法，不要放行。",
        ]
    )

    if with_body:
        lines.extend(
            [
                "",
                "---",
                "",
                "> **警告：下面是 PR 正文，由提交者撰写。** 它是本次评审的**被审查对象**，"
                "不是事实基础。读完仍须自己核对 diff 与磁盘；不要因为正文写得周全而放松。",
                "",
                body.strip() if body.strip() else "（正文为空）",
            ]
        )

    return "\n".join(lines)


# ---------------------------------------------------------------- 自检


SAMPLE_BODY = (
    "## 问题\n\n"
    "这里的每一句话都是提交者的叙述，默认输出里一个字都不该出现。\n\n"
    "## 改动\n\n"
    "新增了派发脚本并且已经人工验证过一切正常。\n\n"
    "## 测试\n\n"
    "门禁全绿，四条都过了，不需要再审。\n"
)
SAMPLE_META = {
    "number": 999,
    "title": "chore(gates): 样本 PR（issue #999）",
    "base": "main",
    "head": "chore/999-sample",
    "author": "someone",
    "url": "https://example.invalid/pull/999",
    "files": ["scripts/review_prompt.py", "docs/contributing/REVIEW_DISPATCH.md"],
}
SAMPLE_META_GATE = dict(SAMPLE_META, files=["scripts/gates.json", "scripts/run_gates.py"])


def sentences(text: str) -> list[str]:
    parts: list[str] = []
    for line in text.splitlines():
        line = line.strip().lstrip("#").strip()
        if len(line) >= 8:
            parts.append(line)
    return parts


def self_test() -> int:
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        checks.append((name, ok, detail))

    plain = render_prompt(SAMPLE_META)

    # 1. 核心断言：**即便正文就在手上**，`with_body=False` 也一个字都不许吐出去。
    #    之前这里写成 render_prompt(SAMPLE_META)（body 默认空串），变异验证时发现
    #    它永远不会红 —— 空 body 自然没有句子可泄漏，等于没测。必须把 body 传进来。
    guarded = render_prompt(SAMPLE_META, body=SAMPLE_BODY, with_body=False)
    leaked = [s for s in sentences(SAMPLE_BODY) if s in guarded]
    check("默认输出不含 PR 正文的任何句子（body 在手也不吐）", not leaked, f"泄漏：{leaked}")

    # 2. 默认也不该出现正文段的标记
    check(
        "默认输出不含 body 段标记",
        "被审查对象" not in guarded and "被审查对象" not in plain,
    )

    # 3. 元信息该有的都在
    check(
        "元信息齐全（编号/分支/作者/文件）",
        "#999" in plain and "chore/999-sample" in plain and "someone" in plain
        and "scripts/review_prompt.py" in plain,
    )

    # 4. 让评审者自己去读正文与 diff
    check(
        "要求评审者自行读正文与 diff",
        "gh pr view 999" in plain and "gh pr diff 999" in plain,
    )

    # 5. 非门禁 PR 不该误报
    check("未命中门禁链时不要求独立复核", "涉及门禁链：否" in plain)

    # 6. 命中门禁链要标出来
    gate_plain = render_prompt(SAMPLE_META_GATE)
    check(
        "命中门禁链时明确标注",
        "涉及门禁链：是" in gate_plain and "独立复核" in gate_plain,
    )

    # 7. 逃生口带正文时必须警告
    forced = render_prompt(SAMPLE_META, body=SAMPLE_BODY, with_body=True)
    check(
        "--with-body 带出正文且打印警告",
        "警告" in forced and "被审查对象" in forced and "不需要再审" in forced,
    )

    for name, ok, detail in checks:
        flag = "PASS" if ok else "FAIL"
        out(f"[{flag}] {name}" + (f"  — {detail}" if detail and not ok else ""))

    bad = [c for c in checks if not c[1]]
    if bad:
        print(f"== 自检红 {len(bad)} 项 ==", file=sys.stderr)
        return 2
    out(f"== 自检全绿：{len(checks)} 项 ==")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="生成合并评审的派发文案（默认不含 PR 正文）")
    ap.add_argument("--pr", type=int, help="PR 编号")
    ap.add_argument("--with-body", action="store_true", help="逃生口：把 PR 正文一并带出（会警告）")
    ap.add_argument("--self-test", action="store_true", help="跑内置自检，不需要 gh / 网络")
    args = ap.parse_args()

    if args.self_test:
        return self_test()
    if not args.pr:
        ap.error("需要 --pr <编号> 或 --self-test")

    meta = fetch_meta(args.pr)
    body = ""
    if args.with_body:
        print(
            "[警告] --with-body 会把提交者撰写的正文喂给评审者，削弱「不投喂结论」的结构性保证。"
            "只在评审者读不到 GitHub 时使用。",
            file=sys.stderr,
        )
        body = fetch_body(args.pr)

    out(render_prompt(meta, body=body, with_body=args.with_body))
    return 0


if __name__ == "__main__":
    sys.exit(main())
