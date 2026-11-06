#!/usr/bin/env python3
"""敏感信息门禁：把「换个编码又贴回来」的隐私值挡在推送之前。

设计取自四个成熟方案，只抄适合本仓库的部分（详见 issue #109）：

- Apache Magpie：确定性占位符（同值同符、可重复执行）、豁免必须显式带理由；
- gitleaks：集中式规则表 + allowlist、默认只读报告、`--redact` 式打码预览；
- detect-secrets：行内 allowlist、只盯「新增」的思路（首版用 `--warn` 输出基线）；
- ggshield / gitleaks：挂在 pre-push；本仓库只走 `scripts/gates.json` 的 static 分片，
  `.githooks/pre-push` 不再写第二份命令。

只扫 `git ls-files` 里的文本文件，跳过二进制；Python 标准库，离线可跑。

用法：
    python scripts/check_sensitive.py --check      # 门禁模式：命中即非零退出
    python scripts/check_sensitive.py --warn       # 首版落地：只报告、退出 0
    python scripts/check_sensitive.py --fix        # 真正改写，改完自动复扫自证
    python scripts/check_sensitive.py --self-test  # 自检（含编码形态与豁免用例）

豁免：在命中行的行尾写 `# allow-sensitive: <理由>`（Java/Kotlin 用 `//`，Markdown 用
`<!-- -->`）。理由为空、`TODO`、`<reason>` 这类模板文本不算数，且只对同一行生效。
"""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import re
import subprocess
import sys
import urllib.parse
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 二进制扩展名：直接跳过，不读内容。真正的判定还会 sniff 文件头里的 NUL。
BINARY_SUFFIXES = {
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".bmp", ".tif", ".tiff",
    ".pdf", ".zip", ".gz", ".bz2", ".7z", ".jar", ".keystore", ".apk", ".aar",
    ".so", ".dll", ".exe", ".class", ".ttf", ".otf", ".woff", ".woff2",
    ".mp4", ".mov", ".mp3", ".pcap", ".pcapng", ".bin", ".arw", ".raw",
}

# 占位符里出现的「用户段」写法：只有这些都算掩码，才允许整条路径豁免。
PLACEHOLDER_SEGMENTS = {
    "...", "…", "<name>", "<user>", "<用户名>", "%username%", "$user", "$username",
    "{user}", "{username}", "*", "~", "public", "default",
}

ALLOW_RE = re.compile(r"(?:#|//|<!--|--)\s*allow-sensitive:\s*(.+?)(?:\s*-->)?\s*$")
ALLOW_TEMPLATE_REASONS = {
    "", "todo", "fixme", "xxx", "n/a", "na", "无", "理由", "reason", "<reason>", "<理由>",
}


@dataclass(frozen=True)
class Rule:
    """一条检测规则：category 决定占位符前缀，group 决定替换哪一段。"""

    category: str
    label: str
    regex: re.Pattern
    group: int = 0
    accept: object = None  # callable(value) -> bool；None 表示都接受


def _has_letter_and_digit(value: str) -> bool:
    return bool(re.search(r"[A-Fa-f]", value)) and bool(re.search(r"[0-9]", value))


def _guid_ok(value: str) -> bool:
    # 只要求同时含字母与数字：排除 `20260508-1234-56` 这类日期串，保留索尼的
    # 8-4-2 截断 GUID 与标准 UUID。这里不写任何真实 GUID 示例——门禁会扫自己。
    return _has_letter_and_digit(value)


def _serial_ok(value: str) -> bool:
    # 12–16 位大写十六进制的裸串：至少 2 个字母 + 2 个数字，避开全 0 / DEADBEEF 这类常量。
    return (
        12 <= len(value) <= 16
        and sum(c.isalpha() for c in value) >= 2
        and sum(c.isdigit() for c in value) >= 2
    )


def _ip_ok(value: str) -> bool:
    octets = value.split(".")
    return len(octets) == 4 and all(o.isdigit() and 0 <= int(o) <= 255 for o in octets)


