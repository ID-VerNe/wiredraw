#!/usr/bin/env python3
"""跨工程格式码与 ARW / JPEG 判定比对门禁（issue #322）。

为什么有这条：导片工具（Gradle，`java.srcDirs("src/com")`）与探针（手搓 `build.sh` 的
`find src`）是两个独立编译单元，没有共享 sourceSet、没有 inter-project 依赖。MTP 格式码
常量和「ARW / JPEG 怎么算」因此各写一份。新增格式（例如 HEIF）或改判定只改一个工程时，
探针的枚举直方图与导片工具的分类会给出不同数字，静态检查发现不了，只能真机复测——
这是本项目最贵的一次排障。

比对什么（结构化抽取，不绑物理行号——行号会随上方插行漂移）：

1. `FMT_<名字> = 0x<值>` 常量表：两个文件的名字→值必须逐项相等；一侧新增或删除常量
   （例如只在一个工程里加 `FMT_HEIF`）也算不一致。
2. ARW 判定：写成 `FMT_ARW || …endsWith("<后缀>")` 的那条判定，两侧后缀字面量集合必须相等。
   比结构而不是比字符串，是因为局部变量名不同（导片工具用 `format`、探针用 `fmt`），
   逐字比对会假红。
3. JPEG 判定：同样形状的那条判定，两侧后缀集合必须等于下面 `JPEG_DIVERGENCE` 的**登记值**。
   当前两侧已知不一致（导片工具多认 `.JPEG`），修它要改探针的产品源码，不在本门禁的范围；
   所以把现状登记下来，任何**新**分叉（例如探针单方面加 `.HEIF`）照样判红。

退出码（`_common` 的具名常量）：0 全绿 / 1 抽到的不一致或判定抽不到 / 2 目标文件缺失或读不到。
3 与 1 必须分开：2 是「这次比对没做成」，1 是「这两份源码对不上」。

回归用例：`python scripts/check_format_codes.py --self-test`
"""

from __future__ import annotations

import argparse
import re
import sys

from _common import CONTENT_FAIL, ENV_FAIL, OK, ROOT, EnvProblem

# 被比对的两个文件：标签 → 相对仓库根的路径。改路径只改这里（自检断言条目数）。
TARGETS = (
    ("导片工具", "tools/android-mtp-import/src/com/rea/mtpimport/Catalog.java"),
    ("探针", "tools/android-mtp-probe/src/com/rea/mtpprobe/Main.java"),
)

# 常量行：`FMT_ARW = 0xB101;`（两侧都有 `static final int` 前缀，值必须是 hex）。
CONST = re.compile(r"\bFMT_([A-Z][A-Z0-9_]*)\s*=\s*(0x[0-9A-Fa-f]+)\s*;")
# 判定表达式：从 `FMT_XXX ||` 到语句边界（`;` 是 return 语句，`{` 是 if 分支体）。
# 用「名字 ||」定位是刻意的：有人把 `format == FMT_ARW` 写成裸 `0xB101` 时，这里抽不到，
# 门禁会以「判定抽不到」判红，而不是悄悄跳过。
COND = r"\b{name}\s*\|\|[^;{{}}]*"
# 判定里认的后缀字面量：`…endsWith(".ARW")`。
SUFFIX = re.compile(r'\.endsWith\("(\.[A-Za-z0-9]+)"\)')
# 要求必须存在的常量名：改名 / 删名要显式改这里，避免「两台机器都少一个码」被当成一致。
REQUIRED_CONSTS = ("FMT_FOLDER", "FMT_ARW", "FMT_JPEG")

# 已知且已裁定的 JPEG 后缀分叉：标签 → 该侧应当有的后缀集合（已归一化为大写）。
# 偏离登记值即红；命中登记值时只打一行 [注]。
# 理由：导片工具的 `isJpeg()` 认 `.JPG` 与 `.JPEG`，探针的 `scanObjects()` 只认 `.JPG`；
# 修它必须改探针产品源码（issue #322 明确不做跨工程收口），所以先登记、后收口。
JPEG_DIVERGENCE = {
    "导片工具": (".JPEG", ".JPG"),
    "探针": (".JPG",),
}
JPEG_DIVERGENCE_WHY = (
    "导片工具 isJpeg() 多认 .JPEG；探针 scanObjects() 只认 .JPG。"
    "修它要改探针 src/（#322 明确不做），本门禁只钉住现状：新增分叉必红"
)


class ExtractionError(Exception):
    """判定结构抽不到：源码形状变了，门禁没法比对。

    归内容类（退出码 1），不是环境类：文件读得到，只是不再是能比对的写法。
    """


