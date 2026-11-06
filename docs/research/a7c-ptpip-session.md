# a7C × IED 实测会话分析（PTP/IP over Wi-Fi AP 模式）

抓包：`captures/wlan.pcapng`（148 MB，2026-11-04 21:16–21:19）
环境：a7C 固件 2.02，相机 <相机内网 IP>，PC <PC 内网 IP>，二者同一网段、同一台路由器
解析：`python parse_ptpip.py captures/wlan.pcapng --stream 7 --data`

---

## 1. 结论先行

| 问题 | 实测答案 |
|---|---|
| Wi-Fi 下能不能传 RAW | **能**，实测传了 2 张 `.ARW` |
| 走什么协议 | **PTP/IP，裸 TCP 15740**（不是老的 JSON API） |
| 有没有 SSH 隧道 | **a7C 没有**。全流程 0 个 22 端口连接，`DigitalImagingDesc.xml` 里也没有 `X_SSH_Support` 字段。之前从 `Lja_PTP_IP.dll` 推的 SSH 只适用于其他机型 |
| 发现流程 | UPnP/SSDP + HTTP，端口 **64321**（`GET /dd.xml`、`GET /DigitalImagingDesc.xml`） |
| 配对要求 | `X_PTP_PairingNecessity = Necessary`，PTP 版本 3.00 |
| 文件怎么取 | `GetPartialObject`，**每次 1 MB**，最后一包按剩余长度收尾 |
| 实时取景 | `GetObject` 轮询句柄 `0xFFFFC002`，本次 1617 次 |
| 新拍文件句柄 | `0xFFFFC001`（"最后一张"，内容随拍摄变化） |
| 实测速度 | 143 MB / 150 s ≈ **0.95 MB/s**（含实时取景，纯文件下载更快） |

---

## 2. 完整调用时序

### 2.1 发现（TCP 64321，三次短连接）

```
PC → GET /dd.xml HTTP/1.1
CAM → 200 OK, Server: UPnP/1.0 SonyImagingDevice/1.0
      <friendlyName>ILCE-7C</friendlyName>
      <serviceType>urn:schemas-sony-com:service:DigitalImaging:1</serviceType>
      <controlURL>/upnp/control/DigitalImaging</controlURL>

PC → GET /DigitalImagingDesc.xml
CAM → <X_ModelName>ILCE-7C</X_ModelName>
      <X_FirmwareVersion>2.02</X_FirmwareVersion>
      <X_Category>ILCE</X_Category>
      <X_ServerType>Control with Smartphone</X_ServerType>
      <X_MacAddress>70:74:14:AE:8D:0B</X_MacAddress>
      <X_PTP_Versions>3.00</X_PTP_Versions>
      <X_PTP_PairingNecessity>Necessary</X_PTP_PairingNecessity>
```

注意 `X_ServerType` 写的是 "Control with Smartphone" —— **IED 连的就是手机 App 那个 UPnP 服务**，
只是手机 App 走 JSON、IED 走 PTP/IP。

### 2.2 握手（TCP 15740，流 7）

```
PC  → Init_Command_Request  name='VereNeDeS14'     (主机名)
CAM → Init_Command_Ack      connection=859057508  name='ILCE-7C'
PC  → OpenSession(1)                               → OK
```

### 2.3 索尼初始化序列（关键，缺一不可）

```
0x9201 Sony_SDIOConnect         params=[1 0 0]        → 8 字节数据 + OK
0x9201 Sony_SDIOConnect         params=[2 0 0]        → 8 字节数据 + OK
0x1001 GetDeviceInfo            params=[0]            → 303 字节 (DeviceInfo)
0x9202 Sony_GetExtDeviceInfo    params=[300]          → 260 字节
0x9201 Sony_SDIOConnect         params=[3 0 0]        → 8 字节数据 + OK
0x9202 Sony_GetExtDeviceInfo    params=[300]          → 260 字节
0x9209 Sony_SetDevicePropValueEx params=[0]           → 送 3,206 字节 blob
0x920E/0x9205 读属性…
```

