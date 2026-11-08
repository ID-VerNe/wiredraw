#!/usr/bin/env python3
"""门禁执行器。

清单的唯一事实来源是 `scripts/gates.json`，本机与 CI 跑同一份、顺序也一样。
不要在别处再写一份命令数组。

用法：
    python scripts/run_gates.py --list          # 看档位与顺序
    python scripts/run_gates.py ci              # 跑 ci 档（= 本机全量）
    python scripts/run_gates.py ci --fail-fast  # 见到红就停
    python scripts/run_gates.py ci --shard static
    python scripts/run_gates.py manual          # 跑需要真设备/真工具链的那一档
    python scripts/run_gates.py --self-test     # 执行器内置回归用例（不进清单）

退出码：0 全绿 / 1 有门禁红 / 2 清单或环境问题。
"""
from __future__ import annotations

import argparse
import contextlib
import io
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

from _common import CONTENT_FAIL, ENV_FAIL, OK, EnvProblem, run

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
    """把 cmd 里的 shell 换成解析过的绝对路径；其余命令原样返回。"""
    if cmd and cmd[0] in ("bash", "sh"):
        return [resolve_bash()] + cmd[1:]
    return cmd


def load_manifest() -> dict:
    # 清单读不到 / 形状不合规都是「这次执行没做成」，抛 EnvProblem 由 main 映射成退出码 2（#204）。
    try:
        text = MANIFEST.read_text(encoding="utf-8")
    except OSError as e:
        raise EnvProblem(f"找不到 {MANIFEST}（{e.strerror or e}）") from e
    try:
        data = json.loads(text)
    except json.JSONDecodeError as e:
        raise EnvProblem(f"gates.json 不是合法 JSON：{e}") from e

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
    def fake_green(argv, **kwargs):
        return subprocess.CompletedProcess(argv, 0, "ok", "")

    def fake_red(argv, **kwargs):
        return subprocess.CompletedProcess(argv, 3, "boom", "")

    try:
        ok_green, _, out_green = run_entry({"cmd": ["python", "-c", "x"]}, runner=fake_green)
        ok_red, _, out_red = run_entry({"cmd": ["python", "-c", "x"]}, runner=fake_red)
        wired = ok_green and out_green == "ok" and not ok_red and "boom" in out_red
    except Exception:  # noqa: BLE001 - 未接共享层时 run_entry 不认识 runner
        wired = False
    check("#204：run_entry 走共享层 run() 且按退出码判定", wired)

    runner_src = Path(__file__).read_text(encoding="utf-8")
    check("#204：执行器不再有本地 subprocess.run 样板", "subprocess.run" + "(" not in runner_src)
    check("#204：退出码常量与文档一致", (OK, CONTENT_FAIL, ENV_FAIL) == (0, 1, 2))

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
    ap.add_argument("--fail-fast", action="store_true", help="第一条红即停")
    ap.add_argument("--self-test", action="store_true", help="跑执行器内置回归用例")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()

    try:
        data = load_manifest()
    except EnvProblem as exc:
        print(f"[清单错误] {exc}", file=sys.stderr)
        return ENV_FAIL
    if args.list:
        print_list(data)
        return OK

    entries = data.get(args.tier, [])
    if args.shard:
        # 分片名先对清单校验（issue #205）：未知名字若按「过滤后为空」处理，只会得到
        # 「没有要跑的门禁」这种误导性提示——`.githooks/pre-push` 写死 `--shard static`
        # 时，分片一旦改名就没人看得出是名字错了还是清单空了。
        shards = [s["name"] for s in data.get("shards", [])]
        if args.shard not in shards:
            print(
                f"未知分片：{args.shard!r}；已声明：{', '.join(shards)}",
                file=sys.stderr,
            )
            return ENV_FAIL
        entries = [e for e in entries if e.get("shard") == args.shard]

    if not entries:
        print(f"[{args.tier}] 没有要跑的门禁。", file=sys.stderr)
        return ENV_FAIL

    print(f"== 门禁 [{args.tier}]  共 {len(entries)} 条 ==")
    failures: list[str] = []
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

    print()
    if failures:
        print(f"== 红 {len(failures)} 条：{', '.join(failures)} ==", file=sys.stderr)
        return CONTENT_FAIL
    print(f"== 全绿：{len(entries)} 条 ==")
    return OK


if __name__ == "__main__":
    sys.exit(main())
