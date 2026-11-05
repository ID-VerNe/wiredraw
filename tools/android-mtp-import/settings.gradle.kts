import java.io.File

// 导片工具的 Gradle 工程（issue #36：从手搓 aapt2/javac/d8 迁移过来）。
//
// 工具链版本锁在 gradle/wrapper/gradle-wrapper.properties 里，别在本机全局安装 Gradle。

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mtp-import"

// ---- Android SDK 定位 ----
//
// AGP 只认 local.properties 的 `sdk.dir`，或环境变量 ANDROID_HOME / ANDROID_SDK_ROOT。
// 本机（AGENTS.md §4）两个环境变量都没设，而 local.properties 是机器相关、不入库的，
// 每个新 worktree 都不会带它 —— 缺文件时按 %LOCALAPPDATA%\Android\Sdk 推断并写一份。
// 推断不出来就明确失败：让调用者设 ANDROID_HOME 或自己写 local.properties，不猜路径。
val localProperties = file("local.properties")
if (!localProperties.isFile) {
    val fromEnv = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }
    val fromLocalAppData = System.getenv("LOCALAPPDATA")
        ?.let { File(it, "Android/Sdk") }
        ?.takeIf { it.isDirectory }
        ?.absolutePath
    val sdkDir = fromEnv ?: fromLocalAppData
        ?: throw GradleException(
            "找不到 Android SDK：请设置 ANDROID_HOME / ANDROID_SDK_ROOT，" +
                "或在 $localProperties 里写 sdk.dir=<SDK 路径>"
        )
    localProperties.writeText("sdk.dir=${sdkDir.replace('\\', '/')}\n")
    logger.lifecycle("已生成本地 $localProperties（sdk.dir=$sdkDir，不入库）")
}
