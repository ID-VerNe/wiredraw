#!/usr/bin/env python3
"""敏感信息门禁：把「换个编码又贴回来」的隐私值挡在推送之前。

设计取自四个成熟方案，只抄适合本仓库的部分（详见 issue #109）：

- Apache Magpie：确定性占位符（同值同符、可重复执行）、豁免必须显式带理由；
- gitleaks：集中式规则表 + allowlist、默认只读报告、`--redact` 式打码预览；
- detect-secrets：行内 allowlist、只盯「新增」的思路（首版先用 `--warn` 输出基线，转 block 见 #110）；
- ggshield / gitleaks：挂在 pre-push；本仓库只走 `scripts/gates.json` 的 static 分片，
  `.githooks/pre-push` 不再写第二份命令。

只扫 `git ls-files` 里的文本文件，跳过二进制；Python 标准库，离线可跑。

本文件只留**只读报告与 CLI**（#313 拆分）：规则表在 `sensitive_rules.py`，扫描引擎在
`sensitive_scan.py`，`--fix` 的唯一写盘点在 `sensitive_fix.py`，内置用例在
`sensitive_selftest.py`。改规则表或编码形态时，这四处要一起看——拆开只是让边界显式，
不是让它们可以各改各的。

退出码（契约与 `scripts/_common.py` 同一张表，见 `docs/testing.md`）：
`0` 绿 / `1` 命中敏感值（内容不达标）/ `2` 环境或参数问题
（git 不可用、被跟踪文件读不到、allowlist 配置不合格）。

已知边界（不要指望它兜住这些）：
- **不扫 commit message、PR 正文与 git 历史**。PR 正文/评论由 `check_pr_body.py`（`support` 档，#125）
  覆盖，commit message 由 `check_commit_messages.py`（`support` 档 + `.githooks/commit-msg`，#134）
  覆盖；本脚本只扫 `git ls-files` 里的文本文件。
- **跨行切断的 token 不保证命中**：逐行扫描，一段值被换行拆开就看不到完整形态。
- `--fix` 只改工作区文件，不改 git 历史，也不自动 `git add` / `git commit`。

用法：
    python scripts/check_sensitive.py --check      # 门禁模式：命中即非零退出
    python scripts/check_sensitive.py --warn       # 只报告、退出 0（演练或排查基线用）
    python scripts/check_sensitive.py --fix        # 真正改写，改完自动复扫自证
    python scripts/check_sensitive.py --self-test  # 自检（含编码形态与豁免用例）

豁免：在命中行的行尾写 `# allow-sensitive: <理由>`（Java/Kotlin 用 `//`，Markdown 用
`<!-- -->`）。理由为空、`TODO`、`<reason>` 这类模板文本不算数，且只对同一行生效。

公开固定值（相机 AP 的官方示例网关、Android debug keystore 的公开默认口令）走
`sensitive_rules.py` 的 `ALLOWLIST`：按「类别 + 精确值」放行，不做前缀 / 通配 / 网段匹配（#110）。
理由必须是具体事实，`main()` 启动前校验，不合格直接拒绝运行。
"""

from __future__ import annotations

import argparse
import sys

from _common import CONTENT_FAIL, ENV_FAIL, OK, EnvProblem, format_hit
from sensitive_fix import _fix
from sensitive_rules import _validate_allowlist
from sensitive_scan import Hit, _repo_slug, scan_repo


def _summary(hits: list[Hit], scanned: int, skipped: int) -> str:
    by_category: dict[str, int] = {}
    files: set[str] = set()
    for hit in hits:
        by_category[hit.category] = by_category.get(hit.category, 0) + 1
        files.add(hit.path)
    categories = "、".join(f"{k} {v}" for k, v in sorted(by_category.items())) or "无"
    return (
        f"扫描 {scanned} 个文本文件（跳过 {skipped} 个二进制），"
        f"命中 {len(hits)} 处 / {len(files)} 个文件；按类别：{categories}"
    )


def _print_hits(hits: list[Hit], warn: bool) -> None:
    for hit in hits:
        print(format_hit(hit, tag="WARN" if warn else "红"))


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="敏感信息门禁（详见 issue #109）")
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="门禁模式：命中即非零退出（默认）")
    mode.add_argument("--warn", action="store_true", help="只报告不阻断（首版落地与排查基线用）")
    mode.add_argument("--fix", action="store_true", help="真正改写文件，改完自动复扫")
    mode.add_argument("--self-test", action="store_true", help="跑内置用例")
    args = ap.parse_args(argv)

    if args.self_test:
        # 延迟导入：`sensitive_selftest.py` 反过来要 import 本模块（打桩 `_repo_slug`、
        # 跑 `main(["--check"])`），模块顶层互相 import 会成环。
        from sensitive_selftest import self_test

        return self_test()

    bad_entries = _validate_allowlist()
    if bad_entries:
        print(f"[红] allowlist 第 {bad_entries} 条的理由不是具体事实，拒绝运行", file=sys.stderr)
        # 脚本自身的配置不合格 = 这次检查没做成，不是「内容不达标」（#204）。
        return ENV_FAIL

    repo_slug = _repo_slug()
    if not repo_slug:
        print("[提示] 读不到 origin 的 owner/repo，跳过「私有仓库名字面量」规则", file=sys.stderr)

    try:
        hits, scanned, skipped = scan_repo(repo_slug)
    except EnvProblem as exc:
        print(f"[红] {exc}", file=sys.stderr)
        return ENV_FAIL

    if args.fix:
        if not hits:
            print(f"[绿] {_summary(hits, scanned, skipped)}")
            return OK
        changed = _fix(hits)
        print(f"[改写] 共修改 {len(changed)} 个文件：{', '.join(changed) if changed else '无'}")
        # 自证：改写后按同一口径再扫一遍。
        try:
            remaining, scanned2, skipped2 = scan_repo(repo_slug)
        except EnvProblem as exc:
            print(f"[红] {exc}", file=sys.stderr)
            return ENV_FAIL
        if remaining:
            _print_hits(remaining, warn=False)
            print(f"[红] 改写后仍有 {len(remaining)} 处命中，需人工处理", file=sys.stderr)
            return CONTENT_FAIL
        print(f"[绿] 自证通过：{_summary(remaining, scanned2, skipped2)}")
        return OK

    _print_hits(hits, warn=args.warn)
    summary = _summary(hits, scanned, skipped)
    if args.warn:
        print(f"[WARN] {summary}")
        print("[WARN] 当前为 warn 模式，只报告不阻断")
        return OK
    if hits:
        print(f"[红] {summary}", file=sys.stderr)
        return CONTENT_FAIL
    print(f"[绿] {summary}")
    return OK


if __name__ == "__main__":
    sys.exit(main())
