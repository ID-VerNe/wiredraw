#!/usr/bin/env python3
"""元门禁：`scripts/gates.json`、`AGENTS.md` 的生成段、磁盘上的脚本文件三者零漂移。

手改 AGENTS.md 里的门禁表格会被这条打红。改清单之后跑：

    python scripts/check_gates_manifest.py --write

它会用清单重写 AGENTS.md 里 `<!-- gates:ci:begin -->` 与 `<!-- gates:ci:end -->` 之间那一段。

顺带校验：条目引用的脚本确实在磁盘上、没有重复的门禁名、以及 `cmd` 的参数没有被
静默降级（#131）：门禁命令里不许出现只报告不阻断、会改写工作区或只跑用例的参数
（`--warn` / `--soft` / `--dry-run` / `--fix` / `--write` / `--self-test`）；条目可用 `flags.require` 声明必须在命令里原样
出现的参数；脚本自己声明了降级开关时，必须用 `flags.require` 显式选阻断模式；
命令里跟在仓库内 `.py` 脚本后的长参数必须被该脚本的 `--help` 声明过（拼错即红）。

回归用例：`python scripts/check_gates_manifest.py --self-test`
"""
from __future__ import annotations

import copy
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "scripts" / "gates.json"
AGENTS = ROOT / "AGENTS.md"
BEGIN = "<!-- gates:ci:begin -->"
END = "<!-- gates:ci:end -->"

# 出现在门禁命令里就等于把门关掉：前三个只报告不阻断；`--fix` / `--write` 会在跑门禁时
# 改写工作区（门禁必须只读）；`--self-test` 只跑用例、跳过清单与生成段比对。
# 这张表是**策略**，不是「某条命令应该怎么写」的期望值。
FORBIDDEN_FLAGS = {"--warn", "--soft", "--dry-run", "--fix", "--write", "--self-test"}
# 脚本自带这些开关时，清单必须用 `flags.require` 显式声明一个阻断模式。
DOWNGRADE_FLAGS = {"--warn", "--soft", "--dry-run"}
LONG_FLAG = re.compile(r"--[a-z][a-z0-9-]*")


def load() -> dict:
    return json.loads(MANIFEST.read_text(encoding="utf-8"))


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
            name = entry["name"]
            if name in seen:
                problems.append(f"门禁名重复：{name}")
            seen.add(name)
            for token in entry["cmd"]:
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


def _script_flags(script: Path) -> set[str] | None:
    """取脚本自己声明的长参数（`--xxx`）。

    先看源码是否用 argparse。不走 argparse 的脚本收到 `--help` 会执行真实检查，
    元门禁不能顺手跑别的门禁（也会把元门禁自己递归调起来），所以直接跳过。
    拿不到选项集时返回 None，调用方只保留策略层校验。
    """
    if script.resolve() == Path(__file__).resolve():
        return None  # 不探测自己：本脚本没有 argparse，`--help` 会真的跑一遍检查
    try:
        source = script.read_text(encoding="utf-8")
    except OSError:
        return None
    # 只看真正的 import 行。注释里写「argparse」不算——否则元门禁会把自己
    # 当成 argparse 脚本递归探测下去（2026-11-07 实测单次 15 秒）。
    if not re.search(r"^\s*(?:import\s+argparse|from\s+argparse\s+import)", source, re.M):
        return None
    try:
        proc = subprocess.run(
            [sys.executable or "python", str(script), "--help"],
            cwd=str(ROOT),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=15,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if proc.returncode != 0:
        return None
    flags = set(LONG_FLAG.findall(proc.stdout))
    return flags or None


def check_cmd_contract(data: dict) -> list[str]:
    """校验 `cmd` 的参数没有把门禁降级（#131）。

    三层，各管一种错法，都不维护「命令 → 期望参数」的完整比对表：
    1. 策略层：任何档位都不许出现 `--warn` / `--soft` / `--dry-run` / `--fix`；
    2. 声明层：清单里的 `flags.require` 必须在命令里原样出现；
    3. 自证层：脚本 `--help` 声明的长参数才准用（拼错即红），并且脚本自带
       降级开关时，条目必须显式声明阻断模式（连字段一起删也红）。
    """
    problems = []
    for tier in ("ci", "manual", "support"):
        for entry in data.get(tier, []):
            name = entry["name"]
            cmd = entry["cmd"]
            long_flags = [t for t in cmd if isinstance(t, str) and t.startswith("--")]
            for flag in long_flags:
                if not LONG_FLAG.fullmatch(flag):
                    problems.append(f"`{name}` 的参数形态不对：{flag}")
                elif flag in FORBIDDEN_FLAGS:
                    problems.append(f"`{name}` 用了会关掉门禁的参数：{flag}")

            required = (entry.get("flags") or {}).get("require") or []
            for flag in required:
                if flag in FORBIDDEN_FLAGS:
                    problems.append(f"`{name}` 的必须参数本身会关掉门禁：{flag}")
                if flag not in cmd:
                    problems.append(f"`{name}` 缺少清单声明的必需参数：{flag}")

            script = _subject_script(entry)
            declared = _script_flags(script) if script else None
            if declared is None:
                continue
            for flag in long_flags:
                if flag not in declared:
                    problems.append(
                        f"`{name}` 的参数不在 {script.relative_to(ROOT)} 的 --help 里：{flag}"
                    )
            downgrades = sorted(declared & DOWNGRADE_FLAGS)
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
    base = load()
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

    if "python scripts/check_sensitive.py --check" not in render(base):
        failures.append("生成段没有渲染 cmd")
        print("[红] 自检：生成段没有渲染 cmd", file=sys.stderr)
    else:
        passed += 1
        print("[绿] 自检：生成段渲染 cmd")

    if failures:
        print(f"[红] 自检失败 {len(failures)} 项：{', '.join(failures)}", file=sys.stderr)
        return 1
    print(f"[绿] 自检 {passed} 项通过")
    return 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()

    write = "--write" in sys.argv
    data = load()

    if not AGENTS.is_file():
        print(f"[红] 找不到 {AGENTS.name}", file=sys.stderr)
        return 1

    text = AGENTS.read_text(encoding="utf-8")
    current = extract(text)
    expected = render(data)

    if write:
        if current is None:
            print(f"[红] {AGENTS.name} 里没有生成段标记 {BEGIN} / {END}", file=sys.stderr)
            return 1
        if current != expected:
            AGENTS.write_text(text.replace(current, expected), encoding="utf-8")
            print(f"[已重写] {AGENTS.name} 的门禁生成段")
        else:
            print("[无变化] 生成段已是最新")
    elif current != expected:
        print("[红] AGENTS.md 的门禁生成段与 scripts/gates.json 不一致。", file=sys.stderr)
        print("     修法：python scripts/check_gates_manifest.py --write", file=sys.stderr)
        return 1

    problems = check_scripts_exist(data) + check_cmd_contract(data)
    if problems:
        for p in problems:
            print(f"[红] {p}", file=sys.stderr)
        return 1

    print(f"[绿] 清单一致：{len(data.get('ci', []))} 个 ci 门禁，脚本均存在，无重名")
    return 0


if __name__ == "__main__":
    sys.exit(main())
