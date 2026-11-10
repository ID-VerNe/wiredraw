#!/usr/bin/env python3
"""`tools/sony-ptpip/tests/` 下各离线自检脚本的共用夹具（#317）。

此前 `test_cli.py` 与 `test_tables.py` 各自复制了一整套夹具：`ROOT`、`sys.path` 注入、
`check()`、计数与汇总退出。两边逐字相同却各维护一份，给 `check` 加计时 / 跳过计数、
改退出码语义或调整目录层级时，只改一边就会让统计口径悄悄分叉。

对外只有三样：
- `PY`：当前解释器，供需要起子进程的用例使用。
- `setup_imports()`：把 `tools/sony-ptpip` 插到 `sys.path` 首位并返回该目录，
  必须在 `import parse_ptpip` / `import probe` 之前调用。
- `check()` / `summary()`：记一条断言；打印汇总并返回进程退出码。

本仓库不引单测框架，脚本仍由人工执行、PR 正文贴原始输出（未挂门禁，见 issue #317）。
"""
import sys
from pathlib import Path

PY = sys.executable

_checks = 0
_failed = 0


def setup_imports():
    """把 `tools/sony-ptpip` 放到 `sys.path` 首位，返回该目录。重复调用无副作用。"""
    root = Path(__file__).resolve().parents[1]
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))
    return root


def check(name, cond, detail=""):
    """记一条断言：通过打印 `[PASS]`，失败打印 `[FAIL]` 并累加失败数。"""
    global _checks, _failed
    _checks += 1
    if cond:
        print(f"[PASS] {name}")
    else:
        _failed += 1
        print(f"[FAIL] {name} {detail}")
    return bool(cond)


def summary():
    """打印汇总行并返回退出码。一条都没跑也算失败，避免空脚本被当成通过。"""
    if _checks == 0:
        print("\n== 失败：一条用例都没跑 ==")
        return 1
    if _failed == 0:
        print(f"\n== 全过：{_checks} 条 ==")
        return 0
    print(f"\n== 失败：{_failed}/{_checks} 条 ==")
    return 1
