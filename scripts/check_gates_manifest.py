#!/usr/bin/env python3
"""元门禁：`scripts/gates.json`、`AGENTS.md` 的生成段、磁盘上的脚本文件三者零漂移。

手改 AGENTS.md 里的门禁表格会被这条打红。改清单之后跑：

    python scripts/check_gates_manifest.py --write

它会用清单重写 AGENTS.md 里 `<!-- gates:ci:begin -->` 与 `<!-- gates:ci:end -->` 之间那一段。

顺带校验：条目引用的脚本确实在磁盘上、没有重复的门禁名、以及 `cmd` 的参数没有被
静默降级（#131）：门禁命令里不许出现只报告不阻断、会改写工作区、只跑用例或只打印用法的参数；
禁用参数表的唯一来源是本模块的 `FORBIDDEN_FLAGS`（不在本段与 `gates.json` 里重复枚举）。
条目可用 `flags.require` 声明必须在命令里原样
出现的参数；脚本自己声明了降级开关时，必须用 `flags.require` 显式选阻断模式；
命令里跟在仓库内 `.py` 脚本后的参数（含短别名）必须被该脚本的 `--help` 声明过（拼错即红）。
探测失败（读不到脚本 / `--help` 非 0 / 超时）一律报红，不再退化成「没有 argparse」的静默分支（#203）。

形状层（#139）：`cmd` 必须是非空字符串数组、门禁名必须等于主体脚本的文件名，
否则明确报红——不认识/不合规的形状一律不许静默跳过。脚本给降级开关配了短别名时，
策略层按 `--help` 里的「同一行选项组」把别名映射回长参数，同样判红。

回归用例：`python scripts/check_gates_manifest.py --self-test`
"""
from __future__ import annotations

import contextlib
import copy
import io
import json
import re
import subprocess
import sys
import tempfile
import types
from pathlib import Path

from _common import CONTENT_FAIL, ENV_FAIL, OK, EnvProblem, resolve_python, run

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "scripts" / "gates.json"
AGENTS = ROOT / "AGENTS.md"
BEGIN = "<!-- gates:ci:begin -->"
END = "<!-- gates:ci:end -->"

# 出现在门禁命令里就等于把门关掉：前三个只报告不阻断；`--fix` / `--write` 会在跑门禁时
# 改写工作区（门禁必须只读）；`--self-test` 只跑用例、跳过清单与生成段比对；
# `--help` / `-h` 只打印用法就退 0，一个字节都不扫（#173）。
# `-h` 必须单独入表：元门禁不探测自己（拿不到 `-h, --help` 的别名组），只靠别名组兜不住。
# 这张表是**策略**，不是「某条命令应该怎么写」的期望值。
FORBIDDEN_FLAGS = {
    "--warn", "--soft", "--dry-run", "--fix", "--write", "--self-test", "--help", "-h",
}
# 脚本自带这些开关时，清单必须用 `flags.require` 显式声明一个阻断模式。
DOWNGRADE_FLAGS = {"--warn", "--soft", "--dry-run"}
# 命令里的「参数形状」：长参数 `--xxx` 与单字母短别名 `-x` 都算，其它 `-` 开头的 token
# （例如 `--warn=x`、`-` 本身）一律判形态不对。短别名不能只看 `--`（#139）。
FLAG_TOKEN = re.compile(r"--[a-z][a-z0-9-]*|-[A-Za-z]")


def load() -> dict:
    # 清单读不到 / 不是 JSON 都是「这次检查没做成」，抛 EnvProblem 由 main 映射成退出码 2（#204）。
    try:
        text = MANIFEST.read_text(encoding="utf-8")
    except OSError as e:
        raise EnvProblem(f"读不到 {MANIFEST.relative_to(ROOT)}：{e.strerror or e}") from e
    try:
        return json.loads(text)
    except json.JSONDecodeError as e:
        raise EnvProblem(f"gates.json 不是合法 JSON：{e}") from e


