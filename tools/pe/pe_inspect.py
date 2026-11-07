#!/usr/bin/env python3
"""极简 PE 检查器（纯标准库，不依赖 pefile）。

用途：看某个 DLL 导出/导入了什么、有哪些 C++ 类（RTTI），以及谁在调它。

    python pe_inspect.py exports  <file>
    python pe_inspect.py imports  <file>
    python pe_inspect.py rtti     <file>
    python pe_inspect.py strings  <file> [minlen]
    python pe_inspect.py whoimports <dllname> <file...>   # 谁 import 了这个 dll
"""
import struct
import sys


class PE:
    def __init__(self, path):
        self.path = path
        with open(path, "rb") as f:
            self.data = f.read()
        d = self.data
        if d[:2] != b"MZ":
            raise ValueError("not a PE")
        e_lfanew = struct.unpack_from("<I", d, 0x3C)[0]
        if d[e_lfanew:e_lfanew + 4] != b"PE\0\0":
            raise ValueError("bad PE signature")
        self.nt = e_lfanew
        coff = e_lfanew + 4
        self.machine, self.nsec = struct.unpack_from("<HH", d, coff)
        opt_size = struct.unpack_from("<H", d, coff + 16)[0]
        opt = coff + 20
        self.magic = struct.unpack_from("<H", d, opt)[0]
        self.is64 = self.magic == 0x20B
        dd = opt + (112 if self.is64 else 96)
        self.image_base = struct.unpack_from("<Q" if self.is64 else "<I", d, opt + 24)[0]
        # data directories
        self.dirs = [struct.unpack_from("<II", d, dd + 8 * i) for i in range(16)]

        # sections
        self.sections = []
        sh = opt + opt_size
        for i in range(self.nsec):
            off = sh + 40 * i
            name = d[off:off + 8].rstrip(b"\0").decode("ascii", "replace")
            vsize, vaddr, rawsize, rawptr = struct.unpack_from("<IIII", d, off + 8)
            self.sections.append((name, vaddr, vsize, rawptr, rawsize))

    def rva2off(self, rva):
        for name, vaddr, vsize, rawptr, rawsize in self.sections:
            if vaddr <= rva < vaddr + max(vsize, rawsize):
                return rawptr + (rva - vaddr)
        return None

    def _require_off(self, rva, what):
        """需要真实偏移的地方用它：映射失败时给出带用途与 RVA 的 ValueError。"""
        off = self.rva2off(rva)
        if off is None:
            raise ValueError(f"{what} RVA 0x{rva:X} 不在任何节内")
        return off

    def cstr(self, rva):
        off = self.rva2off(rva)
        if off is None:
            return None
        end = self.data.find(b"\0", off)
        return self.data[off:end].decode("ascii", "replace")

    # ---------- exports ----------
    def exports(self):
        rva, size = self.dirs[0]
        if not rva:
            return []
        off = self._require_off(rva, "导出表")
        (_, _, _, _, name_rva, base, nfunc, nname,
         addr_funcs, addr_names, addr_ordinals) = struct.unpack_from("<IIHHIIIIIII", self.data, off)
        out = []
        for i in range(nname):
            names_off = self._require_off(addr_names, "导出名表")
            ordinals_off = self._require_off(addr_ordinals, "导出序号表")
            funcs_off = self._require_off(addr_funcs, "导出函数表")
            nrva = struct.unpack_from("<I", self.data, names_off + 4 * i)[0]
            ordinal = struct.unpack_from("<H", self.data, ordinals_off + 2 * i)[0]
            frva = struct.unpack_from("<I", self.data, funcs_off + 4 * ordinal)[0]
            out.append((base + ordinal, self.cstr(nrva), frva))
        return out

    # ---------- imports ----------
    def imports(self):
        rva, size = self.dirs[1]
        if not rva:
            return {}
        off = self._require_off(rva, "导入表")
        result = {}
        i = 0
        while True:
            desc = struct.unpack_from("<IIIII", self.data, off + 20 * i)
            if not any(desc):
                break
            oft, _, _, name_rva, ft = desc
            dll = self.cstr(name_rva)
            thunk_rva = oft or ft
            toff = self._require_off(thunk_rva, "导入 thunk 表")
            names = []
            j = 0
            step = 8 if self.is64 else 4
            while True:
                val = struct.unpack_from("<Q" if self.is64 else "<I", self.data, toff + step * j)[0]
                if val == 0:
                    break
                if val & (1 << (63 if self.is64 else 31)):
                    names.append(f"#{val & 0xFFFF}")
                else:
                    names.append(self.cstr(val + 2))
                j += 1
            result[dll] = names
            i += 1
        return result

    # ---------- RTTI class names ----------
    def rtti(self):
        """扫 .data/.rdata 里的 .?AV<name>@@ 类型描述符。"""
        found = set()
        pos = 0
        while True:
            pos = self.data.find(b".?AV", pos)
            if pos < 0:
                break
            end = self.data.find(b"@@", pos)
            if end < 0 or end - pos > 200:
                pos += 4
                continue
            try:
                raw = self.data[pos + 4:end]
                name = raw.decode("ascii")
                if name.replace("_", "").isalnum() or "::" in name or "@" in name:
                    found.add(name)
            except UnicodeDecodeError:
                pass
            pos = end + 2
        return sorted(found)


def cmd_whoimports(dllname, files):
    dllname = dllname.lower()
    for f in files:
        try:
            pe = PE(f)
        except Exception:
            continue
        for dll, names in pe.imports().items():
            if dll and dll.lower() == dllname:
                print(f"--- {f}")
                for n in names:
                    print(f"    {n}")


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 1
    cmd, target = sys.argv[1], sys.argv[2]

    if cmd == "whoimports":
        cmd_whoimports(target, sys.argv[3:])
        return 0

    pe = PE(target)
    print(f"{target}")
    print(f"  machine=0x{pe.machine:04X} {'x64' if pe.is64 else 'x86'}  imagebase=0x{pe.image_base:X}  "
          f"sections={[s[0] for s in pe.sections]}")

    if cmd == "exports":
        exps = pe.exports()
        print(f"  导出 {len(exps)} 项：")
        for ordinal, name, rva in exps:
            print(f"    [{ordinal:>4}] {rva:#010x}  {name}")
    elif cmd == "imports":
        for dll, names in pe.imports().items():
            print(f"  <- {dll} ({len(names)})")
            for n in names:
                print(f"       {n}")
    elif cmd == "rtti":
        for n in pe.rtti():
            print(f"    {n}")
    elif cmd == "strings":
        minlen = int(sys.argv[3]) if len(sys.argv) > 3 else 6
        import re
        for m in re.finditer(rb"[ -~]{%d,}" % minlen, pe.data):
            s = m.group().decode("ascii")
            print(s)
    return 0


if __name__ == "__main__":
    sys.exit(main())