三次 `SDIOConnect` 分别是 1/2/3，相当于"注册/切换模式/确认"。
初始化完毕后开始交替：`SetDevicePropValueEx`（改参数）+ `GetObject(0xFFFFC002)`（取景）+ `GetObjectInfo/GetPartialObject`（取文件）。

### 2.4 实时取景

```
0x1008 GetObjectInfo  [0xFFFFC002]
0x1009 GetObject      [0xFFFFC002]   ← 反复轮询，每次返回一帧 JPEG
0x1009 GetObject      [0xFFFFC002]
...（本次 1617 次）
```

### 2.5 取文件（这是你要的核心）

```
0x1008 GetObjectInfo  [0xFFFFC001]        → ObjectInfo：DSC00001.ARW
0x101B GetPartialObject [0xFFFFC001, 0x00000000, 0x00100000]  → 1 MB
0x101B GetPartialObject [0xFFFFC001, 0x00100000, 0x00100000]  → 1 MB
...
0x101B GetPartialObject [0xFFFFC001, 0x01700000, 0x0009EB00]  → 末尾
                                        合计 0x0179EB00 = 24,767,232 B（23.6 MB）

0x1008 GetObjectInfo  [0xFFFFC001]        → DSC00001.JPG
0x101B GetPartialObject [0xFFFFC001, 0, 0x000D5BCF]           → 一次取完（实际 591,175 B）

（第二张同理：DSC00002.ARW ≈ 23.6 MB、DSC00002.JPG ≈ 0.85 MB）
```

**要点**：`GetPartialObject` 的第三个参数是"最多给多少"，不是精确长度；
客户端按 1 MB 步进，最后一包把剩余量作为 max 传进去。
安卓端照抄即可，断点续传天然支持（offset 是入参）。

### 2.6 事件通道（独立 TCP 连接，流 8）

```
PC  → Init_Event_Request      (type 3)
CAM → Init_Event_Ack          (type 4)
CAM → Event 0xC203 / 0xC201 / 0xC202 / 0xC206 / 0xC207 / 0xC20C ...  (tid=0xFFFFFFFF)
```

**PTP/IP 的事件走单独一条 TCP 连接**，不是命令通道里内联。
`0xC2xx` 是索尼私有事件码（拍摄完成、参数变化等）。安卓端必须开第二条连接，否则只能轮询。

---

## 3. 对安卓实现的直接结论

1. **不需要 SSH、不需要 libssh2**（a7C 这条路）。纯 TCP + PTP/IP 编解码即可。
2. **不需要买 SDK**。协议行为已被完整观测，且命令集与 Camera Remote Command 文档对得上。
3. 最小实现路径：
   ```
   SSDP/HTTP 发现（或直接让用户输 IP）
   → TCP 15740，Init_Command_Request/Ack
   → OpenSession(1)
   → 0x9201(1/2/3) + 0x9202(300) 初始化
   → GetObjectInfo(0xFFFFC001) 拿文件名/大小
   → GetPartialObject 按 1 MB 循环落盘
   → 第二条连接 Init_Event_Request 收事件
   ```
4. 速度上限在 Wi-Fi：实测 ≈1 MB/s（含取景并发）。想快还是得切 USB，
   但**协议层完全一样**，换 transport 即可 —— 这正是 IED 的 `LjAdapter` 结构。

---

## 4. 遗留待测

- `PC Save Image Size` 设 `Original` 时是否走不同的 opcode（本次未对比 2M 模式）
- 只拍 RAW（非 RAW+JPEG）时 IED 是否跳过 JPG 那一轮
- `GetObjectInfo` 里 ARW 的 size 字段与 `GetPartialObject` 总长有 1 字节错位现象，
  解码器已按最终传输量校正，需再用未压缩 RAW 复核一次
- USB 路径（`Lja_PTP_USB`）是否插入额外 opcode（PTP 载荷应一致，值得抓一次对照）