def render(data: dict) -> str:
    ci = data.get("ci", [])
    shards = [s["name"] for s in data.get("shards", [])]
    lines = [
        BEGIN,
        "> 本节由 `python scripts/check_gates_manifest.py --write` 从 `scripts/gates.json` 生成，**不要手改**；",
        "> 顺序即本机全量执行顺序。",
        "",
        "| # | 分片 | 门禁 | 命令 | 防什么 |",
        "|---|---|---|---|---|",
    ]
    for i, entry in enumerate(ci, 1):
        shard = entry.get("shard", "-")
        cmd = " ".join(entry["cmd"])
        lines.append(f"| {i} | `{shard}` | `{entry['name']}` | `{cmd}` | {entry['why']} |")

    counts = []
    for name in shards:
        n = sum(1 for e in ci if e.get("shard") == name)
        counts.append(f"{name} {n}")
    manual = len(data.get("manual", []))
    support = len(data.get("support", []))
    lines.append("")
    lines.append(
        f"共 {len(ci)} 个 CI 门禁，分 {len(shards)} 个分片（{'、'.join(counts)}）；"
        f"manual {manual}、support {support} 见 `scripts/gates.json`。"
    )
    lines.append(END)
    return "\n".join(lines)


def extract(text: str) -> str | None:
    m = re.search(re.escape(BEGIN) + r".*?" + re.escape(END), text, re.S)
    return m.group(0) if m else None


def check_scripts_exist(data: dict) -> list[str]:
    """ci/manual 条目里出现的「仓库内脚本路径」必须真实存在。

    只认带 `/` 且以 .py/.sh/.json/.md 结尾的 token —— 故意不校验 `-m` 后面的
    模块名（那是标准库调用，没有仓库内文件）。
    """
    problems = []
    seen = set()
    for tier in ("ci", "manual", "support"):
        for entry in data.get(tier, []):
            name = entry.get("name")
            if not isinstance(name, str) or not name:
                continue  # 形状层（check_cmd_contract）会明确报红
            if name in seen:
                problems.append(f"门禁名重复：{name}")
            seen.add(name)
            cmd = entry.get("cmd")
            if not isinstance(cmd, list):
                continue  # 形状不对由 check_cmd_contract 明确报红，这里不再 TypeError
            for token in cmd:
                if not isinstance(token, str) or token.startswith("-"):
                    continue
                if "/" not in token or not re.search(r"\.(py|sh|json|md)$", token):
                    continue
                if not (ROOT / token).exists():
                    problems.append(f"`{name}` 引用的文件不存在：{token}")
    return problems


def _subject_script(entry: dict) -> Path | None:
    """`cmd` 里第一个仓库内 `.py` 脚本的绝对路径；没有则 None。"""
    for token in entry["cmd"]:
        if isinstance(token, str) and "/" in token and token.endswith(".py"):
            path = ROOT / token
            if path.is_file():
                return path
    return None


class ProbeError(Exception):
    """脚本存在但参数自证探测失败。

    以前这里一律 `return None`，把「脚本没有 argparse」和「脚本读不到 / `--help` 跑不起来」
    混成同一个静默分支，结果是探测失败也照样跑绿（#203）。现在失败必须报红。
    """


def _script_flags(script: Path) -> tuple[set[str], list[set[str]]] | None:
    """取脚本自己声明的参数（长参数与短别名）以及它们的别名组。

    先看源码是否用 argparse。不走 argparse 的脚本收到 `--help` 会执行真实检查，
    元门禁不能顺手跑别的门禁（也会把元门禁自己递归调起来），所以直接跳过。
    `None` 只表示「无需探测」；探测本身失败抛 `ProbeError`，由调用方报红。

    第二项是「同一行里列出的选项」组成的别名组（argparse 把 `-h, --help` 渲染成
    一行），用来把短别名映射回同组的长参数（#139）。
    """
    if script.resolve() == Path(__file__).resolve():
        return None  # 不探测自己：本脚本没有 argparse，`--help` 会真的跑一遍检查
    try:
        source = script.read_text(encoding="utf-8")
    except OSError as e:
        raise ProbeError(f"读不到脚本源码：{script.relative_to(ROOT)}（{e}）") from e
    # 只看真正的 import 行。注释里写「argparse」不算——否则元门禁会把自己
    # 当成 argparse 脚本递归探测下去（2026-11-07 实测单次 15 秒）。
    if not re.search(r"^\s*(?:import\s+argparse|from\s+argparse\s+import)", source, re.M):
        return None
    try:
        # 解释器收敛（issue #205）：探测别的脚本 --help 用的解释器与执行器同一源；
        # PATH 上一个都没有时退回当前解释器（探测目的只是打印用法，版本族不影响判定）。
        proc = run([resolve_python() or sys.executable or "python", str(script), "--help"], timeout=15)
    except EnvProblem as e:
        raise ProbeError(
            f"`{script.relative_to(ROOT)} --help` 跑不起来：{e}"
        ) from e
    if proc.returncode != 0:
        raise ProbeError(
            f"`{script.relative_to(ROOT)} --help` 退出 {proc.returncode}，无法自证参数"
        )
    flags: set[str] = set()
    groups: list[set[str]] = []
    for line in proc.stdout.splitlines():
        stripped = line.strip()
        # 选项行以 `-` 开头；`usage:` 与折行后的帮助文本都不算
        if not stripped.startswith("-"):
            continue
        group = set(FLAG_TOKEN.findall(stripped))
        if group:
            groups.append(group)
            flags |= group
    return (flags, groups) if flags else None


