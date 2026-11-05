import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// 导片工具构建（issue #36）：AGP + Kotlin + Compose 编译器。
//
// 构建体系在本 PR（#40）里接了 Compose 三屏：`Main.java` 已降到编排层，
// 界面全部由 `src/com/rea/mtpimport/ui/*.kt` 渲染（`ComposeHost` 是唯一挂载点）。
// `ComposeSmoke.kt` 只保留编译链冒烟用途（`assembleDebug` 时验证 Compose 编译器就位），
// 不再代表界面实现。
plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "com.rea.mtpimport"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rea.mtpimport"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-framework"
    }

    signingConfigs {
        create("reaDebug") {
            // 与手搓构建同一把 key：别重新生成，换 key 会让覆盖安装 INSTALL_FAILED_UPDATE_INCOMPATIBLE。
            // 指纹对照见 issue #36 的验收证据（apksigner --print-certs 的新旧 SHA-256 必须一致）。
            storeFile = rootProject.file("../android-mtp-probe/rea-debug.keystore")
            storePassword = "android"
            keyAlias = "rea"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("reaDebug")
        }
    }

    // 沿用原有目录布局，不为了 Gradle 默认结构搬动源码：
    //   AndroidManifest.xml / res/ / src/ 都在工程根下，外加 test/SelfTest.java 给 JVM 自检用。
    //
    // `src/debug/` 是假数据模式（U-54）：main 必须显式排除它，否则 release 也会编进 DemoEntry；
    // 纯 JDK 的 DemoTree.java 反过来要进 selfTest 的 classpath（见下面的 pureJdkDebugClasses）。
    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            // 源码根取 `src/com` 而不是 `src`：后者会把 debug 源集（`src/debug`）一起卷进 main，
            // 于是 release 也会编进 `DemoEntry`（U-54 的禁项）。AGP 的
            // `AndroidSourceDirectorySet` 在这版没有可用的 exclude/filter，
            // 用「更窄的根目录」是最直接的隔离方式。
            java.srcDirs("src/com")
            kotlin.srcDirs("src/com")
            res.srcDirs("res")
        }
        getByName("debug") {
            java.srcDirs("src/debug")
            kotlin.srcDirs("src/debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.runtime:runtime")
    // 界面重建（#40）：只引 ui + foundation —— 不引 material3，避免默认主题的
    // 字号/色调/涟漪盖过 UI-SPEC 的定稿 token。
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    // `ComposeView` 挂在窗口上时要求 ViewTreeLifecycleOwner（否则 `onAttachedToWindow` 直接抛
    // `ViewTreeLifecycleOwner not found`）。`android.app.Activity` 不提供它，`ComponentActivity` 提供；
    // 这一条是宿主 Activity 能承载 Compose 的最小依赖，不是 UI 组件库。
    implementation("androidx.activity:activity:1.7.0")
}

// ---------------------------------------------------------------- 纯 JDK 自检
//
// 原 check.sh 的两层保护原样迁过来，断言一条不放宽：
//   1. PURE 清单与磁盘上「不 import android 的文件」必须一致（漏列即红）；
//   2. 这批文件在**空 classpath** 下编译 —— 偷偷 import android.* 会直接编译失败；
//   3. SelfTest 的 80 项断言由 JavaExec 跑，入口与断言零改动。
//
// 清单的唯一事实来源就是下面这个列表（原 check.sh 的 PURE 数组）。

val pureJdkClasses = listOf(
    "AttachGate.java",
    "Catalog.java",
    "DemoBridge.java",
    "DemoPayload.java",
    "ImportListener.java",
    "ImportPlan.java",
    "ImportService.java",
    "Journal.java",
    "Log.java",
    "PermGate.java",
    "PhotoWall.java",
    "ResumableTransfer.java",
    "ScanRun.java",
    "Selection.java",
    "TransferProgress.java",
    "UiState.java",
    "UiText.java",
)