def _path_ok(value: str) -> bool:
    """路径里必须有至少一个「不是占位符」的段落。

    纯模板路径（用户名与后续段落都是 `<name>` / `...`）不报；用户名被掩码成 `...`
    但后面还挂着真实目录名的路径必须报——这正是 #100 里那条注释的情况。
    """
    parts = [p for p in re.split(r"[\\/]", value) if p]
    try:
        idx = next(i for i, p in enumerate(parts) if p.lower() in ("users", "home"))
    except StopIteration:
        return False
    rest = parts[idx + 1:]
    if not rest:
        return False
    for part in rest:
        low = part.lower()
        if low in PLACEHOLDER_SEGMENTS:
            continue
        if part.startswith("%") or part.startswith("<") or part.startswith("{"):
            continue
        return True
    return False


def _secret_value_ok(value: str) -> bool:
    """keystore 口令只报字面量；环境变量 / 占位符不算泄露。"""
    stripped = value.strip()
    return not stripped.startswith(("$", "%", "<", "${")) and "getenv" not in stripped


# 明文规则。顺序即同一段文本上的优先级（先匹配到的类别赢）。
RULES = [
    Rule(
        "guid",
        "配对 GUID 明文",
        re.compile(
            r"(?<![0-9A-Fa-f])[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{2,12}"
            r"(?:-[0-9A-Fa-f]{4})?(?:-[0-9A-Fa-f]{12})?(?![0-9A-Fa-f])"
        ),
        accept=_guid_ok,
    ),
    Rule(
        "serial",
        "相机序列号（SN=/iSerial=）",
        re.compile(r"(?i)\b(?:SN|iSerial|serial|serialnumber)\s*[=:：]\s*([0-9A-Za-z]{12,40})\b"),
        group=1,
    ),
    Rule(
        "private-ip",
        "内网 IPv4",
        re.compile(
            r"(?<![\d.])(?:"
            r"192\.168(?:\.\d{1,3}){2}"
            r"|10(?:\.\d{1,3}){3}"
            r"|172\.(?:1[6-9]|2[0-9]|3[01])(?:\.\d{1,3}){2}"
            r")(?![\d.])"
        ),
        accept=_ip_ok,
    ),
    Rule(
        "user-path",
        "私人用户路径",
        re.compile(r"(?<![A-Za-z0-9])[A-Za-z]:[\\/]+Users[\\/]+[^\s\"'<>|`]+", re.I),
        accept=_path_ok,
    ),
    Rule(
        "user-path",
        "Git Bash 用户路径",
        # 这一行是规则定义本身，会被 Unix 路径规则扫到；豁免必须带理由。
        re.compile(r"(?<![A-Za-z0-9])/(?:c|d|e|f|g|mnt/[c-z])/Users/[^\s\"'<>|`]+", re.I),  # allow-sensitive: 规则定义本身
        accept=_path_ok,
    ),
    Rule(
        "user-path",
        "Unix 用户路径",
        re.compile(r"(?<![A-Za-z0-9])/(?:home|Users)/[^\s\"'<>|`]+", re.I),
        accept=_path_ok,
    ),
    Rule(
        "private-repo",
        "私有仓库标记",
        re.compile(r"(?:（\s*private\s*）|\(\s*private\s*\)|\bprivate\s+(?:repo|repository)\b)", re.I),
    ),
    Rule(
        "secret",
        "GitHub 经典 PAT",
        re.compile(r"\bghp_[A-Za-z0-9]{36}\b"),
    ),
    Rule(
        "secret",
        "GitHub fine-grained PAT",
        re.compile(r"\bgithub_pat_[A-Za-z0-9_]{22,}"),
    ),
    Rule(
        "secret",
        "AWS Access Key ID",
        re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    ),
    Rule(
        "secret",
        "私钥头",
        re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP |ENCRYPTED )?PRIVATE KEY(?: BLOCK)?-----"),
    ),
    Rule(
        "secret",
        "keystore 口令字面量",
        re.compile(r"(?i)\b(?:storePassword|keyPassword|storePass|keyPass)\s*[=:]\s*(\S{4,})"),
        group=1,
        accept=_secret_value_ok,
    ),
    # 通用序列号放最后：`AKIA...` 这类串同时符合「大写十六进制」，必须先归到 secret。
    Rule(
        "serial",
        "12–16 位大写十六进制序列号",
        re.compile(r"(?<![0-9A-Za-z])[0-9A-F]{12,16}(?![0-9A-Za-z])"),
        accept=_serial_ok,
    ),
]

