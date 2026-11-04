#!/usr/bin/env python3
"""把 PTP/IP 会话的 pcap 翻译成可读的事务流水（v2，支持大文件与多流）。

用法：
    python parse_ptpip.py capture.pcapng                      # 列出所有 15740 流
    python parse_ptpip.py capture.pcapng --stream 7           # 只看某条流
    python parse_ptpip.py capture.pcapng --stream 7 --events  # 连事件也打印
    python parse_ptpip.py capture.pcapng --stream 7 --limit 200

依赖：tshark（PATH 里，或设 TSHARK 环境变量）。

实现要点：大流（百 MB 级）不整段读进内存，按帧号顺序增量喂给两个方向的状态机；
巨型数据包只取 tcp.len 补零占位（只需要 PTP/IP 头里的长度字段）。
"""
import os
import struct
import subprocess
import sys

TSHARK = os.environ.get("TSHARK", "tshark")
BULK = 1200  # 超过这个长度的包不取 payload，只补零占位

OP_NAMES = {
    0x1001: "GetDeviceInfo", 0x1002: "OpenSession", 0x1003: "CloseSession",
    0x1004: "GetStorageIDs", 0x1005: "GetStorageInfo", 0x1006: "GetNumObjects",
    0x1007: "GetObjectHandles", 0x1008: "GetObjectInfo", 0x1009: "GetObject",
    0x100A: "GetThumb", 0x100B: "GetPartialObject", 0x100C: "GetDevicePropDesc",
    0x100D: "GetDevicePropValue", 0x100E: "SetDevicePropValue", 0x100F: "ResetDevice",
    0x101B: "GetPartialObject", 0x1014: "GetDevicePropDescEx", 0x1015: "GetDevicePropValueEx", 0x1016: "SetDevicePropValueEx",
    0x9001: "GetDeviceInfoEx", 0x9002: "GetObjectInfoEx", 0x9003: "GetObjectEx",
    0x9201: "Sony_SDIOConnect", 0x9202: "Sony_GetExtDeviceInfo",
    0x9203: "Sony_SetDevicePropValue", 0x9204: "Sony_GetDevicePropDesc",
    0x9205: "Sony_GetDevicePropValue", 0x9206: "Sony_SetDevicePropValueEx?",
    0x9207: "Sony_GetAllExtDevicePropInfo", 0x9209: "Sony_SetDevicePropValueEx",
    0x920C: "Sony_GetVendorPropCodes", 0x920D: "Sony_SetDevicePropValueEx?",
    0x920E: "Sony_GetDevicePropValue2", 0x9210: "Sony_GetDevicePropValue3",
}

RESP_NAMES = {
    0x2001: "OK", 0x2002: "GeneralError", 0x2003: "SessionNotOpen",
    0x2004: "InvalidTransactionID", 0x2005: "OperationNotSupported",
    0x2006: "ParameterNotSupported", 0x2007: "IncompleteTransfer",
    0x2008: "InvalidStorageID", 0x2009: "InvalidObjectHandle",
    0x200A: "DevicePropNotSupported", 0x200C: "InvalidObjectFormatCode",
    0x2013: "AccessDenied", 0x2019: "DeviceBusy", 0x201D: "InvalidParentObject",
    0x201E: "NoThumbnailPresent",
    0xA001: "Sony_GenericError", 0xA002: "Sony_SessionAlreadyOpened",
}


def op_name(c):
    if c in OP_NAMES:
        return OP_NAMES[c]
    if 0x9200 <= c <= 0x92FF:
        return f"SonyVendor_0x{c:04X}"
    if 0x9000 <= c <= 0x91FF:
        return f"Vendor_0x{c:04X}"
    return f"Op_0x{c:04X}"


def resp_name(c):
    return RESP_NAMES.get(c, f"Resp_0x{c:04X}")


class Side:
    """单个方向上的 PTP/IP 帧重组。"""

    def __init__(self):
        self.buf = bytearray()

    def feed(self, data):
        self.buf += data
        out = []
        while True:
            if len(self.buf) < 8:
                break
            length, ptype = struct.unpack_from("<II", self.buf, 0)
            if length < 8 or length > 0x8000000:
                del self.buf[0]  # 失步，逐字节重找
                continue
            if len(self.buf) < length:
                break
            out.append((ptype, bytes(self.buf[8:length]), length))
            del self.buf[:length]
        return out


