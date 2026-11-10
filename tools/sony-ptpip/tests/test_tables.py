#!/usr/bin/env python3
"""opcode / RESP / 帧类型表一致性用例（#224）。

防复发：`probe.py` 与 `parse_ptpip.py` 的 opcode 名字漂移、死表项与写 opcode 混入。
运行：python tools/sony-ptpip/tests/test_tables.py
说明：本仓库不引单测框架，也未把本用例挂进门禁（挂门禁会牵动 AGENTS.md 生成段）；
      手动执行即可，PR 正文贴原始输出。
"""
import re
import sys

from _harness import check, setup_imports, summary

ROOT = setup_imports()

import parse_ptpip  # noqa: E402
import probe  # noqa: E402


# 1) probe.py 的 OP 表不允许有死项，唯一例外是文档明确保留的写名常量
src = (ROOT / "probe.py").read_text(encoding="utf-8")
used = set(re.findall(r'OP\["([^"]+)"\]', src))
dead = set(probe.OP) - used
check("probe OP 死项仅限文档化例外", dead <= {"SetDevicePropValueEx"}, f"死项={sorted(dead)}")

# 2) 只读红线：探针表里只允许 0x9209 这一个写名常量（`docs/safety-and-privacy.md` 的「允许的 opcode」节）
WRITE_OPS = {0x9209, 0x1016, 0x100B}
hits = (set(probe.OP.values()) & WRITE_OPS) - {0x9209}
check("probe OP 无额外写 opcode", not hits, f"命中={[hex(c) for c in hits]}")

# 3) 实测口径（captures/stream7.txt:1192-1194）：0x101B 是取回，相机随后回 1 MB；
#    0x100B 是删除类，不能与取回同名。
check("parse 0x101B=GetPartialObject", parse_ptpip.OP_NAMES.get(0x101B) == "GetPartialObject",
      f"实际={parse_ptpip.OP_NAMES.get(0x101B)!r}")
check("parse 0x100B=DeleteObject", parse_ptpip.OP_NAMES.get(0x100B) == "DeleteObject",
      f"实际={parse_ptpip.OP_NAMES.get(0x100B)!r}")

# 4) RESP 表：两边共有码名字必须一致；parse 必须补上 0xA003
shared = set(probe.RC) & set(parse_ptpip.RESP_NAMES)
bad = [(hex(c), probe.RC[c], parse_ptpip.RESP_NAMES[c]) for c in sorted(shared)
       if probe.RC[c].replace("Sony_", "") != parse_ptpip.RESP_NAMES[c].replace("Sony_", "")]
check("probe RC 与 parse RESP 同名", not bad, f"不一致={bad}")
check("parse RESP 含 0xA003", 0xA003 in parse_ptpip.RESP_NAMES)

# 5) 帧类型 1..14 必须有名字（probe.py 的常量与离线描述不许各写一套又漏项）
frame_names = getattr(parse_ptpip, "FRAME_NAMES", {})
missing = [t for t in range(1, 15) if t not in frame_names]
check("帧类型 1..14 均具名", not missing, f"缺失={missing}")

# 6) 帧长上界：两条排障链路必须用同一个值，且不许再退回裸字面量（#315）。
#    旧形态是 1<<24 / 1<<27 十六进制字面量，这里写成算式，避免本文件自己命中
#    「工具目录里不许出现上界字面量」这条断言。
_OLD_BOUND_LITERALS = tuple(f"0x{n:x}" for n in (1 << 24, 1 << 27))
probe_max = getattr(probe, "MAX_FRAME_LEN", None)
parse_max = getattr(parse_ptpip, "MAX_FRAME_LEN", None)
check("两处 MAX_FRAME_LEN 一致", probe_max is not None and probe_max == parse_max,
      f"probe={probe_max!r} parse={parse_max!r}")

literal_hits = [(name, tok) for name in ("probe.py", "parse_ptpip.py")
                for tok in _OLD_BOUND_LITERALS
                if tok in (ROOT / name).read_text(encoding="utf-8")]
check("帧长上界无裸字面量", not literal_hits, f"命中={literal_hits}")

sys.exit(summary())
