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

import ast
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
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

# 与判定表并排放一份冻结清单：新增 / 删除触发器必须两处一起改，否则 self_test() 直接红。
# 没有它，往 GATE_TRIGGERS 里加一项不会触发任何断言，「判定表被悄悄扩展」只有绿灯（#283）。
FROZEN_GATE_TRIGGERS = (
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


def _shown(target: Path) -> str:
    """错误文案里的路径：仓库内的显示相对路径，仓库外的原样显示（避免把机器目录写进输出）。"""
    try:
        return target.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return str(target)


def load_json_file(path) -> object:
    """读一个 UTF-8 的 JSON 文件并解析；读不到 / 不是合法 JSON 都抛 `EnvProblem`。

    `scripts/gates.json` 的元门禁与执行器原本各写一份逐字相同的
    「`read_text` + JSON 解析 + `JSONDecodeError` → `EnvProblem`」前缀（#312）：
    清单要加 BOM 容忍、编码回退或大小上限时，只改元门禁不改执行器，就会出现
    「元门禁判绿、执行器读歪」的分叉。

    **形状校验不在这里**：两个调用方的判据不同，策略留在各自脚本里——
    元门禁把形状问题交给 `check_cmd_contract` 报内容不合规（退 1），
    执行器直接把形状问题当清单读不成（退 2）。

    路径按参数传入而不是读模块级常量：两个 `self_test` 都靠替换模块级 `MANIFEST`
    注入一份不存在的清单来验证「读不到退 2」，默认参数会在 `def` 时把旧值钉死。
    """
    target = Path(path)
    try:
        text = target.read_text(encoding="utf-8")
    except OSError as exc:
        raise EnvProblem(f"读不到 {_shown(target)}：{exc.strerror or exc}") from exc
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise EnvProblem(f"{_shown(target)} 不是合法 JSON：{exc}") from exc


def read_event_payload() -> dict | None:
    """读 GitHub Actions 的事件载荷（`GITHUB_EVENT_PATH`）；没有 PR 上下文时返回 `None`。

    support 档三条门禁（PR 正文 / commit message / PR 分段结构）原本各写一份
    「取环境变量 → 判文件 → `read_text` + `json.loads` → 把两种失败映射成 `EnvProblem`」
    的前缀（#311）。载荷要加 BOM 容忍、大小上限或把「文件存在但读失败」单独归类时，
    只改一处会让三条门禁对同一份载荷给出不同结论。

    只负责「拿到载荷」：`pull_request` 在不在、缺不缺 `number` / `base.sha` / `head.sha`，
    由调用方各自收窄——那几处的错误文案与判据不同，不往这里搬。

    每次调用都现读环境变量：三个脚本的 `self_test` 都靠改 `os.environ` 构造载荷，
    缓存或默认参数会让用例失效。
    """
    path = os.environ.get("GITHUB_EVENT_PATH")
    if not path:
        return None
    target = Path(path)
    if not target.is_file():
        return None
    try:
        return json.loads(target.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise EnvProblem(f"读不到事件载荷 {_shown(target)}：{exc}") from exc


def resolve_python() -> str:
    """门禁链上 Python 解释器的唯一解析（issue #205）：先 `python` 后 `python3`。

    PATH 上一个都没有时返回空串，由调用方决定 fail-closed 的姿势：`run_gates.py`
    的 `resolve_cmd` 直接拒（退 2），`check_gates_manifest.py` 退回当前解释器
    （探测目的只是打印用法，版本族不影响判定）。shell 侧的对等实现是
    `scripts/resolve_python.sh`——钩子与探针 `build.sh` 在还没有 Python 可用时
    也要解析解释器，不能反过来依赖 Python。
    """
    return shutil.which("python") or shutil.which("python3") or ""


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

    # 判定表本身的变化必须可见：新增 / 删除触发器时这条要红，逼着一起改冻结清单（#283）。
    check(
        "门禁链判定表与冻结清单一致（新增触发器须显式确认）",
        tuple(GATE_TRIGGERS) == FROZEN_GATE_TRIGGERS,
        f"实际 {GATE_TRIGGERS}",
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
    # 期望值拆成两段，只为让本文件不被 check_line_refs 的源码扫描命中；运行时仍是同一个
    # 字面量。实际值来自 format_hit 的输出、期望值手写，两者不是同一段代码生成。
    check("命中行格式含位置与类别", ("[红] docs/x.md" + ":12 [guid]") in plain, plain)
    _Hit.encoding = "hex"
    check("编码形态补 /hex 后缀", "[guid/hex]" in format_hit(_Hit(), where="PR #7"), format_hit(_Hit()))

    # 7. 清单读取的机械前缀只许活在本模块（#312）：元门禁与执行器各有形状校验，
    #    但「读文件 + 解 JSON + 把两种失败映射成 EnvProblem」逐字重复过一份。
    #    断言瞄准的字面量用拼接写法，避免本文件自己被扫描命中。
    dup_read = "MANIFEST.read_text" + "(encoding="
    dup_json_err = "gates.json 不是" + "合法 JSON"
    siblings = {
        p.name: p.read_text(encoding="utf-8")
        for p in sorted((ROOT / "scripts").glob("*.py"))
        if p.name != Path(__file__).name
    }
    read_offenders = [n for n, src in siblings.items() if dup_read in src]
    err_offenders = [n for n, src in siblings.items() if dup_json_err in src]
    check("gates.json 机械读取段只在共享层一处", read_offenders == [], f"实际 {read_offenders}")
    check("gates.json 解析失败映射只在共享层一处", err_offenders == [], f"实际 {err_offenders}")

    # 第三种形态：换掉变量名、把清单路径内联进 JSON 解析调用的参数——上面两条字面量都抓不到。
    # 评审在 `run_gates.py` 的自检里逮到过这一行：#312 要消灭的正是「第二个读清单的地方失败时
    # 走 traceback 退 1，而 load_manifest 同一失败退 2」的分叉。判据按 issue 原文：
    # 兄弟脚本里同一行同时出现 JSON 解析调用与清单文件名即红。
    # 两个判据串都拆开写，避免本文件自己成为 `git grep` 的第二处命中。
    loads_attr = "json" + ".loads"
    gate_name = "gates" + ".json"
    inline_offenders = [
        n
        for n, src in siblings.items()
        if any(loads_attr in line and gate_name in line for line in src.splitlines())
    ]
    check(
        "兄弟脚本不再把清单文件内联进 JSON 解析",
        inline_offenders == [],
        f"实际 {inline_offenders}",
    )

    load = globals().get("load_json_file")
    check("共享层提供 load_json_file", callable(load))
    if callable(load):
        def missing_file():
            load(ROOT / "scripts" / "__missing_gates.json")

        def bad_json_file():
            with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as fh:
                fh.write("{not json")
                bad = Path(fh.name)
            try:
                load(bad)
            finally:
                bad.unlink()

        check("load_json_file 读不到文件时抛 EnvProblem", outcome(missing_file) == "", outcome(missing_file))
        check("load_json_file 坏 JSON 时抛 EnvProblem", outcome(bad_json_file) == "", outcome(bad_json_file))
        check(
            "load_json_file 正常 JSON 原样返回",
            load(ROOT / "scripts" / "gates.json").get("version") is not None,
        )

    # 8. 事件载荷读取只许活在本模块（#311）：support 档三条门禁对同一份
    #    `GITHUB_EVENT_PATH` 各解析一份，载荷要加 BOM 容忍或大小上限时会三处不同步。
    #
    #    判据按**语义形态**扫、不认固定片段（#339）：旧实现找的是字面量
    #    `json.loads(Path(path)` 拼 `.read_text`，把变量名从 `path` 换成 `_p` 即可绕过
    #    （评审实测）。现在改成「本作用域读 `GITHUB_EVENT_PATH` -> 读出来的路径被
    #    `read_text` 读 -> 结果被 `loads` / `load` 解析」三件齐全才命中——与变量名、
    #    与调用嵌套写法无关。
    EVENT_ENV = "GITHUB_EVENT_PATH"

    def env_read(node: ast.AST) -> bool:
        """是不是在读取事件载荷的那个环境变量：`os.environ.get("GITHUB_EVENT_PATH")` /
        `os.getenv("GITHUB_EVENT_PATH")` / `os.environ["GITHUB_EVENT_PATH"]`（取值，不是赋值）。

        只认**读取**：`os.environ.pop("GITHUB_EVENT_PATH")` 是自检里清场的写法、`os.environ[...]
        = ...` 是写回，都不是在读事件载荷；裸字面量更不算——若把 `EVENT_ENV =
        "GITHUB_EVENT_PATH"` 这类模块常量也算数，配合本作用域另一次无关的 `read_text` +
        `loads` 会被误报（第三轮评审实测多例）。
        """
        if isinstance(node, ast.Call):
            fn = node.func
            if not isinstance(fn, ast.Attribute):
                return False
            holder = fn.value
            is_env_get = fn.attr == "get" and isinstance(holder, ast.Attribute) and holder.attr == "environ"
            is_getenv = fn.attr == "getenv" and isinstance(holder, ast.Name) and holder.id == "os"
            if not (is_env_get or is_getenv):
                return False
            return any(isinstance(a, ast.Constant) and a.value == EVENT_ENV for a in node.args)
        if isinstance(node, ast.Subscript):
            holder = node.value
            return (
                isinstance(node.ctx, ast.Load)
                and isinstance(holder, ast.Attribute)
                and holder.attr == "environ"
                and isinstance(node.slice, ast.Constant)
                and node.slice.value == EVENT_ENV
            )
        return False

    def reaches_env(node: ast.AST) -> bool:
        return any(env_read(child) for child in ast.walk(node))

    def event_payload_inliners(src: str) -> list[str]:
        """源码里「把事件载荷读成 JSON」的作用域名；判据见上方注释（#339）。

        扫描范围 = 模块顶层 + 每个函数 / 类体，逐个作用域独立判定。**模块顶层必须在内**：
        判据收窄到函数级会让模块顶层 / `if __name__` 块里的内联读取漏判（首轮评审的阻断项，
        实测同一注入 base 判红、收窄后判绿）。类体同样纳入，方法体由各自的函数作用域单独判定。

        **嵌套作用域一律不并入外层**：遍历在遇到 `FunctionDef` / `AsyncFunctionDef` /
        `ClassDef` 时停住。只跳过外层语句的**直接**子节点是不够的——定义在模块级
        `if` / `try` 块里的函数会顺着 `ast.walk` 被外层作用域一并收走，于是「外层只提到环境
        变量名、真正的解析在块内函数里」被误报成 `<module>`（第二轮评审的构造性误报）。
        `lambda` 不在停表里：它没有自己的名字可报，其函数体本就属于外层作用域，据实归给外层。

        命中还要求**路径能追到那次环境读取**：只要求「同一作用域里出现过环境变量名 + 有
        `read_text` + 有 `loads`」三件套的话，本作用域另读一份无关 JSON（例如自检里先
        `pop("GITHUB_EVENT_PATH")` 再读 `fixtures/` 下的样例）会被当成事件解析而误报
        （第三轮评审实测两例）。判据因此收成：`read_text` 的**接收者**要么内联含环境读取，
        要么引用了 `名字 = 环境读取` 那次赋值的名字。
        """
        try:
            tree = ast.parse(src)
        except SyntaxError:
            return []
        scopes: list[tuple[str, list[ast.stmt]]] = [("<module>", tree.body)]
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                scopes.append((node.name, node.body))
        stops = (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)
        found: list[str] = []
        for name, body in scopes:
            scope_nodes: list[ast.AST] = []
            # 显式栈而不是 ast.walk：遇到嵌套作用域即停，不把它的内容算进本作用域。
            stack: list[ast.AST] = list(body)
            while stack:
                node = stack.pop()
                if isinstance(node, stops):
                    continue
                scope_nodes.append(node)
                stack.extend(ast.iter_child_nodes(node))
            if not any(env_read(node) for node in scope_nodes):
                continue
            derived = {
                target.id
                for node in scope_nodes
                if isinstance(node, ast.Assign)
                for target in node.targets
                if isinstance(target, ast.Name) and reaches_env(node.value)
            }
            reads_derived = any(
                isinstance(node, ast.Call)
                and isinstance(node.func, ast.Attribute)
                and node.func.attr == "read_text"
                and (
                    reaches_env(node.func.value)
                    or any(
                        isinstance(child, ast.Name) and child.id in derived
                        for child in ast.walk(node.func.value)
                    )
                )
                for node in scope_nodes
            )
            parses = any(
                isinstance(node, ast.Call)
                and isinstance(node.func, ast.Attribute)
                and node.func.attr in ("loads", "load")
                for node in scope_nodes
            )
            if reads_derived and parses:
                found.append(name)
        return found

    event_offenders = {
        n: event_payload_inliners(src)
        for n, src in siblings.items()
        if event_payload_inliners(src)
    }
    check("GITHUB_EVENT_PATH 解析只在共享层一处", event_offenders == {}, f"实际 {event_offenders}")

    # 变异用例（#339）：换变量名 / 模块顶层 / `if __name__` 块内的事件读取都必须判红，
    # 只调共享层的正常写法不许误伤。模块顶层这条是首轮评审的阻断项回归用例。
    renamed_event = "\n".join((
        "def fetch_event_body():",
        "    _p = os.environ.get(\"GITHUB_EVENT_PATH\")",
        "    if not _p:",
        "        return None",
        "    return json.loads(Path(_p).read_text(encoding=\"utf-8\"))",
    ))
    module_level_event = "\n".join((
        "if __name__ == \"__main__\":",
        "    path = os.environ.get(\"GITHUB_EVENT_PATH\")",
        "    payload = json.loads(Path(path).read_text(encoding=\"utf-8\"))",
        "    print(payload)",
    ))
    delegating_event = "\n".join((
        "from _common import read_event_payload",
        "def fetch_event_body():",
        "    return read_event_payload()",
    ))
    nested_in_block = "\n".join((
        "_ENV = \"GITHUB_EVENT_PATH\"",
        "if __name__ == \"__main__\":",
        "    def _p(x):",
        "        return json.loads(Path(x).read_text(encoding=\"utf-8\"))",
    ))
    class_method_event = "\n".join((
        "class Probe:",
        "    def load(self):",
        "        p = os.environ.get(\"GITHUB_EVENT_PATH\")",
        "        return json.loads(Path(p).read_text(encoding=\"utf-8\"))",
    ))
    check(
        "事件载荷判据抓住变量重命名（#339）",
        event_payload_inliners(renamed_event) == ["fetch_event_body"],
        f"实际 {event_payload_inliners(renamed_event)}",
    )
    check(
        "事件载荷判据抓住模块顶层内联读取（#339）",
        event_payload_inliners(module_level_event) == ["<module>"],
        f"实际 {event_payload_inliners(module_level_event)}",
    )
    check(
        "事件载荷判据不误伤只调共享层的写法（#339）",
        event_payload_inliners(delegating_event) == [],
        f"实际 {event_payload_inliners(delegating_event)}",
    )
    # 嵌套作用域守卫（#339，第二轮评审的构造性误报回归用例）：模块级 `if` / `try` 块内定义的
    # 函数必须由它自己的作用域判定，不能顺着遍历被并在 `<module>` 里——否则「模块级只有环境
    # 变量名、真正的内联读取在块内函数里」会被误报。期望顶层只出现两个形态（缺 `loads`），不命中。
    check(
        "事件载荷判据不把块内嵌套作用域并进外层（#339）",
        event_payload_inliners(nested_in_block) == [],
        f"实际 {event_payload_inliners(nested_in_block)}",
    )
    check(
        "事件载荷判据覆盖类方法作用域（#339）",
        event_payload_inliners(class_method_event) == ["load"],
        f"实际 {event_payload_inliners(class_method_event)}",
    )
    # 误报回归（#339，第三轮评审的构造性误报）：判据只认「本作用域读过事件环境变量」。
    # 只把环境变量名当普通字符串常量、或读一份**无关** JSON，都不算事件解析。
    const_noise = "\n".join((
        "EVENT_ENV = \"GITHUB_EVENT_PATH\"",
        "_DATA = json.loads(Path(\"fixtures/data.json\").read_text(encoding=\"utf-8\"))",
    ))
    block_def_unrelated = "\n".join((
        "EVENT_ENV = \"GITHUB_EVENT_PATH\"",
        "try:",
        "    def _p():",
        "        return json.loads(Path(\"data.json\").read_text(encoding=\"utf-8\"))",
        "except Exception:",
        "    pass",
    ))
    env_then_other_json = "\n".join((
        "def _p_o():",
        "    _name = os.environ.get(\"GITHUB_EVENT_PATH\")",
        "    _txt = Path(\"scripts/gates_local.json\").read_text(encoding=\"utf-8\")",
        "    return json.loads(_txt), _name",
    ))
    # 第四轮评审追加：同作用域读事件环境变量，紧挨着内联解析一份**无关** JSON（`config.json`）。
    # 与上一条的差别是这次 `read_text` 内联在 `loads` 实参里、与无关路径字面量同现。
    env_path_other_json = "\n".join((
        "def _p_jj():",
        "    _path = os.environ.get(\"GITHUB_EVENT_PATH\")",
        "    _cfg = json.loads(Path(\"config.json\").read_text(encoding=\"utf-8\"))",
        "    return _cfg, _path",
    ))
    selftest_pop_env = "\n".join((
        "def _selftest_case():",
        "    saved = os.environ.pop(\"GITHUB_EVENT_PATH\", None)",
        "    try:",
        "        data = json.loads(Path(\"fixtures/sample.json\").read_text(encoding=\"utf-8\"))",
        "    finally:",
        "        if saved is not None:",
        "            os.environ[\"GITHUB_EVENT_PATH\"] = saved",
        "    return data",
    ))
    event_false_positives = {
        "常量名非读取": event_payload_inliners(const_noise),
        "块内 def 读无关 JSON": event_payload_inliners(block_def_unrelated),
        "读 env 名后解析别的文件": event_payload_inliners(env_then_other_json),
        "读 env 名后内联解析别的文件": event_payload_inliners(env_path_other_json),
        "自检式 pop env 后读样例": event_payload_inliners(selftest_pop_env),
    }
    check(
        "事件载荷判据不把无关 JSON 读取算成事件解析（#339）",
        all(not v for v in event_false_positives.values()),
        f"实际误报 {event_false_positives}",
    )

    read_event = globals().get("read_event_payload")
    check("共享层提供 read_event_payload", callable(read_event))
    if callable(read_event):
        saved_event = os.environ.pop("GITHUB_EVENT_PATH", None)
        try:
            check("缺少 GITHUB_EVENT_PATH 时返回 None", read_event() is None)
            os.environ["GITHUB_EVENT_PATH"] = str(ROOT / "scripts" / "__missing_event.json")
            check("事件载荷路径不存在时返回 None", read_event() is None)
            with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as fh:
                fh.write("{not json")
                bad_event = Path(fh.name)
            os.environ["GITHUB_EVENT_PATH"] = str(bad_event)
            try:
                bad_event_result = outcome(read_event)
            finally:
                bad_event.unlink()
            check("事件载荷坏 JSON 时抛 EnvProblem", bad_event_result == "", bad_event_result)
            with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as fh:
                json.dump({"pull_request": {"number": 7}}, fh)
                good_event = Path(fh.name)
            os.environ["GITHUB_EVENT_PATH"] = str(good_event)
            try:
                payload = read_event()
            finally:
                good_event.unlink()
            check(
                "事件载荷正常时原样返回",
                isinstance(payload, dict) and payload.get("pull_request", {}).get("number") == 7,
                f"实际 {payload!r}",
            )
        finally:
            os.environ.pop("GITHUB_EVENT_PATH", None)
            if saved_event is not None:
                os.environ["GITHUB_EVENT_PATH"] = saved_event

    # 9. 退出码只许用常量表达（#314）：`OK` / `CONTENT_FAIL` / `ENV_FAIL` 已经是全仓契约，
    #    但 6 个 check_*.py 里还散着 `return 0/1/2` 字面量——契约要扩展（新增 SKIP 码、
    #    给 ENV_FAIL 加统一上报）时，只改常量的那几个脚本会跟着变，写字面量的不会。
    #
    #    判据走 AST，不扫文本（#339）：整行式 `^[ \t]*return [012][ \t]*$` 要求 `return`
    #    到行尾只剩一个数字，行内注释、双空格、单行三元右侧都能绕过；换成宽松正则
    #    `\breturn\s+[012]\b` 又会把注释 / docstring 里的字样、`return 1.5` 一起误判——
    #    正则看不见语法，认不出「这串字在注释里」也不认「点后面还有小数位」。AST 只问
    #    「有没有 `return` 的值是整型常量 0 / 1 / 2」，上述两类都天然成立：
    #    `return (1)` / 跨行括号在 AST 里是同一个 `Constant(1)`（正则抓不到），
    #    注释与 docstring 根本不进语法树。`return 1 if x else 0` 看 `IfExp` 两支。
    def literal_exit_returns(src: str) -> int:
        """源码里「`return` 的值是整型常量 0 / 1 / 2」的处数；注释 / 字符串不算（#339）。

        `return 1.5` / `return 12` / `return OK` / `return ENV_FAIL` 不命中。
        `True` / `False` 不是退出码，不算（`bool` 是 `int` 子类，要排掉）。
        `return 0x1` 在 AST 里就是 `Constant(1)`，与 `return 1` 同形，一并判红——它本来就
        是字面量 1，判红才是契约要的语义。
        """
        try:
            tree = ast.parse(src)
        except SyntaxError:
            return 0

        def is_exit_literal(value: ast.expr | None) -> bool:
            if isinstance(value, ast.IfExp):
                return is_exit_literal(value.body) or is_exit_literal(value.orelse)
            return (
                isinstance(value, ast.Constant)
                and isinstance(value.value, int)
                and not isinstance(value.value, bool)
                and value.value in (0, 1, 2)
            )

        return sum(
            1
            for node in ast.walk(tree)
            if isinstance(node, ast.Return) and is_exit_literal(node.value)
        )

    literal_offenders = {
        n: literal_exit_returns(src)
        for n, src in siblings.items()
        if n.startswith("check_") and literal_exit_returns(src)
    }
    check(
        "check_*.py 不再用 return 0/1/2 字面量表达退出码",
        literal_offenders == {},
        f"实际 {literal_offenders}",
    )

    # 变异用例（#339）：三种绕过形态必须判红，合法写法不许误伤。
    literal_mutations = {
        "行内注释": "def f():\n    return 1  # 注释\n",
        "双空格": "def f():\n    return  1\n",
        "单行三元": "def f():\n    return 1 if x else 0\n",
    }
    literal_evaders = {
        n: s for n, s in literal_mutations.items() if literal_exit_returns(s) != 1
    }
    check(
        "return 字面量判据抓住注释/双空格/三元绕过（#339）",
        literal_evaders == {},
        f"实际未命中 {literal_evaders}",
    )
    # 反向用例（第三轮评审的构造性误报回归）：注释 / docstring / 浮点里的字样不算字面量；
    # 括号与跨行写法的 `1` 仍是字面量，必须命中（正则两样都做不到）。
    literal_legit = {
        "具名常量": "def f():\n    return OK\n",
        "多位数字": "def f():\n    return 12\n",
        "布尔值": "def f():\n    return True\n",
        "浮点 1.5": "def f():\n    return 1.5\n",
        "浮点 0.5": "def f():\n    return 0.5\n",
        "幂 2**10": "def f():\n    return 2**10\n",
        "注释提到": "# 旧写法 return 1 已废弃\n",
        "docstring 提到": 'DOC = """退出码不得写成 return 0/1/2"""\n',
    }
    literal_bracketed = {
        "括号 return (1)": "def f():\n    return (1)\n",
        "跨行括号": "def f():\n    return (\n        2\n    )\n",
    }
    literal_false_positives = sorted(
        n for n, s in literal_legit.items() if literal_exit_returns(s)
    ) + sorted(n for n, s in literal_bracketed.items() if literal_exit_returns(s) != 1)
    check(
        "return 字面量判据只认语法树里的整型常量（#339）",
        literal_false_positives == [],
        f"实际误判 {literal_false_positives}",
    )

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