/**
 * debug 源集里的纯 JDK 类。它们不进 APK 的 main 源集，但同样要受「不许 import android」
 * 保护，并且必须编进 selfTest 的 classpath（假数据总量由 JVM 断言守住，U-54）。
 * `DemoEntry.java` 故意不在这里：它读 `Intent`，属于 Android 侧。
 */
val pureJdkDebugClasses = listOf(
    "DemoTree.java",
)

val pureJdkClassesDir = file("src/com/rea/mtpimport")
val pureJdkDebugClassesDir = file("src/debug/com/rea/mtpimport")
val pureJdkOutput = layout.buildDirectory.dir("classes/pureJdk")
val selfTestOutput = layout.buildDirectory.dir("classes/selfTest")

tasks.register("checkPureJdkList") {
    group = "verification"
    description = "纯 JDK 类清单与磁盘一致性（原 check.sh 第 0 步，main + debug 两个目录）"
    val declared = (pureJdkClasses + pureJdkDebugClasses).sorted()
    val mainDir = pureJdkClassesDir
    val debugDir = pureJdkDebugClassesDir
    doLast {
        // 与 `grep -L '^import android'` 等价：只看行首的 import android
        val androidImport = Regex("^import android", RegexOption.MULTILINE)
        fun scan(dir: File): List<String> =
            (dir.listFiles { f -> f.isFile && f.name.endsWith(".java") } ?: emptyArray())
                .filterNot { androidImport.containsMatchIn(it.readText()) }
                .map { it.name }

        val actual = (scan(mainDir) + scan(debugDir)).sorted()
        if (actual != declared) {
            throw GradleException(
                "PURE 清单与磁盘上「不 import android 的文件」不一致。\n" +
                    "磁盘上纯 JDK 文件：$actual\n" +
                    "清单声明：$declared\n" +
                    "新增纯逻辑类必须加进 build.gradle.kts 的 pureJdkClasses / pureJdkDebugClasses，" +
                    "否则它不受「不许 import android」保护。"
            )
        }
        println("  ${declared.size} 个纯 JDK 文件，与清单一致")
    }
}

val compilePureJdkJava = tasks.register<JavaCompile>("compilePureJdkJava") {
    group = "build"
    description = "编译纯 JDK 类（含 debug 的 DemoTree）：classpath 故意为空，import android.* 会直接失败"
    source(pureJdkClasses.map { File(pureJdkClassesDir, it) } +
        pureJdkDebugClasses.map { File(pureJdkDebugClassesDir, it) })
    classpath = files()
    destinationDirectory.set(pureJdkOutput)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

val compileSelfTestJava = tasks.register<JavaCompile>("compileSelfTestJava") {
    group = "verification"
    description = "编译 test/SelfTest.java，只依赖纯 JDK 类的输出"
    dependsOn(compilePureJdkJava)
    source(file("test/SelfTest.java"))
    classpath = files(pureJdkOutput)
    destinationDirectory.set(selfTestOutput)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

// 验签：base 的 check.sh 里有 `apksigner verify`，迁移后不能静默丢掉。
// validateSigningDebug 只校验签名配置可读，不等于产物真的被签过 —— 这里对产物实测。
// 仓库只在 Windows 上做 Android 构建（AGENTS.md §4），所以用 cmd 调 SDK 的 apksigner.bat。
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

    val localProperties = Properties()
    file("local.properties").inputStream().use { localProperties.load(it) }
    val sdkDir = File(localProperties.getProperty("sdk.dir"))
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

tasks.register<JavaExec>("selfTest") {
    group = "verification"
    description = "JVM 纯逻辑自检（80 项，不需要真机）"
    dependsOn("checkPureJdkList", compilePureJdkJava, compileSelfTestJava)
    // 中文断言名在 Windows 控制台按 cp936 输出会乱码，强制 UTF-8（原 check.sh 同样设置）
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    mainClass.set("com.rea.mtpimport.SelfTest")
    classpath = files(pureJdkOutput, selfTestOutput)
}
