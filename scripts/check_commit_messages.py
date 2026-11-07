#!/usr/bin/env python3
"""commit message 的敏感信息门禁（`support` 档 + `.githooks/commit-msg`，issue #134）。

背景：`check_sensitive.py` 只扫 `git ls-files`，`check_pr_body.py` 只扫 PR 正文与评论，
commit message 既不进工作树、也不进 PR 正文，把配对值 / 内网地址 / 私人路径写进提交正文
不会被任何门禁拦住。`git log` 会随分支长期保留，事后清理要改写历史。

输入来源：
- `--message-file <path>`：`.githooks/commit-msg` 调用，单条扫，写入历史前先拦；
- `--range <base>..<head>`：本地手查一段提交；
- 默认：读 `GITHUB_EVENT_PATH` 的 `pull_request.base.sha` / `head.sha`，跑
  `git log --format=%H%x1f%B%x1e <base>..<head>`。

规则：复用 `check_sensitive.scan_text`；commit message 是外部输入，**不认行内
`allow-sensitive` 豁免**，脚本内 `ALLOWLIST` 仍生效。命中退出 1，只打印打码值与占位符。

退出码：0 绿或 SKIP / 1 命中 / 2 取不全（事件缺 sha、`git log` 失败、消息文件读不了）。

已知边界：
- 只查 `base..head` 的新增提交，不回溯已合并历史；回溯请手跑 `--range`。
- 不扫 PR 标题、review comment / review 正文（#125 已登记的边界）。
- 跨行切断的 token 不保证命中（沿用 `scan_text` 的逐行扫描）。
- 本地钩子对 `Merge` / `Revert` 提交沿用 `.githooks/commit-msg` 的既有早退；
  这类提交若进了 PR 范围，仍由 support 档扫描。

用法：
    python scripts/check_commit_messages.py                       # CI：事件载荷；本地无上下文则 SKIP
    python scripts/check_commit_messages.py --range main..HEAD     # 本地查一段提交
    python scripts/check_commit_messages.py --message-file .git/COMMIT_EDITMSG
    python scripts/check_commit_messages.py --self-test
"""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_sensitive import Hit, _repo_slug, scan_text  # noqa: E402
from _common import EnvProblem, format_hit, run  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent

# `git log --format` 的分隔符：`\x1f` 分 hash 与正文，`\x1e` 分提交。正文里的换行原样保留，
# 这样 `scan_text` 的行号仍然对应提交正文的行号。
FIELD_SEP = "\x1f"
RECORD_SEP = "\x1e"

# `--message-file` 模式没有 hash，用这个固定标签代替。
MESSAGE_LABEL = "commit message"


def from_event() -> tuple[str, str] | None:
    """返回事件载荷里的 (base, head)；没有 PR 上下文返回 None；有 PR 但缺 sha 抛 EnvProblem。"""
    path = os.environ.get("GITHUB_EVENT_PATH")
    if not path or not Path(path).is_file():
        return None
    try:
        payload = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise EnvProblem(f"读不了 GITHUB_EVENT_PATH：{exc}") from exc
    pr = payload.get("pull_request")
    if not isinstance(pr, dict):
        return None
    base = (pr.get("base") or {}).get("sha")
    head = (pr.get("head") or {}).get("sha")
    if not (base and head):
        raise EnvProblem(
            "事件载荷缺 pull_request.base.sha / head.sha，取不到本 PR 的提交；"
            "此时不能把「没扫到」当成绿"
        )
    return str(base), str(head)


def fetch_range(rev_range: str, runner=None) -> list[tuple[str, str]]:
    """跑 `git log` 取一段提交，返回 [(sha, 正文)]；失败抛 EnvProblem。

    `runner` 仅为自检注入，默认 `subprocess.run`。
    """
    if not isinstance(rev_range, str) or ".." not in rev_range:
        raise EnvProblem(f"range 形态不对：{rev_range!r}，应为 <base>..<head>")

    try:
        proc = run(["git", "log", "--format=%H%x1f%B%x1e", rev_range], runner=runner)
    except EnvProblem as exc:
        raise EnvProblem(f"git log {rev_range} 失败：{exc}") from exc
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or "").strip().splitlines()
        raise EnvProblem(
            f"git log {rev_range} 失败（退出 {proc.returncode}）："
            f"{detail[-1] if detail else '无输出'}"
        )

    records: dict[str, str] = {}
    for chunk in (proc.stdout or "").split(RECORD_SEP):
        # `git log` 在两条记录之间会补一个换行；正文自身的行尾不能整体剥掉，
        # 只剥记录边界的 `\n`（尾部空行没有命中可丢）。
        chunk = chunk.strip("\n")
        if not chunk:
            continue
        sha, _, body = chunk.partition(FIELD_SEP)
        sha = sha.strip()
        if not sha:
            continue
        # 同一 commit 只扫一次：git log 正常不会重复，防御 merge / 异常输入。
        records.setdefault(sha, body)
    return list(records.items())


