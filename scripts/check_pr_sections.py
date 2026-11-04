#!/usr/bin/env python3
"""PR 正文的分段结构校验（`support` 档）。

只查**结构**，不查内容真假 —— 内容真假只有评审者能判（见 `doc/REVIEW_DISPATCH.md`）。
盯住三件事：

1. 必填分段齐全：`## 问题` / `## 改动` / `## 测试`（或 `## 验证`）/ `## 影响面`；
2. 有关闭关键词：`Closes #NN` 或 `Refs #NN`；
3. diff 命中门禁链时，必须有 `## 门禁改动` 段。

用法：
    python scripts/check_pr_sections.py --pr 12
    python scripts/check_pr_sections.py --body-file .tmp-body.md --files scripts/gates.json
    python scripts/check_pr_sections.py            # 无 PR 上下文 → SKIP，退出 0

无 PR 上下文时 SKIP 是设计：这条需要 GitHub 上下文，放进 `ci` 档会让本地每次
`run_gates.py ci` 都得带 `--pr`，把最便宜的一档弄脆。

但 **有 PR 上下文却取不全**（事件载荷缺 base/head、`git diff` 跑不通）时**必须红**：
那种情况下 `files` 会是空的、门禁链判定恒为否，同一份正文会因环境抖动从红变绿。

退出码：0 绿或 SKIP / 1 红 / 2 环境问题。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 命中任一前缀即视为「动了门禁链」，要求正文有 `## 门禁改动` 段。
GATE_TRIGGERS = (
    "scripts/check_",
    "scripts/gates.json",
    "scripts/run_gates.py",
    ".github/workflows/ci.yml",
    ".githooks/",
)

# (显示名, 可接受的段名候选)；每行一个「必填段」。
REQUIRED = [
    ("问题", ("问题",)),
    ("改动", ("改动",)),
    ("测试 / 验证", ("测试", "验证")),
    ("影响面", ("影响面",)),
]

HEADING = re.compile(r"^##\s+(.+?)\s*$", re.M)
CLOSING = re.compile(r"\b(Closes|Refs)\s+#(\d+)", re.I)
GATE_SECTION = "门禁改动"


def headings(body: str) -> list[str]:
    return [m.group(1).strip() for m in HEADING.finditer(body)]


def has_section(body: str, names: tuple[str, ...]) -> bool:
    """段名按前缀匹配：`## 测试（门禁原始输出）` 也算 `测试`。"""
    for h in headings(body):
        for n in names:
            if h == n or h.startswith(n):
                return True
    return False


def gate_hits(files: list[str]) -> list[str]:
    return [f for f in files if any(f.startswith(t) or f == t for t in GATE_TRIGGERS)]


def check_body(body: str, files: list[str]) -> list[str]:
    problems: list[str] = []

    for label, names in REQUIRED:
        if not has_section(body, names):
            problems.append(f"缺必填段：`## {label}`")

    if not CLOSING.search(body):
        problems.append("缺关闭关键词：`Closes #NN` 或 `Refs #NN`（行为类必须 `Refs`）")

    hits = gate_hits(files)
    if hits and not has_section(body, (GATE_SECTION,)):
        problems.append(
            f"diff 命中门禁链（{len(hits)} 个文件：{', '.join(hits[:3])}"
            f"{' 等' if len(hits) > 3 else ''}）但正文缺 `## {GATE_SECTION}` 段"
        )

    return problems


# ---------------------------------------------------------------- 取上下文


def from_gh(pr: int) -> tuple[str, list[str]]:
    proc = subprocess.run(
        ["gh", "pr", "view", str(pr), "--json", "body,files"],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if proc.returncode != 0:
        print(f"[红] gh 返回 {proc.returncode}：{(proc.stderr or proc.stdout).strip()}", file=sys.stderr)
        raise SystemExit(2)
    data = json.loads(proc.stdout)
    files = [f.get("path", "") for f in data.get("files") or [] if f.get("path")]
    return data.get("body") or "", files


class EnvProblem(Exception):
    """有 PR 上下文但取不全。**必须红（退出 2），不许降级成绿。**

    「取不到改动文件 → `files` 为空 → 门禁链判定恒为否 → 输出绿」是最恶劣的失败形态：
    同一份正文会因为环境抖动从红变绿，正好是本仓库「把 SKIP 当 PASS」那条误判。
    """


def from_event() -> tuple[str, list[str]] | None:
    """CI 里从 GitHub 的事件载荷取 PR 正文与改动文件。

    没有 PR 上下文（非 pull_request 事件）返回 None → 上层 SKIP。
    有 PR 上下文但 base/head 缺失、或 `git diff` 跑不通 → 抛 `EnvProblem` → 上层红。
    """
    path = os.environ.get("GITHUB_EVENT_PATH")
    if not path or not Path(path).is_file():
        return None
    try:
        event = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as e:
        raise EnvProblem(f"读不到事件载荷 {path}：{e}") from e
    pr = event.get("pull_request")
    if not isinstance(pr, dict):
        return None

    body = pr.get("body") or ""
    base = (pr.get("base") or {}).get("sha")
    head = (pr.get("head") or {}).get("sha")
    if not (base and head):
        raise EnvProblem(
            "事件载荷里缺 pull_request.base.sha / head.sha，取不到改动文件；"
            "此时无法判断是否命中门禁链，不能报绿"
        )

    proc = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...{head}"],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if proc.returncode != 0:
        raise EnvProblem(
            f"git diff {base}...{head} 失败（{proc.returncode}）："
            f"{(proc.stderr or proc.stdout).strip()[:200]}"
        )
    files = [x.strip() for x in proc.stdout.splitlines() if x.strip()]
    return body, files


# ---------------------------------------------------------------- 自检

# 常量钉住四条用例，不依赖磁盘上的样本文件 —— 样本一进仓库就会腐烂。
# 注意：每条样本只带**一个**缺陷。`SAMPLE_MISSING` 特意加了 `Closes`，
# 否则它会因为「缺关闭关键词」也变红 —— 那样即使断言的实现被改坏，
# 用例照样红，等于测不出东西（变异验证时真踩到了这一点）。
SAMPLE_MISSING = """## 问题