def check_cmd_contract(data: dict) -> list[str]:
    """校验 `cmd` 的参数没有把门禁降级（#131）。

    四层，各管一种错法，都不维护「命令 → 期望参数」的完整比对表：
    0. 形状层：`cmd` 必须是非空字符串数组、门禁名必须等于主体脚本文件名——
       不合规的形状直接报红并跳过该条目，不再逐字符/静默跳过（#139）；
    1. 策略层：任何档位都不许出现 `--warn` / `--soft` / `--dry-run` / `--fix` /
       `--write` / `--self-test` / `--help` / `-h`；脚本给降级开关配了短别名时，
       按 `--help` 的选项行别名组映射回长参数，同样判红；
    2. 声明层：清单里的 `flags.require` 必须在命令里原样出现；
    3. 自证层：脚本 `--help` 声明的参数（含短别名）才准用（拼错即红），并且脚本
       自带降级开关时，条目必须显式声明阻断模式（连字段一起删也红）。
    """
    problems = []
    for tier in ("ci", "manual", "support"):
        for entry in data.get(tier, []):
            name = entry.get("name")
            if not isinstance(name, str) or not name:
                problems.append(f"{tier} 里有条目的 name 不是非空字符串：{entry!r}")
                continue
            cmd = entry.get("cmd")
            if not isinstance(cmd, list) or not cmd or not all(
                isinstance(t, str) and t for t in cmd
            ):
                problems.append(f"`{name}` 的 cmd 必须是非空字符串数组（当前：{cmd!r}）")
                continue

            script = _subject_script(entry)
            if script is not None and script.name != name:
                problems.append(
                    f"`{name}` 的主体脚本是 {script.relative_to(ROOT)}，与门禁名不一致"
                    "（整条命令可能被换成了别的脚本）"
                )
            try:
                declared = _script_flags(script) if script else None
            except ProbeError as e:
                problems.append(str(e))
                declared = None
            declared_flags, alias_groups = declared if declared else (None, [])
            flags_in_cmd = [t for t in cmd if t.startswith("-")]
            for flag in flags_in_cmd:
                if not FLAG_TOKEN.fullmatch(flag):
                    problems.append(f"`{name}` 的参数形态不对：{flag}")
                    continue
                # 短别名没有独立策略语义：它所在的选项组里有禁用参数，就按该长参数判红。
                forbidden = flag in FORBIDDEN_FLAGS or any(
                    flag in group and group & FORBIDDEN_FLAGS for group in alias_groups
                )
                if forbidden:
                    problems.append(f"`{name}` 用了会关掉门禁的参数：{flag}")

            required = (entry.get("flags") or {}).get("require") or []
            for flag in required:
                if flag in FORBIDDEN_FLAGS:
                    problems.append(f"`{name}` 的必须参数本身会关掉门禁：{flag}")
                if flag not in cmd:
                    problems.append(f"`{name}` 缺少清单声明的必需参数：{flag}")

            if declared_flags is not None:
                for flag in flags_in_cmd:
                    if flag not in declared_flags:
                        problems.append(
                            f"`{name}` 的参数不在 {script.relative_to(ROOT)} 的 --help 里：{flag}"
                        )
            downgrades = sorted((declared_flags or set()) & DOWNGRADE_FLAGS)
            if downgrades and not required:
                problems.append(
                    f"`{name}` 的 {script.relative_to(ROOT)} 支持降级参数 {downgrades}，"
                    "清单必须用 flags.require 显式声明阻断模式"
                )
    return problems


def _ci_entry(data: dict, name: str) -> dict:
    return next(e for e in data["ci"] if e["name"] == name)


