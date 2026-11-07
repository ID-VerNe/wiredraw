# 架构（Architecture）

> 本文只画**模块边界与依赖方向**，细节链回各工具 README 与
> [`AGENTS.md`](../AGENTS.md) §3「单一归属」表。实现改动前先读 §3。

## 一、总览

```
                         ┌──────────────────────────────┐
                         │  tools/android-mtp-probe     │  一次性验证：先证明 MTP 走得通
                         │  UsbManager → MtpDevice      │  只读枚举 / importFile / 分块语义
                         └──────────────┬───────────────┘
                                        │ 已验证的 MTP 结论与语义
                                        ▼
┌──────────────────────────────────────────────────────────────────┐
│  tools/android-mtp-import                                        │  产品化导片工具
│  Main（Activity：权限 / 插拔 / 三屏）                              │
│   ├─ MtpSession        会话生命周期 + 唤醒重试                     │
│   ├─ CatalogScanner → Catalog                                    │
│   ├─ Selection → ImportPlan                                      │
│   ├─ ImportService → MtpTransfer → Journal                       │
│   └─ ThumbnailStore                                              │
│  纯 JDK 逻辑层（编译期禁止 import android.*）                       │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│  tools/sony-ptpip                                                │  另一条路线（PTP/IP）
│  probe.py         cmd 通道 + 事件通道 + OpenSession（只读）         │
│  parse_ptpip.py   pcap → REQ/RSP/DATA 事务流水                   │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│  门禁层：scripts/gates.json（唯一清单） → scripts/run_gates.py      │
│  static（5） / manual（2） / support（3）；本机与 CI 共用           │
└──────────────────────────────────────────────────────────────────┘
```

## 二、三条链路

| 链路 | 落点 | 职责 | 只读边界 |
|---|---|---|---|
| MTP 探针 | [`tools/android-mtp-probe/`](../tools/android-mtp-probe/README.md) | 找 Sony 设备、开 MTP 会话、枚举、下载、验证 `importFile` 与 `getPartialObject` 语义 | 不删除、不格式化、不改设置、不触发快门 |
| 导片工具 | [`tools/android-mtp-import/`](../tools/android-mtp-import/README.md) | 扫描 → 照片墙 → 勾选 → 导入计划 → 传输（暂停 / 取消；续传已停用 #177）→ 缩略图与预览 | 同上；所有原生调用都走只读或下载接口 |
| PTP/IP 工具 | [`tools/sony-ptpip/`](../tools/sony-ptpip/README.md) | 直连相机 TCP 15740，复刻 IED 握手，逐条验证枚举被拒的事实；解析抓包 | 只发会话与取值类 opcode |

**依赖方向**：探针先验证语义，导片工具把结论产品化；导片工具不反向依赖探针的实验代码，
只复用已验证的结论（format 过滤不可信、会话挂起）；非零 `offset` 只有在恰好等于相机内部游标时
才被采纳、对不上时忽略 offset 从对象头返回，因此 `getPartialObject` 的分块续传已停用（#177），
不再作为依赖。
PTP/IP 与 MTP 是并列的两条路线，不互相依赖。

## 三、导片工具的内部分层

| 层 | 模块 | 约束 |
|---|---|---|
| Android 边界 | `Main`、`MtpSession`、`CatalogScanner`、`MtpTransfer`、`ThumbnailStore` | 唯一允许碰 `android.*` 与 MTP 原生调用的地方 |
| 纯逻辑 | `Catalog`、`Selection`、`ImportPlan`、`TransferProgress`、`Journal`、`SpaceCheck`、`ImportGate`、`ImportService`、`UiState`、`UiText` 等 | 不允许 `import android.*`；由 Gradle 的 `compilePureJdkJava` 在编译期强制 |
| UI | `ui/`（Compose） | 只做界面与状态映射，不承载业务规则 |

纯逻辑层能在普通 JVM 上跑 `test/SelfTest.java`，这是 `manual` 档的一部分；断言数量随用例增加，
以 `selfTest` 输出末行为准。
`pureJdkClasses` 清单还有漂移检查兜底，漏列一个纯逻辑文件就判红。

## 四、门禁层

- [`scripts/gates.json`](../scripts/gates.json) 是**唯一事实来源**；新增门禁只改清单，
  再跑 `python scripts/check_gates_manifest.py --write` 重写 `AGENTS.md` 的生成段。
- [`scripts/run_gates.py`](../scripts/run_gates.py) 是执行器，本机与 CI 跑同一份清单。
- 分档：`static`、`manual`（Android 构建）、`support`（需要 PR 上下文）；条数与执行顺序以
  [`scripts/gates.json`](../scripts/gates.json) 为准，本文不重复计数。
- 元门禁 `check_gates_manifest.py` 校验「清单 ↔ `AGENTS.md` 生成段 ↔ 磁盘脚本」零漂移，
  并禁止门禁命令带会降级或改写工作区的参数——完整清单以 `scripts/check_gates_manifest.py`
  的 `FORBIDDEN_FLAGS` 与 `flags.require` 为准，本文不再枚举。

## 五、单一归属

新代码往哪儿写，以 [`AGENTS.md`](../AGENTS.md) §3 的「单一归属」表为准；本文不再维护第二份表，避免两处漂移。

## 相关文档

- 构建：[building.md](building.md)
- 测试与门禁：[testing.md](testing.md)
- 已知限制：[known-limitations.md](known-limitations.md)
