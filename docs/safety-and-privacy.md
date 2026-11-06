# 安全与隐私边界

> 本文是**对外可见的边界说明**，约束源在 [AGENTS.md](../AGENTS.md) §2 与各工具源码。
> 两者冲突时以源码与 AGENTS.md 为准；发现不一致请开 issue。

## 一、探针只读（硬约束）

`tools/` 下所有连相机的代码**只读**：不删除、不格式化、不改相机设置、不触发快门。
要加写操作，必须先开会话讨论并落进 issue，不在 PR 里顺手加。

### 允许的 opcode

PTP/IP 探针（[`tools/sony-ptpip/probe.py`](../tools/sony-ptpip/probe.py)）实际下发的都是会话与取值类：

| opcode | 值 | 用途 |
|---|---|---|
| `GetDeviceInfo` | `0x1001` | 取设备能力 |
| `OpenSession` | `0x1002` | 开只读会话 |
| `CloseSession` | `0x1003` | 关会话 |
| `GetStorageIDs` | `0x1004` | 枚举存储 |
| `GetStorageInfo` | `0x1005` | 取存储容量 |
| `GetNumObjects` | `0x1006` | 取对象数 |
| `GetObjectHandles` | `0x1007` | 枚举对象句柄 |
| `GetObjectInfo` | `0x1008` | 取对象元信息 |
| `GetObject` | `0x1009` | 取对象内容 |
| `GetThumb` | `0x100A` | 取缩略图 |
| `GetPartialObject` | `0x101B` | 按偏移取对象分块 |
| `GetExtDeviceInfo` | `0x9202` | 索尼私有：取设备扩展信息 |
| `GetDevicePropValue2` | `0x920E` | 索尼私有：取属性值 |
| `SDIOConnect` | `0x9201` | 索尼私有：复刻 IED 的引导序列，属会话类，不写相机 |

`probe.py` 的 opcode 表里还定义了一个写属性常量 `SetDevicePropValueEx`（`0x9209`），
但**探针从不调用它**；表保留它只是为了让抓包事务名能翻译。任何对写 opcode 的调用都属于红线变更。

### 明令禁止

- 删除对象（`DeleteObject`）、格式化存储（`FormatStore`）；
- 修改相机设置（`SetDevicePropValue` / `SetDevicePropValueEx` 等写属性 opcode）；
- 触发快门或启动拍摄（`InitiateCapture` 等）；
- 在 MTP 侧调用 Android `MtpDevice` 的删除、发送、格式化接口。

## 二、设备身份与隐私

真机测试会读到真实的相机序列号、PTP 配对 GUID、内网地址与文件路径。仓库的处理口径：

| 数据 | 仓库中的表示 | 说明 |
|---|---|---|
| 相机序列号 | `<CAMERA_SERIAL>` | 不入库；截图与报告里的真实值需先替换 |
| PTP 配对 GUID | `<PAIRED_GUID>` | 它是握手身份凭据，公开等于泄露既有配对；由命令行参数或本地文件传入 |
| 相机 / PC 内网地址 | `<相机IP>` / `<PC 内网 IP>` | 不入库；示例与文档一律用占位 |
| 抓包文件 | 不入库 | `captures/` 与 `out/` 是本地证据；pcap 内含序列号、GUID 与路径，分享前必须脱敏 |

- 隐私清洗的执行记录见 #100；新写文档与示例统一使用上表占位符。
- 被跟踪文件由 [`scripts/check_sensitive.py`](../scripts/check_sensitive.py) 扫描（`ci` 档）；
  PR 正文与评论另由 `support` 档的 [`scripts/check_pr_body.py`](../scripts/check_pr_body.py) 扫描。
  两者命中即阻断，行内豁免必须带具体理由。
- 实测**数字指标**（21.43 MB/s、1526 个对象、511 张 ARW、11.9 GB）是结论本身，保持原样，不做模糊化。

## 三、签名密钥

`tools/android-mtp-probe/rea-debug.keystore` 是**调试用签名密钥**，口令是 AOSP 公开的调试口令
（见 [README](../README.md) 说明），不含任何真实凭据。它只用于让 `adb install -r` 可复现：

- 不要换成个人或生产密钥；
- 不要删除：签名一变，覆盖安装会直接 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；
- 导片工具复用同一把 key（包名不同，不冲突）。

## 四、使用范围

这些工具只用于**观察自己拥有的相机**：被动读取卡内文件、复刻官方软件的只读枚举。
不修改固件、不影响保修；也不提供绕过相机访问控制的能力。

## 相关文档

- 协作红线：[AGENTS.md](../AGENTS.md) §2
- 已知限制：[known-limitations.md](known-limitations.md)
- 构建与门禁：[building.md](building.md)、[testing.md](testing.md)