# 编码形态候选：hex / Base64 / URL 编码。只做「解码后再跑明文规则」，不猜密钥。
HEX_TOKEN_RE = re.compile(r"(?<![0-9A-Fa-f])[0-9A-Fa-f]{16,}(?![0-9A-Fa-f])")
B64_TOKEN_RE = re.compile(r"(?<![A-Za-z0-9+/_-])[A-Za-z0-9+/_-]{16,}={0,2}(?![A-Za-z0-9+/_=-])")
URL_TOKEN_RE = re.compile(
    r"(?<![A-Za-z0-9_.~%-])(?:[A-Za-z0-9_.~-]|%[0-9A-Fa-f]{2})+"
    r"%(?:[A-Za-z0-9_.~-]|%[0-9A-Fa-f]{2})+(?![A-Za-z0-9_.~%-])"
)


@dataclass
class Hit:
    """一条命中。value 是用于哈希的「真实值」（编码形态取解码后的命中文本）。"""

    path: str
    line: int
    category: str
    label: str
    start: int
    end: int
    value: str
    encoding: str = "plain"

    def placeholder(self) -> str:
        digest = hashlib.sha256(self.value.strip().lower().encode("utf-8")).hexdigest()[:6]
        return f"<redacted:{self.category}:{digest}>"

    def masked(self) -> str:
        # 默认不在 CI 日志里回显原值：只留前 2 个字符，连后缀都不留。
        # 后缀可能是私人目录名的一部分（#100 的路径注释就吃过这个亏）。
        if len(self.value) <= 6:
            return "*" * len(self.value)
        return self.value[:2] + "*" * min(len(self.value) - 2, 12)


def _allow_reason(line: str) -> str | None:
    """同一行行尾的豁免；理由无效时返回 None（等于没有豁免）。"""
    m = ALLOW_RE.search(line)
    if not m:
        return None
    reason = m.group(1).strip().lower()
    if reason in ALLOW_TEMPLATE_REASONS or re.fullmatch(r"<[^>]*>", reason):
        return None
    return reason


