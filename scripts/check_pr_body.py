#!/usr/bin/env python3
"""PR 正文与评论的敏感信息门禁（`support` 档，issue #125）。

背景：#109 的原始事故发生在 PR 正文——真实配对 GUID 换成 hex 后贴进 PR，并标注「已脱敏」。
`check_sensitive.py` 只扫 `git ls-files`，覆盖不到 PR 正文、评论与草稿这类不在工作树里的文本。

输入来源：
- 正文：优先 `GITHUB_EVENT_PATH` 事件载荷的 `pull_request.body`（CI 不依赖 gh 登录态）；
  本地 `--pr N` 用 `gh pr view N --json body`；`--body-file` 供离线自查。
- 评论：`gh pr view N --json comments`（issue comments，不含 review 线程）；定位用评论 URL
  里的 `#issuecomment-<数字>`，拿不到 URL 时退回 node id 或本次返回序号。
- `--all`：手动盘点全部历史 PR（`gh pr list --state all`），逐个复用同一套抓取与扫描。
  **只读、不进 `scripts/gates.json`、不在 CI 跑**（#136）。

规则：直接复用 `check_sensitive.scan_text`，命中即退出 1；只打印打码值与确定性占位符，
不回显原值。PR 正文/评论是外部输入，**不认行内 `allow-sensitive` 豁免**（任何人都能自带
理由绕过），但脚本内 `ALLOWLIST` 仍生效，因为它的值固定在仓库里。

已知边界（issue #125 / #136）：
- 不扫 commit message（由 `check_commit_messages.py` 覆盖，#134）、review 评论 / review 正文。
- 评论只在 PR 事件上复扫；晚于最后一次 PR 事件的评论不会被自动覆盖，必要时手跑 `--pr N`。
- **历史 PR 不回扫**：support 档只在 `pull_request` 事件上跑，合并后的 PR 不再触发；盘点时手跑
  `--all`（只读，单次最多枚举 1000 个 PR，gh 上限）。GitHub 的编辑历史（`userContentEdits`）、
  issue 正文、review 行内评论不在覆盖范围。
- 跨行切断的 token 不保证命中（沿用 `check_sensitive.py` 的逐行扫描）。
- 无 PR 上下文时 SKIP；有上下文但正文/评论取不全时退出 2——不许把「取不到」当绿。

用法：
    python scripts/check_pr_body.py                          # CI：读事件载荷；本地无上下文则 SKIP
    python scripts/check_pr_body.py --pr 129                 # 本地查指定 PR（正文 + 评论）
    python scripts/check_pr_body.py --all                    # 手动盘点全部历史 PR（只读，不进 CI）
    python scripts/check_pr_body.py --body-file .tmp/pr.md   # 离线只查正文
    python scripts/check_pr_body.py --self-test
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
from _common import EnvProblem, format_hit, gh_json  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent


def _gh_json(args: list[str], runner=None) -> object:
    """历史名字保留：实际能力已收口到 `_common.gh_json`（#204）。"""
    return gh_json(args, runner=runner)


def _comment_label(item: dict, idx: int) -> str:
    """评论定位优先用 URL 里的 issuecomment 数字 ID；缺 URL 时退回 node id / 返回序号。"""
    url = item.get("url") or ""
    marker = "#issuecomment-"
    if marker in url:
        return f"PR 评论 #{url.rsplit(marker, 1)[1]}"
    return f"PR 评论 #{item.get('databaseId') or item.get('id') or idx}"


def _parse_comments(data: dict) -> list[tuple[str, str]]:
    comments: list[tuple[str, str]] = []
    for idx, item in enumerate(data.get("comments") or [], 1):
        comments.append((_comment_label(item, idx), item.get("body") or ""))
    return comments


def fetch_pr(number: int, runner=None) -> tuple[str, list[tuple[str, str]]]:
    data = _gh_json(["pr", "view", str(number), "--json", "body,comments"], runner)
    if not isinstance(data, dict):
        raise EnvProblem("gh pr view 返回的不是对象，取不到正文")
    return data.get("body") or "", _parse_comments(data)


def fetch_comments(number: int, runner=None) -> list[tuple[str, str]]:
    data = _gh_json(["pr", "view", str(number), "--json", "comments"], runner)
    if not isinstance(data, dict):
        raise EnvProblem("gh pr view 返回的不是对象，取不到评论")
    return _parse_comments(data)


