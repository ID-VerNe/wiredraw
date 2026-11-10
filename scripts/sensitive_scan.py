#!/usr/bin/env python3
"""敏感信息扫描引擎（#313 从 `check_sensitive.py` 拆出），**只读**。

对外入口两个：`scan_text()`（内存字符串，自检与 PR 正文扫描用）与 `scan_repo()`
（`git ls-files` 全仓，ci 门禁用）。`check_sensitive.py`、`check_pr_body.py`、
`check_commit_messages.py` 共用这一份实现，不许在别处再写第二份解码 / 豁免 / 打码逻辑。

规则表在 `sensitive_rules.py`。本模块不写盘：写工作区的唯一入口是 `sensitive_fix.py`。
"""
from __future__ import annotations

import base64
import binascii
import hashlib
import re
import urllib.parse
from dataclasses import dataclass
from pathlib import Path

from _common import EnvProblem, git_ls_files, run
from sensitive_rules import (
    ALLOWLIST,
    ALLOW_RE,
    ALLOW_TEMPLATE_REASONS,
    SELF_IDENTITY_DOCS,
    Rule,
    _allow_key,
    build_rules,
)

ROOT = Path(__file__).resolve().parent.parent

# 二进制扩展名：直接跳过，不读内容。真正的判定还会 sniff 文件头里的 NUL。
BINARY_SUFFIXES = {
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".bmp", ".tif", ".tiff",
    ".pdf", ".zip", ".gz", ".bz2", ".7z", ".jar", ".keystore", ".apk", ".aar",
    ".so", ".dll", ".exe", ".class", ".ttf", ".otf", ".woff", ".woff2",
    ".mp4", ".mov", ".mp3", ".pcap", ".pcapng", ".bin", ".arw", ".raw",
}

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


def _is_allowlisted(hit: Hit) -> bool:
    """类别 + 精确值命中 ALLOWLIST 才放行；不做前缀 / 通配 / 网段匹配（#110）。"""
    key = _allow_key(hit.category, hit.value)
    return any(
        hit.category == entry.category and key == _allow_key(entry.category, entry.value)
        for entry in ALLOWLIST
    )


def _repo_slug() -> str | None:
    """从 origin 推 `owner/repo`，不在源码里写死私有仓库名。

    https://github.com/o/r.git 与 git@github.com:o/r.git 都要能解析；取不到就跳过这条规则，
    不能让门禁因为网络/远端缺失而假红。
    """
    try:
        out = run(["git", "remote", "get-url", "origin"])
    except EnvProblem:
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


def _scan_line(
    line: str,
    rel: str,
    line_no: int,
    rules: list[Rule],
    allow_exemptions: bool = True,
) -> list[Hit]:
    # PR 正文/评论是外部输入，任何人都能在行尾补一条 `allow-sensitive` 自带豁免；
    # `allow_exemptions=False` 只给这类调用用（#125）。
    if allow_exemptions and _allow_reason(line):
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


def scan_text(
    text: str,
    rel: str,
    repo_slug: str | None = None,
    allow_exemptions: bool = True,
) -> list[Hit]:
    """纯文本扫描入口；`--self-test` 直接喂内存字符串。

    `allow_exemptions=False` 用于 PR 正文/评论这类外部输入：那里不能认行内豁免，
    否则任何人都能自带理由绕过门禁；脚本内 ALLOWLIST 仍然生效（#125）。
    """
    rules = build_rules(repo_slug)
    hits: list[Hit] = []
    for no, line in enumerate(text.split("\n"), 1):
        hits.extend(_scan_line(line, rel, no, rules, allow_exemptions))
    # 对外公开的固定默认值（相机 AP 网关、debug keystore 口令）集中放行，
    # 避免在文档里逐行豁免；只按「类别 + 精确值」匹配（#110）。
    hits = [h for h in hits if not _is_allowlisted(h)]
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
        # 读盘失败不是「二进制」：静默归入跳过统计会让门禁假绿（#203）。
        # 只留给调用方加相对路径，异常文本里不带本机绝对路径。
        raise
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


def tracked_files() -> list[str]:
    """被跟踪文件清单；git 用不了抛 `EnvProblem`，由 main 映射成退出码 2（#204）。"""
    return git_ls_files()


def scan_repo(repo_slug: str | None) -> tuple[list[Hit], int, int]:
    hits: list[Hit] = []
    scanned = skipped = 0
    for rel in tracked_files():
        try:
            text = _read_text(ROOT / rel)
        except OSError as e:
            # 读盘失败不是「内容不合格」而是「这次扫描没做成」：交给 main 映射成退出码 2（#204）。
            raise EnvProblem(
                f"被跟踪文件读不到：{rel}（{e.strerror or '未知错误'}）"
            ) from e
        if text is None:
            skipped += 1
            continue
        scanned += 1
        hits.extend(scan_text(text, rel, repo_slug))
    return hits, scanned, skipped
