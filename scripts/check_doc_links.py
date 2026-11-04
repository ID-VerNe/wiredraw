#!/usr/bin/env python3
"""文档里的相对链接必须能落到磁盘上。

这套协作里文档是「项目记忆」，链接断了等于记忆断了：新人按 AGENTS.md 的索引点进去
拿到 404，就退回「问人」而不是「读文档」。所以这条盯住：

- 所有被 git 跟踪的 `.md` 里的相对链接（行内式、引用式、HTML 属性、图片）目标必须存在；
- 带 `#frag` 的锚点，目标 md 里必须真有那个标题；
- `AGENTS.md` §6 文档索引表里的反引号路径必须存在（反向校验）；
- 链接路径按该 md 文件所在目录解析（不是仓库根）。

跳过：`http(s)://`、`mailto:`、`tel:`、以及围栏代码块里的内容（那是示例不是链接）。
"""
from __future__ import annotations

import posixpath
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

LINK = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
FENCE = re.compile(r"^(```|~~~).*?^\1", re.S | re.M)
SKIP_PREFIX = ("http://", "https://", "mailto:", "tel:")


def tracked_markdown() -> list[Path]:
    out = subprocess.run(
        ["git", "ls-files", "*.md"],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if out.returncode != 0:
        raise SystemExit("[红] git ls-files 失败")
    return [ROOT / line for line in out.stdout.splitlines() if line.strip()]


def _resolve(md_rel: str, target: str) -> str:
    """把链接目标归一化成仓库相对路径；纯页内锚点返回空串。"""
    path_part = target.split("#", 1)[0]
    if not path_part:
        return ""
    return posixpath.normpath(posixpath.join(posixpath.dirname(md_rel), path_part))


def scan_text(md_rel: str, text: str, exists, read_text) -> tuple[list[str], int]:
    """扫一个 md 的正文，返回 (问题列表, 检查过的链接数)。

    exists(rel) / read_text(rel) 由调用方注入：真实运行时读磁盘，--self-test 用内存 dict。
    第一步（用例先行）故意保留旧行为，只把 main() 的逻辑抽成函数，用来证明新用例能红。
    """
    problems: list[str] = []
    checked = 0
    stripped = FENCE.sub("", text)
    for raw in LINK.findall(stripped):
        target = raw.strip()
        if not target or target.startswith(SKIP_PREFIX) or target.startswith("#"):
            continue
        if target.startswith("<") and target.endswith(">"):
            target = target[1:-1]
        rel = _resolve(md_rel, target)
        if not rel:
            continue
        checked += 1
        if not exists(rel):
            problems.append(f"{md_rel} → 断链：{target}")
    return problems, checked


def check_index(text: str, exists) -> list[str]:
    """校验 AGENTS.md §6 文档索引表第一列的反引号路径。第一步故意不实现。"""
    return []


def disk_exists(rel: str) -> bool:
    return (ROOT / rel).exists()


def disk_read(rel: str) -> str:
    p = ROOT / rel
    if not p.is_file():
        return ""
    return p.read_text(encoding="utf-8", errors="replace")


def self_test() -> int:
    """纯内存用例：先写能红的用例，再改实现。"""
    cases: list[tuple[str, str, str, dict[str, str], bool, bool]] = [
        ("引用式断链必红", "doc/PR_GUIDE.md",
         "[x][ghost]\n\n[ghost]: ./nope.md\n", {}, True, False),
        ("引用式链接修好必绿", "doc/PR_GUIDE.md",
         "[x][ghost]\n\n[ghost]: ./nope.md\n", {"doc/nope.md": "# ok"}, False, False),
        ("HTML a 断链必红", "doc/PR_GUIDE.md",
         '<a href="./nope2.md">x</a>\n', {}, True, False),
        ("HTML a 修好必绿", "doc/PR_GUIDE.md",
         '<a href="./nope2.md">x</a>\n', {"doc/nope2.md": "# ok"}, False, False),
        ("HTML img 断链必红", "doc/PR_GUIDE.md",
         '<img src="./nope3.png" alt="x">\n', {}, True, False),
        ("锚点标题不存在必红", "doc/PR_GUIDE.md",
         "[x](ISSUE_GUIDE.md#no-such)\n",
         {"doc/ISSUE_GUIDE.md": "# h\n\n## 真标题\n"}, True, False),
        ("锚点标题存在必绿", "doc/PR_GUIDE.md",
         "[x](ISSUE_GUIDE.md#真标题)\n",
         {"doc/ISSUE_GUIDE.md": "# h\n\n## 真标题\n"}, False, False),
        ("纯页内锚点不存在必红", "doc/PR_GUIDE.md",
         "[x](#no-such)\n", {"doc/PR_GUIDE.md": "# h\n"}, True, False),
        ("纯页内锚点存在必绿", "doc/PR_GUIDE.md",
         "## 真标题\n\n[x](#真标题)\n", {"doc/PR_GUIDE.md": "## 真标题\n"}, False, False),
        ("索引表假路径必红", "AGENTS.md",
         "| `doc/不存在.md` | x |\n", {}, True, True),
        ("索引表合法路径必绿", "AGENTS.md",
         "| `doc/PR_GUIDE.md` | x |\n", {"doc/PR_GUIDE.md": "# h"}, False, True),
        ("混合围栏不误报", "doc/PR_GUIDE.md",
         "```\n[x](./nope4.md)\n~~~\n", {}, False, False),
        ("未闭合围栏不误报", "doc/PR_GUIDE.md",
         "```\n[x](./nope5.md)\n", {}, False, False),
        ("脚注定义不算链接", "doc/PR_GUIDE.md",
         "[^1]: see [#99](https://example.com/99)\n", {}, False, False),
        ("外链与 mailto 跳过", "doc/PR_GUIDE.md",
         "[a](https://x/y) [b](mailto:a@b.c)\n", {}, False, False),
        ("行内式断链仍红（不回归）", "doc/PR_GUIDE.md",
         "[x](./missing.md)\n", {}, True, False),
    ]

    bad = 0
    for name, md_rel, text, files, expect_red, use_index in cases:
        fs = {posixpath.normpath(k): v for k, v in files.items()}
        exists = lambda rel, fs=fs: posixpath.normpath(rel) in fs
        read_text = lambda rel, fs=fs: fs.get(posixpath.normpath(rel), "")
        if use_index:
            problems = check_index(text, exists)
        else:
            problems, _ = scan_text(md_rel, text, exists, read_text)
        got_red = bool(problems)
        ok = got_red == expect_red
        if not ok:
            bad += 1
        print(f"[{'PASS' if ok else 'FAIL'}] {name}"
              f"（期望{'红' if expect_red else '绿'}，实际{'红' if got_red else '绿'}）")
        if not ok and problems:
            print("        " + "\n        ".join(problems))

    if bad:
        print(f"== 自检红 {bad} 项 ==", file=sys.stderr)
        return 2
    print(f"== 自检全绿：{len(cases)} 项 ==")
    return 0


def main() -> int:
    problems: list[str] = []
    checked = 0
    md_files = tracked_markdown()

    for md in md_files:
        if not md.is_file():
            continue
        rel = md.relative_to(ROOT).as_posix()
        p, n = scan_text(rel, md.read_text(encoding="utf-8", errors="replace"),
                         disk_exists, disk_read)
        problems.extend(p)
        checked += n

    agents = ROOT / "AGENTS.md"
    if agents.is_file():
        problems.extend(check_index(agents.read_text(encoding="utf-8", errors="replace"),
                                    disk_exists))

    if problems:
        for p in problems:
            print(f"[红] {p}", file=sys.stderr)
        return 1

    print(f"[绿] {len(md_files)} 个 md、{checked} 条相对链接全部可达")
    return 0


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        sys.exit(self_test())
    sys.exit(main())
