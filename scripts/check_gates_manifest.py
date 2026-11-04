#!/usr/bin/env python3
"""元门禁：`scripts/gates.json`、`AGENTS.md` 的生成段、磁盘上的脚本文件三者零漂移。

手改 AGENTS.md 里的门禁表格会被这条打红。改清单之后跑：

    python scripts/check_gates_manifest.py --write

它会用清单重写 AGENTS.md 里 `<!-- gates:ci:begin -->` 与 `<!-- gates:ci:end -->` 之间那一段。

顺带校验：ci 条目引用的脚本确实在磁盘上，且没有重复的门禁名。
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "scripts" / "gates.json"
AGENTS = ROOT / "AGENTS.md"
BEGIN = "<!-- gates:ci:begin -->"
END = "<!-- gates:ci:end -->"


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
        "| # | 分片 | 门禁 | 防什么 |",
        "|---|---|---|---|",
    ]
    for i, entry in enumerate(ci, 1):
        shard = entry.get("shard", "-")
        lines.append(f"| {i} | `{shard}` | `{entry['name']}` | {entry['why']} |")

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


def main() -> int:
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

    problems = check_scripts_exist(data)
    if problems:
        for p in problems:
            print(f"[红] {p}", file=sys.stderr)
        return 1

    print(f"[绿] 清单一致：{len(data.get('ci', []))} 个 ci 门禁，脚本均存在，无重名")
    return 0


if __name__ == "__main__":
    sys.exit(main())
