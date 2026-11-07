#!/usr/bin/env python3
"""直连索尼相机（PTP/IP, TCP 15740）枚举卡内已有文件。

只读操作：OpenSession / GetStorageIDs / GetObjectHandles / GetObjectInfo，
不下发任何写命令，不删不改。

连接顺序严格复刻 IED 实测行为（captures/wlan.pcapng 帧 319-331）：
    1. cmd 通道 TCP 连接 → Init_Command_Request / Ack
    2. 用 ACK 里的 connection number 新建 **第二条 TCP 连接** 作事件通道
       → Init_Event_Request / Ack
    3. 回到 cmd 通道才发 OpenSession
顺序反了相机会对 OpenSession 完全不响应（只超时，不回错误码）。

用法：
    python probe.py <相机IP> --guid <已配对GUID>                 # GUID 获取见 README §6
    python probe.py <相机IP> --guid <已配对GUID> --limit 80
    python probe.py <相机IP> --replay <IED 握手 payload 文件>   # 见 README §6
"""
import re
import socket
import struct
import sys
import threading
import time
import uuid

INIT_REQ, INIT_ACK, INIT_EVT_REQ, INIT_EVT_ACK, INIT_FAIL = 1, 2, 3, 4, 5
OP_REQ, OP_RSP, EVENT = 6, 7, 8
START_DATA, DATA, CANCEL, END_DATA = 9, 10, 11, 12

# 只保留本探针实际下发的只读 opcode。`GetObject` / `GetThumb` /
# `GetPartialObject`(0x101B) / `GetDevicePropValue2`(0x920E) 均无调用，已删。
# `SetDevicePropValueEx`(0x9209) 是写 opcode，探针从不调用，但
# docs/safety-and-privacy.md 明确要求保留它做抓包事务名翻译——删它要同步改文档，
# 超出本单的代码文件边界，故留在这里并在测试里钉住「只有这一个写名常量」。
OP = {"GetDeviceInfo": 0x1001, "OpenSession": 0x1002, "CloseSession": 0x1003,
      "GetStorageIDs": 0x1004, "GetStorageInfo": 0x1005, "GetNumObjects": 0x1006,
      "GetObjectHandles": 0x1007, "GetObjectInfo": 0x1008,
      # 索尼私有
      "SDIOConnect": 0x9201, "GetExtDeviceInfo": 0x9202,
      "SetDevicePropValueEx": 0x9209}

# 相机把 GUID 当配对标识记着：随机 UUID 会被 Init_Fail reason=1 直接拒掉，
# 只有当初配对成功的那个 GUID 才放行。它是配对凭据，真实值不入库：
# 由 --guid 传入，或用 --replay 重放 IED 的原始握手（获取方法见 README §6）。

RC = {0x2001: "OK", 0x2002: "GeneralError", 0x2003: "SessionNotOpen",
      0x2004: "InvalidTransactionID", 0x2005: "OperationNotSupported",
      0x2006: "ParameterNotSupported", 0x2007: "IncompleteTransfer",
      0x2008: "InvalidStorageID", 0x2009: "InvalidObjectHandle",
      0x200A: "DevicePropNotSupported", 0x2013: "AccessDenied",
      0x2019: "DeviceBusy", 0x201D: "InvalidParentObject",
      0xA002: "Sony_SessionAlreadyOpened", 0xA003: "Sony_SessionNotOpened"}


def frame(ptype, body=b""):
    return struct.pack("<II", len(body) + 8, ptype) + body


