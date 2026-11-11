// 构建常量的唯一来源读取器（issue #205）。
//
// 与 `gradle/local-properties.gradle.kts` 并列，职责同样是「一个文件、一个解析入口」：
// SDK 版本与签名参数散在探针 `build.sh` 与 `build.gradle.kts` 两处时，改一处漏一处就会出现
// 「探针 APK 与 Gradle APK 签名不同」（覆盖安装 INSTALL_FAILED_UPDATE_INCOMPATIBLE）
// 或「两边 minSdk 不一致」这类只在换机器时才暴露的错。现在两份构建脚本都读仓库根这一份。
//
// 解析口径与 shell 侧（`build.sh` 的 `source`）逐字对齐：UTF-8 逐行、`#` 注释、
// `键=值`，不做转义。**刻意不用 `java.util.Properties`**：它按 ISO-8859-1 解码并解释
// `\:` `\\` 转义，同一份文件在 Gradle 与 shell 会得到两个值，等于把「单一来源」拆回去了。
//
// 只负责解析与校验，结果放 `extra["buildConstants"]`；`build.gradle.kts` 从那里取。
// 文件路径：`rootProject` 就是本工程，仓库根 = `../../`。

val buildConstantsFile = rootProject.file("../../build-constants.properties")
if (!buildConstantsFile.isFile) {
    throw GradleException(
        "找不到构建常量文件：$buildConstantsFile。" +
            "它是 SDK / 签名常量的唯一来源（issue #205），探针的 build.sh 读同一份。"
    )
}

val buildConstants: Map<String, String> = buildConstantsFile.readLines(Charsets.UTF_8)
    .mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || '=' !in trimmed) {
            null
        } else {
            trimmed.substringBefore('=').trim() to trimmed.substringAfter('=').trim()
        }
    }
    .toMap()

// 缺项在这里一次性报清，不给默认值——默认值会让「漏改一处」重新变成静默通过。
val requiredKeys = listOf(
    "platformApi", "buildToolsVersion", "minSdk", "targetSdk",
    "keystoreFile", "keystoreAlias", "keystorePassword",
)
val missing = requiredKeys.filter { buildConstants[it].isNullOrEmpty() }
if (missing.isNotEmpty()) {
    throw GradleException("$buildConstantsFile 缺少必需键：${missing.joinToString(", ")}")
}

extra["buildConstants"] = buildConstants