缺 `## 测试` 与 `## 影响面`，关闭关键词是齐的，红只能来自缺段。

## 改动

样本用，证明缺段必红。

Closes #3
"""

SAMPLE_FULL = """## 问题

四段齐全、有 `Closes`，且未命中门禁链。

## 改动

`doc/REVIEW_DISPATCH.md`
- 样本用。

## 测试

```
[绿] 全部可达
```

## 影响面

仅文档。

Closes #3
"""

SAMPLE_GATE = """## 问题

四段齐全 + 门禁改动段，命中门禁链时应转绿。

## 改动

`scripts/gates.json`
- 样本用。

## 测试

```
[绿] 清单一致
```

## 门禁改动

`scripts/gates.json`
- 改前：support 为空；改后：一条。未打补丁 base 上不存在该条目。

## 影响面

仅清单。

Refs #3
"""


def self_test() -> int:
    cases = [
        ("缺段必红", SAMPLE_MISSING, [], 1),
        ("段齐必绿", SAMPLE_FULL, [], 0),
        ("命中门禁链但缺 `## 门禁改动` 必红", SAMPLE_FULL, ["scripts/gates.json"], 1),
        ("命中门禁链且写了 `## 门禁改动` 必绿", SAMPLE_GATE, ["scripts/gates.json"], 0),
        ("只改文档不触发门禁链要求", SAMPLE_FULL, ["doc/PR_GUIDE.md"], 0),
    ]
    checks: list[tuple[str, bool, str]] = []
    for name, body, files, expected in cases:
        got = 1 if check_body(body, files) else 0
        detail = "\n        ".join(check_body(body, files))
        checks.append((f"{name}（期望退出 {expected}，实际 {got}）", got == expected, detail))

    # 事件载荷用例：这条钉住「取不到改动文件时不许报绿」。
    # 原来的实现是 files 留空 + 照常输出绿，同一份正文会因环境抖动从红变绿。
    with tempfile.TemporaryDirectory() as tmp:
        ev = Path(tmp) / "event.json"

        def probe(payload: dict) -> str:
            ev.write_text(json.dumps(payload), encoding="utf-8")
            saved = os.environ.get("GITHUB_EVENT_PATH")
            os.environ["GITHUB_EVENT_PATH"] = str(ev)
            try:
                return "ok" if from_event() is not None else "none"
            except EnvProblem:
                return "red"
            finally:
                if saved is None:
                    os.environ.pop("GITHUB_EVENT_PATH", None)
                else:
                    os.environ["GITHUB_EVENT_PATH"] = saved

        got = probe({"pull_request": {"body": SAMPLE_FULL}})
        checks.append(
            ("事件载荷缺 base/head 时必须红（不许降级成绿）", got == "red", f"实际 {got}")
        )

        got = probe({"ref": "refs/heads/main"})
        checks.append(("非 PR 事件仍是 SKIP（返回 None）", got == "none", f"实际 {got}"))

    bad = 0
    for name, ok, detail in checks:
        flag = "PASS" if ok else "FAIL"
        print(f"[{flag}] {name}")
        if not ok:
            bad += 1
            if detail:
                print(f"        {detail}")

    if bad:
        print(f"== 自检红 {bad} 项 ==", file=sys.stderr)
        return 2
    print(f"== 自检全绿：{len(checks)} 项 ==")
    return 0


# ---------------------------------------------------------------- 入口


def main() -> int:
    ap = argparse.ArgumentParser(description="校验 PR 正文的必填分段（support 档）")
    ap.add_argument("--pr", type=int, help="PR 编号（走 gh）")
    ap.add_argument("--body-file", help="本地自查：直接给正文文件")
    ap.add_argument("--files", nargs="*", default=None, help="本地自查：改动文件列表")
    ap.add_argument("--self-test", action="store_true", help="跑内置自检，不需要 gh / 网络")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    if args.body_file:
        p = Path(args.body_file)
        if not p.is_file():
            print(f"[红] 找不到 {p}", file=sys.stderr)
            return 2
        body = p.read_text(encoding="utf-8", errors="replace")
        files = list(args.files or [])
        source = f"--body-file {args.body_file}"
    elif args.pr:
        body, files = from_gh(args.pr)
        source = f"PR #{args.pr}"
    else:
        try:
            ctx = from_event()
        except EnvProblem as e:
            print(f"[红] {e}", file=sys.stderr)
            print("     取不到上下文时不能报绿——否则同一份正文会因环境抖动从红变绿。", file=sys.stderr)
            return 2
        if ctx is None:
            print("[SKIP] 没有 PR 上下文（非 pull_request 事件且未给 --pr / --body-file）。")
            return 0
        body, files = ctx
        source = "pull_request 事件载荷"

    problems = check_body(body, files)
    if problems:
        for p in problems:
            print(f"[红] {p}", file=sys.stderr)
        print(f"     正文结构见 doc/PR_GUIDE.md「正文分段」；{source}", file=sys.stderr)
        return 1

    gate_note = f"，门禁链 {len(gate_hits(files))} 个文件已声明" if gate_hits(files) else ""
    print(f"[绿] {source}：必填段齐全、关闭关键词存在{gate_note}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
