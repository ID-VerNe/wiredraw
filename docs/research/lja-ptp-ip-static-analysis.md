# Lja_PTP_IP.dll 静态分析结果

分析对象：`C:\Program Files\Sony\Imaging Edge\LjAdapter\Lja_PTP_IP.dll`
（IED 1.2.02.08050，文件时间 2025-06-12，x64，157 KB）
工具：`tools/pe/pe_inspect.py`（纯标准库 PE 解析，可复用）

---

## 1. 导出的 method（11 个 C ABI + 5 个 C++ 辅助）

符号未修饰 → `extern "C"`，是插件 ABI，不是 C++ 导出：

| # | 导出名 | RVA | 作用（按名字推断） |
|---|---|---|---|
| — | `Init` | 0xab00 | 全局初始化（大概率注册日志/回调） |
| — | `Release` | 0xab50 | 反初始化 |
| — | `GetAdaptorName` | 0xaba0 | 返回适配器名（UTF-16） |
| — | `GetConnectionTypeName` | 0xab70 | 返回连接类型名，"Wi-Fi" 一类 |
| — | `GetFingerprint` | 0xb4b0 | **仅 IP 版有**：配对/指纹 |
| — | `EnumCameraObjects` | 0xb680 | 枚举相机，返回枚举句柄 |
| — | `ReleaseEnum` | 0xbb70 | 释放枚举句柄 |
| — | `GetCameraObjectInfofromDetector` | 0xba50 | **仅 IP 版有**：从探测结果（SSDP）构造相机对象 |
| — | `Connect` | 0xbbe0 | 连接，返回 `IDeviceConnection*` |
| — | `CancelConnection` | 0xc0f0 | 取消 |
| — | `ReleaseConnection` | 0xc070 | 释放连接对象 |

另有 5 个 C++ 成员导出（`LjCommandData` 的构造/拷贝/赋值/析构/autoclassinit），
说明 `LjCommandData` 是**跨 DLL 按值传递**的数据类，两边必须用同一套 MSVC 运行时 —— 
这解释了 IED 目录里为什么自带 `msvcp140.dll` / `vcruntime140.dll`。

`Lja_PTP_USB.dll` 是同一套 ABI 的裁剪版（9 个）：没有 `GetFingerprint` 和
`GetCameraObjectInfofromDetector`，且 `Release` 与 `CancelConnection` 落在同一地址（同一个 stub）。

## 2. 调用方式：动态加载的 transport 插件

`LjCore.dll` 的静态导入表里**没有** `Lja_PTP_*.dll`，只有 `LoadLibraryExW` + `GetProcAddress`。
DLL 名是运行时拼出来的（二进制里搜不到 `Lja_PTP_IP.dll` 字面量），推测来自配置/目录扫描。

推断的调用序列（待动态验证）：

```
LjCore.dll
  LoadLibraryExW("…\LjAdapter\Lja_PTP_IP.dll")
  Init(...)                                   // 全局
  GetAdaptorName()  / GetConnectionTypeName()  // 让 UI 列出可用 transport
  EnumCameraObjects(...)                       // → 枚举句柄
      └─ GetCameraObjectInfofromDetector(...)  // 仅 IP：解析 SSDP 描述
  ReleaseEnum(h)
  Connect(cameraObj, ...)                      // → IDeviceConnection*
  GetFingerprint(...)                          // 仅 IP：配对
  ── 之后所有 PTP 事务走连接对象的虚表 ──
  CancelConnection(...) / ReleaseConnection(...)
  Release()
```

连接对象是 C++ 类：RTTI 里可见
`sony_lajiao::IDeviceConnection`（接口）← `DeviceConnectionPTP`（实现），
另有 `LjLogUtil`、`XpCriticalSection`、`XpSingleLock`、`XpSyncObject` 支撑类。

## 3. 传输实现：SSH 隧道里的 PTP/IP

这是本次分析最有价值的发现。

