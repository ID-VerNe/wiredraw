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

import argparse
import sys
from pathlib import Path

from _common import ENV_FAIL, EnvProblem, git_ls_files

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
    """被跟踪文件清单；`-z` 与失败语义统一由 `_common.git_ls_files` 负责（#204）。"""
    return git_ls_files()


def main() -> int:
    problems: list[str] = []
    try:
        files = tracked_files()
    except EnvProblem as exc:
        print(f"[红] {exc}", file=sys.stderr)
        return ENV_FAIL

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
        except OSError as e:
            # 被跟踪文件却读不到，说明索引与磁盘已经脱节；静默跳过会让门禁假绿（#203）。
            problems.append(f"被跟踪文件读不到：{rel}（{e.strerror or '未知错误'}）")
            continue
        if size > MAX_BYTES:
            problems.append(f"被跟踪的大文件：{rel}（{size / 1048576:.1f} MB，上限 5 MB）")

    if problems:
        for p in problems:
            print(f"[红] {p}", file=sys.stderr)
        return 1

    print(f"[绿] 已跟踪 {len(files)} 个文件：无 vendored 目录、忽略规则齐全、无超 5 MB 的大文件")
    return 0


def _cli() -> argparse.Namespace:
    """声明参数，让元门禁的 `--help` 自证层覆盖本脚本（#203）。

    本脚本没有可选开关；加 argparse 的唯一目的是让拼错参数变成 `exit 2`，
    而不是被 `main()` 静默忽略后照常跑绿。
    """
    ap = argparse.ArgumentParser(description="守住第三方参考库不进版本控制")
    return ap.parse_args()


if __name__ == "__main__":
    _cli()
    sys.exit(main())