def fetch_event_body() -> tuple[int, str] | None:
    """返回 (PR 编号, 正文)；没有 PR 上下文返回 None；有 PR 但缺编号抛 EnvProblem。"""
    path = os.environ.get("GITHUB_EVENT_PATH")
    if not path or not Path(path).is_file():
        return None
    try:
        payload = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise EnvProblem(f"读不了 GITHUB_EVENT_PATH：{exc}") from exc
    pr = payload.get("pull_request")
    if not pr:
        return None
    number = pr.get("number") or payload.get("number")
    if not number:
        raise EnvProblem("事件载荷缺 PR 编号，无法取评论")
    return int(number), pr.get("body") or ""


def scan_sources(
    body: str,
    comments: list[tuple[str, str]],
    repo_slug: str | None,
) -> list[Hit]:
    """正文 + 评论逐条扫描。外部输入不认行内豁免，但脚本内 ALLOWLIST 仍生效（#125）。"""
    hits = list(scan_text(body, "PR 正文", repo_slug, allow_exemptions=False))
    for label, text in comments:
        hits.extend(scan_text(text, label, repo_slug, allow_exemptions=False))
    return hits


def list_pr_numbers(runner=None) -> list[int]:
    """列出全部状态的 PR 编号（`--all` 手动盘点专用，只读）。"""
    data = _gh_json(
        ["pr", "list", "--state", "all", "--limit", "1000", "--json", "number"], runner
    )
    if not isinstance(data, list):
        raise EnvProblem("gh pr list 返回的不是数组")
    numbers: list[int] = []
    for item in data:
        number = item.get("number") if isinstance(item, dict) else None
        if not isinstance(number, int) or number <= 0:
            raise EnvProblem("gh pr list 返回项缺合法 number")
        numbers.append(number)
    return numbers


def scan_all_prs(runner=None) -> tuple[int, int, list[tuple[int, list[Hit]]]]:
    """遍历全部历史 PR（只读）；返回 (PR 数, 评论数, [(PR 号, 命中)])。"""
    numbers = list_pr_numbers(runner)
    repo = _repo_slug()
    total_comments = 0
    bad: list[tuple[int, list[Hit]]] = []
    for number in numbers:
        body, comments = fetch_pr(number, runner)
        total_comments += len(comments)
        hits = scan_sources(body, comments, repo)
        if hits:
            bad.append((number, hits))
    return len(numbers), total_comments, bad


def _report(hits: list[Hit], comments: list[tuple[str, str]]) -> int:
    scope = "PR 正文" if not comments else f"PR 正文 + {len(comments)} 条评论"
    if not hits:
        print(f"[绿] {scope}：无敏感信息命中")
        return 0
    for hit in hits:
        print(format_hit(hit))
    print(f"[红] {scope}：命中 {len(hits)} 处。只显示打码值，原值不落日志")
    return 1


def _report_all(
    pr_count: int, comment_count: int, hits_by_pr: list[tuple[int, list[Hit]]]
) -> int:
    """`--all` 的汇总报告：先总后分，命中只打码。"""
    if not hits_by_pr:
        print(f"[绿] 历史 PR 回扫：{pr_count} 个 PR、{comment_count} 条评论，无敏感信息命中")
        return 0
    for number, hits in hits_by_pr:
        print(f"[红] PR #{number}：命中 {len(hits)} 处")
        for hit in hits:
            print(format_hit(hit, where=f"PR #{number}"))
    total = sum(len(hits) for _, hits in hits_by_pr)
    print(
        f"[红] 历史 PR 回扫：{pr_count} 个 PR、{comment_count} 条评论，"
        f"命中 {total} 处（{len(hits_by_pr)} 个 PR）。只显示打码值，原值不落日志"
    )
    return 1


