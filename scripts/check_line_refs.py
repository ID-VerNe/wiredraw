#!/usr/bin/env python3
"""文档物理行号门禁：被跟踪的 `.md` 不许写「文件名 + 冒号 + 数字」（issue #252）。

为什么有这条：行号是物理坐标，上方插一行就漂，而文档没有编译或测试能发现这种漂移。
规则全文在 `AGENTS.md` §2 第 10 条；`UI-SPEC.md` §10 第 8 条只给指针。

扫什么：
- `git ls-files -z` 拿到的 `*.md`（`-z` 的由来见 #204）；
- 剥掉围栏代码块后逐行匹配 `扩展名 + 冒号（半角/全角）+ 数字`。

为什么用扩展名白名单 `java / kt / kts / py / md`：
- 不用「任意扩展名 + 冒号」是为了不把 `example.com:8080`、`http://host:8080` 判成行号；
- 不用窄到只留 `java` 是为了让 md / py 的同类引用也一起收敛。

不扫什么（每条都有理由，不是偷懒）：
- 围栏代码块：与 `check_doc_links.py` 同口径，围栏里是示例；issue / PR 模板里的行号
  示例属一次性上下文，规则显式允许；
- `docs/archive/`：当时证据的冻结副本，引用对象是本地不入库的第三方仓库，改坐标等于篡改记录；
- 不带文件名的裸冒号数字：与时刻（`12:30`）、章节号（`§4.2:1`）、版本号无法区分，靠评审把关。

退出码（与 `_common` 常量一致）：0 绿 / 1 有命中 / 2 环境或参数问题。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from _common import CONTENT_FAIL, ENV_FAIL, EnvProblem, git_ls_files, strip_fences

ROOT = Path(__file__).resolve().parent.parent
# 冻结证据目录：见模块 docstring「不扫什么」。
SKIP_PREFIXES = ("docs/archive/",)
# `[)]*` 覆盖 `[ImportGate.java](path/ImportGate.java):17` 这种链接尾巴的写法。
LINE_REF = re.compile(r"\.(?:kts|java|kt|py|md)[)]*[:：][0-9]+")


def skipped(md_rel: str) -> bool:
    return md_rel.startswith(SKIP_PREFIXES)


def scan_text(md_rel: str, text: str) -> list[str]:
    """返回命中行的人类可读描述；行号取剥围栏前的原始行号。"""
    problems: list[str] = []
    for lineno, line in enumerate(strip_fences(text).split("\n"), 1):
        for m in LINE_REF.finditer(line):
            problems.append(
                f"{md_rel}:{lineno} 物理行号引用「{m.group(0)}」"
                "→ 改成文件名 + 可唯一 grep 的字符串（AGENTS.md §2 第 10 条）"
            )
    return problems


def scan_md(md_rel: str, text: str) -> list[str]:
    """按仓库口径扫一份 md（归档目录直接放行）。"""
    if skipped(md_rel):
        return []
    return scan_text(md_rel, text)


def self_test() -> int:
    failures: list[str] = []

    def expect_red(label: str, text: str, *, rel: str = "docs/x.md") -> None:
        problems = scan_md(rel, text)
        if not problems:
            failures.append(f"[FAIL] {label}：应判红却判绿")

    def expect_green(label: str, text: str, *, rel: str = "docs/x.md") -> None:
        problems = scan_md(rel, text)
        if problems:
            failures.append(f"[FAIL] {label}：应判绿却判红（{problems[0]}）")

    # 正样本：同一类物理行号的各种写法。
    expect_red("半角冒号", "见 `tools/x/Main.java:182`。")
    expect_red("全角冒号", "见 `tools/x/Main.java：182`。")
    expect_red("行号范围", "见 `x/ThumbnailStore.java:294-301`。")
    expect_red("链接尾巴", "见 [ImportGate.java](x/ImportGate.java):17。")
    expect_red("md 自引用", "见 `docs/testing.md:24`。")
    expect_red("py 行号", "见 `scripts/run_gates.py:12`。")

    # 负样本：误报评估表里的每一条。
    expect_green("合规锚点", "见 `ImportGate.java` 的 `enabled(...)`。")
    expect_green("issue 号", "见 #177。")
    expect_green("URL 端口", "见 `http://host:8080/x`。")
    expect_green("主机名 + 端口", "见 `example.com:8080`。")
    expect_green("版本号", "见版本 v1.2.3。")
    expect_green("章节号 + 数值", "见 §4.2:1。")
    expect_green("时刻", "见 12:30。")
    expect_green("围栏示例", "```\n见 `x/Main.java:182`。\n```\n")
    expect_green("归档目录", "见 `x/Main.java:182`。", rel="docs/archive/2026-11-audit/x.md")

    # 行号必须取原始位置，不能被剥围栏改写。
    problems = scan_md("docs/x.md", "开头\n```\n示例\n```\n见 `x/Main.java:182`。")
    if not problems or "docs/x.md:5 " not in problems[0]:
        failures.append(f"[FAIL] 定位行号：期望 docs/x.md:5，实际 {problems}")

    if failures:
        for f in failures:
            print(f)
        return ENV_FAIL
    print("[绿] 自检通过：正样本 6 条、负样本 9 条、定位行号 1 条")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="md 物理行号门禁（AGENTS.md §2 第 10 条）")
    parser.add_argument("--self-test", action="store_true", help="跑内置正负样本自检")
    args = parser.parse_args()
    if args.self_test:
        return self_test()

    try:
        md_files = git_ls_files("*.md")
    except EnvProblem as exc:
        print(f"[环境] {exc}", file=sys.stderr)
        return ENV_FAIL

    problems: list[str] = []
    checked = 0
    archived = 0
    for rel in md_files:
        if skipped(rel):
            archived += 1
            continue
        path = ROOT / rel
        if not path.is_file():
            problems.append(f"被跟踪 md 读不到：{rel}")
            continue
        checked += 1
        problems.extend(scan_md(rel, path.read_text(encoding="utf-8", errors="replace")))

    if problems:
        for p in problems:
            print(f"[红] {p}", file=sys.stderr)
        print(f"[红] {len(problems)} 处物理行号引用（AGENTS.md §2 第 10 条）", file=sys.stderr)
        return CONTENT_FAIL

    print(f"[绿] {checked} 个 md 无物理行号引用（{archived} 个归档文件按规则跳过）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