def iter_packets(pcap, stream_no):
    """单遍流式读取：逐包产出 (frame, from_camera, payload)。不缓存整段流。"""
    base = ("tcp.port==15740 && tcp.len>0 && !tcp.analysis.retransmission "
            "&& !tcp.analysis.out_of_order && !tcp.analysis.duplicate_ack")
    if stream_no is not None:
        base += f" && tcp.stream=={stream_no}"
    cmd = [TSHARK, "-r", pcap, "-n", "-Y", base, "-T", "fields",
           "-e", "frame.number", "-e", "tcp.srcport", "-e", "tcp.payload"]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1 << 20)
    try:
        for line in proc.stdout:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3 or not parts[2]:
                continue
            try:
                yield int(parts[0]), int(parts[1]) == 15740, bytes.fromhex(parts[2])
            except ValueError:
                continue
    finally:
        proc.stdout.close()
        proc.wait()


def _friendly(body, base):
    """Init 包里的 UCS-2 友好名：长度字节位置在不同实现上有 1 字节漂移，两种都试。"""
    best = ""
    for off in (base, base + 1, base - 1):
        if off < 0 or off >= len(body):
            continue
        n = body[off]
        if n == 0 or n > 64:
            continue
        raw = body[off + 1: off + 1 + n * 2]
        try:
            cand = raw.decode("utf-16-le").rstrip("\x00")
        except UnicodeDecodeError:
            continue
        score = sum(1 for ch in cand if 32 <= ord(ch) < 127)
        if score > sum(1 for ch in best if 32 <= ord(ch) < 127):
            best = cand
    return best


def describe(ptype, body, length):
    if ptype == 1:
        return f"Init_Command_Request  name={_friendly(body, 16)!r}"
    if ptype == 2:
        conn = struct.unpack_from("<I", body, 0)[0] if len(body) >= 4 else -1
        return f"Init_Command_Ack  connection={conn} name={_friendly(body, 20)!r}"
    if ptype == 3:
        return "Init_Event_Request"
    if ptype == 4:
        return "Init_Event_Ack"
    if ptype == 5:
        return f"Init_Fail reason={struct.unpack_from('<I', body, 0)[0] if len(body) >= 4 else -1}"
    if ptype == 6:
        if len(body) < 10:
            return "Operation_Request (short)"
        phase, code, tid = struct.unpack_from("<IHI", body, 0)
        n = (len(body) - 10) // 4
        params = struct.unpack_from(f"<{n}I", body, 10) if n else ()
        plist = " ".join(str(p) if p < 1000 else f"0x{p:08X}" for p in params)
        nm = op_name(code)
        if nm.startswith(("Op_", "Vendor_", "SonyVendor_")):
            nm = f"{nm}"
        return f"REQ  tid={tid:<5} {nm:<30} (0x{code:04X}) phase={phase} params=[{plist}]"
    if ptype == 7:
        if len(body) < 6:
            return "Operation_Response (short)"
        code, tid = struct.unpack_from("<HI", body, 0)
        n = (len(body) - 6) // 4
        params = struct.unpack_from(f"<{n}I", body, 6) if n else ()
        plist = " ".join(f"0x{p:08X}" if p > 99999 else str(p) for p in params)
        tail = f" params=[{plist}]" if params else ""
        return f"RSP  tid={tid:<5} {resp_name(code)}{tail}"
    if ptype == 8:
        if len(body) < 6:
            return "Event (short)"
        code, tid = struct.unpack_from("<HI", body, 0)
        return f"EVT  {code:#06X} tid={tid}"
    if ptype == 9:
        if len(body) < 12:
            return "Start_Data (short)"
        tid, total = struct.unpack_from("<IQ", body, 0)
        return f"DATA-START tid={tid} 总长={total:,} 字节 ({total / 1048576:.2f} MB)"
    if ptype == 10:
        return None
    if ptype == 11:
        return "Cancel"
    if ptype == 12:
        tid = struct.unpack_from("<I", body, 0)[0] if len(body) >= 4 else -1
        return f"DATA-END   tid={tid}"
    if ptype == 13:
        return "Ping"
    if ptype == 14:
        return "Pong"
    return f"type={ptype} len={length}"