class Chan:
    """一条 PTP/IP 连接（命令通道或事件通道，二者帧格式同构）。"""

    def __init__(self, host, port=15740, timeout=8.0):
        self.s = socket.create_connection((host, port), timeout=timeout)
        self.s.settimeout(timeout)
        self.buf = bytearray()

    def send(self, ptype, body=b""):
        self.s.sendall(frame(ptype, body))

    def recv_frame(self):
        while True:
            if len(self.buf) >= 8:
                length, ptype = struct.unpack_from("<II", self.buf, 0)
                if 8 <= length <= 0x1000000 and len(self.buf) >= length:
                    body = bytes(self.buf[8:length])
                    del self.buf[:length]
                    return ptype, body
                if length < 8 or length > 0x1000000:
                    del self.buf[0]          # 失步，逐字节重找
                    continue
            chunk = self.s.recv(65536)
            if not chunk:
                raise ConnectionError("连接被关闭")
            self.buf += chunk

    def close(self):
        try:
            self.s.close()
        except OSError:
            pass


class PtpIp:
    def __init__(self, host, port=15740, timeout=8.0):
        self.host, self.port = host, port
        self.timeout = timeout
        self.cmd = Chan(host, port, timeout)
        self.evt = None
        self.evt_thread = None
        self.evt_log = []
        self.tid = 0

    # ---------- 1. 命令通道握手 ----------
    def init(self, name="rea-probe", replay=None, guid=None):
        # 实测格式（帧 322，44 字节）：
        #   16 字节 GUID 的 ASCII 文本 + UTF-16LE 名称(含 \0\0 结尾) + u32 版本，
        #   没有 PTP 规范里的 1 字节长度前缀。a7C 只认这个。
        #   名称必须从第 16 字节开始（16 字节对齐），否则相机解析错位。
        if replay:
            self.cmd.send(INIT_REQ, bytes.fromhex(replay))
        else:
            if not guid:
                raise ValueError("缺少配对 GUID：请用 --guid 传入，获取方法见 README §6")
            g = guid[:16]
            g = g.ljust(16, "0").encode("ascii")
            nm = name.encode("utf-16-le") + b"\x00\x00"
            self.cmd.send(INIT_REQ, g + nm + struct.pack("<I", 0x00010000))
        ptype, body = self.cmd.recv_frame()
        if ptype != INIT_ACK:
            reason = struct.unpack_from("<I", body, 0)[0] if len(body) >= 4 else -1
            hint = {1: "相机拒绝了这次连接（多半是 IED 的 Remote 进程还占着相机）",
                    2: "相机认为已有连接"}.get(reason, "版本不匹配")
            raise RuntimeError(f"握手被拒 type={ptype} reason={reason} → {hint}")
        # ACK body = connection(4) + 回显 GUID(16) + UTF-16 名称 + \0\0 + 版本(4)
        conn = struct.unpack_from("<I", body, 0)[0]
        cam = body[20:].decode("utf-16-le", "replace").split("\x00")[0]
        return conn, cam

    # ---------- 2. 事件通道 ----------
    def open_event_channel(self, conn):
        """IED 在 OpenSession 之前就先建好事件通道，body 只放 4 字节 connection number。"""
        self.evt = Chan(self.host, self.port, self.timeout)
        self.evt.send(INIT_EVT_REQ, struct.pack("<I", conn))
        ptype, body = self.evt.recv_frame()
        if ptype != INIT_EVT_ACK:
            raise RuntimeError(f"事件通道被拒 type={ptype} body={body.hex()}")
        self.evt_thread = threading.Thread(target=self._evt_loop, daemon=True)
        self.evt_thread.start()

    def _evt_loop(self):
        """收事件，顺带把缓冲区排空，否则相机发事件多了会阻塞。"""
        while True:
            try:
                ptype, body = self.evt.recv_frame()
            except (ConnectionError, OSError):
                return
            if ptype == EVENT and len(body) >= 2:
                code = struct.unpack_from("<H", body, 0)[0]
                params = [struct.unpack_from("<I", body, 2 + 4 * i)[0]
                          for i in range((len(body) - 2) // 4)]
                self.evt_log.append((code, params))

    # ---------- 3. 操作 ----------
    def op(self, code, params=(), data_in=False, timeout=None):
        self.tid += 1
        tid = self.tid
        # 实测 IED 所有操作（含 OpenSession）的 dataPhase 位都置 1，这里照抄。
        body = struct.pack("<IHI", 1, code, tid)
        for p in params:
            body += struct.pack("<I", p)
        self.cmd.send(OP_REQ, body)

        payload = bytearray()
        got_data = False
        if timeout:
            self.cmd.s.settimeout(timeout)
        try:
            while True:
                ptype, b = self.cmd.recv_frame()
                if ptype == START_DATA:
                    payload = bytearray()
                    got_data = True
                elif ptype in (DATA, END_DATA):
                    payload += b[4:]
                elif ptype == OP_RSP:
                    rc, _rtid = struct.unpack_from("<HI", b, 0)
                    n = (len(b) - 6) // 4
                    rparams = struct.unpack_from(f"<{n}I", b, 6) if n else ()
                    return rc, bytes(payload) if got_data else b"", rparams
                else:
                    continue    # EVENT 等，忽略
        finally:
            if timeout:
                self.cmd.s.settimeout(self.timeout)

    # ---------- 3. 索尼私有初始化 ----------
    def sony_init(self, verbose=True):
        """复刻 IED 在 OpenSession 之后的私有引导序列（抓包帧 337-370）。

        0x9201 SDIOConnect 是索尼的「SD 通道」控制，返回 8 字节数据；
        0x9202 GetExtDeviceInfo[300] 取扩展设备信息（260 字节）。
        不做这段，后面 GetStorageIDs 会直接 AccessDenied。
        """
        seq = [(OP["SDIOConnect"], [1, 0, 0]), (OP["SDIOConnect"], [2, 0, 0]),
               (OP["GetDeviceInfo"], [0]),
               (OP["GetExtDeviceInfo"], [300]),
               (OP["SDIOConnect"], [3, 0, 0]),
               (OP["GetExtDeviceInfo"], [300])]
        for code, prm in seq:
            name = next((k for k, v in OP.items() if v == code), hex(code))
            try:
                rc, data, rp = self.op(code, prm, data_in=True)
            except Exception as e:
                if verbose:
                    print(f"    {name}{prm} → 异常 {e}")
                return False
            if verbose:
                extra = f" data={len(data)}B" if data else ""
                print(f"    {name}{prm} → {RC.get(rc, hex(rc))}{extra}")
            if rc != 0x2001:
                return False
        return True

    def close(self):
        try:
            self.op(OP["CloseSession"])
        except Exception:
            pass
        self.cmd.close()
        if self.evt:
            self.evt.close()


def u32_array(buf):
    if len(buf) < 4:
        return []
    n = struct.unpack_from("<I", buf, 0)[0]
    if n > 100000:
        return []
    return list(struct.unpack_from(f"<{n}I", buf, 4))


def object_info(buf):
    """解析 PTP ObjectInfo（字段偏移经抓包 + 实时探针两次交叉验证）。

        0  StorageID            u32
        4  ObjectFormat         u16
        6  ProtectionStatus     u16
        8  ObjectCompressedSize u32
       12  ThumbFormat          u16
       14  ThumbCompressedSize  u32
       18  ThumbPixWidth        u32
       22  ThumbPixHeight       u32
       26  ImagePixWidth        u32
       30  ImagePixHeight       u32
       34  ParentObject         u32
       38  AssociationType      u16
       40  AssociationDesc      u32
       44  SequenceNumber       u32
       48  （索尼在此多 4 字节填充；按规范算文件名应在 48，实测是 52）
       52  文件名长度 u8
       53  文件名 UTF-16LE

    验证：抓包 tid=299 的 ARW 记录中 d[52]=0x0d、53 起为 "DSC00001.ARW"；
    实时探针 0xFFFFC002 同样 d[52]=0x0d、"DSC09999.JPG"。
    """
    if len(buf) < 53:
        return None
    storage = struct.unpack_from("<I", buf, 0)[0]
    fmt = struct.unpack_from("<H", buf, 4)[0]
    size = struct.unpack_from("<I", buf, 8)[0]
    w, h = struct.unpack_from("<II", buf, 26)
    n = buf[52]
    name = buf[53:53 + n * 2].decode("utf-16-le", "replace").split("\x00")[0]
    return dict(storage=storage, format=fmt, size=size, w=w, h=h, name=name)


FMT = {0x3000: "Undef", 0x3801: "JPEG", 0xB101: "ARW", 0xB982: "HEIF", 0x300A: "MP4"}


def main():
    argv = sys.argv[1:]
    host = next((a for a in argv if not a.startswith("-") and a.count(".") == 3), None)
    if host is None:
        print("缺相机 IP。用法：python probe.py <相机IP> --guid <已配对GUID> [--limit N] [--replay <文件>]")
        print("配对 GUID 与 replay 文件的获取方法见 README §6。")
        return 2
    limit = 80
    if "--limit" in argv:
        i = argv.index("--limit")
        if len(argv) <= i + 1 or argv[i + 1].startswith("-"):
            print("--limit 必须显式给整数，例如 --limit 80。")
            return 2
        try:
            limit = int(argv[i + 1])
        except ValueError:
            print(f"--limit 需要整数，收到 {argv[i + 1]!r}；例如 --limit 80。")
            return 2

    replay = None
    if "--replay" in argv:
        i = argv.index("--replay")
        if len(argv) <= i + 1:
            print("用法：python probe.py <相机IP> --guid <已配对GUID> [--limit N] [--replay <IED 握手 payload 文件>]")
            print("--replay 必须显式给路径，仓库里没有默认文件。")
            print("文件内容 = Init_Command_Request 的 data 段，去掉空白后的连续十六进制字符串")
            print("（无 0x 前缀）。它含配对凭据，不随仓库分发；生成方法见 README §6。")
            return 2
        p = argv[i + 1]
        try:
            replay = open(p).read().strip()
            size = len(bytes.fromhex(replay))
        except OSError as e:
            print(f"找不到 replay 文件：{p}（{e}），生成方法见 README §6")
            return 2
        except ValueError as e:
            print(f"replay 文件内容不是有效十六进制：{p}（{e}），生成方法见 README §6")
            return 2
        # 空文件不抛异常：bytes.fromhex("") 返回 0 字节，会一路进握手重试。
        # 与其让相机端超时 6 次，不如当场判空并给指引。
        if not replay:
            print(f"replay 文件内容为空：{p}，生成方法见 README §6")
            return 2
        print(f"[0] 用 IED 原始握手 payload 重放（{size} 字节）")

    guid = None
    if "--guid" in argv:
        i = argv.index("--guid")
        if len(argv) <= i + 1 or argv[i + 1].startswith("-"):
            print("--guid 必须显式给值：它就是你本机 IED 首次配对时用过的 GUID，获取方法见 README §6。")
            return 2
        guid = argv[i + 1]
        body = guid.replace("-", "")
        if not (12 <= len(body) <= 32 and re.fullmatch(r"[0-9A-Fa-f]+", body)):
            print(f"--guid 形态不对：{guid!r}。去掉短横后应为 12–32 位十六进制。")
            print("获取方法见 README §6；真实值不要写进仓库。")
            return 2

    if replay is None and guid is None:
        print("缺握手凭据：请用 --guid 传入你自己配对得到的 GUID，或用 --replay 重放 IED 的原始握手。")
        print("获取方法见 README §6；相机不在线时不要尝试连接。")
        return 2

    # 相机偶尔会因为上一轮的残留会话直接拒握手，退避重试几次。
    c, conn, cam = None, None, None
    last = None
    for attempt in range(1, 7):
        try:
            c = PtpIp(host)
            conn, cam = c.init(replay=replay, guid=guid)
            break
        except Exception as e:
            last = e
            print(f"[!] 第 {attempt} 次握手失败: {e}")
            if c:
                c.cmd.close()
            c = None
            time.sleep(4)
    if c is None:
        print(f"\n放弃：{last}")
        return 1
    print(f"[1] 命令通道握手 OK → 相机 {cam!r}  connection={conn} (0x{conn:08X})")

    c.open_event_channel(conn)
    print(f"[2] 事件通道已建立（第二条 TCP，body=connection number）")

    rc, _, _ = c.op(OP["OpenSession"], [1], timeout=12)
    print(f"[3] OpenSession(1) → {RC.get(rc, hex(rc))}")
    if rc != 0x2001:
        print("    会话打不开：先结束 IED 的 Remote/ied/Viewer 进程再试")
        c.close()
        return 1

    print("[3.5] 索尼私有引导序列：")
    ok = c.sony_init()

    rc, data, _ = c.op(OP["GetStorageIDs"], data_in=True)
    if rc != 0x2001:
        print(f"[4] GetStorageIDs → {RC.get(rc, hex(rc))}")
        # 换几条路试探，看是「模式不允许」还是「参数不对」
        print("    试探其它枚举路径：")
        for label, code, prm in [
            ("GetNumObjects(storage=0xFFFFFFFF,all)", OP["GetNumObjects"], [0, 0xFFFFFFFF, 0]),
            ("GetObjectHandles(storage=0x0,all)", OP["GetObjectHandles"], [0x00000000, 0, 0xFFFFFFFF]),
            ("GetObjectHandles(storage=0xFFFFFFFF,all)", OP["GetObjectHandles"], [0xFFFFFFFF, 0, 0xFFFFFFFF]),
            ("GetStorageInfo(0x00010001)", OP["GetStorageInfo"], [0x00010001]),
            ("GetObjectInfo(0xFFFFC001 最近一张)", OP["GetObjectInfo"], [0xFFFFC001]),
        ]:
            try:
                r, d, p = c.op(code, prm, data_in=True)
                print(f"      {label:<42} → {RC.get(r, hex(r))}  data={len(d)}B prm={p}")
            except Exception as e:
                print(f"      {label:<42} → 异常 {e}")
        c.close()
        return 1
    storages = u32_array(data)
    print(f"[4] 存储介质: {[hex(s) for s in storages]}")

    all_handles = []
    for st in storages:
        rc, data, _ = c.op(OP["GetObjectHandles"], [st, 0, 0xFFFFFFFF], data_in=True)
        if rc != 0x2001:
            print(f"    介质 {hex(st)} GetObjectHandles → {RC.get(rc, hex(rc))}")
            continue
        hs = u32_array(data)
        print(f"[5] 介质 {hex(st)}: {len(hs)} 个对象")
        all_handles += [(st, h) for h in hs]

    if not all_handles:
        print("\n卡里没枚举到对象。可能相机处于 PC Remote 模式只暴露待拍句柄，")
        print("试试把相机的 USB 连接模式切到「Mass Storage」或退出遥控模式。")
        c.close()
        return 0

    print(f"\n合计 {len(all_handles)} 个对象，取前 {min(limit, len(all_handles))} 个的 ObjectInfo：\n")
    print(f"{'storage':>9} {'handle':>10} {'格式':>5} {'大小MB':>8} {'尺寸':>11}  文件名")
    for st, h in all_handles[:limit]:
        rc, data, _ = c.op(OP["GetObjectInfo"], [h], data_in=True)
        if rc != 0x2001:
            print(f"{hex(st):>9} {h:>10}  失败 {RC.get(rc, hex(rc))}")
            continue
        oi = object_info(data)
        if not oi:
            print(f"{hex(st):>9} {h:>10}  解析失败 len={len(data)}")
            continue
        print(f"{hex(st):>9} {h:>10} {FMT.get(oi['format'], hex(oi['format'])):>5} "
              f"{oi['size']/1048576:>8.2f} {str(oi['w'])+'x'+str(oi['h']):>11}  {oi['name']}")

    if c.evt_log:
        print(f"\n期间收到 {len(c.evt_log)} 个事件（前 10 个）：")
        for code, prm in c.evt_log[:10]:
            print(f"  0x{code:04X} {prm}")

    c.close()
    print("\n已关闭会话。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
