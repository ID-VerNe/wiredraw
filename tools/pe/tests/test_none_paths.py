#!/usr/bin/env python3
"""G15（#226）：pe_inspect 的 RVA 映射失败必须给可读报错，而不是 TypeError。

不依赖真实 PE 样本：用 PE.__new__ 造最小对象，把 sections 留空，
让 rva2off() 对任何 RVA 都返回 None，直接打 exports() / imports() 的 None 分支。
"""
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("pe_inspect", ROOT / "pe_inspect.py")
pe_inspect = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pe_inspect)

fails = []


def check(name, fn):
    try:
        fn()
    except AssertionError as e:
        fails.append(name)
        print(f"[FAIL] {name} {e}")
    except Exception as e:  # 用例自身出错也算红
        fails.append(name)
        print(f"[FAIL] {name} 意外异常 {type(e).__name__}: {e}")
    else:
        print(f"[PASS] {name}")


def fake_pe(dirs):
    pe = pe_inspect.PE.__new__(pe_inspect.PE)
    pe.data = b"\0" * 256
    pe.sections = []          # 任何 RVA 都映射不到
    pe.is64 = False
    pe.dirs = dirs
    return pe


def assert_readable(fn, what):
    """必须抛 ValueError 且消息里带 RVA 与用途；TypeError 不算通过。"""
    try:
        fn()
    except ValueError as e:
        msg = str(e)
        assert "不在任何节内" in msg, f"消息缺定位：{msg}"
        assert "RVA" in msg, f"消息缺 RVA：{msg}"
        assert what in msg, f"消息缺用途 {what}：{msg}"
    except TypeError as e:
        raise AssertionError(f"仍是 TypeError：{e}")
    else:
        raise AssertionError("没有报错")


def exports_bad_rva():
    pe = fake_pe([(0x1000, 0x28)] + [(0, 0)] * 15)
    assert_readable(pe.exports, "导出表")


def imports_bad_rva():
    pe = fake_pe([(0, 0), (0x1000, 0x28)] + [(0, 0)] * 14)
    assert_readable(pe.imports, "导入表")


def cstr_none_ok():
    pe = fake_pe([(0, 0)] * 16)
    assert pe.cstr(0x9000) is None, "cstr 对映射不到的 RVA 应返回 None"
    assert pe.rva2off(0x9000) is None


def empty_dirs_ok():
    pe = fake_pe([(0, 0)] * 16)
    assert pe.exports() == [], "无导出表应返回空列表"
    assert pe.imports() == {}, "无导入表应返回空字典"


check("exports 坏 RVA 给可读 ValueError", exports_bad_rva)
check("imports 坏 RVA 给可读 ValueError", imports_bad_rva)
check("cstr 映射失败仍返回 None", cstr_none_ok)
check("空数据目录返回空结果", empty_dirs_ok)

print()
if fails:
    print(f"== 失败：{len(fails)}/4 条 ==")
    sys.exit(1)
print("== 全过：4 条 ==")
