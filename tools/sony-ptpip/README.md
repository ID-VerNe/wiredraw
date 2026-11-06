# 抓 Imaging Edge Desktop 的 PTP 会话（给安卓端当参照实现）

目的：把 IED 与 a7C 之间的每一次 PTP 事务录下来，得到"取 RAW 的最小命令序列"，
然后照抄到安卓端，不用逆二进制。

## 0. 为什么找 IED 而不是逆固件

IED 的传输层已经在你机器上摊开了：

```
C:\Program Files\Sony\Imaging Edge\
  LjCore.dll                  ← PTP 编解码核心（C++ 类接口，符号已剥）
  LjAdapter\
    Lja_PTP_USB.dll           ← USB 传输（内部用 libusb + libusbK 驱动）
    Lja_PTP_IP.dll            ← Wi-Fi 传输（PTP/IP over TCP 15740，还带 libssh2）
    Lja_PTP_WIA.dll           ← Windows WIA 路径
  Driver\amd64\libusbK.sys    ← 因此相机插上后不会出现在"图像设备"里
```

同一个核心、三种 transport。**所以安卓端也应该是这个结构**：一套 PTP 编解码 +
两个 transport（IP 先做，USB 后补）。

## 1. 相机端设置（a7C）

```
MENU → 网络 → PC Remote Function
   PC Remote                = On
   PC Remote Cnct Method    = Wi-Fi Direct      （先用无线抓，最容易）
   Still Img. Save Dest.    = PC+Camera
   RAW+J PC Save Img        = RAW & JPEG        ← 关键，否则只送 JPEG
   PC Save Image Size       = Original          ← 设成 2M 时 RAW 会被丢弃
```

> `PC Save Image Size = 2M` 时 IED 会明确提示"RAW 格式的图像将不传输"，
> 这是 `Strings.xml` 里写死的逻辑，不是 bug。

## 2. 抓包（Wi-Fi，最省事）

用 Wi-Fi Direct 时 PC 会连上相机开的 AP，PTP/IP 走 TCP 15740，**明文**。

```powershell
# 管理员 PowerShell，先列出网卡找到连相机的那块
& "C:\Program Files\Wireshark\tshark.exe" -D

# 抓（换成实际编号，注意 Wi-Fi Direct 那块虚拟网卡）
& "C:\Program Files\Wireshark\tshark.exe" -i 5 -f "tcp port 15740" -w sony_ptp.pcap
```

抓的过程中在 IED 里做这几件事，覆盖你要的全部路径：

1. 连接相机（Remote 面板出画面）
2. 拍一张 RAW+JPEG
3. 等它自动下载到 PC（这就是"取 RAW"的完整序列）
4. 在 Viewer 里手动导入卡上已有的旧照片各一张（RAW 和 JPEG 各一）

停止后 Ctrl+C。

> 若 Wi-Fi Direct 网卡抓不到（部分虚拟网卡不支持混杂模式），
> 退回 `PC Remote Cnct Method = Wi-Fi Access Point`，
> 让相机和 PC 都连同一个路由器，再抓那块真实网卡。

## 3. 解析

```bash
python parse_ptpip.py sony_ptp.pcap          # 事务流水
python parse_ptpip.py sony_ptp.pcap --data   # 附带数据阶段摘要（ObjectInfo 等）
```

输出形如：

```
C->H   1      Init_Command_Request guid=… name=Imaging Edge Desktop
H->C   2      Init_Command_Ack connection=1 guid=… name=ILCE-7C
C->H   6      REQ  tid=1    OpenSession                  phase=1 params=[1]
H->C   7      RSP  tid=1    OK
C->H   6      REQ  tid=2    GetDeviceInfo                phase=1 params=[]
...
C->H   6      REQ  tid=9    GetObject                    phase=1 params=[0x0000000A]
H->C   9      DATA start tid=9 total=52428800 bytes  (50.0 MB)   ← 这个大小就是 ARW
```

`total` 字节数是判定依据：ARW（24MP 未压缩约 48–50MB）vs JPEG（几 MB）。
把 `GetObject` 前后的 REQ 序列抄下来，就是安卓端 M2–M3 的实现清单。

## 4. USB 侧（可选，验证是否有额外命令）

IED 用 libusbK 接管相机，所以 USBPcap 能抓到 bulk 端点：

```powershell
& "C:\Program Files\Wireshark\tshark.exe" -D          # 找 USBPcap1
& "C:\Program Files\Wireshark\tshark.exe" -i <USBPcap> -w sony_usb.pcap
```

USB 上跑的是裸 PTP（无 PTP/IP 头），对比两边的 opcode 序列即可确认传输层之外
有没有差异。**通常没有**——差异只在封装。

## 5. 注意

- 抓包文件里有相机序列号、GUID、文件路径，分享前先脱敏。
- 这只是被动观察本机与自己的相机通信，不修改任何固件、不影响保修。
- `Lja_PTP_IP.dll` 里的 `enum sony_lajiao::IDeviceConnection` 是适配器接口，
  导出的 `EnumCameraObjects` / `Connect` / `GetCameraObjectInfofromDetector`
  可以用来确认 IED 走的是哪条 transport（想看的话用 API Monitor 挂这三个符号）。

## 6. `--replay` 的握手 payload 从哪来

`probe.py --replay <文件>` 重放 IED 首次配对时的 `Init_Command_Request` 数据段。
这段数据里含配对凭据，**不随仓库分发**，仓库里也没有默认文件——不传路径时探针会打印
用法并以退出码 2 结束，不会去读一个不存在的文件。

生成自己的本地副本：

1. 用本机抓到的 pcap 取出 `Init_Command_Request`（C→H）的**完整 body**：

   ```powershell
   python tools/sony-ptpip/parse_ptpip.py <你的抓包路径> --stream <N> --dump-init
   ```

   仓库里没有 `captures/wlan.pcapng` 这类抓包文件（未入库），路径要换成你自己抓到的那个。
   输出形如 `Init_Command_Request  body=<N> B  hex=<连续十六进制>`，`hex=` 后面那一整串
   就是重放要的完整 body（典型 44 字节 = 16 字节 GUID 文本 + UTF-16LE 名称 + u32 版本）。
   `--stream <N>` 先用不带该参数的调用列出流编号。
2. 把 `hex=` 后面的字符串原样（无空格、无 `0x` 前缀）另存为本地文件，
   例如 `captures/init_ied.txt`——`captures/` 已在 `.gitignore` 里，不会入库。
3. 运行 `python probe.py <相机IP> --replay captures/init_ied.txt`。

不传 `--replay` 时，探针要求用 `--guid` 传入你自己配对得到的 GUID，按新报文构造握手。
`--guid` 的取值就是上面 `body` 的前 16 字节（GUID 的 ASCII 文本，索尼会把它截断成
`xxxxxxxx-xxxx-xx` 这类形态）；它同样是配对凭据，不要写进仓库。若相机已被 IED 的
Remote / Viewer 进程占着，会被 `Init_Fail` 拒绝。
