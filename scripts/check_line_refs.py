#!/usr/bin/env python3
"""被跟踪文件的物理行号门禁：`.md` / `.java` / `.kt` / `.kts` / `.py`（issue #252）。

为什么有这条：行号是物理坐标，上方插一行就漂；文档与代码注释都没有编译或测试能发现这种漂移。
规则全文在 `AGENTS.md` §2 第 10 条；`UI-SPEC.md` §10 第 8 条只给指针。

扫什么：
- `git ls-files -z` 拿到的五种扩展名文件（`-z` 的由来见 #204）；
- 逐行匹配「扩展名 + 冒号（半角/全角）+ 数字」。

怎么扫：
- `.md`：先剥围栏代码块（示例不算正文）；
- `.java` / `.kt` / `.kts` / `.py`：不剥围栏——这几种类型没有围栏语义，注释与字符串一视同仁。

为什么用扩展名白名单：
- 不用「任意扩展名 + 冒号」是为了不把 `example.com:8080`、`http://host:8080` 判成行号；
- 白名单覆盖代码类型，正是 #252 扩范围的目的（原规则只覆盖 `UI-SPEC.md` 自身）。

不扫什么（每条都有理由，不是偷懒）：
- 路径排除清单 `EXCLUDED`：精确文件 + 具体理由，不做值级白名单。目前只有门禁自身——
  它的正样本必须字面包含「文件名 + 冒号 + 数字」才能验证判红，改成拼接会让用例自证；
- 围栏代码块（仅 md）：issue / PR 模板里的行号示例属一次性上下文，规则显式允许；
- `docs/archive/`：当时证据的冻结副本，引用对象是本地不入库的第三方仓库，本来不可达，
  坐标失效是预期，改坐标等于伪造当时的审计记录；
- 不带文件名的裸冒号数字：与时刻、章节号、版本号无法区分，靠评审把关。

输出：先按扩展名打印「被跟踪 / 扫描 / 命中」，再打印命中明细（红）。

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
# 被扫类型只有这一份真清单：`SCAN_PATTERNS` 与 `LINE_REF` 都由它派生，
# 新增 / 删除类型只改这里。自检会断言它恰好是这五种、派生结果逐项对应、
# 且每种类型都能被行号正则识别。
EXTENSIONS = ("md", "java", "kt", "kts", "py")
SCAN_PATTERNS = tuple(f"*.{ext}" for ext in EXTENSIONS)
# 路径级排除：精确相对路径 → 具体理由。理由必须说明「为什么这个文件必须存在违规文本」，
# 不接受「历史原因」「暂时放行」这类模板话。新增排除必须同时改自检（长度断言为 1）。
EXCLUDED = {
    "scripts/check_line_refs.py": (
        "门禁自身的测试夹具：正样本必须字面包含「文件名 + 冒号 + 数字」"
        "才能验证判红；改成运行时拼接会让用例自证，等于测不到规则"
    ),
}
# `[)]*` 覆盖 `[ImportGate.java](path/ImportGate.java):17` 这种链接尾巴的写法。
# 扩展名分支同样由 EXTENSIONS 派生，避免出现第三份手写清单。
LINE_REF = re.compile(
    r"\.(?:" + "|".join(re.escape(ext) for ext in EXTENSIONS) + r")[)]*[:：][0-9]+"
)


def skipped(rel: str) -> bool:
    return rel.startswith(SKIP_PREFIXES)


def excluded(rel: str) -> bool:
    return rel in EXCLUDED


def extension_of(rel: str) -> str:
    return rel.rsplit(".", 1)[-1]


def body(rel: str, text: str) -> str:
    """按类型准备待扫文本：md 剥围栏，代码类型只统一换行。"""
    if rel.endswith(".md"):
        return strip_fences(text)
    return text.replace("\r\n", "\n").replace("\r", "\n")


def scan_file(rel: str, text: str) -> list[str]:
    """返回命中行的人类可读描述；行号取原始位置（剥围栏不改变行号）。"""
    if skipped(rel) or excluded(rel):
        return []
    problems: list[str] = []
    for lineno, line in enumerate(body(rel, text).split("\n"), 1):
        for m in LINE_REF.finditer(line):
            problems.append(
                f"{rel}:{lineno} 物理行号引用「{m.group(0)}」"
                "→ 改成文件名 + 可唯一 grep 的字符串（AGENTS.md §2 第 10 条）"
            )
    return problems


def exclusion_problems(tracked: list[str]) -> list[str]:
    """排除清单自校验：精确路径存在、被跟踪、带理由，且条目数固定。"""
    problems: list[str] = []
    if len(EXCLUDED) != 1:
        problems.append(
            f"路径排除清单应恰好 1 条（当前 {len(EXCLUDED)} 条）：新增排除必须同时改自检"
        )
    for rel, reason in EXCLUDED.items():
        if not reason.strip():
            problems.append(f"排除项 {rel} 缺具体理由")
        if not (ROOT / rel).is_file():
            problems.append(f"排除项在磁盘上不存在：{rel}")
        if rel not in tracked:
            problems.append(f"排除项未被 git 跟踪：{rel}")
    return problems


def self_test() -> int:
    failures: list[str] = []

    def expect_red(label: str, text: str, *, rel: str = "docs/x.md") -> None:
        problems = scan_file(rel, text)
        if not problems:
            failures.append(f"[FAIL] {label}：应判红却判绿")

    def expect_green(label: str, text: str, *, rel: str = "docs/x.md") -> None:
        problems = scan_file(rel, text)
        if problems:
            failures.append(f"[FAIL] {label}：应判绿却判红（{problems[0]}）")

    # 正样本：同一类物理行号在各种文件类型里的写法。
    expect_red("半角冒号", "见 `tools/x/Main.java:182`。")
    expect_red("全角冒号", "见 `tools/x/Main.java：182`。")
    expect_red("行号范围", "见 `x/ThumbnailStore.java:294-301`。")
    expect_red("链接尾巴", "见 [ImportGate.java](x/ImportGate.java):17。")
    expect_red("md 自引用", "见 `docs/testing.md:24`。")
    expect_red("py 行号", "见 `scripts/run_gates.py:12`。")
    expect_red("java 注释", "// 对齐 Foo.java:17 的常量", rel="tools/x/Foo.java")
    expect_red("kt 注释", "// 对齐 Theme.kt:18 的画布色", rel="tools/x/Theme.kt")
    expect_red("py 注释", "# 见 probe.py:27-29", rel="tools/x/probe.py")
    expect_red("代码类型不剥围栏", "```\nFoo.java:17\n```\n", rel="tools/x/Foo.java")

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
    expect_green("路径排除", "见 `x/Main.java:182`。", rel="scripts/check_line_refs.py")

    # 行号必须取原始位置，不能被剥围栏改写。
    problems = scan_file("docs/x.md", "开头\n```\n示例\n```\n见 `x/Main.java:182`。")
    if not problems or "docs/x.md:5 " not in problems[0]:
        failures.append(f"[FAIL] 定位行号：期望 docs/x.md:5，实际 {problems}")

    # 结构断言：「扫哪些类型」只有一个真清单，派生形态与排除清单都必须可验证。
    # 期望值手写、实际值取模块常量：有人在 EXTENSIONS 里增删一类，这里立刻红。
    expected_extensions = ("md", "java", "kt", "kts", "py")
    if tuple(EXTENSIONS) != expected_extensions:
        failures.append(
            f"[FAIL] 扫描类型清单：应为 {expected_extensions}，实际 {tuple(EXTENSIONS)}"
        )
    # 派生必须保持「*. + 扩展名」这一形态；被改成带目录 / 后缀时 `git ls-files` 会换目标。
    derived_ok = len(SCAN_PATTERNS) == len(EXTENSIONS) and all(
        pattern == f"*.{ext}" for pattern, ext in zip(SCAN_PATTERNS, EXTENSIONS)
    )
    if not derived_ok:
        failures.append(
            f"[FAIL] 扫描入口派生：应为 EXTENSIONS 逐项拼接 `*.` + 扩展名，实际 {SCAN_PATTERNS}"
        )
    # 正则分支同为派生：每种类型都必须能被 `<文件名>.<ext>:<数字>` 命中，
    # 防止只改 EXTENSIONS 而正则漏掉新类型（门禁主体的静默漏检就出在这里）。
    if not all(LINE_REF.search(f"x.{ext}:12") for ext in EXTENSIONS):
        failures.append(
            f"[FAIL] 行号正则覆盖：EXTENSIONS 中有类型未被 LINE_REF 识别，实际 {LINE_REF.pattern}"
        )
    if len(EXCLUDED) != 1 or "scripts/check_line_refs.py" not in EXCLUDED:
        failures.append(
            f"[FAIL] 路径排除清单：应恰好 1 条且只含门禁自身，实际 {sorted(EXCLUDED)}"
        )
    if not EXCLUDED.get("scripts/check_line_refs.py", "").strip():
        failures.append("[FAIL] 路径排除清单：缺具体理由")
    valid = exclusion_problems(["scripts/check_line_refs.py"])
    if valid:
        failures.append(f"[FAIL] 路径排除校验：合法清单被判红 {valid}")
    if not exclusion_problems([]):
        failures.append("[FAIL] 路径排除校验：未跟踪的文件应判红却判绿")

    if failures:
        for f in failures:
            print(f)
        return ENV_FAIL
    print("[绿] 自检通过：正样本 10 条、负样本 10 条、定位行号 1 条、结构 7 条")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="物理行号门禁（AGENTS.md §2 第 10 条）")
    parser.add_argument("--self-test", action="store_true", help="跑内置正负样本自检")
    args = parser.parse_args()
    if args.self_test:
        return self_test()

    try:
        files: list[str] = []
        for pattern in SCAN_PATTERNS:
            files.extend(git_ls_files(pattern))
    except EnvProblem as exc:
        print(f"[环境] {exc}", file=sys.stderr)
        return ENV_FAIL

    problems = exclusion_problems(files)
    stats = {
        ext: {"tracked": 0, "scanned": 0, "archived": 0, "excluded": 0, "hits": 0}
        for ext in EXTENSIONS
    }
    for rel in files:
        ext = extension_of(rel)
        if ext not in stats:
            continue
        st = stats[ext]
        st["tracked"] += 1
        if skipped(rel):
            st["archived"] += 1
            continue
        if excluded(rel):
            st["excluded"] += 1
            continue
        path = ROOT / rel
        if not path.is_file():
            problems.append(f"被跟踪文件读不到：{rel}")
            continue
        st["scanned"] += 1
        hits = scan_file(rel, path.read_text(encoding="utf-8", errors="replace"))
        st["hits"] += len(hits)
        problems.extend(hits)

    width = max(len(ext) for ext in EXTENSIONS)
    for ext in EXTENSIONS:
        st = stats[ext]
        notes = []
        if st["archived"]:
            notes.append(f"归档跳过 {st['archived']}")
        if st["excluded"]:
            notes.append(f"路径排除 {st['excluded']}")
        tail = f"（{'，'.join(notes)}）" if notes else ""
        print(
            f"[统计] {ext:<{width}} 被跟踪 {st['tracked']:>2} / "
            f"扫描 {st['scanned']:>2} / 命中 {st['hits']}{tail}"
        )

    if problems:
        for p in problems:
            print(f"[红] {p}", file=sys.stderr)
        print(f"[红] {len(problems)} 处问题（AGENTS.md §2 第 10 条）", file=sys.stderr)
        return CONTENT_FAIL

    print("[绿] 0 处物理行号引用（规则见 AGENTS.md §2 第 10 条）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