def facts(text: str) -> dict:
    """从一段 Java 源码里抽三类事实：常量表、ARW 后缀集合、JPEG 后缀集合。"""
    consts: dict[str, str] = {}
    for name, value in CONST.findall(text):
        key = f"FMT_{name}"
        if key in consts and consts[key].lower() != value.lower():
            raise ExtractionError(f"{key} 在同一文件里定义了两个不同的值：{consts[key]} / {value}")
        consts[key] = value

    def suffixes(const: str) -> tuple[str, ...]:
        pattern = re.compile(COND.format(name=const))
        matched = pattern.findall(text)
        if len(matched) != 1:
            raise ExtractionError(
                f"找不到唯一一条 `{const} || …endsWith(…)` 判定（命中 {len(matched)} 处）"
                "——判定写法变了就得同步改本门禁，不能默默跳过比对"
            )
        found = {s.upper() for s in SUFFIX.findall(matched[0])}
        if not found:
            raise ExtractionError(f"`{const} ||` 的判定里没有 endsWith(\"…\") 后缀")
        return tuple(sorted(found))

    return {"consts": consts, "arw": suffixes("FMT_ARW"), "jpeg": suffixes("FMT_JPEG")}


def compare(extracted: dict[str, dict]) -> tuple[list[str], list[str]]:
    """逐项比对；返回（红的问题, 不判红的注解）。"""
    problems: list[str] = []
    notes: list[str] = []
    labels = list(extracted)
    left, right = labels[0], labels[-1]
    a, b = extracted[left], extracted[right]

    for const in REQUIRED_CONSTS:
        if const not in a["consts"] or const not in b["consts"]:
            problems.append(f"常量缺失：{left} {'有' if const in a['consts'] else '无'} {const}，"
                            f"{right} {'有' if const in b['consts'] else '无'} {const}")
    only_left = sorted(set(a["consts"]) - set(b["consts"]))
    only_right = sorted(set(b["consts"]) - set(a["consts"]))
    if only_left or only_right:
        problems.append(
            f"常量集合不同：只有 {left} 定义 {only_left or '（无）'}，"
            f"只有 {right} 定义 {only_right or '（无）'}"
        )
    for name in sorted(set(a["consts"]) & set(b["consts"])):
        # hex 大小写不改变码值：`0xB101` 与 `0xb101` 是同一个格式码。
        if a["consts"][name].lower() != b["consts"][name].lower():
            problems.append(
                f"常量值不同：{name} = {a['consts'][name]}（{left}） / "
                f"{b['consts'][name]}（{right}）"
            )

    if a["arw"] != b["arw"]:
        problems.append(
            f"ARW 判定不同：{left} 认 {'/'.join(a['arw'])}，{right} 认 {'/'.join(b['arw'])}"
            "——两侧必须同款：格式码 + 后缀双判，少一个后缀就会让同一张片在两边分类不同"
        )

    for label in labels:
        registered = JPEG_DIVERGENCE.get(label)
        actual = extracted[label]["jpeg"]
        if actual != registered:
            problems.append(
                f"JPEG 判定与登记值不同：{label} 实际 {'/'.join(actual)}，"
                f"登记 {'/'.join(registered) if registered else '（无登记）'}"
                "——新增或修改后缀必须同时改两个工程，并在本门禁更新登记值"
            )
    if not problems:
        detail = "；".join(f"{label} {'/'.join(extracted[label]['jpeg'])}" for label in labels)
        notes.append(f"JPEG 后缀已分叉（登记值，不判红）：{detail}——{JPEG_DIVERGENCE_WHY}")
    return problems, notes


def load_facts() -> dict[str, dict]:
    """读两个目标文件并抽事实；文件缺失 / 读不到抛 EnvProblem（退出码 2）。"""
    extracted: dict[str, dict] = {}
    for label, rel in TARGETS:
        path = ROOT / rel
        if not path.is_file():
            raise EnvProblem(f"找不到 {rel}（{label}）——路径改了要同步改本门禁的 TARGETS")
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as exc:
            raise EnvProblem(f"读不到 {rel}（{label}）：{exc}") from exc
        extracted[label] = facts(text)
    return extracted


