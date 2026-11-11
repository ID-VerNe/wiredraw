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
    #    （评审实测）。现在改成「同一作用域内同时出现 `GITHUB_EVENT_PATH` 环境变量名、
    #    `read_text` 属性访问、`loads` 属性访问」即命中——与变量名、与调用嵌套写法无关。
    def event_payload_inliners(src: str) -> list[str]:
        """源码里把事件载荷读成 JSON 的作用域名；形态判据见上方注释（#339）。

        扫描范围 = 模块顶层 + 每个函数 / 类体，逐个作用域独立判定。**模块顶层必须在内**：
        旧判据是整份文件的子串扫描，把模块顶层与 `if __name__` 块漏掉会让判据从「覆盖」
        退成「不覆盖」（PR #351 首轮评审的阻断项，实测同一注入 base 判红、收窄后判绿）。
        类体同样纳入，方法体由各自的函数作用域单独判定。
        """
        try:
            tree = ast.parse(src)
        except SyntaxError:
            return []
        shape = {"GITHUB_EVENT_PATH", "read_text", "loads"}
        scopes: list[tuple[str, list[ast.stmt]]] = [("<module>", tree.body)]
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                scopes.append((node.name, node.body))
        found: list[str] = []
        for name, body in scopes:
            seen: set[str] = set()
            for stmt in body:
                # 嵌套作用域由它自己的条目判定，不并入外层，否则跨函数拼接会被误判成命中。
                if isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                    continue
                for child in ast.walk(stmt):
                    if isinstance(child, ast.Constant) and child.value == "GITHUB_EVENT_PATH":
                        seen.add("GITHUB_EVENT_PATH")
                    elif isinstance(child, ast.Attribute) and child.attr in ("read_text", "loads"):
                        seen.add(child.attr)
            if seen == shape:
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
    #    判据用 token / 正则级（#339）：旧的整行式 `^[ \t]*return [012][ \t]*$` 要求
    #    `return` 到行尾只剩一个数字，行内注释（`return 1  # 注释`）、双空格（`return  1`）、
    #    单行三元右侧（`return 1 if x else 0`）都能绕过（评审实测）。`\breturn\s+[012]\b`
    #    里 `\s` 含换行、`\b` 挡住 `return 12` / `return 0x1` / `return OK` 这类误伤。
    literal_return = re.compile(r"\breturn\s+[012]\b")
    literal_offenders = {
        n: len(literal_return.findall(src))
        for n, src in siblings.items()
        if n.startswith("check_") and literal_return.search(src)
    }
    check(
        "check_*.py 不再用 return 0/1/2 字面量表达退出码",
        literal_offenders == {},
        f"实际 {literal_offenders}",
    )

    # 变异用例（#339）：三种绕过形态必须判红，合法写法不许误伤。
    literal_mutations = {
        "行内注释": "    return 1  # 注释",
        "双空格": "    return  1",
        "单行三元": "    return 1 if x else 0",
    }
    literal_evaders = {
        n: s for n, s in literal_mutations.items() if not literal_return.search(s)
    }
    check(
        "return 字面量判据抓住注释/双空格/三元绕过（#339）",
        literal_evaders == {},
        f"实际未命中 {literal_evaders}",
    )
    literal_legit = ["    return OK", "    return 12", "    return 0x1", "    return ENV_FAIL"]
    literal_false_positives = [s for s in literal_legit if literal_return.search(s)]
    check(
        "return 字面量判据不误伤具名常量与多位数字（#339）",
        literal_false_positives == [],
        f"实际误命中 {literal_false_positives}",
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