def self_test() -> int:
    """用真实清单的内存副本钉住「参数被改坏必须红」。

    每条用例都要求具体的提示文本，而不是只看「有没有问题」——这样把判定条件
    改坏时用例才会真的红，而不是被任意一条无关问题蒙混过关。
    """
    try:
        base = load()
    except EnvProblem as exc:
        print(f"[红] {exc}", file=sys.stderr)
        return ENV_FAIL
    failures: list[str] = []
    passed = 0

    def run(label: str, mutate, *, want: bool, must: tuple[str, ...] = (), which="cmd") -> None:
        nonlocal passed
        data = copy.deepcopy(base)
        mutate(data)
        problems = check_cmd_contract(data) if which == "cmd" else check_scripts_exist(data)
        text = "\n".join(problems)
        if bool(problems) == want and all(frag in text for frag in must):
            passed += 1
            print(f"[绿] 自检：{label}")
        else:
            failures.append(label)
            print(f"[红] 自检：{label}（want={want}，实际：{problems}）", file=sys.stderr)

    run("真实清单不误报", lambda d: None, want=False)

    def warn_for(d):
        _ci_entry(d, "check_sensitive.py")["cmd"] = ["python", "scripts/check_sensitive.py", "--warn"]

    run("--check 改成 --warn", warn_for, want=True, must=("会关掉门禁的参数：--warn", "必需参数：--check"))

    def typo_for(d):
        _ci_entry(d, "check_sensitive.py")["cmd"] = ["python", "scripts/check_sensitive.py", "--chek"]

    run("参数拼错成 --chek", typo_for, want=True, must=("--chek", "必需参数：--check"))

    def drop_flag(d):
        _ci_entry(d, "check_sensitive.py")["cmd"] = ["python", "scripts/check_sensitive.py"]

    run("少写 --check", drop_flag, want=True, must=("缺少清单声明的必需参数：--check",))

    def drop_both(d):
        entry = _ci_entry(d, "check_sensitive.py")
        entry["cmd"] = ["python", "scripts/check_sensitive.py"]
        entry.pop("flags", None)

    run("连 flags 契约和 --check 一起删", drop_both, want=True, must=("必须用 flags.require",))

    def new_gate_warn(d):
        d["ci"].append({
            "name": "check_new.py",
            "cmd": ["python", "scripts/check_sensitive.py", "--warn"],
            "shard": "static",
            "why": "自检用：新增门禁直接用降级参数",
        })

    run("新增门禁直接用 --warn", new_gate_warn, want=True, must=("会关掉门禁的参数：--warn",))

    def meta_self_test(d):
        _ci_entry(d, "check_gates_manifest.py")["cmd"] = ["python", "scripts/check_gates_manifest.py", "--self-test"]

    run("元门禁自己被改成 --self-test", meta_self_test, want=True, must=("会关掉门禁的参数：--self-test",))

    def meta_write(d):
        _ci_entry(d, "check_gates_manifest.py")["cmd"] = ["python", "scripts/check_gates_manifest.py", "--write"]

    run("元门禁自己被改成 --write", meta_write, want=True, must=("会关掉门禁的参数：--write",))

    def duplicate(d):
        d["ci"].append(copy.deepcopy(d["ci"][0]))

    run("门禁重名", duplicate, want=True, must=("门禁名重复",), which="exist")

    def missing_file(d):
        d["ci"].append({
            "name": "check_missing.py",
            "cmd": ["python", "scripts/not_on_disk.py"],
            "shard": "static",
            "why": "自检用：引用不存在的文件",
        })

    run("引用不存在的脚本", missing_file, want=True, must=("引用的文件不存在",), which="exist")

    # ---- #139：非常规形状不许静默放行 ----
    def cmd_as_string(d):
        _ci_entry(d, "check_sensitive.py")["cmd"] = "python scripts/check_sensitive.py --check"

    run("cmd 写成字符串", cmd_as_string, want=True, must=("cmd 必须是非空字符串数组",))

    def cmd_has_non_string(d):
        _ci_entry(d, "check_sensitive.py")["cmd"] = [
            "python", "scripts/check_sensitive.py", "--check", 7,
        ]

    run("cmd 里混入非字符串元素", cmd_has_non_string, want=True, must=("cmd 必须是非空字符串数组",))

    def empty_cmd(d):
        _ci_entry(d, "check_sensitive.py")["cmd"] = []

    run("cmd 是空数组", empty_cmd, want=True, must=("cmd 必须是非空字符串数组",))

    def undeclared_short(d):
        _ci_entry(d, "check_sensitive.py")["cmd"] = [
            "python", "scripts/check_sensitive.py", "--check", "-w",
        ]

    run("追加 --help 未声明的短别名 -w", undeclared_short, want=True, must=("-w", "--help"))

    def swapped_script(d):
        _ci_entry(d, "check_sensitive.py")["cmd"] = ["python", "scripts/check_doc_links.py", "--check"]

    run("整条命令换成非 argparse 脚本", swapped_script, want=True, must=("与门禁名不一致",))

    # ---- #173：只打印用法就退 0 的 --help / -h 不能当门禁 ----
    def help_long(d):
        _ci_entry(d, "check_sensitive.py")["cmd"] = [
            "python", "scripts/check_sensitive.py", "--check", "--help",
        ]

    run("追加 --help", help_long, want=True, must=("会关掉门禁的参数：--help",))

    def help_short(d):
        _ci_entry(d, "check_sensitive.py")["cmd"] = [
            "python", "scripts/check_sensitive.py", "--check", "-h",
        ]

    run("追加 -h", help_short, want=True, must=("会关掉门禁的参数：-h",))

    # 元门禁对自身返回 None（`_script_flags` 不探测自己），拿不到别名组。
    # 这两条钉住：即使没有别名组，策略层也必须直接命中 --help / -h。
    def meta_help_long(d):
        _ci_entry(d, "check_gates_manifest.py")["cmd"] = [
            "python", "scripts/check_gates_manifest.py", "--help",
        ]

    run("元门禁自己追加 --help", meta_help_long, want=True, must=("会关掉门禁的参数：--help",))

    def meta_help_short(d):
        _ci_entry(d, "check_gates_manifest.py")["cmd"] = [
            "python", "scripts/check_gates_manifest.py", "-h",
        ]

    run("元门禁自己追加 -h", meta_help_short, want=True, must=("会关掉门禁的参数：-h",))

    real_script_flags = _script_flags

    def fake_script_flags(script):
        """假设主体脚本给 --warn 加了 -w 别名：策略层必须沿别名组判红。"""
        return (
            {"--check", "--warn", "-w", "-h", "--help"},
            [{"--check"}, {"--warn", "-w"}, {"-h", "--help"}],
        )

    try:
        globals()["_script_flags"] = fake_script_flags
        run(
            "降级开关的短别名 -w 也判红",
            undeclared_short,
            want=True,
            must=("会关掉门禁的参数：-w",),
        )
    finally:
        globals()["_script_flags"] = real_script_flags

    # ---- #203：探测失败必须报红，不能退化成「没有 argparse」的绿分支 ----
    def probe_failure() -> None:
        nonlocal passed
        data = copy.deepcopy(base)
        original = subprocess.run

        def fake_run(*_args, **_kwargs):
            return types.SimpleNamespace(returncode=3, stdout="", stderr="")

        try:
            subprocess.run = fake_run
            problems = check_cmd_contract(data)
        finally:
            subprocess.run = original
        label = "脚本 --help 非 0 必须报红"
        if problems and "无法自证参数" in "\n".join(problems):
            passed += 1
            print(f"[绿] 自检：{label}")
        else:
            failures.append(label)
            print(f"[红] 自检：{label}（实际：{problems}）", file=sys.stderr)

    probe_failure()

    if "python scripts/check_sensitive.py --check" not in render(base):
        failures.append("生成段没有渲染 cmd")
        print("[红] 自检：生成段没有渲染 cmd", file=sys.stderr)
    else:
        passed += 1
        print("[绿] 自检：生成段渲染 cmd")

    # #204：子进程样板收口到共享层；清单读不到必须退 2（不许 traceback 隐式退 1）。
    def check(label: str, ok: bool, detail: str = "") -> None:
        nonlocal passed
        if ok:
            passed += 1
            print(f"[绿] 自检：{label}")
        else:
            failures.append(label)
            print(f"[红] 自检：{label}（{detail}）", file=sys.stderr)

    manifest_src = Path(__file__).read_text(encoding="utf-8")
    check(
        "#204：子进程走共享层 run()",
        "from _common import" in manifest_src and "subprocess.run" + "(" not in manifest_src,
    )

    real_manifest = MANIFEST
    globals()["MANIFEST"] = ROOT / "scripts" / "__missing_gates.json"
    try:
        # 用例预期这里报红：两个流都要吞掉，否则全绿的自检也会往 stderr 吐假红（#284）。
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            try:
                missing_exit = main([])
            except Exception:  # noqa: BLE001 - 现状是 traceback，用例要报红而不是把自检炸掉
                missing_exit = None
    finally:
        globals()["MANIFEST"] = real_manifest
    check("#204：清单读不到退 2", missing_exit == ENV_FAIL, f"实际 {missing_exit!r}")

    # AGENTS.md 存在但读失败（编码 / 权限 / 被占用）也要退 2，不许 traceback 退 1（#284）。
    # 用「字节不是合法 UTF-8」的临时文件构造，`main` 会走到 `read_text` 的失败分支。
    real_agents = AGENTS
    with tempfile.TemporaryDirectory() as tmp:
        bad_agents = Path(tmp) / AGENTS.name
        bad_agents.write_bytes(b"\xff\xfe")
        globals()["AGENTS"] = bad_agents
        try:
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                try:
                    unreadable_exit = main([])
                except Exception:  # noqa: BLE001 - 现状是 traceback，用例要报红而不是把自检炸掉
                    unreadable_exit = None
        finally:
            globals()["AGENTS"] = real_agents
    check("#284：AGENTS.md 读不到退 2", unreadable_exit == ENV_FAIL, f"实际 {unreadable_exit!r}")

    # 自检红 / 全绿的退出码也必须与 _common 一致（#204）：红是「检查没做成」= 2，不是内容 1。
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        red_exit = _self_test_result(["注入的失败"], 0)
        green_exit = _self_test_result([], 1)
    check("#204：自检红退 2", red_exit == ENV_FAIL, f"实际 {red_exit!r}")
    check("#204：自检全绿退 0", green_exit == OK, f"实际 {green_exit!r}")

    return _self_test_result(failures, passed)


