#!/usr/bin/env python3
"""文档里的相对链接必须能落到磁盘上。

这套协作里文档是「项目记忆」，链接断了等于记忆断了：新人按 AGENTS.md 的索引点进去
拿到 404，就退回「问人」而不是「读文档」。所以这条盯住：

- 所有被 git 跟踪的 `.md` 里的相对链接（行内式、引用式、HTML 属性、图片）目标必须存在；
- 带 `#frag` 的锚点，目标 md 里必须真有那个标题；纯页内锚点比对本文件标题；
- `AGENTS.md` §6 文档索引表里的反引号路径必须存在（反向校验）；
- 链接路径按该 md 文件所在目录解析（不是仓库根）。

跳过：`http(s)://`、`mailto:`、`tel:`、以及围栏代码块里的内容（那是示例不是链接）。
围栏用逐行状态机剥，不用正则：开闭字符、围栏长度、容器缩进、未闭合都要按 CommonMark 来。
"""
from __future__ import annotations

import argparse
import posixpath
import re
import sys
from pathlib import Path

from _common import ENV_FAIL, EnvProblem, git_ls_files, strip_fences

ROOT = Path(__file__).resolve().parent.parent

LINK = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
REF_DEF = re.compile(r"^ {0,3}\[([^\]^][^\]]*)\]:\s*(\S+)", re.M)
REF_USE = re.compile(r"\[([^\]]*)\]\[([^\]]*)\]")
SHORT_REF = re.compile(r"(?<!!)\[([^\]\[]+)\](?!\s*[\[(])")
HTML_TAG = re.compile(r"<(?:a|img)\b[^>]*>", re.I)
HTML_ATTR = re.compile(r"(?:href|src)\s*=\s*[\"']([^\"']+)[\"']", re.I)
HEADING = re.compile(r"^ {0,3}#{1,6}[ \t]+(.+?)[ \t]*$", re.M)
INDEX_SECTION = re.compile(
    r"^##[ \t]+6\.[ \t]*文档索引[ \t]*$([\s\S]*?)(?=^##[ \t]|\Z)", re.M
)
INDEX_ROW = re.compile(r"^\|\s*`([^`]+)`\s*\|", re.M)
SKIP_PREFIX = ("http://", "https://", "mailto:", "tel:")
SLUG_DROP = re.compile(r"[`*_\[\]()#.,:;!?/\\'\"|<>《》，。：；！？、（）]")


def tracked_markdown() -> list[Path]:
    """被跟踪的 `.md`；`-z` 保证中文文件名不被 `core.quotePath` 转义成假路径（#204）。"""
    return [ROOT / name for name in git_ls_files("*.md")]


def _resolve(md_rel: str, target: str) -> str:
    """把链接目标归一化成仓库相对路径；纯页内锚点返回空串。"""
    path_part = target.split("#", 1)[0]
    if not path_part:
        return ""
    return posixpath.normpath(posixpath.join(posixpath.dirname(md_rel), path_part))


def slugify(title: str) -> str:
    """GitHub 锚点 slug 的本项目子集：小写、去标点、空白转连字符，中文原样保留。"""
    return re.sub(r"\s+", "-", SLUG_DROP.sub("", title.strip().lower()))


def slugs(text: str) -> set[str]:
    return {slugify(m.group(1)) for m in HEADING.finditer(strip_fences(text))}


def _targets(clean: str) -> list[str]:
    """收集一份正文里所有待查的链接目标，去重且保持顺序。"""
    defs = {k.lower(): v for k, v in REF_DEF.findall(clean)}
    found: list[str] = list(LINK.findall(clean))
    for tag in HTML_TAG.findall(clean):
        found.extend(HTML_ATTR.findall(tag))
    for label, ref in REF_USE.findall(clean):
        key = (ref or label).lower()
        if key in defs:
            found.append(defs[key])
    for label in SHORT_REF.findall(clean):
        key = label.lower()
        if key in defs:
            found.append(defs[key])
    found.extend(defs.values())  # 定义本身也是一条待查路径，没被引用也要查
    return list(dict.fromkeys(found))


