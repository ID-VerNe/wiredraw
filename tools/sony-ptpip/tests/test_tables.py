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


# 1) probe.py 的 OP 表每个键都必须被源码调用：死表项会把错名/写 opcode 留在库里
src = (ROOT / "probe.py").read_text(encoding="utf-8")
used = set(re.findall(r'OP\["([^"]+)"\]', src))
check("probe OP 无死项", set(probe.OP) == used, f"死项={sorted(set(probe.OP) - used)}")

# 2) 只读红线：probe 侧不得保留写 opcode（0x9209 SetDevicePropValueEx、0x101B SendPartialObject）
WRITE_OPS = {0x9209, 0x101B}
check("probe OP 不含写 opcode", not (set(probe.OP.values()) & WRITE_OPS),
      f"命中={[hex(c) for c in set(probe.OP.values()) & WRITE_OPS]}")

# 3) PTP 规范：0x100B=GetPartialObject，0x101B=SendPartialObject（写）
check("parse 0x101B=SendPartialObject", parse_ptpip.OP_NAMES.get(0x101B) == "SendPartialObject",
      f"实际={parse_ptpip.OP_NAMES.get(0x101B)!r}")
check("parse 0x100B=GetPartialObject", parse_ptpip.OP_NAMES.get(0x100B) == "GetPartialObject",
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
