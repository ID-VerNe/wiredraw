// local.properties 的唯一解析入口（#207）。
//
// 原来有两套解析器：`verifyApk` 用 `java.util.Properties`（ISO-8859-1），demo 照片用
// 逐行读（UTF-8）。同一个文件两种编码，中文目录名在一边必然乱码。这里统一成 UTF-8 逐行，
// 结果放进 `extra["localProperties"]`，其余脚本只读这一份。
//
// 保留 `java.util.Properties` 的反转义（#207 评审 B2）：Android Studio 在 Windows 上
// 生成的是 `sdk.dir=C\:\\Users\\...\\Android\\Sdk`，不还原就会把 `\:` 与 `\\` 原样带进路径。

val propsFile = file("local.properties")
val localProperties: Map<String, String> = if (propsFile.isFile) {
    propsFile.readLines(Charsets.UTF_8)
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || '=' !in trimmed) {
                null
            } else {
                val raw = trimmed.substringAfter('=').trim()
                val value = raw.replace("\\\\", "\\").replace("\\:", ":")
                trimmed.substringBefore('=').trim() to value
            }
        }
        .toMap()
} else {
    emptyMap()
}
extra["localProperties"] = localProperties