def scan_records(records: list[tuple[str, str]], repo_slug: str | None) -> list[Hit]:
    """逐条扫描提交正文；commit message 是外部输入，不认行内豁免（#134）。"""
    hits: list[Hit] = []
    for sha, body in records:
        label = MESSAGE_LABEL if sha == "-" else f"commit {sha[:7]}"
        hits.extend(scan_text(body, label, repo_slug, allow_exemptions=False))
    return hits


def _report(source: str, records: list[tuple[str, str]], hits: list[Hit]) -> int:
    if not hits:
        print(f"[绿] {source}：{len(records)} 条提交 message，无敏感信息命中")
        return 0
    for hit in hits:
        print(format_hit(hit))
    print(f"[红] {source}：{len(records)} 条提交、命中 {len(hits)} 处。只显示打码值，原值不落日志")
    return 1


def main(argv: list[str] | None = None, runner=None) -> int:
    parser = argparse.ArgumentParser(description="commit message 的敏感信息门禁（#134）")
    parser.add_argument("--range", help="本地查一段提交，形态 <base>..<head>")
    parser.add_argument("--message-file", help="只查一份消息文件（供 commit-msg 钩子）")
    parser.add_argument("--self-test", action="store_true", help="跑内置自检")
    args = parser.parse_args(argv)

    if args.self_test:
        return _self_test()
    if args.range and args.message_file:
        print("[红] --range 与 --message-file 只能二选一", file=sys.stderr)
        return 2

    try:
        if args.message_file:
            path = Path(args.message_file)
            if not path.is_file():
                raise EnvProblem(f"找不到消息文件：{args.message_file}")
            try:
                text = path.read_text(encoding="utf-8", errors="replace")
            except OSError as exc:
                raise EnvProblem(f"读不了消息文件：{exc}") from exc
            records = [("-", text)]
            # 只打文件名，不回显绝对路径：钩子拿到的路径里可能带本机用户目录。
            source = f"消息文件 {path.name}"
        elif args.range:
            records = fetch_range(args.range, runner)
            source = f"--range {args.range}"
        else:
            event = from_event()
            if event is None:
                print("[SKIP] 无 PR 上下文（事件不是 pull_request，也未给 --range/--message-file）")
                return 0
            base, head = event
            records = fetch_range(f"{base}..{head}", runner)
            source = "pull_request 事件载荷 base..head"
    except EnvProblem as exc:
        print(f"[红] {exc}", file=sys.stderr)
        print("[红] 取不到 commit message，拒绝报绿（#134）", file=sys.stderr)
        return 2

    return _report(source, records, scan_records(records, _repo_slug()))


# ---------------------------------------------------------------------------- 自检


