# tools/pe —— 极简 PE 检查器

纯标准库（不依赖 `pefile`）的只读 PE 检查器，用来查看某个 DLL 的导出、导入、C++ RTTI
类名与可打印字符串，以及在给定 PE 文件里找出「谁 import 了某个 DLL」。

它服务 [`docs/research/lja-ptp-ip-static-analysis.md`](../../docs/research/lja-ptp-ip-static-analysis.md)
的复跑，不参与构建，也不连相机。

## 边界

- **只读**：打开给定文件读取字节，不写回、不修改、不联网。
- **样本不入库**：被分析的 DLL（例如 `Lja_PTP_IP.dll`）属第三方软件产物，仓库不随附；
  用时自备样本，路径按 `<path-to-dll>` 占位。
- Python 3，任意平台；无第三方依赖。

## 用法

```bash
python tools/pe/pe_inspect.py exports   <path-to-dll>
python tools/pe/pe_inspect.py imports   <path-to-dll>
python tools/pe/pe_inspect.py rtti      <path-to-dll>
python tools/pe/pe_inspect.py strings   <path-to-dll> [minlen]
python tools/pe/pe_inspect.py whoimports <dllname> <path-to-pe>...
```

| 子命令 | 输出 |
|---|---|
| `exports` | 文件头（machine / imagebase / sections）+ 导出表，逐项 `[序号] RVA 名称` |
| `imports` | 按 DLL 分组的导入函数名与数量 |
| `rtti` | `.data` / `.rdata` 中扫到的 `.?AV<name>@@` 类名 |
| `strings` | 可打印 ASCII 串，默认最短 6 字符，`minlen` 可调 |
| `whoimports` | 在给定 PE 文件里找 import 了指定 DLL 的文件，逐条打印函数名 |

输出全部走 stdout，不落盘；无参数时打印用法（退出码 1）。

## 与静态分析记录的关系

[`lja-ptp-ip-static-analysis.md`](../../docs/research/lja-ptp-ip-static-analysis.md) 记录了用本工具
对 Imaging Edge Desktop 的 `Lja_PTP_IP.dll` 做的导出 / RTTI 分析。换了 IED 版本要复跑时，
按上面的命令重跑并把新结论回填到该记录；工具本身不内置任何分析结论。
