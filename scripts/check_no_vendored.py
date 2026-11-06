#!/usr/bin/env python3
"""守住「别人的参考库不进版本控制」这条约定。

本项目早期审计过 15 个第三方索尼相机控制仓库，它们只作本地参考，不随本项目分发。
`git add` 时一个手滑（`git add .`）就会把它们吸进来，而 .gitignore 挡不住显式 add。

这条门禁同时盯三件事：
1. 15 个 vendored 目录名一个都不在 `git ls-files` 里；
2. 每个名字都在 `.gitignore` 里有一条规则（防止有人删掉忽略项）；
3. 没有被跟踪的大文件（> 5 MB）—— GitHub 单文件上限 100 MB，原始抓包就是靠这条挡在外面的
   （实测体积见 docs/archive/INDEX.md）。
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

VENDORED = [
    "alpha-fairy",
    "alpharemote",
    "Camera-Remote-API",
    "furble",
    "kz-remote-api",
    "locana",
    "ofxSonyRemoteCamera",
    "RemoteCam",
    "RemoteCamera",
    "sony_camera_api",
    "sony_camera_ble_remote",
    "sony-camera-api",
    "SonyCameraRemoteAPI",
    "sonypy",
    "sony-remote",
]

MAX_BYTES = 5 * 1024 * 1024


def tracked_files() -> list[str]:
    out = subprocess.run(
        ["git", "ls-files"],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if out.returncode != 0:
        raise SystemExit("[红] git ls-files 失败，确认当前目录是 git 仓库")
    return [line for line in out.stdout.splitlines() if line.strip()]


def main() -> int:
    problems: list[str] = []
    files = tracked_files()

    for name in VENDORED:
        hits = [f for f in files if f == name or f.startswith(name + "/")]
        if hits:
            problems.append(f"第三方参考库被跟踪了：{name}（{len(hits)} 个文件）")

    ignore = (ROOT / ".gitignore").read_text(encoding="utf-8") if (ROOT / ".gitignore").is_file() else ""
    for name in VENDORED:
        if f"/{name}/" not in ignore:
            problems.append(f".gitignore 缺少对 `/{name}/` 的忽略规则")

    for rel in files:
        p = ROOT / rel
        try:
            size = p.stat().st_size
        except OSError:
            continue
        if size > MAX_BYTES:
            problems.append(f"被跟踪的大文件：{rel}（{size / 1048576:.1f} MB，上限 5 MB）")

    if problems:
        for p in problems:
            print(f"[红] {p}", file=sys.stderr)
        return 1

    print(f"[绿] 已跟踪 {len(files)} 个文件：无 vendored 目录、忽略规则齐全、无超 5 MB 的大文件")
    return 0


if __name__ == "__main__":
    sys.exit(main())
