#!/usr/bin/env python3
"""门禁执行器。

清单的唯一事实来源是 `scripts/gates.json`，本机与 CI 跑同一份、顺序也一样。
不要在别处再写一份命令数组。

用法：
    python scripts/run_gates.py --list          # 看档位与顺序
    python scripts/run_gates.py ci              # 跑 ci 档（= 本机全量）
    python scripts/run_gates.py ci --fail-fast  # 见到红就停
    python scripts/run_gates.py ci --shard static
    python scripts/run_gates.py ci --prepush  # pre-push 钩子口径：按 gates.json 的 prePush 标记取分片
    python scripts/run_gates.py manual          # 跑需要真设备/真工具链的那一档
    python scripts/run_gates.py --self-test     # 执行器内置回归用例（不进清单）

退出码：0 全绿 / 1 有门禁红 / 2 清单或环境问题。
"""
from __future__ import annotations

import argparse
import contextlib
import copy
import io
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

from _common import (
    CONTENT_FAIL,
    ENV_FAIL,
    OK,
    EnvProblem,
    git_ls_files,
    load_json_file,
    resolve_python,
    run,
)

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "scripts" / "gates.json"


def resolve_bash() -> str:
    """在 Windows 上找**真正的** bash，别让子进程落到 WSL 的 bash.exe。

    Windows 的 `bash` 在 PATH 里常先命中 `C:\\Windows\\System32\\bash.exe`，
    那是 WSL 的入口：它看到的是另一个文件系统，`tools/**.sh` 里的 SDK 路径全都不存在，
    于是 manual 档会以「命令跑不起来」的形式假红（而不是真跑一遍构建）。
    优先级：显式覆盖 > PATH 里的非 System32 候选 > Git for Windows 常见安装位置。
    """
    override = os.environ.get("WIREDRAW_BASH")
    if override:
        return override

    found = shutil.which("bash")
    if found and "system32" not in found.replace("/", "\\").lower():
        return found

    if os.name == "nt":
        for cand in (
            r"C:\Program Files\Git\bin\bash.exe",
            r"C:\Program Files\Git\usr\bin\bash.exe",
            r"C:\Program Files (x86)\Git\bin\bash.exe",
        ):
            if os.path.isfile(cand):
                return cand

    return found or "bash"


def resolve_cmd(cmd: list[str]) -> list[str]:
    """把 cmd 里的 shell / 解释器换成解析过的绝对路径；其余命令原样返回。"""
    if cmd and cmd[0] in ("bash", "sh"):
        return [resolve_bash()] + cmd[1:]
    if cmd and cmd[0] == "python":
        # gates.json 的 cmd 保持裸 python（清单可读、可手跑）；执行器统一解析（issue #205）。
        # PATH 上一个都没有时不静默降级：抛 EnvProblem 让本次执行退 2，
        # 而不是拿「python: command not found」冒充门禁红（退出码 1）。
        resolved = resolve_python()
        if not resolved:
            raise EnvProblem("找不到 python（python3 也没有），无法执行清单里的门禁命令")
        return [resolved] + cmd[1:]
    return cmd


def select_entries(data: dict, tier: str, *, shard: str | None, prepush: bool) -> list[dict]:
    """按 --shard / --prepush 过滤条目；分片名先对 gates.json 的 shards 声明校验。

    --shard 的「未知名字明确报错」是 #243 定的；--prepush（issue #205）不写死分片名，
    改读 shards 条目上的 prePush 标记，让「钩子跑哪几档」与「分片叫什么」解耦。
    """
    entries = data.get(tier, [])
    if prepush:
        if tier != "ci":
            raise EnvProblem(f"--prepush 只用于 ci 档（当前 tier={tier}）")
        names = [s["name"] for s in data.get("shards", []) if s.get("prePush")]
        if not names:
            raise EnvProblem("gates.json 的 shards 里没有 prePush 标记的分片")
        return [e for e in entries if e.get("shard") in names]
    if shard:
        shards = [s["name"] for s in data.get("shards", [])]
        if shard not in shards:
            raise EnvProblem(f"未知分片：{shard!r}；已声明：{', '.join(shards)}")
        return [e for e in entries if e.get("shard") == shard]
    return entries