def self_test() -> int:
    failures: list[str] = []

    def snippet(arw: str = ".ARW", jpeg: str = '".JPG"', arw_code: str = "0xB101",
                jpeg_code: str = "0x3801", with_arw: bool = True) -> str:
        branch = (
            f"    }} else if (fmt == FMT_ARW || name.toUpperCase(Locale.US).endsWith(\"{arw}\")) {{\n"
            if with_arw else ""
        )
        return (
            "class X {\n"
            "    static final int FMT_FOLDER = 0x3001;\n"
            f"    static final int FMT_ARW = {arw_code};\n"
            f"    static final int FMT_JPEG = {jpeg_code};\n"
            "    void scan() {\n"
            "        if (fmt == FMT_FOLDER) {\n"
            f"{branch}"
            f"    }} else if (fmt == FMT_JPEG || name.toUpperCase(Locale.US).endsWith({jpeg})) {{\n"
            "        }\n"
            "    }\n"
            "}\n"
        )

    def case(label: str, left: str, right: str, *, want_red: bool,
             must: tuple[str, ...] = (), want_notes: int = 0) -> None:
        try:
            pairs = {"导片工具": facts(left), "探针": facts(right)}
        except ExtractionError as exc:
            if want_red and must and all(frag in str(exc) for frag in must):
                return
            failures.append(f"[FAIL] {label}：抽取抛 {exc!r}，期望 {'红' if want_red else '绿'}")
            return
        problems, notes = compare(pairs)
        ok = (bool(problems) == want_red) and all(f in "\n".join(problems) for f in must)
        ok = ok and len(notes) == want_notes
        if not ok:
            failures.append(f"[FAIL] {label}：want_red={want_red} 实际 problems={problems} notes={notes}")

    # 两侧分别按真实源码的形状构造：导片工具多认一个 .JPEG，探针只认 .JPG。
    import_side = snippet(jpeg='".JPG") || upper.endsWith(".JPEG"')
    probe_side = snippet()
    case("两侧命中登记值", import_side, probe_side, want_red=False, want_notes=1)
    case("常量值改坏", import_side, snippet(arw_code="0xA101"), want_red=True, must=("FMT_ARW", "0xA101"))
    case("JPEG 格式码改坏", import_side, snippet(jpeg_code="0x3802"), want_red=True, must=("FMT_JPEG",))
    case("ARW 后缀丢失", import_side, snippet(arw=".ARWX"), want_red=True, must=("ARW 判定不同",))
    # 变异口径：把断言瞄准的子串换掉，而不是加前缀。
    case("判定抽不到", import_side, snippet(with_arw=False), want_red=True, must=("找不到唯一一条",))
    case(
        "单侧新增常量",
        import_side.replace(
            "FMT_JPEG = 0x3801;",
            "FMT_JPEG = 0x3801;\n    static final int FMT_HEIF = 0x3802;",
        ),
        probe_side,
        want_red=True,
        must=("常量集合不同", "FMT_HEIF"),
    )
    # 登记值同时钉住「少一个后缀」和「多一个后缀」两个方向。
    case("JPEG 后缀少一个", snippet(), probe_side, want_red=True,
         must=("JPEG 判定与登记值不同",))
    case("新分叉未登记", import_side, snippet(jpeg='".JPG") || upper.endsWith(".HEIF"'),
         want_red=True, must=("JPEG 判定与登记值不同",))

    # 结构断言：路径表恰好两项、必需常量是这三个、登记值带理由——都不许被悄悄删掉。
    if len(TARGETS) != 2 or [label for label, _ in TARGETS] != ["导片工具", "探针"]:
        failures.append(f"[FAIL] TARGETS：应恰好两项（导片工具 / 探针），实际 {TARGETS}")
    if tuple(REQUIRED_CONSTS) != ("FMT_FOLDER", "FMT_ARW", "FMT_JPEG"):
        failures.append(f"[FAIL] REQUIRED_CONSTS：应为三个格式码常量，实际 {REQUIRED_CONSTS}")
    if set(JPEG_DIVERGENCE) != {"导片工具", "探针"} or not JPEG_DIVERGENCE_WHY.strip():
        failures.append(f"[FAIL] JPEG 分叉登记：两侧都要有登记值且必须写理由，实际 {JPEG_DIVERGENCE}")

    if failures:
        for line in failures:
            print(line)
        print(f"== 自检红 {len(failures)} 项 ==", file=sys.stderr)
        return ENV_FAIL
    print("[绿] 自检通过：正样本 1 条、负样本 7 条、结构断言 3 条")
    return OK


def main() -> int:
    parser = argparse.ArgumentParser(description="跨工程格式码与 ARW / JPEG 判定比对门禁（#322）")
    parser.add_argument("--self-test", action="store_true", help="跑内置正负样本自检")
    args = parser.parse_args()
    if args.self_test:
        return self_test()

    try:
        extracted = load_facts()
        problems, notes = compare(extracted)
    except EnvProblem as exc:
        print(f"[环境] {exc}", file=sys.stderr)
        return ENV_FAIL
    except ExtractionError as exc:
        print(f"[红] {exc}", file=sys.stderr)
        return CONTENT_FAIL

    consts = extracted["导片工具"]["consts"]
    print(f"[统计] 常量 {len(consts)} 个（{' / '.join(sorted(consts))}）逐项比对；"
          f"ARW 判定 {len(extracted['导片工具']['arw'])} 个后缀、JPEG 判定按登记值比对")
    for note in notes:
        print(f"[注] {note}")

    if problems:
        for problem in problems:
            print(f"[红] {problem}", file=sys.stderr)
        print(f"[红] {len(problems)} 处跨工程不一致（issue #322）", file=sys.stderr)
        return CONTENT_FAIL

    print("[绿] 两工程格式码与 ARW 判定一致")
    return OK


if __name__ == "__main__":
    sys.exit(main())