def _self_test_result(failures: list[str], passed: int) -> int:
    """自检结论：红属于「检查没做成」，与 `_common.py` 及其它门禁统一退 2（#204）。"""
    if failures:
        print(f"[红] 自检失败 {len(failures)} 项：{', '.join(failures)}", file=sys.stderr)
        return ENV_FAIL
    print(f"[绿] 自检 {passed} 项通过")
    return OK


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    if "--self-test" in argv:
        return self_test()

    write = "--write" in argv
    try:
        data = load()
    except EnvProblem as exc:
        print(f"[红] {exc}", file=sys.stderr)
        return ENV_FAIL

    # 清单本身不合规时先报红：既不让后续渲染把畸形 cmd 写进 AGENTS.md，
    # 也避免形状问题在更早的检查里变成异常栈（#139）。
    problems = check_cmd_contract(data) + check_scripts_exist(data)
    if problems:
        for p in problems:
            print(f"[红] {p}", file=sys.stderr)
        return CONTENT_FAIL

    if not AGENTS.is_file():
        print(f"[红] 找不到 {AGENTS.name}", file=sys.stderr)
        # 生成段的目标文件读不到 = 这次比对没做成（#204）。
        return ENV_FAIL

    try:
        text = AGENTS.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as e:
        # 文件存在但读失败同样是「这次比对没做成」：退 2，不许 traceback 退 1（#284）。
        print(f"[红] {AGENTS.name} 读不到：{e}", file=sys.stderr)
        return ENV_FAIL
    current = extract(text)
    expected = render(data)

    if write:
        if current is None:
            print(f"[红] {AGENTS.name} 里没有生成段标记 {BEGIN} / {END}", file=sys.stderr)
            return CONTENT_FAIL
        if current != expected:
            AGENTS.write_text(text.replace(current, expected), encoding="utf-8")
            print(f"[已重写] {AGENTS.name} 的门禁生成段")
        else:
            print("[无变化] 生成段已是最新")
    elif current != expected:
        print("[红] AGENTS.md 的门禁生成段与 scripts/gates.json 不一致。", file=sys.stderr)
        print("     修法：python scripts/check_gates_manifest.py --write", file=sys.stderr)
        return CONTENT_FAIL

    print(f"[绿] 清单一致：{len(data.get('ci', []))} 个 ci 门禁，脚本均存在，无重名")
    return OK


if __name__ == "__main__":
    sys.exit(main())