def _repo_slug() -> str | None:
    """从 origin 推 `owner/repo`，不在源码里写死私有仓库名。

    https://github.com/o/r.git 与 git@github.com:o/r.git 都要能解析；取不到就跳过这条规则，
    不能让门禁因为网络/远端缺失而假红。
    """
    try:
        out = subprocess.run(
            ["git", "remote", "get-url", "origin"],
            cwd=str(ROOT),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except OSError:
        return None
    if out.returncode != 0:
        return None
    url = out.stdout.strip()
    m = re.search(r"github\.com[:/]([^/]+)/([^/]+?)(?:\.git)?$", url)
    if not m:
        return None
    return f"{m.group(1)}/{m.group(2)}"


def build_rules(repo_slug: str | None) -> list[Rule]:
    rules = list(RULES)
    if repo_slug:
        # 私有仓库名本身：用运行时从远端解析出的字面量，避免把名字写进门禁源码。
        rules.append(
            Rule(
                "private-repo",
                "私有仓库名",
                re.compile(
                    r"(?<![A-Za-z0-9_.-])" + re.escape(repo_slug) + r"(?![A-Za-z0-9_.-])"
                ),
            )
        )
    return rules


def _decoded_variants(token: str, kind: str) -> list[str]:
    """一种编码候选 → 若干可能文本；解不出来就返回空。"""
    variants: list[str] = []
    if kind == "hex":
        try:
            raw = bytes.fromhex(token)
        except ValueError:
            return []
        text = raw.decode("latin-1")
        variants.append(text)
        # 抓包里常见 ASCII 与 UTF-16 交错：去掉 NUL 再试一次（init_ied.txt 就是这种）。
        stripped = raw.replace(b"\x00", b"").decode("latin-1")
        if stripped != text:
            variants.append(stripped)
    elif kind == "base64":
        normalized = token.replace("-", "+").replace("_", "/")
        for pad in ("", "=", "=="):
            try:
                raw = base64.b64decode(normalized + pad, validate=False)
            except (ValueError, binascii.Error):
                continue
            if len(raw) < 6:
                continue
            text = raw.decode("latin-1")
            printable = sum(1 for c in text if 32 <= ord(c) < 127)
            if printable / len(text) >= 0.8:
                variants.append(text)
                break
    elif kind == "url":
        variants.append(urllib.parse.unquote(token))
    return variants


def _plain_hits_on(text: str, rules: list[Rule]) -> list[tuple[Rule, int, int, str]]:
    found = []
    for rule in rules:
        for m in rule.regex.finditer(text):
            value = m.group(rule.group)
            if not value:
                continue
            if rule.accept and not rule.accept(value):
                continue
            start, end = m.start(rule.group), m.end(rule.group)
            if rule.category == "user-path":
                # Markdown 里路径常被反引号/句号包住；扩展名之外标点不算路径本身。
                trimmed = value.rstrip(".,;:")
                end -= len(value) - len(trimmed)
                value = trimmed
            found.append((rule, start, end, value))
    return found


def _scan_line(line: str, rel: str, line_no: int, rules: list[Rule]) -> list[Hit]:
    if _allow_reason(line):
        return []

    hits: list[Hit] = []
    for rule, start, end, value in _plain_hits_on(line, rules):
        hits.append(Hit(rel, line_no, rule.category, rule.label, start, end, value))

    # 编码形态：只对候选 token 做解码，解码后的文本再跑同一批明文规则。
    for kind, token_re in (("hex", HEX_TOKEN_RE), ("base64", B64_TOKEN_RE), ("url", URL_TOKEN_RE)):
        for m in token_re.finditer(line):
            token = m.group(0)
            for decoded in _decoded_variants(token, kind):
                for rule, _, _, value in _plain_hits_on(decoded, rules):
                    hits.append(
                        Hit(rel, line_no, rule.category, rule.label, m.start(), m.end(), value, kind)
                    )
                    break

    # 同一段文本可能被明文规则与编码规则同时命中：保留先到的明文命中。
    hits.sort(key=lambda h: (h.start, 0 if h.encoding == "plain" else 1, -(h.end - h.start)))
    deduped: list[Hit] = []
    last_end = -1
    for hit in hits:
        if hit.start < last_end:
            continue
        deduped.append(hit)
        last_end = hit.end
    return deduped


def scan_text(text: str, rel: str, repo_slug: str | None = None) -> list[Hit]:
    """纯文本扫描入口；--self-test 直接喂内存字符串。"""
    rules = build_rules(repo_slug)
    hits: list[Hit] = []
    for no, line in enumerate(text.split("\n"), 1):
        hits.extend(_scan_line(line, rel, no, rules))
    return hits


def _read_text(path: Path) -> str | None:
    """读文本；二进制（扩展名或文件头 NUL）返回 None。"""
    if path.suffix.lower() in BINARY_SUFFIXES:
        return None
    try:
        data = path.read_bytes()
    except OSError:
        return None
    if data[:2] in (b"\xff\xfe", b"\xfe\xff"):
        return data.decode("utf-16", errors="replace")
    if b"\x00" in data[:8192]:
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return None


def tracked_files() -> list[str]:
    out = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if out.returncode != 0:
        raise SystemExit("[红] git ls-files 失败，确认当前目录是 git 仓库")
    return [f for f in out.stdout.split("\0") if f]


def scan_repo(repo_slug: str | None) -> tuple[list[Hit], int, int]:
    hits: list[Hit] = []
    scanned = skipped = 0
    for rel in tracked_files():
        text = _read_text(ROOT / rel)
        if text is None:
            skipped += 1
            continue
        scanned += 1
        hits.extend(scan_text(text, rel, repo_slug))
    return hits, scanned, skipped


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
        tag = "WARN" if warn else "红"
        encoded = "" if hit.encoding == "plain" else f"/{hit.encoding}"
        print(
            f"[{tag}] {hit.path}:{hit.line} [{hit.category}{encoded}] "
            f"{hit.masked()} → {hit.placeholder()}"
        )


def _fix(hits: list[Hit]) -> list[str]:
    """按文件、按行、从后往前替换，保持行号与换行风格不变。"""
    changed: list[str] = []
    by_file: dict[str, list[Hit]] = {}
    for hit in hits:
        by_file.setdefault(hit.path, []).append(hit)

    for rel, file_hits in sorted(by_file.items()):
        path = ROOT / rel
        text = _read_text(path)
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
            path.write_text(new_text, encoding="utf-8", newline="")
            changed.append(rel)
    return changed


def self_test() -> int:
    """纯内存用例。敏感样本全部在运行时拼出来，避免门禁源码本身成为下一次命中。

    防 issue #109 复发：hex/Base64/URL 三种编码形态必须与明文同罪；
    豁免必须有理由，且只能作用于同一行。
    """
    # 拼接构造，源码里不出现完整敏感值，`check_sensitive.py` 扫自己时保持干净。
    fake_guid = "-".join(("0123abcd", "4567", "89"))
    fake_serial = "".join(("D010", "204D8E55"))
    fake_ip = ".".join(("192", "168", "3", "17"))
    fake_repo = "/".join(("acme", "secret"))
    fake_pat = "ghp" + "_" + "A" * 36
    fake_key = "-----BEGIN " + "RSA " + "PRIVATE KEY" + "-----"
    fake_path_win = "C:" + "/" + "Users" + "/" + "VerNe" + "/AppData/Local"
    fake_path_bash = "/" + "c" + "/" + "Users" + "/" + "VerNe" + "/AppData"
    fake_path_home = "/" + "home" + "/" + "alice" + "/.ssh/id_rsa"
    fake_path_placeholder = "C:" + "/" + "Users" + "/" + "<name>" + "/..."
    fake_path_private_dir = "C:" + "/" + "Users" + "/" + "..." + "/" + "知更鸟"
    hex_guid = fake_guid.encode("utf-8").hex()
    b64_guid = base64.b64encode(fake_guid.encode("utf-8")).decode("ascii")
    url_guid = fake_guid.replace("-", "%2D")

    cases: list[tuple[str, str, bool, set[str]]] = [
        ("明文 GUID 必红", f"GUID={fake_guid}", True, {"guid"}),
        ("hex 编码 GUID 必红", f"raw={hex_guid}", True, {"guid"}),
        ("Base64 编码 GUID 必红", f"blob={b64_guid}", True, {"guid"}),
        ("URL 编码 GUID 必红", f"q={url_guid}", True, {"guid"}),
        ("SN= 长串必红", f"SN={fake_serial}0000000000", True, {"serial"}),
        ("12–16 位大写 hex 序列号必红", fake_serial, True, {"serial"}),
        ("内网 IP 192.168 必红", fake_ip, True, {"private-ip"}),
        ("内网 IP 10.x 必红", ".".join(("10", "0", "0", "1")), True, {"private-ip"}),
        ("内网 IP 172.16 必红", ".".join(("172", "16", "0", "1")), True, {"private-ip"}),
        ("公网 IP 不报", "8.8.8.8", False, set()),
        ("172.32 不是内网段", "172.32.0.1", False, set()),
        ("Windows 用户路径必红", fake_path_win, True, {"user-path"}),
        ("Git Bash 用户路径必红", fake_path_bash, True, {"user-path"}),
        ("Unix home 路径必红", fake_path_home, True, {"user-path"}),
        ("纯占位符路径不报", fake_path_placeholder, False, set()),
        ("掩码用户但真实目录名必红", fake_path_private_dir, True, {"user-path"}),
        ("私有仓库标记必红", "仓库 `" + fake_repo + "`（" + "private" + "）", True, {"private-repo"}),
        ("私有仓库名字面量必红", f"remote={fake_repo}", True, {"private-repo"}),
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
    ]

    bad = 0
    for name, text, expect_red, expect_categories in cases:
        hits = scan_text(text, "self-test.txt", fake_repo)
        got_red = bool(hits)
        got_categories = {h.category for h in hits}
        ok = got_red == expect_red and (not expect_red or got_categories == expect_categories)
        if not ok:
            bad += 1
        detail = f"（期望{'红' if expect_red else '绿'}，实际{'红' if got_red else '绿'}"
        if got_red:
            detail += f"，类别 {sorted(got_categories)}"
        detail += "）"
        print(f"[{'PASS' if ok else 'FAIL'}] {name}{detail}")
        if not ok:
            for hit in hits:
                print(f"        {hit.path}:{hit.line} [{hit.category}/{hit.encoding}] {hit.masked()}")

    # --fix 的幂等性：同一批命中替换两次，第二次必须没有可替换项。
    fix_text = "GUID=" + fake_guid + "\nSN=" + fake_serial + "0000\n"
    first = scan_text(fix_text, "fix-test.txt", fake_repo)
    lines = fix_text.split("\n")
    for hit in sorted(first, key=lambda h: (h.line, -h.start)):
        lines[hit.line - 1] = lines[hit.line - 1][:hit.start] + hit.placeholder() + lines[hit.line - 1][hit.end:]
    fixed = "\n".join(lines)
    second = scan_text(fixed, "fix-test.txt", fake_repo)
    ok = bool(first) and not second
    if not ok:
        bad += 1
    print(f"[{'PASS' if ok else 'FAIL'}] --fix 幂等（第一次 {len(first)} 处，第二次 {len(second)} 处）")

    # 占位符是「同值同符」：明文与 hex 必须落到同一个标识，换值必须换标识。
    plain_hits = scan_text("GUID=" + fake_guid, "ph-test.txt", fake_repo)
    hex_hits = scan_text("raw=" + hex_guid, "ph-test.txt", fake_repo)
    other_guid = "-".join(("89abcdef", "0123", "45"))
    other_hits = scan_text("GUID=" + other_guid, "ph-test.txt", fake_repo)
    plain_ph = plain_hits[0].placeholder() if plain_hits else "无命中"
    hex_ph = hex_hits[0].placeholder() if hex_hits else "无命中"
    other_ph = other_hits[0].placeholder() if other_hits else "无命中"
    ok = (
        bool(plain_hits and hex_hits and other_hits)
        and plain_ph == hex_ph
        and plain_ph != other_ph
        and re.fullmatch(r"<redacted:guid:[0-9a-f]{6}>", plain_ph) is not None
    )
    if not ok:
        bad += 1
    print(
        f"[{'PASS' if ok else 'FAIL'}] 占位符同值同符 / 换值换符"
        f"（明文 {plain_ph}，hex {hex_ph}，另一值 {other_ph}）"
    )

    if bad:
        print(f"== 自检红 {bad} 项 ==", file=sys.stderr)
        return 2
    print(f"== 自检全绿：{len(cases) + 2} 项 ==")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="敏感信息门禁（详见 issue #109）")
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="门禁模式：命中即非零退出（默认）")
    mode.add_argument("--warn", action="store_true", help="只报告不阻断，用于首版落地")
    mode.add_argument("--fix", action="store_true", help="真正改写文件，改完自动复扫")
    mode.add_argument("--self-test", action="store_true", help="跑内置用例")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()

    repo_slug = _repo_slug()
    if not repo_slug:
        print("[提示] 读不到 origin 的 owner/repo，跳过「私有仓库名字面量」规则", file=sys.stderr)

    hits, scanned, skipped = scan_repo(repo_slug)

    if args.fix:
        if not hits:
            print(f"[绿] {_summary(hits, scanned, skipped)}")
            return 0
        changed = _fix(hits)
        print(f"[改写] 共修改 {len(changed)} 个文件：{', '.join(changed) if changed else '无'}")
        # 自证：改写后按同一口径再扫一遍。
        remaining, scanned2, skipped2 = scan_repo(repo_slug)
        if remaining:
            _print_hits(remaining, warn=False)
            print(f"[红] 改写后仍有 {len(remaining)} 处命中，需人工处理", file=sys.stderr)
            return 1
        print(f"[绿] 自证通过：{_summary(remaining, scanned2, skipped2)}")
        return 0

    _print_hits(hits, warn=args.warn)
    summary = _summary(hits, scanned, skipped)
    if args.warn:
        print(f"[WARN] {summary}")
        print("[WARN] 当前为 warn 模式，不阻断；转 block 见 #110（依赖 #100 清洗完成）")
        return 0
    if hits:
        print(f"[红] {summary}", file=sys.stderr)
        return 1
    print(f"[绿] {summary}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
