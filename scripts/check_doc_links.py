#!/usr/bin/env python3
"""文档里的相对链接必须能落到磁盘上。

这套协作里文档是「项目记忆」，链接断了等于记忆断了：新人按 AGENTS.md 的索引点进去
拿到 404，就退回「问人」而不是「读文档」。所以这条盯住：

- 所有被 git 跟踪的 `.md` 里的相对链接（含图片）目标文件必须存在；
- 链接路径按该 md 文件所在目录解析（不是仓库根）。

跳过：`http(s)://`、`mailto:`、纯锚点 `#xxx`、以及代码块里的内容（那是示例不是链接）。
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

LINK = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
FENCE = re.compile(r"^(```|~~~).*?^\1", re.S | re.M)


def tracked_markdown() -> list[Path]:
    out = subprocess.run(
        ["git", "ls-files", "*.md"],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if out.returncode != 0:
        raise SystemExit("[红] git ls-files 失败")
    return [ROOT / line for line in out.stdout.splitlines() if line.strip()]


def main() -> int:
    problems: list[str] = []
    checked = 0
    md_files = tracked_markdown()

    for md in md_files:
        if not md.is_file():
            continue
        text = md.read_text(encoding="utf-8", errors="replace")
        text = FENCE.sub("", text)  # 代码块里的括号不是链接
        for raw in LINK.findall(text):
            target = raw.strip()
            if not target or target.startswith(("#", "http://", "https://", "mailto:", "tel:")):
                continue
            if target.startswith("<") and target.endswith(">"):
                target = target[1:-1]
            path_part = target.split("#", 1)[0]
            if not path_part:
                continue
            checked += 1
            resolved = (md.parent / path_part).resolve()
            if not resolved.exists():
                rel = md.relative_to(ROOT)
                problems.append(f"{rel} → 断链：{target}")

    if problems:
        for p in problems:
            print(f"[红] {p}", file=sys.stderr)
        return 1

    print(f"[绿] {len(md_files)} 个 md、{checked} 条相对链接全部可达")
    return 0


if __name__ == "__main__":
    sys.exit(main())