def scan_text(md_rel: str, text: str, exists, read_text) -> tuple[list[str], int]:
    """扫一个 md 的正文，返回 (问题列表, 检查过的链接数)。

    exists(rel) / read_text(rel) 由调用方注入：真实运行时读磁盘，--self-test 用内存 dict。
    """
    problems: list[str] = []
    checked = 0
    clean = strip_fences(text)
    own_slugs = slugs(text)

    for raw in _targets(clean):
        target = raw.strip()
        if target.startswith("<") and target.endswith(">"):
            target = target[1:-1]
        if not target or target.startswith(SKIP_PREFIX):
            continue
        checked += 1
        path_part, _, frag = target.partition("#")

        if path_part:
            rel = _resolve(md_rel, target)
            if not exists(rel):
                problems.append(f"{md_rel} → 断链：{target}")
                continue
            if frag and slugify(frag) not in slugs(read_text(rel)):
                problems.append(f"{md_rel} → 锚点不存在：{target}")
        elif frag and slugify(frag) not in own_slugs:
            problems.append(f"{md_rel} → 页内锚点不存在：{target}")

    return problems, checked


def check_index(text: str, exists) -> list[str]:
    """校验 AGENTS.md §6 文档索引表第一列的反引号路径。"""
    problems: list[str] = []
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    m = INDEX_SECTION.search(normalized)
    if m is None:
        # 找不到段落就静默返回空 = 校验凭空消失，必须红（与 from_event() 取不全上下文同理）。
        return ["AGENTS.md → 找不到 `## 6. 文档索引` 段，索引表反向校验无法执行"]
    section = m.group(1)
    rows = INDEX_ROW.findall(section)
    if not rows:
        # 段落找到了、却一行可解析的都没有 ＝ 表格首列没按约定写反引号路径，
        # 校验同样会凭空消失，必须红（issue #8）。不能静默返回空。
        return ["AGENTS.md §6 文档索引 → 表格首列格式不符合约定"
                "（未找到反引号路径行），索引表反向校验无法执行"]
    for raw in rows:
        p = raw.strip()
        if not p or p.startswith(SKIP_PREFIX) or p.startswith("#"):
            continue
        if not exists(posixpath.normpath(p)):
            problems.append(f"AGENTS.md §6 文档索引 → 路径不存在：{p}")
    return problems


def disk_exists(rel: str) -> bool:
    return (ROOT / rel).exists()


def disk_read(rel: str) -> str:
    p = ROOT / rel
    if not p.is_file():
        return ""
    return p.read_text(encoding="utf-8", errors="replace")


