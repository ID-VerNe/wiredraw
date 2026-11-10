#!/usr/bin/env python3
"""敏感信息门禁的检测规则与 allowlist（#313 从 `check_sensitive.py` 拆出）。

本模块只回答「什么算敏感」：规则表、行内豁免语法、公开默认值 allowlist、仓库身份文档豁免名单。
扫描引擎在 `sensitive_scan.py`，写盘改写器在 `sensitive_fix.py`，报告与 CLI 在
`check_sensitive.py`，用例在 `sensitive_selftest.py`。

拆开的原因：调整规则表时必须同时确认自检夹具与 `--fix` 的替换位置，三者原本混在同一个
一千多行的文件里，没有任何边界逼着一起看。规则表本身仍是**唯一事实源**——别处不许再写第二份。
"""
from __future__ import annotations

import re
from dataclasses import dataclass


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
class AllowEntry:
    """allowlist 的一条：类别 + 精确值 + 具体理由（#110）。

    只放行「对外公开、值本身固定」的常量，匹配是**类别 + 精确值**：
    不做前缀 / 后缀 / 通配 / 网段 / CIDR / 端口匹配，相邻地址与大小写变体都照常报红。
    """

    category: str
    value: str
    reason: str


# 公开默认值白名单。这些值在任何仓库里都一样，逐个加行内豁免既啰嗦又容易写成模板话，
# 所以集中登记；每条理由必须是具体事实，`_validate_allowlist()` 会把空话判红（main 拒绝运行）。
ALLOWLIST: tuple[AllowEntry, ...] = (
    AllowEntry(
        "private-ip",
        "192.168.122.1",
        "索尼相机 Wi-Fi AP 的公开默认网关；docs/research/a7c-usb-raw-2026-11.md 1.1 节记录的 11 个第三方参考仓库全部硬编码此地址，不是本机内网地址",
    ),
    AllowEntry(
        "private-ip",
        "10.0.0.1",
        "同一套 Wi-Fi JSON 协议里的相机 AP 默认网关；docs/archive/2026-11-audit/evidence-notes-audit.md 第 6 节记录的多个第三方仓库硬编码此地址，不是本机内网地址",
    ),
    AllowEntry(
        "secret",
        "android",
        "Android debug keystore 的公开默认口令（AOSP 通用值）；tools/android-mtp-probe/build.sh 的 `--ks-pass pass:` 参数用它签同一把 rea-debug.keystore",
    ),
)


def _allow_key(category: str, value: str) -> str:
    """把值归一成比较用的键；只做大小写折叠与去包裹引号，不做任何模糊化。"""
    text = value.strip()
    if category in ("guid", "serial", "private-ip"):
        return text.lower()
    if category == "secret":
        return text.strip("\"'")
    return text


def _validate_allowlist(entries: tuple[AllowEntry, ...] = ALLOWLIST) -> list[int]:
    """返回理由不合格的条目下标：空话 / 模板话 / `<reason>` 这类占位都不算事实。"""
    bad: list[int] = []
    for idx, entry in enumerate(entries):
        reason = entry.reason.strip()
        if (
            len(reason) < 12
            or reason.lower() in ALLOW_TEMPLATE_REASONS
            or re.fullmatch(r"<[^>]*>", reason)
        ):
            bad.append(idx)
    return bad


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
        # 这一行是 Unix 路径规则的正则源码，`_PATH_BODY` 要求至少一个真实路径字符，
        # 而源码里 `/Users/` 后面紧跟引号，所以它不构成命中（实测 #110）。
        re.compile(r"(?<![A-Za-z0-9])/(?:c|d|e|f|g|mnt/[c-z])/Users/" + _PATH_BODY, re.I),
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
