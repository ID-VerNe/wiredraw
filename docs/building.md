# 构建（Building）

> 本文只给**入口与版本要求**；各工具的完整步骤、踩坑与产物说明见对应 README。
> 门禁口径见 [testing.md](testing.md)，清单唯一来源是 [`scripts/gates.json`](../scripts/gates.json)。

## 一、工具链要求

| 项 | 要求 | 说明 |
|---|---|---|
| Python | 3（仅标准库） | 工具脚本与门禁零依赖 |
| JDK | 21（探针）；17+（导片工具，本机用 Temurin 21） | `JAVA_HOME` 必须指向 JDK，不能是 JRE |
| Android SDK | `platforms;android-35` + `build-tools;35.0.1` | 版本号写死在探针 `build.sh` 顶部，装的版本不同就改那两行 |
| Gradle | **只用仓库内的 wrapper** | 不要用本机全局 Gradle；版本锁在 `gradle/wrapper/` |

## 二、环境变量（`manual` 档前置）

`manual` 档会在配置阶段校验 SDK 与 JDK，**跑之前先设好**：

```powershell
$env:ANDROID_HOME = "<本机 Android SDK 路径>"
$env:JAVA_HOME = "<本机 JDK 路径>"
```

- 探针 `build.sh` 的探测顺序：SDK 为 `ANDROID_HOME` → `ANDROID_SDK_ROOT` → 常见默认路径；
  JDK 为 `JAVA_HOME` → `PATH` 里的 `javac`。
- 导片工具的探测顺序：`local.properties` 的 `sdk.dir` → `ANDROID_HOME` / `ANDROID_SDK_ROOT`
  → `%LOCALAPPDATA%\Android\Sdk`；仍找不到就报错退出。
- 找不到时脚本会打印可直接复制的设置示例并以非 0 退出，不会静默降级。

## 三、探针 APK（`tools/android-mtp-probe`）

不需要 Gradle，手搓构建：

```bash
# Linux / macOS / Git Bash
bash tools/android-mtp-probe/build.sh
# 产物：build/mtp-probe.apk
```

Windows PowerShell 下 `bash` 命中 `C:\Windows\System32\bash.exe`（WSL），Windows 的 SDK
路径在 WSL 内不可见，`build.sh` 会以「找不到 Android SDK」退出。等价写法二选一
（均已实测通过）：

```powershell
# 显式用 Git for Windows 的 bash
& "C:\Program Files\Git\bin\bash.exe" tools/android-mtp-probe/build.sh

# 或走门禁执行器（内部避开 System32 的 WSL bash，并顺带验导片工具）
python scripts/run_gates.py manual
```

流程是 `aapt2 compile → aapt2 link → javac → d8 → 手动塞 classes.dex → zipalign → apksigner`。
签名用的 `rea-debug.keystore` 必须留在工程根目录（**别删**，见 [安全与隐私](safety-and-privacy.md)）。
完整说明与实测输出见[探针 README](../tools/android-mtp-probe/README.md)。

## 四、导片工具 APK（`tools/android-mtp-import`）

用 Gradle wrapper，一条命令验完 JVM 自检 + APK 构建：

```bash
# Linux / macOS / Git Bash
bash tools/android-mtp-import/gradlew -p tools/android-mtp-import selfTest assembleDebug
# 产物：tools/android-mtp-import/build/outputs/apk/debug/mtp-import-debug.apk
```

Windows PowerShell 用 `.bat` 包装脚本（`gradlew` 的 LF shell 脚本在 WSL 下会报
`$'\r': command not found`）：

```powershell
.\tools\android-mtp-import\gradlew.bat -p tools/android-mtp-import selfTest assembleDebug
```

- 纯逻辑自检不需要真机；只需定位 Android SDK（配置阶段即校验）。项数随用例增加，
  **以 `selfTest` 输出末行 `== 自检全绿：N 项 ==` 为准**。
- 纯 JDK 边界由编译期强制：`compilePureJdkJava` 编译这批文件时不给 `android.jar`，
  偷 `import android.*` 会直接编译失败。
- `manual` 档还会跑 `verifyApk`，用 SDK 的 `apksigner` 实测验签。
- 完整类职责与真机待验清单见[导片工具 README](../tools/android-mtp-import/README.md)。

## 五、安装到测试机

```bash
adb install -g tools/android-mtp-probe/build/mtp-probe.apk
adb install -g tools/android-mtp-import/build/outputs/apk/debug/mtp-import-debug.apk
```

相机侧设为 `MENU → 设置 → USB连接 → MTP`（不是 PC 遥控）。
首次插线系统会弹授权，且要勾一次「一律打开」；应用侧无法绕过。

## 六、门禁里的构建档

```bash
python scripts/run_gates.py manual
```

当前 `manual` 档两条：`android-build`（探针 `build.sh`）与 `android-import`
（`selfTest assembleDebug verifyApk`）。两条都需要 Android SDK + JDK，CI 跑不了，
**动到对应工具目录时必须本机手跑**。

## 常见失败

| 症状 | 原因 | 处理 |
|---|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 覆盖安装的签名与已装 APK 不一致 | 找回原来的 `rea-debug.keystore`；不要重新生成 |
| 找不到 Android SDK | 未设 `ANDROID_HOME` / `ANDROID_SDK_ROOT`，也无默认路径 | 按第二节设置后重跑 |
| SDK 版本不匹配 | `build-tools` / `platform` 不是脚本写死的版本 | 装对应版本，或改 `build.sh` 顶部 `BT_VER` / `PLATFORM` |
| Windows 下 `javac` 失败但脚本继续 | 退出码被管道吞掉 | 构建脚本一律先落日志再判退出码，不要用 `cmd` 管道 |
