#!/usr/bin/env python3
"""门禁脚本共享层（issue #204）：只放跨脚本逐字重复的机械能力。

分工：
- **本模块**：子进程 / `gh` JSON / `git ls-files -z` / 门禁链判定 / 命中行格式化 /
  围栏代码块剥除 / 退出码常量。
- **各门禁脚本**：扫什么、算不算命中、报告什么文案。策略不往这里搬。

退出码契约（全仓统一，`docs/testing.md` 有同一张表）：
    0  绿：检查通过、自检全绿；
    1  内容不达标：命中问题（断链、敏感值、缺段、参数拼错以外的内容错误）；
    2  环境或参数问题：gh / git 用不了、输出不是 JSON、文件读不到、参数形态不对。

`1` 与 `2` 必须分开：`1` 是「这份东西不合格」，`2` 是「这次检查根本没做成」。
混用会让 CI 把环境抖动当成内容问题（#204 的原始症状之一）。
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

OK = 0
CONTENT_FAIL = 1
ENV_FAIL = 2

ROOT = Path(__file__).resolve().parent.parent

# 命中任一前缀即视为「动了门禁链」，要求 PR 走 `docs/contributing/PR_GUIDE.md` 的独立复核。
# 曾经在 `review_prompt.py` 与 `check_pr_sections.py` 里各抄一份；两份一起改才不漂移，
# 所以收敛到这里（#204）。
# 本文件自己也在范围内：它现在存着门禁链判定表与退出码常量，改它等于改门禁。
GATE_TRIGGERS = (
    "scripts/check_",
    "scripts/_common.py",
    "scripts/gates.json",
    "scripts/run_gates.py",
    ".github/workflows/ci.yml",
    ".githooks/",
)


class EnvProblem(Exception):
    """环境或参数问题：取不到上下文、命令不可用、输出不可解析。

    上层必须映射到 `ENV_FAIL`（也为 2），**不许降级成绿**，也不要让它以 traceback 的形式
    以退出码 1 结束——那会把「没做成」记成「内容不合格」。
    """


def run(
    argv: list[str],
    *,
    runner=None,
    timeout: float | None = None,
) -> subprocess.CompletedProcess:
    """跑一次子进程，返回 `CompletedProcess`；起不来一律抛 `EnvProblem`。

    只负责「跑起来」：非 0 退出码怎么解释由调用方决定（`gh` / `git` 的失败细节不同）。
    """
    runner = runner or subprocess.run
    try:
        return runner(
            argv,
            cwd=str(ROOT),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
    except FileNotFoundError as exc:
        raise EnvProblem(f"找不到命令：{argv[0]}") from exc
    except (OSError, subprocess.SubprocessError) as exc:
        raise EnvProblem(f"`{' '.join(argv)}` 跑不起来：{exc}") from exc


def gh_json(args: list[str], runner=None) -> object:
    """跑 `gh <args>` 并把 stdout 当 JSON 解析；任何失败都抛 `EnvProblem`。

    返回 `object`：`gh pr view` 给对象、`gh pr list` 给数组，由调用方各自收窄。
    """
    proc = run(["gh", *args], runner=runner)
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or "").strip().splitlines()
        raise EnvProblem(
            f"gh 退出 {proc.returncode}：{detail[-1] if detail else '无 stderr'}"
        )
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        raise EnvProblem(f"gh 返回的不是 JSON：{exc}") from exc


def git_ls_files(*patterns: str, runner=None) -> list[str]:
    """`git ls-files` 的被跟踪文件清单，**固定带 `-z`**。

    不带 `-z` 时 git 会按 `core.quotePath` 把中文文件名转义成 `"\\344\\270\\255.md"`，
    拼回磁盘路径必然 `is_file() == False`。`check_doc_links.py` 的 #203 守卫会把这种
    **确实存在**的文件报成「被跟踪 md 读不到」，即一条假红——在 `core.quotePath=true`
    的机器上对任何中文名文档都必现。本单直接消掉误报源头（#204）。
    """
    proc = run(["git", "ls-files", "-z", *patterns], runner=runner)
    if proc.returncode != 0:
        detail = (proc.stderr or "").strip().splitlines()
        raise EnvProblem(
            f"git ls-files 失败（退出 {proc.returncode}）："
            f"{detail[-1] if detail else '确认当前目录是 git 仓库'}"
        )
    return [p for p in (proc.stdout or "").split("\0") if p.strip()]


def gate_hits(files: list[str]) -> list[str]:
    """改动文件里命中门禁链的那些（前缀或精确名匹配）。"""
    return [f for f in files if any(f.startswith(t) or f == t for t in GATE_TRIGGERS)]


FENCE_OPEN = re.compile(r"^ {0,3}(`{3,}|~{3,})")


def strip_fences(text: str) -> str:
    """把围栏代码块的内容替换成空行（保留行号），未闭合则吃到文末。

    围栏里是示例，不是正文：`check_doc_links.py` 用它避免把示例链接当断链，
    `check_line_refs.py` 用它避免把 issue / PR 模板里的行号示例当违规。
    闭围栏须与开围栏同字符、长度不短于开围栏；混合围栏（开 ``` 闭 ~~~）不算闭合。
    """
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    out: list[str] = []
    char = ""
    width = 0
    for line in normalized.split("\n"):
        m = FENCE_OPEN.match(line)
        if not char:
            if m:
                char, width = m.group(1)[0], len(m.group(1))
                out.append("")
            else:
                out.append(line)
        else:
            if m and m.group(1)[0] == char and len(m.group(1)) >= width:
                char, width = "", 0
            out.append("")
    return "\n".join(out)


def format_hit(hit, *, tag: str = "红", where: str = "") -> str:
    """命中行的统一格式：`[红] path:line [类别] 打码值 → 占位符`。

    `hit` 按结构取字段（`path` / `line` / `category` / `masked()` / `placeholder()`），
    另外 `encoding` 可选：非 `plain` 时补一个 `/hex` 之类的后缀。
    """
    suffix = ""
    encoding = getattr(hit, "encoding", "plain")
    if encoding != "plain":
        suffix = f"/{encoding}"
    location = f"{hit.path}:{hit.line}"
    if where:
        location = f"{where} {location}"
    return (
        f"[{tag}] {location} [{hit.category}{suffix}] "
        f"{hit.masked()} → {hit.placeholder()}"
    )


# ---------------------------------------------------------------- 自检


def _legacy_gate_hits(files: list[str]) -> list[str]:
    """迁移前的实现（逐字保留），只用于「新旧两条路径同一输入结果一致」的对照。"""
    triggers = (
        "scripts/check_",
        "scripts/gates.json",
        "scripts/run_gates.py",
        ".github/workflows/ci.yml",
        ".githooks/",
    )
    return [f for f in files if any(f.startswith(t) or f == t for t in triggers)]


def self_test() -> int:
    checks: list[tuple[str, bool, str]] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        checks.append((name, ok, detail))

    # 1. 门禁链判定：新旧两条路径在同一组输入上必须逐项一致（含前缀与精确名边界）。
    samples = [
        ["scripts/check_pr_sections.py", "docs/testing.md"],
        ["scripts/gates.json"],
        ["scripts/run_gates.py", "scripts/run_gates.py.bak"],
        [".githooks/commit-msg", ".github/workflows/ci.yml", "tools/pe/README.md"],
        ["scripts/check_sensitive.py"],
        ["AGENTS.md"],
        [],
    ]
    expected = [_legacy_gate_hits(s) for s in samples]
    actual = [gate_hits(s) for s in samples]
    check("同一输入下新旧 gate_hits 结果一致", expected == actual, f"新={actual} 旧={expected}")

    # 新增触发器必须单独断言：共享层自己存着门禁规则，改它等于改门禁，
    # 不能因为「迁移前没这条」而被上面的对照用例漏掉（评审阻断项）。
    check(
        "共享层自身命中门禁链",
        gate_hits(["scripts/_common.py"]) == ["scripts/_common.py"],
        f"实际 {gate_hits(['scripts/_common.py'])}",
    )

    # 2/3. 环境问题统一抛 EnvProblem（由上层映射成退出码 2），不许裸抛 JSONDecodeError
    #     或 FileNotFoundError —— 那两种都会以退出码 1 结束，把「没做成」记成「内容不合格」。
    def outcome(fn) -> str:
        try:
            fn()
        except EnvProblem:
            return ""
        except Exception as exc:  # noqa: BLE001 - 自检就是要抓「抛错了类型」
            return f"实际抛 {type(exc).__name__}"
        return "没有抛异常"

    def bad_json():
        gh_json(
            ["pr", "view", "1"],
            runner=lambda *a, **k: subprocess.CompletedProcess(a[0], 0, "not-json", ""),
        )

    def missing_gh():
        def missing(*a, **k):
            raise FileNotFoundError("no gh")

        gh_json([], runner=missing)

    check("gh 输出坏 JSON 时抛 EnvProblem", outcome(bad_json) == "", outcome(bad_json))
    check("命令不存在时抛 EnvProblem", outcome(missing_gh) == "", outcome(missing_gh))

    # 4. git_ls_files 必须带 -z：文件名里的空格不能被当成记录分隔。
    seen: list[list[str]] = []

    def fake_ls(*a, **k):
        seen.append(list(a[0]))
        return subprocess.CompletedProcess(a[0], 0, "a.md\0带 空格.md\0", "")

    names = git_ls_files("*.md", runner=fake_ls)
    check("git_ls_files 固定带 -z", seen and "-z" in seen[0], f"argv={seen[0] if seen else None}")
    check("git_ls_files 按 NUL 切分（空格文件名不被拆开）", names == ["a.md", "带 空格.md"], f"得到 {names}")

    # 5. 退出码常量稳定：CI 与文档都按这三个值写中文名。
    check("退出码常量 0/1/2", (OK, CONTENT_FAIL, ENV_FAIL) == (0, 1, 2))

    # 6. 命中行格式：普通命中与编码形态各一条。
    class _Hit:
        path = "docs/x.md"
        line = 12
        category = "guid"
        encoding = "plain"

        def masked(self):
            return "<redacted:guid:1>"

        def placeholder(self):
            return "<PAIRED_GUID>"

    plain = format_hit(_Hit())
    check("命中行格式含位置与类别", "[红] docs/x.md:12 [guid]" in plain, plain)
    _Hit.encoding = "hex"
    check("编码形态补 /hex 后缀", "[guid/hex]" in format_hit(_Hit(), where="PR #7"), format_hit(_Hit()))

    for name, ok, detail in checks:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f"  — {detail}" if detail and not ok else ""))

    bad = [c for c in checks if not c[1]]
    if bad:
        print(f"== 自检红 {len(bad)} 项 ==", file=sys.stderr)
        return ENV_FAIL
    print(f"== 自检全绿：{len(checks)} 项 ==")
    return OK


if __name__ == "__main__":
    # 没有可选开关；带参数一律当参数形态错误（退出 2），不静默忽略。
    if len(sys.argv) > 1:
        print(f"[红] _common.py 不接受参数：{' '.join(sys.argv[1:])}", file=sys.stderr)
        sys.exit(ENV_FAIL)
    sys.exit(self_test())
