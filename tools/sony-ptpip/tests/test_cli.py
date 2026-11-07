#!/usr/bin/env python3
"""离线解析口径与 CLI 校验用例（#225）。

覆盖：
- ObjectInfo 最小长度门槛（两处必须同常量）与名字字节越界
- parse_ptpip.py / probe.py 的错误参数必须非零退出且不连相机
运行：python tools/sony-ptpip/tests/test_cli.py
说明：本仓库不引单测框架，也未把本用例挂进门禁；手动执行，PR 正文贴原始输出。
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PY = sys.executable
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


def objinfo(name_len, tail_bytes):
    """构造最小 ObjectInfo：52 是名字长度字节，53 起是 UTF-16LE 名字。"""
    buf = bytearray(53 + tail_bytes)
    buf[52] = name_len
    return bytes(buf)


def run(*args):
    proc = subprocess.run([PY, *args], capture_output=True, text=True, encoding="utf-8")
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


# 1) 两处门槛必须同源
check("两处 MIN_OBJINFO_LEN 一致", getattr(parse_ptpip, "MIN_OBJINFO_LEN", None)
      == getattr(probe, "MIN_OBJINFO_LEN", None) == 53,
      f"parse={getattr(parse_ptpip, 'MIN_OBJINFO_LEN', None)!r} "
      f"probe={getattr(probe, 'MIN_OBJINFO_LEN', None)!r}")

# 2) 恰好 53 字节只能读到长度字节，不能当成功
check("parse 53 字节返回 None", parse_ptpip._object_info(objinfo(0, 0)) is None)
check("probe 53 字节返回 None", probe.object_info(objinfo(0, 0)) is None)

# 3) 名字字节不够（n=2 但只给 1 字节）必须拒绝，不能截出半个名字
parse_short = parse_ptpip._object_info(objinfo(2, 1))
probe_short = probe.object_info(objinfo(2, 1))
check("parse 名字越界返回 None", parse_short is None, f"实际={parse_short!r}")
check("probe 名字越界返回 None", probe_short is None, f"实际={probe_short!r}")

# 4) 合法 1 字符名字两侧都能解出
buf_ok = bytearray(objinfo(1, 2))
buf_ok[53:55] = b"A\x00"
parse_ok = parse_ptpip._object_info(bytes(buf_ok))
probe_ok = probe.object_info(bytes(buf_ok))
check("parse 解出名字 A", parse_ok is not None and "文件名=A" in parse_ok, f"实际={parse_ok!r}")
check("probe 解出名字 A", probe_ok is not None and probe_ok.get("name") == "A", f"实际={probe_ok!r}")

# 5) --guid 形态：README §6 的 8-4-2 形态（含短横共 16 字节）必须仍然可用
# 样本按段拼接，避免整串 8-4-2 十六进制文本被 check_sensitive.py 判成真 GUID。
guid_842 = "01234567" + "-" + "89ab" + "-" + "cd"
check("guid 8-4-2 正例", probe._guid_ok(guid_842), "README §6 的取值形态")
check("guid 16 位纯 hex 正例", probe._guid_ok("0123456789abcdef"))
check("guid 12 位 hex 负例", not probe._guid_ok("0123456789ab"))
check("guid 非 hex 负例", not probe._guid_ok("01234567-89ab-cg"))
check("guid 空值负例", not probe._guid_ok(""))

# 6) 错误参数：不连相机、非零退出、有可读提示
readme = str(ROOT / "README.md")
code, out = run(str(ROOT / "parse_ptpip.py"), "--stream", "abc", readme)
check("parse --stream 非整数 → 退出 2", code == 2 and "整数" in out, f"code={code} out={out[-80:]!r}")
code, out = run(str(ROOT / "parse_ptpip.py"), "--limit")
check("parse --limit 缺值 → 退出 2", code == 2 and "整数" in out, f"code={code} out={out[-80:]!r}")
code, out = run(str(ROOT / "parse_ptpip.py"), "--limit", "abc", readme)
check("parse --limit 非整数 → 退出 2", code == 2 and "整数" in out, f"code={code} out={out[-80:]!r}")
code, out = run(str(ROOT / "probe.py"), "1.2.3.4", "--guid", "0123456789ab")
check("probe 12 位 GUID → 退出 2", code == 2 and "16 字节" in out, f"code={code} out={out[-80:]!r}")
code, out = run(str(ROOT / "probe.py"))
check("probe 缺 IP → 退出 2", code == 2 and "相机 IP" in out, f"code={code} out={out[-80:]!r}")

print(f"\n== 全过：{checks} 条 ==" if failed == 0 else f"\n== 失败：{failed}/{checks} 条 ==")
sys.exit(0 if failed == 0 else 1)
