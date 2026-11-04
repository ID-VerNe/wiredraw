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

退出码：0 全绿 / 1 有门禁红 / 2 清单或环境问题。
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

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


def die(msg: str):
    print(f"[清单错误] {msg}", file=sys.stderr)
    raise SystemExit(2)


def load_manifest() -> dict:
    if not MANIFEST.is_file():
        die(f"找不到 {MANIFEST}")
    try:
        data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        die(f"gates.json 不是合法 JSON：{e}")

    shards = [s["name"] for s in data.get("shards", [])]
    for tier in ("ci", "manual", "support"):
        for entry in data.get(tier, []):
            for key in ("name", "cmd", "why"):
                if key not in entry:
                    die(f"{tier} 里的条目缺 `{key}` 字段：{entry}")
            if not isinstance(entry["cmd"], list):
                die(f"{tier} 的 `{entry['name']}` 的 cmd 必须是数组")
            if tier == "ci" and entry.get("shard") not in shards:
                die(f"ci 条目 `{entry['name']}` 的 shard 不在 shards 声明里")
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


def run_entry(entry: dict) -> tuple[bool, float, str]:
    started = time.time()
    cmd = resolve_cmd(entry["cmd"])
    try:
        proc = subprocess.run(
            cmd,
            cwd=str(ROOT),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except FileNotFoundError as e:
        return False, time.time() - started, f"命令不存在：{e}"
    elapsed = time.time() - started
    out = (proc.stdout or "") + (proc.stderr or "")
    if cmd != entry["cmd"]:
        # 只在真的改写了解释器时透出一行，方便排查「为什么和清单里写的不一样」
        out = f"(解释器解析为 {cmd[0]})\n{out}"
    return proc.returncode == 0, elapsed, out.strip()


def main() -> int:
    ap = argparse.ArgumentParser(description="按 scripts/gates.json 执行门禁")
    ap.add_argument("tier", nargs="?", default="ci", choices=["ci", "manual", "support"])
    ap.add_argument("--list", action="store_true", help="只打印清单，不执行")
    ap.add_argument("--shard", help="只跑 ci 档里指定分片")
    ap.add_argument("--fail-fast", action="store_true", help="第一条红即停")
    args = ap.parse_args()

    data = load_manifest()
    if args.list:
        print_list(data)
        return 0

    entries = data.get(args.tier, [])
    if args.shard:
        entries = [e for e in entries if e.get("shard") == args.shard]

    if not entries:
        print(f"[{args.tier}] 没有要跑的门禁。", file=sys.stderr)
        return 2

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
        return 1
    print(f"== 全绿：{len(entries)} 条 ==")
    return 0


if __name__ == "__main__":
    sys.exit(main())