def load_manifest() -> dict:
    # 清单读不到 / 不是合法 JSON 都是「这次执行没做成」，由共享层抛 EnvProblem、
    # main 映射成退出码 2（#204）；读取机械段收口到 `_common.load_json_file`（#312）。
    # 下面的形状校验是本执行器独有的策略：形状不对就当清单读不成，不能带着坏清单去跑门禁。
    data = load_json_file(MANIFEST)

    shards = [s["name"] for s in data.get("shards", [])]
    for tier in ("ci", "manual", "support"):
        for entry in data.get(tier, []):
            for key in ("name", "cmd", "why"):
                if key not in entry:
                    raise EnvProblem(f"{tier} 里的条目缺 `{key}` 字段：{entry}")
            if not isinstance(entry["cmd"], list):
                raise EnvProblem(f"{tier} 的 `{entry['name']}` 的 cmd 必须是数组")
            if tier == "ci" and entry.get("shard") not in shards:
                raise EnvProblem(f"ci 条目 `{entry['name']}` 的 shard 不在 shards 声明里")
    return data


def print_list(data: dict) -> None:
    print(f"门禁清单：{MANIFEST.relative_to(ROOT)}  (version {data.get('version')})")
    for tier in ("ci", "manual", "support"):
        items = data.get(tier, [])
        if not items:
            continue
        print(f"\n[{tier}]  {len(items)} 条")
        for i, entry in enumerate(items, 1):
            shard = f" <{entry['shard']}>" if entry.get("shard") else ""
            print(f"  {i}. {entry['name']}{shard}")
            print(f"     防：{entry['why']}")


def run_entry(entry: dict, runner=None) -> tuple[bool, float, str]:
    started = time.time()
    cmd = resolve_cmd(entry["cmd"])
    try:
        proc = run(cmd, runner=runner)
    except EnvProblem as e:
        return False, time.time() - started, f"命令跑不起来：{e}"
    elapsed = time.time() - started
    out = (proc.stdout or "") + (proc.stderr or "")
    if cmd != entry["cmd"]:
        # 只在真的改写了解释器时透出一行，方便排查「为什么和清单里写的不一样」
        out = f"(解释器解析为 {cmd[0]})\n{out}"
    return proc.returncode == 0, elapsed, out.strip()