def self_test() -> int:
    """纯内存用例：先写能红的用例，再改实现。"""
    index_head = "## 6. 文档索引\n\n| 文件 | 内容 |\n|---|---|\n"
    index_tail = "\n## 7. 工作习惯\n"
    cases: list[tuple[str, str, str, dict[str, str], bool, bool]] = [
        ("引用式断链必红", "docs/contributing/PR_GUIDE.md",
         "[x][ghost]\n\n[ghost]: ./nope.md\n", {}, True, False),
        ("引用式链接修好必绿", "docs/contributing/PR_GUIDE.md",
         "[x][ghost]\n\n[ghost]: ./nope.md\n", {"docs/contributing/nope.md": "# ok"}, False, False),
        ("HTML a 断链必红", "docs/contributing/PR_GUIDE.md",
         '<a href="./nope2.md">x</a>\n', {}, True, False),
        ("HTML a 修好必绿", "docs/contributing/PR_GUIDE.md",
         '<a href="./nope2.md">x</a>\n', {"docs/contributing/nope2.md": "# ok"}, False, False),
        ("HTML img 断链必红", "docs/contributing/PR_GUIDE.md",
         '<img src="./nope3.png" alt="x">\n', {}, True, False),
        ("锚点标题不存在必红", "docs/contributing/PR_GUIDE.md",
         "[x](ISSUE_GUIDE.md#no-such)\n",
         {"docs/contributing/ISSUE_GUIDE.md": "# h\n\n## 真标题\n"}, True, False),
        ("锚点标题存在必绿", "docs/contributing/PR_GUIDE.md",
         "[x](ISSUE_GUIDE.md#真标题)\n",
         {"docs/contributing/ISSUE_GUIDE.md": "# h\n\n## 真标题\n"}, False, False),
        ("纯页内锚点不存在必红", "docs/contributing/PR_GUIDE.md",
         "[x](#no-such)\n", {"docs/contributing/PR_GUIDE.md": "# h\n"}, True, False),
        ("纯页内锚点存在必绿", "docs/contributing/PR_GUIDE.md",
         "## 真标题\n\n[x](#真标题)\n", {"docs/contributing/PR_GUIDE.md": "## 真标题\n"}, False, False),
        ("索引表假路径必红", "AGENTS.md",
         index_head + "| `docs/不存在.md` | x |\n" + index_tail, {}, True, True),
        ("索引表合法路径必绿", "AGENTS.md",
         index_head + "| `docs/contributing/PR_GUIDE.md` | x |\n" + index_tail,
         {"docs/contributing/PR_GUIDE.md": "# h"}, False, True),
        ("缺 §6 段落必红（不许静默跳过）", "AGENTS.md",
         "## 5. 别的东西\n\n| `docs/不存在.md` | x |\n", {}, True, True),
        # 防 issue #8 复发：§6 段落在、但表格首列不用反引号时 INDEX_ROW 命中 0 行，
        # 旧实现直接返回空列表 → 门禁静默绿。这里路径本身存在，只有「格式不合约定」能让它红。
        ("§6 段在但首列无反引号必红（防 #8 静默失效）", "AGENTS.md",
         index_head + "| docs/contributing/PR_GUIDE.md | x |\n" + index_tail,
         {"docs/contributing/PR_GUIDE.md": "# h"}, True, True),
        ("混合围栏不误报", "docs/contributing/PR_GUIDE.md",
         "```\n[x](./nope4.md)\n~~~\n", {}, False, False),
        ("未闭合围栏不误报", "docs/contributing/PR_GUIDE.md",
         "```\n[x](./nope5.md)\n", {}, False, False),
        ("闭合围栏后的真断链仍红（不回归）", "docs/contributing/PR_GUIDE.md",
         "```\n[x](./shown.md)\n```\n\n[y](./missing.md)\n", {}, True, False),
        ("脚注定义不算链接", "docs/contributing/PR_GUIDE.md",
         "[^1]: see [#99](https://example.com/99)\n", {}, False, False),
        ("外链与 mailto 跳过", "docs/contributing/PR_GUIDE.md",
         "[a](https://x/y) [b](mailto:a@b.c)\n", {}, False, False),
        ("行内式断链仍红（不回归）", "docs/contributing/PR_GUIDE.md",
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
    try:
        md_files = tracked_markdown()
    except EnvProblem as exc:
        print(f"[红] {exc}", file=sys.stderr)
        return ENV_FAIL

    for md in md_files:
        rel = md.relative_to(ROOT).as_posix()
        if not md.is_file():
            # 被跟踪的 md 却不在磁盘上，是索引与工作区脱节；静默跳过会打印假绿（#203）。
            problems.append(f"被跟踪 md 读不到：{rel}")
            continue
        p, n = scan_text(rel, md.read_text(encoding="utf-8", errors="replace"),
                         disk_exists, disk_read)
        problems.extend(p)
        checked += n

    agents = ROOT / "AGENTS.md"
    if agents.is_file():
        problems.extend(check_index(agents.read_text(encoding="utf-8", errors="replace"),
                                    disk_exists))
    else:
        problems.append("AGENTS.md 不存在，索引表反向校验无法执行")

    if problems:
        for p in problems:
            print(f"[红] {p}", file=sys.stderr)
        return 1

    # checked 是「每个 md 内去重后的目标数」合计，不是链接出现次数。旧版按出现次数
    # 计数（19），同一目标在多处出现会重复算；这里按不重复目标算，避免同一断链刷屏。
    print(f"[绿] {len(md_files)} 个 md、{checked} 条不重复的相对链接全部可达")
    return 0


def _cli() -> argparse.Namespace:
    """声明参数，让元门禁的 `--help` 自证层覆盖本脚本（#203）。

    不走 argparse 时，拼错参数会被静默忽略、门禁照样跑绿；这里只声明实际用到的开关，
    清单 cmd 里出现未声明参数时元门禁会直接报红。
    """
    ap = argparse.ArgumentParser(description="校验被跟踪 md 的相对链接")
    ap.add_argument("--self-test", action="store_true", help="跑内置回归用例")
    return ap.parse_args()


if __name__ == "__main__":
    args = _cli()
    sys.exit(self_test() if args.self_test else main())
