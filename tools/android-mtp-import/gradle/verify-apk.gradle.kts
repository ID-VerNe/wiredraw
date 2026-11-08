// 产物验签（#207 从 build.gradle.kts 拆出）。
//
// 原 base 的 check.sh 里有 `apksigner verify`，迁移后不能静默丢掉。
// `validateSigningDebug` 只校验签名配置可读，不等于产物真的被签过 —— 这里对产物实测。
// 仓库只在 Windows 上做 Android 构建（AGENTS.md §4），所以用 cmd 调 SDK 的 apksigner.bat。
//
// SDK 定位与空值检查必须留在任务块内（惰性求值，#207 评审 B1）：`apply(from = ...)`
// 的顶层语句在配置阶段就执行，提到顶层会让缺 `sdk.dir` 时连 `gradlew help` 都失败。

fun versionParts(name: String): List<Int> = name.split('.').map { it.toIntOrNull() ?: 0 }

fun compareBuildTools(a: File, b: File): Int {
    val pa = versionParts(a.name)
    val pb = versionParts(b.name)
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val diff = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
        if (diff != 0) return diff
    }
    return 0
}

val verifyApk = tasks.register<Exec>("verifyApk") {
    group = "verification"
    description = "用 SDK 的 apksigner 校验 debug APK 签名（原 check.sh 的验签断言）"
    dependsOn("assembleDebug")

    @Suppress("UNCHECKED_CAST")
    val props = project.extra["localProperties"] as Map<String, String>
    val sdkPath = props["sdk.dir"].orEmpty()
    if (sdkPath.isBlank()) {
        throw GradleException("local.properties 里没有 sdk.dir，无法定位 apksigner")
    }
    val sdkDir = File(sdkPath)

    val buildTools = sdkDir.resolve("build-tools")
        .listFiles { f: File -> f.isDirectory }
        ?.maxWithOrNull(::compareBuildTools)
        ?: throw GradleException("SDK 下没有 build-tools：$sdkDir")
    val apksigner = buildTools.resolve("apksigner.bat")
    if (!apksigner.isFile) throw GradleException("找不到 $apksigner")

    val apk = layout.buildDirectory.file("outputs/apk/debug/${rootProject.name}-debug.apk").get().asFile
    executable = "cmd.exe"
    args("/c", apksigner.absolutePath, "verify", "--print-certs", apk.absolutePath)
}
