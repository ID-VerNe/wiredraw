#!/usr/bin/env python3
"""敏感信息门禁：把「换个编码又贴回来」的隐私值挡在推送之前。

设计取自四个成熟方案，只抄适合本仓库的部分（详见 issue #109）：

- Apache Magpie：确定性占位符（同值同符、可重复执行）、豁免必须显式带理由；
- gitleaks：集中式规则表 + allowlist、默认只读报告、`--redact` 式打码预览；
- detect-secrets：行内 allowlist、只盯「新增」的思路（首版先用 `--warn` 输出基线，转 block 见 #110）；
- ggshield / gitleaks：挂在 pre-push；本仓库只走 `scripts/gates.json` 的 static 分片，
  `.githooks/pre-push` 不再写第二份命令。

只扫 `git ls-files` 里的文本文件，跳过二进制；Python 标准库，离线可跑。

已知边界（不要指望它兜住这些）：
- **不扫 commit message、PR 正文与 git 历史**。本门禁的起因就是 PR 正文里贴了换编码的值；
  那一条由 issue #125 在 `support` 档补「读 PR 正文与评论」的门禁，不在本脚本范围。
- **跨行切断的 token 不保证命中**：逐行扫描，一段值被换行拆开就看不到完整形态。
- `--fix` 只改工作区文件，不改 git 历史，也不自动 `git add` / `git commit`。

用法：
    python scripts/check_sensitive.py --check      # 门禁模式：命中即非零退出
    python scripts/check_sensitive.py --warn       # 只报告、退出 0（演练或排查基线用）
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
import tempfile
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

# 路径体字符：遇到空白、引号、Markdown 结构符与中英文标点就停。
# 必须排除 `)` / `]` / `）` / `】`：否则 Markdown 链接的闭括号会被吞进路径，
# `--fix` 后链接结构被破坏而自证仍绿（#117 评审 B2）。
_PATH_BODY = r"[^\s\"'<>|`)\]）】,，。；：、！？]+"

# 已知的 RFC 示例 UUID：不是真人数据，显式放行。值在运行时拼出，避免门禁源码自己命中
# 「配对 GUID」规则（#117 评审非阻断 2）。
KNOWN_EXAMPLE_UUIDS = {
    "-".join(("550e8400", "e29b", "41d4", "a716", "446655440000")),
    "-".join(("123e4567", "e89b", "12d3", "a456", "426614174000")),
}

# 仓库自身身份文档：仓库名与可见性标记写在这里是产品事实，不是泄露；`--fix` 若把它们
# 改成占位符会直接破坏文档。只豁免 private-repo 一个类别，其余类别照常扫（#117 评审非阻断 1）。
SELF_IDENTITY_DOCS = {
    "AGENTS.md", "README.md", "CONTRIBUTING.md", "docs/contributing/PR_GUIDE.md",
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
    """GUID 候选判定：既要抓住索尼的截断形态，也别把标准示例 UUID 全判红。"""
    # 排除 `20260508-1234-56` 这类日期串：必须同时含字母与数字。
    if not _has_letter_and_digit(value):
        return False
    low = value.lower()
    if low in KNOWN_EXAMPLE_UUIDS:
        return False
    parts = low.split("-")
    # 标准 8-4-4-4-12 形态按 RFC 4122 判版本位与变体位：版本 0 / 6–15、
    # 变体位不在 8–b 的都不是真 UUID（#117 评审非阻断 2）。
    if list(map(len, parts)) == [8, 4, 4, 4, 12]:
        version = int(parts[2][0], 16)
        variant = int(parts[3][0], 16)
        if version not in (1, 2, 3, 4, 5) or variant not in (8, 9, 10, 11):
            return False
    # 8-4-2 / 8-4-4 等索尼截断形态没有版本位，不能套 RFC 判定。
    return True


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
        re.compile(r"(?<![A-Za-z0-9])[A-Za-z]:[\\/]+Users[\\/]+" + _PATH_BODY, re.I),
        accept=_path_ok,
    ),
    Rule(
        "user-path",
        "Git Bash 用户路径",
        # 这一行是规则定义本身，会被 Unix 路径规则扫到；豁免必须带理由。
        re.compile(r"(?<![A-Za-z0-9])/(?:c|d|e|f|g|mnt/[c-z])/Users/" + _PATH_BODY, re.I),  # allow-sensitive: 规则定义本身
        accept=_path_ok,
    ),
    Rule(
        "user-path",
        "Unix 用户路径",
        re.compile(r"(?<![A-Za-z0-9])/(?:home|Users)/" + _PATH_BODY, re.I),
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

# 编码形态候选：hex / 重新分组的 hex / Base64 / URL 编码。只做「解码后再跑明文规则」，不猜密钥。
# 门槛取 14：索尼 8-4-2 配对 GUID 去掉短横线正好 14 位，写 16 会把这种换形漏掉
# （#117 二轮评审阻断 2）。12 位与「无字母」的 hex 仍由明文规则挡在外面。
HEX_TOKEN_RE = re.compile(r"(?<![0-9A-Fa-f])[0-9A-Fa-f]{14,}(?![0-9A-Fa-f])")
# 4-4-4-2 / 4-4-4-4 这类重新分组：短横、冒号或单空格分隔。去掉分隔符后
# 总 hex 长度 ≥12 才当候选（配对 GUID 的 8-4-2 重排后是 14 位，#117 评审 B4）。
GROUPED_HEX_RE = re.compile(
    r"(?<![0-9A-Fa-f])[0-9A-Fa-f]{4}(?:[-: ][0-9A-Fa-f]{2,4}){2,}(?![0-9A-Fa-f])"
)
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
    # 显式去掉 `.git`：否则规则拿 `owner/repo.git` 去匹配，源文件里只写 `owner/repo`
    # 的引用反而漏报（#117 评审 B3）。
    return f"{m.group(1)}/{m.group(2).removesuffix('.git')}"


def build_rules(repo_slug: str | None) -> list[Rule]:
    rules = list(RULES)
    if repo_slug:
        # 私有仓库名本身：用运行时从远端解析出的字面量，避免把名字写进门禁源码。
        rules.append(
            Rule(
                "private-repo",
                "私有仓库名",
                re.compile(
                    # 右侧允许 `.`：`owner/repo.git` 里的仓库名后面跟的是点，
                    # 旧写法把这个点排掉，导致带 `.git` 的 URL 漏报（#117 评审 B3）。
                    # GitHub 的 owner/repo 在 URL 里不区分大小写：全小写形态同样要报
                    # （#117 二轮评审阻断 1）。占位符按 `lower()` 生成，同值同符不受影响。
                    r"(?<![A-Za-z0-9_.-])" + re.escape(repo_slug) + r"(?![A-Za-z0-9_-])",
                    re.IGNORECASE,
                ),
            )
        )
    return rules


def _decoded_variants(token: str, kind: str) -> list[str]:
    """一种编码候选 → 若干可能文本；解不出来就返回空。"""
    variants: list[str] = []
    if kind == "grouped-hex":
        # 重新分组不是「十六进制编码」，只是把同一串 hex 数字换了分隔符：
        # 去掉分隔符后按 GUID 习惯重新分组，交给明文规则（#117 评审 B4）。
        compact = re.sub(r"[-: ]", "", token)
        if len(compact) < 12:
            return []
        if len(compact) >= 32:
            return [f"{compact[0:8]}-{compact[8:12]}-{compact[12:16]}-{compact[16:20]}-{compact[20:32]}"]
        if len(compact) >= 16:
            return [f"{compact[0:8]}-{compact[8:12]}-{compact[12:16]}-{compact[16:]}"]
        return [f"{compact[0:8]}-{compact[8:12]}-{compact[12:]}"]
    if kind == "hex":
        # 14 位是 8-4-2 配对 GUID 去掉短横线后的长度：直接按 8-4-2 重新插回短横线，
        # 交给明文规则（#117 二轮评审阻断 2）。
        if len(token) == 14:
            variants.append(f"{token[0:8]}-{token[8:12]}-{token[12:]}")
        try:
            raw = bytes.fromhex(token)
        except ValueError:
            return variants
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
                # Markdown 里路径常被反引号/句号/闭括号包住；标点不算路径本身。
                trimmed = value.rstrip(".,;:)]}）】，。；：、！？")
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
    for kind, token_re in (
        ("hex", HEX_TOKEN_RE),
        ("grouped-hex", GROUPED_HEX_RE),
        ("base64", B64_TOKEN_RE),
        ("url", URL_TOKEN_RE),
    ):
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
    # 仓库自身的身份文档只放行 private-repo：仓库名与可见性标记在那儿是产品事实，
    # 不是泄露；`--fix` 若改写会破坏文档（#117 评审非阻断 1）。
    if rel in SELF_IDENTITY_DOCS:
        hits = [h for h in hits if h.category != "private-repo"]
    return hits


def _looks_utf16le(data: bytes) -> bool:
    """无 BOM 的 UTF-16LE：ASCII 为主的文本在奇数位大量是 NUL。

    只看前 4 KiB；中文占比高的 UTF-16 可能判不出来，属于已知边界（宁可漏，不误伤二进制）。
    """
    sample = data[:4096]
    if len(sample) < 16:
        return False
    odd = sample[1::2]
    return odd.count(0) / len(odd) >= 0.5


def _read_text_ex(path: Path) -> tuple[str | None, tuple[str, bytes]]:
    """读文本并记住原编码，供 `--fix` 按原样回写。

    返回 (文本, (codec, bom))；二进制或解不出来返回 (None, ...)。
    """
    if path.suffix.lower() in BINARY_SUFFIXES:
        return None, ("", b"")
    try:
        data = path.read_bytes()
    except OSError:
        return None, ("", b"")
    if data[:2] == b"\xff\xfe":
        return data[2:].decode("utf-16-le", errors="replace"), ("utf-16-le", b"\xff\xfe")
    if data[:2] == b"\xfe\xff":
        return data[2:].decode("utf-16-be", errors="replace"), ("utf-16-be", b"\xfe\xff")
    if b"\x00" in data[:8192]:
        # 无 BOM 的 UTF-16LE 以前会被当二进制整文件跳过（#117 评审 B5）。
        if _looks_utf16le(data):
            return data.decode("utf-16-le", errors="replace"), ("utf-16-le", b"")
        return None, ("", b"")
    try:
        return data.decode("utf-8"), ("utf-8", b"")
    except UnicodeDecodeError:
        pass
    # 中文 Windows 上的历史文件常是 GBK；UTF-8 失败再回退 gb18030（#117 评审 B5）。
    try:
        return data.decode("gb18030"), ("gb18030", b"")
    except UnicodeDecodeError:
        return None, ("", b"")


def _read_text(path: Path) -> str | None:
    """读文本；二进制（扩展名或文件头 NUL）返回 None。"""
    return _read_text_ex(path)[0]


def _write_text(path: Path, text: str, encoding: tuple[str, bytes]) -> None:
    """按读入时的编码与 BOM 回写，避免 `--fix` 改写非命中区域（#117 评审非阻断 3）。"""
    codec, bom = encoding
    path.write_bytes(bom + text.encode(codec))


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
    src_hits = scan_text(Path(__file__).read_text(encoding="utf-8"), "scripts/check_sensitive.py", fake_repo)
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

    if bad:
        print(f"== 自检红 {bad} 项 ==", file=sys.stderr)
        return 2
    print(f"== 自检全绿：{total} 项 ==")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="敏感信息门禁（详见 issue #109）")
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="门禁模式：命中即非零退出（默认）")
    mode.add_argument("--warn", action="store_true", help="只报告不阻断（首版落地与排查基线用）")
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
        print("[WARN] 当前为 warn 模式，只报告不阻断")
        return 0
    if hits:
        print(f"[红] {summary}", file=sys.stderr)
        return 1
    print(f"[绿] {summary}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
