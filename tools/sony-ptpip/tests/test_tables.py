#!/usr/bin/env python3
"""opcode / RESP / 帧类型表一致性用例（#224）。

防复发：`probe.py` 与 `parse_ptpip.py` 的 opcode 名字漂移、死表项与写 opcode 混入。
运行：python tools/sony-ptpip/tests/test_tables.py
说明：本仓库不引单测框架，也未把本用例挂进门禁（挂门禁会牵动 AGENTS.md 生成段）；
      手动执行即可，PR 正文贴原始输出。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import parse_ptpip  # noqa: E402
import probe  # noqa: E402

checks = 0
failed = 0


def check(name, cond, detail=""):
    global checks, failed
    checks += 1
    if cond:
        print(f"[PASS] {name}")
    else:
        failed += 1
        print(f"[FAIL] {name} {detail}")


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

print(f"\n== 全过：{checks} 条 ==" if failed == 0 else f"\n== 失败：{failed}/{checks} 条 ==")
sys.exit(0 if failed == 0 else 1)