def self_test() -> int:
    """#204：钉住「清单问题退 2、子进程走共享层、门禁红退 1」三条契约。"""
    failures: list[str] = []
    passed = 0

    def check(label: str, ok: bool, detail: str = "") -> None:
        nonlocal passed
        if ok:
            passed += 1
            print(f"[绿] 自检：{label}")
        else:
            failures.append(label)
            print(f"[红] 自检：{label}（{detail}）", file=sys.stderr)

    # 清单读不到必须是**返回值 2**，不是 SystemExit，也不是 traceback 退 1。
    real_manifest = MANIFEST
    globals()["MANIFEST"] = ROOT / "scripts" / "__missing_gates.json"
    try:
        try:
            with contextlib.redirect_stderr(io.StringIO()):
                missing_exit, raised = main(["ci"]), None
        except BaseException as exc:  # noqa: BLE001 - 要把「抛出去」本身记成红
            missing_exit, raised = None, type(exc).__name__
    finally:
        globals()["MANIFEST"] = real_manifest
    check("#204：清单读不到退 2（返回值而非 SystemExit）", missing_exit == ENV_FAIL, f"实际 {missing_exit!r}/{raised}")

    # 子进程必须走共享层：run_entry 能注入 runner，绿/红两种结果都按退出码判定。
    # cmd 首项用中性命令名：python / bash 会被 resolve_cmd 改写并带出前缀行，
    # 本用例钉的是 runner 注入与退出码语义，与解释器解析（#205 用例）互不干扰。
    def fake_green(argv, **kwargs):
        return subprocess.CompletedProcess(argv, 0, "ok", "")

    def fake_red(argv, **kwargs):
        return subprocess.CompletedProcess(argv, 3, "boom", "")

    try:
        ok_green, _, out_green = run_entry({"cmd": ["echo", "-c", "x"]}, runner=fake_green)
        ok_red, _, out_red = run_entry({"cmd": ["echo", "-c", "x"]}, runner=fake_red)
        wired = ok_green and out_green == "ok" and not ok_red and "boom" in out_red
    except Exception:  # noqa: BLE001 - 未接共享层时 run_entry 不认识 runner
        wired = False
    check("#204：run_entry 走共享层 run() 且按退出码判定", wired)

    runner_src = Path(__file__).read_text(encoding="utf-8")
    check("#204：执行器不再有本地 subprocess.run 样板", "subprocess.run" + "(" not in runner_src)
    check("#204：退出码常量与文档一致", (OK, CONTENT_FAIL, ENV_FAIL) == (0, 1, 2))
    # #312：清单的读+解析收口到共享层，本文件只保留形状校验（三段必填 / shard 归属）。
    check(
        "#312：清单读取机械前缀走共享层 load_json_file",
        "load_json_file(MANIFEST)" in runner_src
        and "MANIFEST.read_text" + "(encoding=" not in runner_src,
    )
    # 自检里直接读清单做比对也必须走共享层：内联读法没有 EnvProblem 映射，清单缺失/损坏时
    # 以 traceback 退 1，而 load_manifest() 同一失败退 2——这正是 #312 要消灭的分叉。
    # 判据：本文件里不许有一行同时出现 `json.loads` 与清单文件名（清单名拆开写，避免自命中）。
    gate_name = "gates" + ".json"
    inline_manifest = [
        line.strip() for line in runner_src.splitlines() if "json.loads" in line and gate_name in line
    ]
    check(
        "#312：自检不内联解析清单（失败须与 load_manifest 同退 2）",
        inline_manifest == [],
        f"实际 {inline_manifest}",
    )

    # ---- #205：解释器与分片名收口的守卫 ----
    # 断言瞄准的字面量用拼接写法：本文件自己不许含那个字面量，否则验收 grep
    # （全仓只剩探针一处）会先把本文件报出来。
    inline_resolve = "command -v " + "python"

    try:
        resolved = resolve_cmd(["python", "-c", "pass"])
        py_mapped = (
            resolved[0] == resolve_python() and resolved[0] != "python" and resolved[1:] == ["-c", "pass"]
        )
    except EnvProblem:
        py_mapped = False
    check("#205：resolve_cmd 把裸 python 换成解析后的绝对路径", py_mapped)

    real_which = shutil.which

    def no_python(name):
        return None

    shutil.which = no_python
    try:
        fail_closed = False
        try:
            resolve_cmd(["python", "-c", "pass"])
        except EnvProblem:
            fail_closed = True
    finally:
        shutil.which = real_which
    check("#205：PATH 无解释器时 resolve_cmd fail-closed（抛 EnvProblem，不拿裸 python 硬跑）", fail_closed)

    # 端到端口径：main 的执行循环也要把 EnvProblem 映射成退 2（环境问题），
    # 不许以 traceback 退 1 冒充门禁红（与上面 resolve_cmd 单测互补）。
    shutil.which = no_python
    try:
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            no_py_exit = main(["ci"])
    finally:
        shutil.which = real_which
    check("#205：PATH 无解释器时执行器退 2（环境问题，不伪装成门禁红）", no_py_exit == ENV_FAIL, f"实际 {no_py_exit!r}")

    probe_rel = "scripts/resolve_python.sh"
    shell_consumers = (
        ".githooks/commit-msg",
        ".githooks/pre-push",
        "tools/android-mtp-probe/build.sh",
    )
    for rel in shell_consumers:
        src = (ROOT / rel).read_text(encoding="utf-8")
        check(f"#205：{rel} 不再自带解释器查找（该字面量只活在探针里）", inline_resolve not in src)
        check(f"#205：{rel} 引用共享探针 {probe_rel}", probe_rel in src)
    tracked = git_ls_files()
    probe_hits = [
        p
        for p in tracked
        if (ROOT / p).is_file() and inline_resolve in (ROOT / p).read_text(encoding="utf-8", errors="ignore")
    ]
    check("#205：全仓解释器查找只剩探针一处", probe_hits == [probe_rel], f"实际 {probe_hits}")

    pre_push_src = (ROOT / ".githooks/pre-push").read_text(encoding="utf-8")
    check("#205：pre-push 不再写死 --shard（分片名归 gates.json 管）", "--shard" not in pre_push_src)
    check("#205：pre-push 走 --prepush 口径", "ci --prepush" in pre_push_src)

    gates = load_json_file(ROOT / "scripts" / "gates.json")
    marked = [s["name"] for s in gates.get("shards", []) if s.get("prePush")]
    check("#205：gates.json 分片声明带 prePush 标记", len(marked) >= 1, f"实际 {marked}")
    try:
        selected = select_entries(gates, "ci", shard=None, prepush=True)
        filter_ok = selected == [e for e in gates["ci"] if e.get("shard") in marked] and bool(selected)
    except EnvProblem:
        filter_ok = False
    check("#205：--prepush 只取 prePush 标记分片下的 ci 条目", filter_ok)
    unmarked = copy.deepcopy(gates)
    for s in unmarked["shards"]:
        s.pop("prePush", None)
    try:
        select_entries(unmarked, "ci", shard=None, prepush=True)
        marker_guards = False
    except EnvProblem:
        marker_guards = True
    check("#205：分片丢掉 prePush 标记时 --prepush 报环境错（不伪装成清单空）", marker_guards)
    try:
        select_entries(gates, "ci", shard="staitc", prepush=False)
        typo_guards = False
    except EnvProblem as e:
        typo_guards = "未知分片" in str(e)
    check("#205：未知分片名仍报 EnvProblem 且列出已声明分片（#243 行为不回归）", typo_guards)

    meta_src = (ROOT / "scripts" / "check_gates_manifest.py").read_text(encoding="utf-8")
    check(
        "#205：元门禁的解释器解析走共享 resolver（不再自带 sys.executable 兜底串）",
        "run([resolve_python()" in meta_src and "[sys.executable or" not in meta_src,
    )

    if failures:
        print(f"[红] 自检失败 {len(failures)} 项：{', '.join(failures)}", file=sys.stderr)
        return ENV_FAIL
    print(f"[绿] 自检 {passed} 项通过")
    return OK


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="按 scripts/gates.json 执行门禁")
    ap.add_argument("tier", nargs="?", default="ci", choices=["ci", "manual", "support"])
    ap.add_argument("--list", action="store_true", help="只打印清单，不执行")
    ap.add_argument("--shard", help="只跑 ci 档里指定分片")
    ap.add_argument(
        "--prepush",
        action="store_true",
        help="跑 gates.json 里 prePush 标记的分片（.githooks/pre-push 的口径，分片名不写死在钩子里）",
    )
    ap.add_argument("--fail-fast", action="store_true", help="第一条红即停")
    ap.add_argument("--self-test", action="store_true", help="跑执行器内置回归用例")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()

    if args.prepush and args.shard:
        print("--prepush 与 --shard 互斥：prePush 标记本身就是分片选择", file=sys.stderr)
        return ENV_FAIL

    try:
        data = load_manifest()
    except EnvProblem as exc:
        print(f"[清单错误] {exc}", file=sys.stderr)
        return ENV_FAIL
    if args.list:
        print_list(data)
        return OK

    try:
        entries = select_entries(data, args.tier, shard=args.shard, prepush=args.prepush)
    except EnvProblem as exc:
        print(str(exc), file=sys.stderr)
        return ENV_FAIL

    if not entries:
        print(f"[{args.tier}] 没有要跑的门禁。", file=sys.stderr)
        return ENV_FAIL

    print(f"== 门禁 [{args.tier}]  共 {len(entries)} 条 ==")
    failures: list[str] = []
    try:
        for entry in entries:
            ok, elapsed, out = run_entry(entry)
            flag = "PASS" if ok else "FAIL"
            print(f"\n[{flag}] {entry['name']}  ({elapsed:.2f}s)")
            if out:
                for line in out.splitlines():
                    print(f"    {line}")
            if not ok:
                failures.append(entry["name"])
                if args.fail_fast:
                    break
    except EnvProblem as exc:
        # #205：执行中才发现 PATH 上一个解释器都没有（清单 cmd 是裸 python、由本执行器
        # 统一解析），属「这次执行没做成」：退 2，不许以 traceback 退 1 冒充门禁红。
        print(f"[环境问题] {exc}", file=sys.stderr)
        return ENV_FAIL

    print()
    if failures:
        print(f"== 红 {len(failures)} 条：{', '.join(failures)} ==", file=sys.stderr)
        return CONTENT_FAIL
    print(f"== 全绿：{len(entries)} 条 ==")
    return OK


if __name__ == "__main__":
    sys.exit(main())