`Lja_PTP_IP.dll` 的依赖：

- `WS2_32.dll`（25 个 ordinal）→ 裸 socket
- `IPHLPAPI.dll: GetAdaptersInfo` → 枚举本机网卡（判断 Wi-Fi Direct 子网）
- `SHLWAPI: PathRemoveFileSpecW` → 拼 Adapter 目录路径
- **没有** `libssh2.dll` 的静态导入，但字符串里有一整套 `libssh2_*` 符号名 →
  运行时 `GetProcAddress` 动态加载

libssh2 符号清单（节选，共 27 个）：

```
libssh2_session_init_ex / _handshake / _method_pref / _set_blocking
libssh2_userauth_password_ex
libssh2_userauth_keyboard_interactive_ex          ← keyboard-interactive
libssh2_channel_direct_tcpip_ex                   ← SSH 端口转发
libssh2_channel_read_ex_for_cbm                   ← 注意 _cbm 后缀，索尼私有变体
libssh2_hostkey_hash / libssh2_keepalive_config
```

同时 DLL 里嵌着三段 XML 标签：

```xml
<X_SSH_Support>          </X_SSH_Support>
<X_PTP_PairingNecessity> </X_PTP_PairingNecessity>
<X_PTP_MediaServerSupport></X_PTP_MediaServerSupport>
```

**结论**：IED 的 Wi-Fi PC Remote 不是"裸 PTP/IP"，
而是 **SSDP 发现 → 读设备描述 XML（看 X_SSH_Support / X_PTP_PairingNecessity）
→ SSH 登录相机（password 或 keyboard-interactive）
→ `direct-tcpip` 转发 → 隧道里跑 PTP/IP**。

`GetFingerprint` 很可能就是配对凭证/主机密钥指纹的来源，只在 IP 版出现，与此吻合。

## 4. 对安卓方案的直接影响（修正上一版建议）

上一版建议"先做 PTP/IP，比 USB 便宜"——**这条要收回一半**：

| 路线 | 真实成本 | 结论 |
|---|---|---|
| Wi-Fi PC Remote | libssh2（NDK 可编）+ 配对凭证 + 设备描述解析 + 端口转发 | 比想象贵，且要复现索尼的 pairing 逻辑 |
| **USB PTP** | UsbDeviceConnection + 裸 PTP，没有 SSH/配对这一层 | **仍是安卓端最干净的路径** |
| MSC 直拷 | 无 | 最快，但不能边拍边传 |

手机端 `Ctrl w/ Smartphone` 那条（旧 JSON API）仍是 JPEG only，与上面两条无关。

## 5. 待相机连上后一起验证的 4 件事

1. **SSH 端口与账号**：相机侧开的是什么端口（22？），用户名/密码从哪来（配对时生成？固定值？）
2. **设备描述 XML**：SSDP 响应的 `LOCATION` 指向哪个 URL，里面 `X_SSH_Support` / `X_PTP_PairingNecessity` 对 a7C 的取值
3. **PTP/IP 握手**：隧道内是不是标准 `Init_Command_Request`（type=1），friendly name 传什么
4. **transport 选择**：`LoadLibraryExW` 的参数值，确认 DLL 名怎么拼

这四项用 Wireshark 抓 Wi-Fi + Process Monitor 看 `LoadLibraryExW` 就能全部落地，
不需要反汇编。

## 6. 说明

`.text` 里按 2 字节扫出的 "PTP opcode 常量" 直方图噪声很大（`0x100F` 出现 48 次看着像
`SetDevicePropValue`，但也可能是相邻指令字节的巧合），**不作为结论**，只留作抓包时的对照线索。
真正的 opcode 序列以 `parse_ptpip.py` 解出来的抓包为准。

`.pdata`/RTTI 里的类名不含函数名，虚表槽位也无法在无符号状态下命名，
所以"连接对象具体有哪些虚方法"必须靠动态跟踪（API Monitor 挂 `Connect` 的返回值）确认。