---

## 5. 直连探针实测（2026-11-04 深夜，probe.py）

不再依赖 IED，直接写 socket 连相机验证"能不能下载卡里已有的旧照片"。结论是**不能**，
原因不在参数，而在会话类型。

### 5.1 三处必须照抄的细节

1. **GUID 是配对身份**。随机 UUID → `Init_Fail type=5 reason=1`，连试 6 次全拒；
   换成 IED 配对时用过的 `<已配对的 GUID>` → 立刻 ACK。相机记住配对方。
2. **事件通道必须先于 OpenSession 建立**。顺序是
   `Init_Command_Ack` → 新开第二条 TCP → `Init_Event_Request(conn)` → `Init_Event_Ack`
   → 才发 `OpenSession`。少了这步，`OpenSession` **一个字节都不回**，只超时——
   很容易误判成"被拒绝"。
3. **`dataPhase` 位 IED 一律填 1**（包括 `OpenSession` 这种无数据阶段的操作），照抄。

### 5.2 枚举失败矩阵

`sony_init()`（SDIOConnect 1/2/3 + GetExtDeviceInfo×2，8 字节返回与抓包一致全零）通过后：

| 调用 | 结果 |
|---|---|
| GetStorageIDs | AccessDenied |
| GetNumObjects(0xFFFFFFFF) | AccessDenied |
| GetObjectHandles(0x0) | AccessDenied |
| GetObjectHandles(0xFFFFFFFF) | AccessDenied |
| GetStorageInfo(0x00010001) | AccessDenied |
| GetObjectInfo(0xFFFFC001) | OK |

而 `GetDeviceInfo` 报告的 32 个 opcode 里**确实包含** 0x1004 / 0x1007。
所以是会话权限，不是实现缺失。**PC Remote 会话不开放存储枚举，到此为止。**

### 5.3 ObjectInfo 偏移定标（两次交叉验证）

原实现宽高用 28/32（错），文件名用 52/53（对）。最终确定：

```
0 StorageID u32 | 4 ObjectFormat u16 | 6 ProtectionStatus u16
8 ObjectCompressedSize u32 | 12 ThumbFormat u16 | 14 ThumbCompressedSize u32
18 ThumbPixWidth u32 | 22 ThumbPixHeight u32
26 ImagePixWidth u32 | 30 ImagePixHeight u32
34 ParentObject u32 | 38 AssociationType u16 | 40 AssociationDesc u32
44 SequenceNumber u32 | 48 索尼多出 4 字节填充 | 52 文件名长度 u8 | 53 文件名 UTF-16LE
```

注意：按 PTP 规范累加（44+4）文件名长度应在 **48**，但索尼实测在 **52**，
多出 4 字节。两处独立验证都指向 52：

- 抓包 tid=299 的 ARW 记录：`d[52]=0x0d`，53 起是 `DSC00001.ARW`
- 实时探针 `GetObjectInfo(0xFFFFC002)`：`d[52]=0x0d`，53 起是 `DSC09999.JPG`

修完实测输出：
```
0xFFFFC002 len=82B  {'storage': 65536, 'format': 0x3801, 'size': 307200, 'name': 'DSC09999.JPG'}
```

### 5.3.1 `0xFFFFC001` 返回 OK 但 0 字节

相机空闲（本次会话没拍过照）时，`GetObjectInfo(0xFFFFC001)` 返回 `OK` + **空数据**，
不是错误码。所以写客户端时**不能只看返回码**，还要判数据长度，
否则会拿一个 length=0 的 buffer 去解析。真正拍过照之后才会返回 82 字节的 ARW 记录。

### 5.4 结论

拿旧照片走 **USB + MTP**（相机 `USB连接 = MTP`，安卓原生 `MtpDevice`），
不走 PTP。PTP 只用于"边拍边传"（`0xFFFFC001` + `GetPartialObject` 1 MB 分块）。
