#!/usr/bin/env python3
"""敏感信息的写盘改写器（#313 从 `check_sensitive.py` 拆出）。

整条门禁链上**唯一**会写工作区文件的地方就在这里（`_write_text` 的 `write_bytes`）。
ci 档跑的是 `--check`、`.githooks/commit-msg` 跑的是 `--message-file`，都走不到本模块；
`--fix` 只能人工显式执行（#109 要求的能力），且不会 `git add` / `git commit`。
"""
from __future__ import annotations

from pathlib import Path

from sensitive_scan import Hit, _read_text_ex, scan_text

ROOT = Path(__file__).resolve().parent.parent


def _write_text(path: Path, text: str, encoding: tuple[str, bytes]) -> None:
    """按读入时的编码与 BOM 回写，避免 `--fix` 改写非命中区域（#117 评审非阻断 3）。"""
    codec, bom = encoding
    path.write_bytes(bom + text.encode(codec))


def _fix(hits: list[Hit]) -> list[str]:
    """按文件、按行、从后往前替换，保持行号与换行风格不变。"""
    changed: list[str] = []
    by_file: dict[str, list[Hit]] = {}
    for hit in hits:
        by_file.setdefault(hit.path, []).append(hit)

    for rel, file_hits in sorted(by_file.items()):
        path = ROOT / rel
        text, encoding = _read_text_ex(path)
        if text is None:
            continue
        lines = text.split("\n")
        by_line: dict[int, list[Hit]] = {}
        for hit in file_hits:
            by_line.setdefault(hit.line, []).append(hit)
        for no, line_hits in by_line.items():
            line = lines[no - 1]
            for hit in sorted(line_hits, key=lambda h: h.start, reverse=True):
                line = line[:hit.start] + hit.placeholder() + line[hit.end:]
                print(f"[改写] {rel}:{no} [{hit.category}] → {hit.placeholder()}")
            lines[no - 1] = line
        new_text = "\n".join(lines)
        if new_text != text:
            _write_text(path, new_text, encoding)
            changed.append(rel)
    return changed


def _apply_fix_text(text: str, rel: str, repo_slug: str | None) -> tuple[str, list[Hit]]:
    """自检用：按 `--fix` 的同一规则在内存里替换，返回 (新文本, 命中)。"""
    hits = scan_text(text, rel, repo_slug)
    lines = text.split("\n")
    for hit in sorted(hits, key=lambda h: (h.line, -h.start)):
        line = lines[hit.line - 1]
        lines[hit.line - 1] = line[:hit.start] + hit.placeholder() + line[hit.end:]
    return "\n".join(lines), hits