def main(argv: list[str] | None = None, runner=None) -> int:
    parser = argparse.ArgumentParser(description="PR 正文与评论的敏感信息门禁（#125）")
    parser.add_argument("--pr", type=int, help="查指定 PR（正文 + 评论），需要 gh 登录态")
    parser.add_argument("--body-file", help="离线只查一份正文文本")
    parser.add_argument(
        "--all", action="store_true", help="手动盘点全部历史 PR（只读；不进 CI）"
    )
    parser.add_argument("--self-test", action="store_true", help="跑内置自检")
    args = parser.parse_args(argv)

    if args.self_test:
        return _self_test()
    if args.pr and args.body_file:
        print("[红] --pr 与 --body-file 只能二选一", file=sys.stderr)
        return 2
    if args.all and (args.pr or args.body_file):
        print("[红] --all 不能与 --pr/--body-file 同用", file=sys.stderr)
        return 2

    try:
        if args.all:
            return _report_all(*scan_all_prs(runner))
        if args.body_file:
            try:
                body = Path(args.body_file).read_text(encoding="utf-8", errors="replace")
            except OSError as exc:
                raise EnvProblem(f"读不了 --body-file：{exc}") from exc
            comments: list[tuple[str, str]] = []
        elif args.pr:
            body, comments = fetch_pr(args.pr, runner)
        else:
            event = fetch_event_body()
            if event is None:
                print("[SKIP] 无 PR 上下文（事件不是 pull_request，也未给 --pr/--body-file）")
                return 0
            number, body = event
            comments = fetch_comments(number, runner)
    except EnvProblem as exc:
        print(f"[红] {exc}", file=sys.stderr)
        print("[红] 取不到 PR 正文/评论，拒绝报绿（#125）", file=sys.stderr)
        return 2

    return _report(scan_sources(body, comments, _repo_slug()), comments)


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
            got: object = main(argv, runner=runner)
        except SystemExit as exc:  # argparse 拒绝参数时抛 SystemExit，不能让它炸掉用例
            got = exc.code
        except Exception as exc:  # 用例本身不许因未实现而炸掉
            got = f"异常 {type(exc).__name__}: {exc}"
        record(f"{name}（期望退出 {want}，实际 {got}）", got == want)

    def capture(argv: list[str], runner=None) -> tuple[object, str]:
        out = io.StringIO()
        try:
            with contextlib.redirect_stdout(out):
                code: object = main(argv, runner=runner)
        except SystemExit as exc:
            code = exc.code
            out.write(f"SystemExit {exc.code}")
        except Exception as exc:
            code = f"异常 {type(exc).__name__}: {exc}"
        return code, out.getvalue()

    sample = "-".join(("0123abcd", "4567", "89"))  # 明显虚构，与自检夹具同值
    clean = "这是一段没有任何敏感信息的正文。"
    repo = _repo_slug()

    hits = scan_sources("配对值 " + sample, [], repo)
    record("正文命中 guid", bool(hits) and hits[0].path == "PR 正文")

    record("干净正文不报", not scan_sources(clean, [], repo))

    comment_hits = scan_sources(clean, [("PR 评论 #11", "值 " + sample)], repo)
    record(
        "评论命中",
        bool(comment_hits) and comment_hits[0].path.startswith("PR 评论"),
    )

    label_hits = _parse_comments(
        {
            "comments": [
                {
                    "url": "https://example.invalid/pull/1#issuecomment-6688496705",
                    "body": "正文",
                }
            ]
        }
    )
    record("评论定位取 URL 里的数字 ID", label_hits[0][0] == "PR 评论 #6688496705")

    both = scan_sources("正文 " + sample,
                        [("PR 评论 #12", "评论 " + sample)], repo)
    record("正文与评论分别计数", len(both) == 2 and {h.path for h in both} != {"PR 正文"})

    exempt = sample + " <!-- allow-sensitive: 公开示例 -->"
    record("正文行内豁免不生效", bool(scan_sources(exempt, [], repo)))

    allow_ip = ".".join(("192", "168", "122", "1"))
    record("ALLOWLIST 在正文里仍生效", not scan_sources("AP=" + allow_ip, [], repo))

    one = scan_sources(sample, [], repo)
    record(
        "占位符不回显原值",
        bool(one) and sample not in one[0].placeholder() and sample not in one[0].masked(),
    )

    # 事件载荷：没有 PR 上下文 → SKIP；有 PR 但缺编号 → 红。
    saved = os.environ.get("GITHUB_EVENT_PATH")

    def with_event(payload: dict) -> None:
        handle = tempfile.NamedTemporaryFile(
            "w", suffix=".json", delete=False, encoding="utf-8"
        )
        json.dump(payload, handle)
        handle.close()
        os.environ["GITHUB_EVENT_PATH"] = handle.name

    try:
        os.environ.pop("GITHUB_EVENT_PATH", None)
        record("无 PR 上下文 SKIP", main([]) == 0)

        with_event({"ref": "refs/heads/main"})
        record("非 PR 事件 SKIP", main([]) == 0)

        with_event({"pull_request": {"body": "正文"}})
        record("PR 事件缺编号必红", main([]) == 2)

        with_event({"pull_request": {"number": 7, "body": clean}})
        record("PR 事件缺正文也能扫", fetch_event_body() == (7, clean))
    finally:
        if saved is None:
            os.environ.pop("GITHUB_EVENT_PATH", None)
        else:
            os.environ["GITHUB_EVENT_PATH"] = saved

    # gh 行为：失败必须退出 2，成功取到的正文与评论照常扫。
    fail = lambda *_a, **_k: SimpleNamespace(
        returncode=1, stdout="", stderr="gh: not logged in\n"
    )
    ok = lambda *_a, **_k: SimpleNamespace(
        returncode=0,
        stdout=json.dumps(
            {
                "body": clean,
                "comments": [{"databaseId": 21, "body": "评论 " + sample}],
            }
        ),
        stderr="",
    )
    record("gh 失败退出 2", main(["--pr", "1"], runner=fail) == 2)
    record("gh 成功命中评论退出 1", main(["--pr", "1"], runner=ok) == 1)

    # --body-file：离线正反两例。
    handle = tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8")
    handle.write("配对值 " + sample)
    handle.close()
    record("--body-file 命中原值退出 1", main(["--body-file", handle.name]) == 1)
    Path(handle.name).unlink()

    handle = tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8")
    handle.write(clean)
    handle.close()
    record("--body-file 干净退出 0", main(["--body-file", handle.name]) == 0)
    Path(handle.name).unlink()

    # --all：手动盘点全部历史 PR（只读，不在 CI 跑）。
    def all_runner(numbers: list[int], payloads: dict[int, tuple[str, list[dict]]]):
        def run(args: list[str], **_kwargs):
            # `_gh_json` 传的是 ["gh", ...]，所以子命令从下标 1 开始。
            if args[1:3] == ["pr", "list"]:
                return SimpleNamespace(
                    returncode=0,
                    stdout=json.dumps([{"number": n} for n in numbers]),
                    stderr="",
                )
            if args[1:3] == ["pr", "view"]:
                number = int(args[3])
                body, comments = payloads[number]
                return SimpleNamespace(
                    returncode=0,
                    stdout=json.dumps({"body": body, "comments": comments}),
                    stderr="",
                )
            raise AssertionError(f"未预期的 gh 调用：{args}")

        return run

    expect_exit(
        "--all 全干净退出 0", ["--all"], 0,
        all_runner([1, 2], {1: (clean, []), 2: (clean, [{"body": "干净评论"}])}),
    )
    expect_exit(
        "--all 正文命中退出 1", ["--all"], 1,
        all_runner([1], {1: ("配对值 " + sample, [])}),
    )
    expect_exit(
        "--all 评论命中退出 1", ["--all"], 1,
        all_runner([3], {3: (clean, [{"body": "评论 " + sample}])}),
    )
    expect_exit("--all gh 枚举失败退出 2", ["--all"], 2, fail)
    expect_exit("--all 与 --pr 互斥退出 2", ["--all", "--pr", "1"], 2)
    expect_exit("--all 与 --body-file 互斥退出 2", ["--all", "--body-file", "x.md"], 2)
    expect_exit(
        "gh pr view 返回数组退出 2", ["--pr", "1"], 2,
        lambda *_a, **_k: SimpleNamespace(returncode=0, stdout="[]", stderr=""),
    )

    code, output = capture(
        ["--all"],
        all_runner([1, 2], {1: (clean, []), 2: (clean, [{"body": "干净评论"}])}),
    )
    record(
        "--all 汇总计数正确",
        code == 0 and "2 个 PR、1 条评论" in output,
    )

    if bad:
        print(f"== 自检失败：{bad} / {total} 项 ==", file=sys.stderr)
        return 2
    print(f"== 自检全绿：{total} 项 ==")
    return 0


if __name__ == "__main__":
    sys.exit(main())
