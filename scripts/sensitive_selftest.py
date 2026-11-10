#!/usr/bin/env python3
"""敏感信息门禁的内置用例（#313 从 `check_sensitive.py` 拆出）。

由 `python scripts/check_sensitive.py --self-test` 调用，不进 `scripts/gates.json`
（ci 档跑的是 `--check`；自检由人工与 PR 描述里的命令触发）。
样本全部在运行时拼出来，源码里不出现完整敏感值——`check_sensitive.py` 自己也在扫描范围内。
"""
from __future__ import annotations

import base64
import contextlib
import io
import re
import sys
import tempfile
from pathlib import Path

import check_sensitive
import sensitive_scan
from _common import ENV_FAIL, OK, EnvProblem
from sensitive_fix import _apply_fix_text, _write_text
from sensitive_rules import AllowEntry, _validate_allowlist
from sensitive_scan import _read_text, _read_text_ex, _repo_slug, scan_repo, scan_text


def self_test() -> int:
    """纯内存用例。敏感样本全部在运行时拼出来，避免门禁源码本身成为下一次命中。

    防 issue #109 复发：hex/Base64/URL 三种编码形态必须与明文同罪；
    豁免必须有理由，且只能作用于同一行。防 #117 评审回归：
    - 夹具只用明显虚构值（12 位大写 hex、测试网段 IP、通用用户名、假目录名），
      源码里再拼一遍「自己扫自己」必须零命中；
    - 路径不吞 Markdown 闭括号与中文标点；
    - 带 `.git` 的仓库 URL、4-4-4-2 重分组 hex、GBK 与无 BOM UTF-16 都要能抓到；
    - 标准示例 UUID 不误报，`--fix` 回写保持原编码。
    """
    # 拼接构造，源码里不出现完整敏感值，`check_sensitive.py` 扫自己时保持干净。
    fake_guid = "-".join(("0123abcd", "4567", "89"))
    fake_serial = "".join(("ABC", "DEF", "123456"))  # 12 位大写 hex，明显虚构
    fake_ip = ".".join(("192", "168", "55", "66"))  # 测试网段，非真实内网
    # 故意混大小写：全小写形态才能验证仓库名规则真的忽略大小写（#117 二轮评审阻断 1）。
    fake_repo = "/".join(("Acme", "Example-Project"))
    # 仓库侧校验用真实 slug，否则对「本仓库自己的名字」这一值天然盲（#117 二轮评审 N1）；
    # 没有远端时退回虚构值，不能让自检因为环境缺失而失败。
    real_repo = _repo_slug() or fake_repo
    fake_pat = "ghp" + "_" + "A" * 36
    fake_key = "-----BEGIN " + "RSA " + "PRIVATE KEY" + "-----"
    fake_path_win = "C:" + "/" + "Users" + "/" + "alice" + "/" + "documents"
    fake_path_bash = "/" + "c" + "/" + "Users" + "/" + "alice" + "/" + "projects"
    fake_path_home = "/" + "home" + "/" + "alice" + "/.ssh/id_rsa"
    fake_path_placeholder = "C:" + "/" + "Users" + "/" + "<name>" + "/..."
    fake_path_private_dir = "C:" + "/" + "Users" + "/" + "..." + "/" + "private-dir"
    hex_guid = fake_guid.encode("utf-8").hex()
    b64_guid = base64.b64encode(fake_guid.encode("utf-8")).decode("ascii")
    url_guid = fake_guid.replace("-", "%2D")
    # 4-4-4-2 / 冒号 / 空格三种重分组（#117 评审 B4）。
    no_dash = fake_guid.replace("-", "")
    grouped_dash = "-".join((no_dash[0:4], no_dash[4:8], no_dash[8:12], no_dash[12:]))
    grouped_colon = ":".join((no_dash[0:4], no_dash[4:8], no_dash[8:12], no_dash[12:]))
    grouped_space = " ".join((no_dash[0:4], no_dash[4:8], no_dash[8:12], no_dash[12:]))
    # RFC 示例 UUID 与一个版本/变体位合法的标准 UUID（#117 评审非阻断 2）。
    example_uuid_1 = "-".join(("550e8400", "e29b", "41d4", "a716", "446655440000"))
    example_uuid_2 = "-".join(("123e4567", "e89b", "12d3", "a456", "426614174000"))
    standard_uuid = "-".join(("a1b2c3d4", "e5f6", "4789", "8abc", "def012345678"))
    bad_version_uuid = "-".join(("a1b2c3d4", "e5f6", "0f89", "8abc", "def012345678"))

    cases: list[tuple[str, str, bool, set[str]]] = [
        ("明文 GUID 必红", f"GUID={fake_guid}", True, {"guid"}),
        ("hex 编码 GUID 必红", f"raw={hex_guid}", True, {"guid"}),
        ("14 位去横线 GUID 必红", f"raw={no_dash}", True, {"guid"}),
        ("12 位 hex 不误报", f"raw={no_dash[:12]}", False, set()),
        ("无字母 14 位 hex 不误报", "12345678901234", False, set()),
        ("4-4-4-2 重分组必红", f"raw={grouped_dash}", True, {"guid"}),
        ("冒号分隔重分组必红", f"raw={grouped_colon}", True, {"guid"}),
        ("空格分隔重分组必红", f"raw={grouped_space}", True, {"guid"}),
        ("Base64 编码 GUID 必红", f"blob={b64_guid}", True, {"guid"}),
        ("URL 编码 GUID 必红", f"q={url_guid}", True, {"guid"}),
        ("SN= 长串必红", f"SN={fake_serial}0000000000", True, {"serial"}),
        ("12–16 位大写 hex 序列号必红", fake_serial, True, {"serial"}),
        ("内网 IP 192.168 必红", fake_ip, True, {"private-ip"}),
        ("内网 IP 10.x 必红", ".".join(("10", "99", "88", "77")), True, {"private-ip"}),
        ("内网 IP 172.16 必红", ".".join(("172", "20", "99", "1")), True, {"private-ip"}),
        ("公网 IP 不报", "8.8.8.8", False, set()),
        ("172.32 不是内网段", "172.32.0.1", False, set()),
        ("Windows 用户路径必红", fake_path_win, True, {"user-path"}),
        ("Git Bash 用户路径必红", fake_path_bash, True, {"user-path"}),
        ("Unix home 路径必红", fake_path_home, True, {"user-path"}),
        ("纯占位符路径不报", fake_path_placeholder, False, set()),
        ("掩码用户但真实目录名必红", fake_path_private_dir, True, {"user-path"}),
        ("私有仓库标记必红", "仓库 `" + fake_repo + "`（" + "private" + "）", True, {"private-repo"}),
        ("私有仓库名字面量必红", f"remote={fake_repo}", True, {"private-repo"}),
        ("仓库 URL 带 .git（SSH）必红", "git@github.com:" + fake_repo + ".git", True, {"private-repo"}),
        ("仓库 URL 带 .git（HTTPS）必红", "https://github.com/" + fake_repo + ".git", True, {"private-repo"}),
        ("全小写 owner/repo 必红", "https://github.com/" + fake_repo.lower(), True, {"private-repo"}),
        ("全小写 owner/repo 带 .git 必红", "git@github.com:" + fake_repo.lower() + ".git", True, {"private-repo"}),
        ("Java 的 private 关键字不报", "private boolean scanning;", False, set()),
        ("GitHub PAT 必红", fake_pat, True, {"secret"}),
        ("AWS key id 必红", "AKIA" + "0" * 16, True, {"secret"}),
        ("私钥头必红", fake_key, True, {"secret"}),
        ("keystore 字面口令必红", "storePassword = " + '"' + "hunter2" + '"', True, {"secret"}),
        ("keystore 环境变量不报", "storePassword = System.getenv('X')", False, set()),
        ("行内豁免带理由生效", fake_guid + "  # allow-sensitive: 文档示例，已确认为虚构值", False, set()),
        ("空理由不豁免", fake_guid + "  # allow-sensitive:", True, {"guid"}),
        ("模板理由不豁免", fake_guid + "  # allow-sensitive: <reason>", True, {"guid"}),
        ("豁免只作用于同一行", fake_guid + "  # allow-sensitive: 本行是示例\n" + fake_serial, True, {"serial"}),
        ("占位符本身不重复命中", "<redacted:guid:abc123>", False, set()),
        ("小写 40 位 git SHA 不报", "commit " + "a" * 40, False, set()),
        ("无数字的大写常量不报", "FFFFFFFFFFFF", False, set()),
        ("Base64 普通词不报", "import MTPIMPORT", False, set()),
        ("RFC 示例 UUID 1 不报", example_uuid_1, False, set()),
        ("RFC 示例 UUID 2 不报", example_uuid_2, False, set()),
        ("标准 UUID（版本/变体合法）仍报", f"id={standard_uuid}", True, {"guid"}),
        ("非 RFC 版本位不报", bad_version_uuid, False, set()),
    ]

    bad = 0
    total = len(cases)

    def record(name: str, ok: bool, detail: str = "") -> None:
        nonlocal bad, total
        total += 1
        if not ok:
            bad += 1
        print(f"[{'PASS' if ok else 'FAIL'}] {name}{detail}")

    for name, text, expect_red, expect_categories in cases:
        hits = scan_text(text, "self-test.txt", fake_repo)
        got_red = bool(hits)
        got_categories = {h.category for h in hits}
        ok = got_red == expect_red and (not expect_red or got_categories == expect_categories)
        detail = f"（期望{'红' if expect_red else '绿'}，实际{'红' if got_red else '绿'}"
        if got_red:
            detail += f"，类别 {sorted(got_categories)}"
        detail += "）"
        if not ok:
            bad += 1
        print(f"[{'PASS' if ok else 'FAIL'}] {name}{detail}")
        if not ok:
            for hit in hits:
                print(f"        {hit.path}:{hit.line} [{hit.category}/{hit.encoding}] {hit.masked()}")

    # PR 正文/评论是外部输入，行内豁免必须失效；否则任何人都能自带理由绕过门禁（#125）。
    record(
        "关闭行内豁免后理由不再生效",
        bool(
            scan_text(
                fake_guid + "  # allow-sensitive: 文档示例，已确认为虚构值",
                "self-test.txt",
                fake_repo,
                allow_exemptions=False,
            )
        ),
    )

    # B2：路径命中不得吞掉 Markdown 闭括号与中文标点，改写后结构必须不变。
    link = "[文件](" + fake_path_win + ")"
    fixed_link, link_hits = _apply_fix_text(link, "link.txt", fake_repo)
    link_ph = link_hits[0].placeholder() if link_hits else "无命中"
    record(
        "路径后紧跟 ) 不吞字符、链接结构不变",
        bool(link_hits) and fixed_link == "[文件](" + link_ph + ")",
        f"（改写结果 {fixed_link}）",
    )
    follow = "路径：" + fake_path_win + "，后续"
    fixed_follow, follow_hits = _apply_fix_text(follow, "link.txt", fake_repo)
    follow_ph = follow_hits[0].placeholder() if follow_hits else "无命中"
    record(
        "路径后紧跟中文标点不吞字符",
        bool(follow_hits) and fixed_follow == "路径：" + follow_ph + "，后续",
        f"（改写结果 {fixed_follow}）",
    )
    bracket = "【" + fake_path_win + "】"
    fixed_bracket, bracket_hits = _apply_fix_text(bracket, "link.txt", fake_repo)
    bracket_ph = bracket_hits[0].placeholder() if bracket_hits else "无命中"
    record(
        "路径被 】 包住时不吞字符",
        bool(bracket_hits) and fixed_bracket == "【" + bracket_ph + "】",
        f"（改写结果 {fixed_bracket}）",
    )

    # 仓库自身身份文档只放行 private-repo，其他文件照报（豁免不能无限宽）。
    # 用真实 slug 构造，否则这条对「本仓库自己的名字」天然盲（#117 二轮评审 N1）。
    marker = "仓库 `" + real_repo + "`（" + "private" + "）"
    record(
        "自身身份文档的 private-repo 不报",
        not scan_text(marker, "AGENTS.md", real_repo),
    )
    record(
        "非身份文档的 private-repo 仍报",
        bool(scan_text(marker, "docs/history/x.md", real_repo)),
    )

    # B1：门禁源码「自己扫自己」必须零命中；夹具若掺入真实值，这条会立刻变红。
    # #313 拆成四个模块后逐个扫：夹具与真实值的隔离要求对每一份源码都成立。
    module_files = (
        "check_sensitive.py",
        "sensitive_rules.py",
        "sensitive_scan.py",
        "sensitive_fix.py",
        "sensitive_selftest.py",
    )

    def module_sources() -> dict[str, str]:
        return {
            name: Path(__file__).with_name(name).read_text(encoding="utf-8") for name in module_files
        }

    sources = module_sources()
    src_hits = []
    for name, src in sources.items():
        src_hits.extend(scan_text(src, f"scripts/{name}", fake_repo))
    record(
        "门禁源码自扫零命中",
        not src_hits,
        "" if not src_hits else f"（{len(src_hits)} 处，首个 {src_hits[0].path}:{src_hits[0].line}）",
    )

    # B1 加强：夹具值即使被拆成片段，占位符指纹也不能与仓库现有命中重合——否则说明
    # 夹具里混进了真实值（#117 评审 B1 的复现方式）。
    fixture_text = "\n".join(text for _, text, _, _ in cases)
    fixture_phs = {h.placeholder() for h in scan_text(fixture_text, "self-test.txt", fake_repo)}
    repo_hits, _, _ = scan_repo(real_repo)
    repo_phs = {h.placeholder() for h in repo_hits}
    overlap = fixture_phs & repo_phs
    record(
        "夹具指纹与仓库现有命中零重合",
        not overlap,
        f"（重合 {sorted(overlap)}）" if overlap else "",
    )

    # allowlist（#110）：只凭「类别 + 精确值」放行。退化成网段 / 前缀 / 通配 / 忽略大小写
    # 时，下面的负向用例会立刻变红。
    allow_ip = ".".join(("192", "168", "122", "1"))
    near_ip = ".".join(("192", "168", "122", "2"))
    record("allowlist 精确放行公开示例网关", not scan_text("AP=" + allow_ip, "x.md", fake_repo))
    record("allowlist 不放过相邻地址", bool(scan_text("AP=" + near_ip, "x.md", fake_repo)))
    keystore_key = "store" + "Password"
    record(
        "allowlist 精确放行 debug keystore 口令",
        not scan_text(keystore_key + ' = "android"', "x.kts", fake_repo),
    )
    record(
        "allowlist 不放过相似口令",
        bool(scan_text(keystore_key + ' = "android1"', "x.kts", fake_repo)),
    )
    record(
        "allowlist 口令区分大小写",
        bool(scan_text(keystore_key + ' = "Android"', "x.kts", fake_repo)),
    )
    mixed = allow_ip + " 与真实路径 " + fake_path_win
    record(
        "allowlist 不整行放行",
        {h.category for h in scan_text(mixed, "x.md", fake_repo)} == {"user-path"},
    )
    template_value = ".".join(("10", "0", "0", "2"))
    record(
        "allowlist 拒绝模板理由",
        bool(_validate_allowlist((AllowEntry("private-ip", template_value, "todo"),))),
    )
    record("仓库扫描零命中（allowlist 生效）", not repo_hits)

    # B5：GBK 与无 BOM UTF-16LE 都要能解出并命中。
    with tempfile.TemporaryDirectory() as tmp:
        tmp_dir = Path(tmp)
        gbk_path = tmp_dir / "gbk.txt"
        gbk_path.write_bytes(("配对=" + fake_guid).encode("gbk"))
        gbk_text = _read_text(gbk_path)
        record(
            "GBK 文件解码后可命中",
            gbk_text is not None and bool(scan_text(gbk_text, "gbk.txt", fake_repo)),
        )

        utf16_path = tmp_dir / "utf16.txt"
        utf16_path.write_bytes(("配对=" + fake_guid).encode("utf-16-le"))
        utf16_text = _read_text(utf16_path)
        record(
            "无 BOM UTF-16LE 解码后可命中",
            utf16_text is not None and bool(scan_text(utf16_text, "utf16.txt", fake_repo)),
        )

        # 非阻断 3：带 BOM 的 UTF-16 经 --fix 回写后必须仍是 UTF-16LE + BOM。
        bom_path = tmp_dir / "bom.txt"
        original = "配对=" + fake_guid + "\n说明：测试\n"
        bom_path.write_bytes(b"\xff\xfe" + original.encode("utf-16-le"))
        bom_text, bom_encoding = _read_text_ex(bom_path)
        assert bom_text is not None
        fixed_text, bom_hits = _apply_fix_text(bom_text, "bom.txt", fake_repo)
        _write_text(bom_path, fixed_text, bom_encoding)
        raw = bom_path.read_bytes()
        record(
            "UTF-16 BOM 文件 --fix 后保持原编码",
            bool(bom_hits)
            and raw[:2] == b"\xff\xfe"
            and raw[2:].decode("utf-16-le") == fixed_text,
        )

    # --fix 的幂等性：同一批命中替换两次，第二次必须没有可替换项。
    fix_text = "GUID=" + fake_guid + "\nSN=" + fake_serial + "0000\n"
    first = scan_text(fix_text, "fix-test.txt", fake_repo)
    lines = fix_text.split("\n")
    for hit in sorted(first, key=lambda h: (h.line, -h.start)):
        lines[hit.line - 1] = lines[hit.line - 1][:hit.start] + hit.placeholder() + lines[hit.line - 1][hit.end:]
    fixed = "\n".join(lines)
    second = scan_text(fixed, "fix-test.txt", fake_repo)
    record(
        "--fix 幂等",
        bool(first) and not second,
        f"（第一次 {len(first)} 处，第二次 {len(second)} 处）",
    )

    # 占位符是「同值同符」：明文与 hex 必须落到同一个标识，换值必须换标识。
    plain_hits = scan_text("GUID=" + fake_guid, "ph-test.txt", fake_repo)
    hex_hits = scan_text("raw=" + hex_guid, "ph-test.txt", fake_repo)
    grouped_hits = scan_text("raw=" + grouped_dash, "ph-test.txt", fake_repo)
    other_guid = "-".join(("89abcdef", "0123", "45"))
    other_hits = scan_text("GUID=" + other_guid, "ph-test.txt", fake_repo)
    plain_ph = plain_hits[0].placeholder() if plain_hits else "无命中"
    hex_ph = hex_hits[0].placeholder() if hex_hits else "无命中"
    grouped_ph = grouped_hits[0].placeholder() if grouped_hits else "无命中"
    other_ph = other_hits[0].placeholder() if other_hits else "无命中"
    record(
        "占位符同值同符 / 换值换符",
        bool(plain_hits and hex_hits and grouped_hits and other_hits)
        and plain_ph == hex_ph == grouped_ph
        and plain_ph != other_ph
        and re.fullmatch(r"<redacted:guid:[0-9a-f]{6}>", plain_ph) is not None,
        f"（明文 {plain_ph}，hex {hex_ph}，重分组 {grouped_ph}，另一值 {other_ph}）",
    )

    # #204：机械逻辑收口到共享层，git 用不了必须映射成退出码 2（不许 SystemExit(str) 隐式退 1）。
    self_src = "\n".join(sources.values())
    record("#204：git 清单走共享层 git_ls_files", "git_ls_files" in self_src)
    record("#204：不再有本地 subprocess.run 样板", "subprocess.run" + "(" not in self_src)
    record("#204：不再用 SystemExit(str) 隐式退 1", "raise SystemExit" + "(" not in self_src)

    def _env_failure_exit() -> int | None:
        """git 用不了时 main 必须退 ENV_FAIL；现状是让异常冒出来（traceback 退 1）。"""

        def boom() -> list[str]:
            raise EnvProblem("git 不可用（自检注入）")

        # #313：`tracked_files` 由 `scan_repo()` 在扫描模块里解析，
        # `_repo_slug` 由 `main()` 在 CLI 模块里解析——打桩必须打到各自的宿主模块。
        real_tracked = sensitive_scan.tracked_files
        real_slug = check_sensitive._repo_slug
        sensitive_scan.tracked_files = boom
        check_sensitive._repo_slug = lambda: None
        try:
            with contextlib.redirect_stderr(io.StringIO()):
                return check_sensitive.main(["--check"])
        except EnvProblem:
            return None
        finally:
            sensitive_scan.tracked_files = real_tracked
            check_sensitive._repo_slug = real_slug

    record("#204：git 用不了时退 2（不隐式退 1）", _env_failure_exit() == ENV_FAIL)

    if bad:
        print(f"== 自检红 {bad} 项 ==", file=sys.stderr)
        return ENV_FAIL
    print(f"== 自检全绿：{total} 项 ==")
    return OK