def list_streams(pcap):
    cmd = [TSHARK, "-r", pcap, "-n", "-Y", "tcp.port==15740 && tcp.len>0",
           "-T", "fields", "-e", "tcp.stream", "-e", "ip.src", "-e", "tcp.srcport"]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    agg = {}
    for line in proc.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) < 3 or not parts[0]:
            continue
        agg.setdefault(parts[0], set()).add(f"{parts[1]}:{parts[2]}")
    return agg


def _object_info(buf):
    """PTP ObjectInfo dataset 简化解码：文件名 + 大小 + 格式码。"""
    if len(buf) < 54:
        return None
    try:
        fmt = struct.unpack_from("<H", buf, 4)[0]
        size = struct.unpack_from("<I", buf, 8)[0]
        p = 52
        n = buf[p]
        name = buf[p + 1: p + 1 + n * 2].decode("utf-16-le", "replace").rstrip("\x00")
        if not name or not name.isprintable():
            return None
        return f"文件名={name} 大小={size:,} B ({size / 1048576:.2f} MB) 格式=0x{fmt:04X}"
    except Exception:
        return None


def main():
    argv = sys.argv[1:]
    flags = {a for a in argv if a.startswith("--")}
    positional = [a for a in argv if not a.startswith("--")]

    def opt(name, default=None):
        if name in argv:
            i = argv.index(name)
            if i + 1 < len(argv):
                return int(argv[i + 1])
        return default

    stream_no = opt("--stream")
    limit = opt("--limit", 400)
    positional = [a for a in positional if not a.isdigit()]
    if not positional:
        print(__doc__)
        return 1
    pcap = positional[0]
    if not os.path.exists(pcap):
        print(f"找不到文件：{pcap}")
        return 1

    if stream_no is None:
        agg = list_streams(pcap)
        print("检测到的 PTP/IP 流：")
        for k in sorted(agg, key=int):
            print(f"  --stream {k:<3} 端点 {', '.join(sorted(agg[k]))}")
        print("\n指定一条流再跑一次。")
        return 0

    cam, host = Side(), Side()
    printed = 0
    show_events = "--events" in flags
    show_data = "--data" in flags
    pending = {}   # tid -> bytearray（数据阶段，用于解 ObjectInfo / 文件名）
    opcode_of = {} # tid -> 请求 opcode

    for frame, from_camera, payload in iter_packets(pcap, stream_no):
        side = cam if from_camera else host
        arrow = "CAM->PC" if from_camera else "PC->CAM"
        for ptype, body, length in side.feed(payload):
            if ptype == 9 and len(body) >= 12:
                pending[struct.unpack_from("<I", body, 0)[0]] = bytearray()
            elif ptype == 10 and len(body) >= 4:
                pending.setdefault(struct.unpack_from("<I", body, 0)[0], bytearray()).extend(body[4:])
            elif ptype == 12 and len(body) >= 4:
                pass  # End_Data 里可能还有尾巴，忽略

            line = describe(ptype, body, length)
            if line is None:
                continue
            if ptype == 8 and not show_events:
                continue
            if ptype == 6 and len(body) >= 10:
                opcode_of[struct.unpack_from("<I", body, 6)[0]] = struct.unpack_from("<H", body, 4)[0]
            if ptype == 7 and len(body) >= 6 and show_data:
                tid = struct.unpack_from("<I", body, 2)[0]
                buf = pending.pop(tid, None)
                if buf is not None and opcode_of.get(tid) == 0x1008:
                    info = _object_info(buf)
                    line += f"  << {info or ('ObjectInfo 无法解码: ' + buf[:40].hex())}"
                elif buf is not None and opcode_of.get(tid) in (0x9201, 0x9202) and len(buf) < 400:
                    line += f"  << 原始: {buf[:32].hex()}"
            if printed < limit:
                print(f"{arrow:<7} {frame:<7} {line}")
                printed += 1

    if printed >= limit:
        print(f"... 已达 --limit {limit}，需要更多请调大")
    return 0


if __name__ == "__main__":
    sys.exit(main())