def _self_test() -> int:
    total = 0
    bad = 0

    def record(name: str, ok: bool) -> None:
        nonlocal total, bad
        total += 1
        if not ok:
            bad += 1
        print(f"[{'PASS' if ok else 'FAIL'}] {name}")

    def expect_exit(name: str, argv: list[str], want: int, runner=None) -> None:
        try:
            got = main(argv, runner=runner)
        except Exception as exc:  # 用例本身不许因未实现而炸掉
            got = f"异常 {type(exc).__name__}: {exc}"
        record(f"{name}（期望退出 {want}，实际 {got}）", got == want)

    def safe(fn, default):
        try:
            return fn()
        except Exception:
            return default

    # 夹具全部虚构；用字符串拼接避免本文件自己命中门禁规则。
    sample_guid = "-".join(("0123abcd", "4567", "89"))
    sample_path = (
        "C" + ":" + "\\" + "Users" + "\\" + "testuser"
        + "\\" + "Pictures" + "\\" + "proof.arw"
    )
    clean = "feat(gates): 正常提交（issue #NN）\n\n正文没有敏感值。"
    repo = _repo_slug()

    def fake_log(*records: tuple[str, str]):
        payload = "".join(f"{sha}{FIELD_SEP}{body}{RECORD_SEP}" for sha, body in records)
        return lambda *_a, **_k: SimpleNamespace(returncode=0, stdout=payload, stderr="")

    fail_log = lambda *_a, **_k: SimpleNamespace(
        returncode=128, stdout="", stderr="fatal: bad revision\n"
    )

    hit_sha = "a" * 40
    other_sha = "b" * 40

    hits = scan_records([(hit_sha, "配对值 " + sample_guid)], repo)
    record("命中 guid", bool(hits) and hits[0].path == "commit " + hit_sha[:7])

    record("干净正文不报", not scan_records([(hit_sha, clean)], repo))

    many = scan_records(
        [(hit_sha, "配对值 " + sample_guid), (other_sha, "路径 " + sample_path)], repo
    )
    record("多 commit 聚合", len(many) == 2 and {h.path for h in many} != {"commit " + hit_sha[:7]})

    exempt = "配对值 " + sample_guid + " # allow-sensitive: 公开示例"
    record("行内豁免不生效", bool(scan_records([(hit_sha, exempt)], repo)))

    duped = safe(
        lambda: fetch_range("a..b", fake_log((hit_sha, "配对值 " + sample_guid),
                                            (hit_sha, "同一个 hash 的 merge 记录"))),
        [],
    )
    record("同 hash 的 merge 记录只算一次", len(duped) == 1)

    expect_exit("range 模式命中退出 1", ["--range", "a..b"], 1,
                fake_log((hit_sha, "配对值 " + sample_guid)))
    expect_exit("range 模式干净退出 0", ["--range", "a..b"], 0,
                fake_log((hit_sha, clean)))
    expect_exit("git log 失败退出 2", ["--range", "a..b"], 2, fail_log)
    expect_exit("range 形态不合法退出 2", ["--range", "main"], 2, fake_log())

    saved_event = os.environ.get("GITHUB_EVENT_PATH")
    event_file: Path | None = None
    try:
        os.environ.pop("GITHUB_EVENT_PATH", None)
        expect_exit("无 PR 上下文 SKIP 退出 0", [], 0)

        handle = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8")
        json.dump({"ref": "refs/heads/main"}, handle)
        handle.close()
        event_file = Path(handle.name)
        os.environ["GITHUB_EVENT_PATH"] = str(event_file)
        expect_exit("非 PR 事件 SKIP 退出 0", [], 0)

        json.dump({"pull_request": {"number": 7, "body": "正文"}}, event_file.open("w", encoding="utf-8"))
        expect_exit("PR 事件缺 base/head 退出 2", [], 2)

        json.dump(
            {"pull_request": {"base": {"sha": other_sha}, "head": {"sha": hit_sha}}},
            event_file.open("w", encoding="utf-8"),
        )
        os.environ["GITHUB_EVENT_PATH"] = str(event_file)
        expect_exit(
            "PR 事件按 base..head 扫描并命中退出 1", [], 1,
            fake_log((hit_sha, "配对值 " + sample_guid)),
        )
    finally:
        if event_file is not None:
            event_file.unlink(missing_ok=True)
        if saved_event is None:
            os.environ.pop("GITHUB_EVENT_PATH", None)
        else:
            os.environ["GITHUB_EVENT_PATH"] = saved_event

    for name, text, want in (
        ("--message-file 命中原值退出 1", "配对值 " + sample_guid, 1),
        ("--message-file 干净退出 0", clean, 0),
    ):
        handle = tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8")
        handle.write(text)
        handle.close()
        expect_exit(name, ["--message-file", handle.name], want)
        Path(handle.name).unlink()

    expect_exit("--message-file 文件不存在退出 2", ["--message-file", "no-such-file.txt"], 2)
    expect_exit(
        "--range 与 --message-file 同给退出 2",
        ["--range", "a..b", "--message-file", "no-such-file.txt"],
        2,
    )

    handle = tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8")
    handle.write("配对值 " + sample_guid)
    handle.close()
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = main(["--message-file", handle.name])
    output = out.getvalue() + err.getvalue()
    record(
        "报告只打码、不回显原值",
        code == 1 and sample_guid not in output and "<redacted:guid:" in output,
    )
    record(
        "不回显消息文件的绝对路径",
        str(Path(handle.name).parent) not in output,
    )
    Path(handle.name).unlink()

    one = scan_records([(hit_sha, "配对值 " + sample_guid)], repo)
    record(
        "占位符不回显原值",
        bool(one) and sample_guid not in one[0].masked() and sample_guid not in one[0].placeholder(),
    )

    if bad:
        print(f"== 自检失败：{bad} / {total} 项 ==", file=sys.stderr)
        return 2
    print(f"== 自检全绿：{total} 项 ==")
    return 0


if __name__ == "__main__":
    sys.exit(main())
