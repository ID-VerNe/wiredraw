import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 导片工具构建（issue #36）：AGP + Kotlin + Compose 编译器。
//
// 本轮只换构建体系，不改任何用户可见行为：界面仍是 Main.java 手写 View，
// ComposeSmoke.kt 只是编译链探针，没有调用点。
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
    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.srcDirs("src")
            kotlin.srcDirs("src")
            res.srcDirs("res")
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
    "ImportListener.java",
    "ImportPlan.java",
    "ImportService.java",
    "Journal.java",
    "Log.java",
    "PermGate.java",
    "ResumableTransfer.java",
    "Selection.java",
    "TransferProgress.java",
)

val pureJdkClassesDir = file("src/com/rea/mtpimport")
val pureJdkOutput = layout.buildDirectory.dir("classes/pureJdk")
val selfTestOutput = layout.buildDirectory.dir("classes/selfTest")

tasks.register("checkPureJdkList") {
    group = "verification"
    description = "纯 JDK 类清单与磁盘一致性（原 check.sh 第 0 步）"
    val declared = pureJdkClasses.sorted()
    val dir = pureJdkClassesDir
    doLast {
        // 与 `grep -L '^import android'` 等价：只看行首的 import android
        val androidImport = Regex("^import android", RegexOption.MULTILINE)
        val actual = dir.listFiles { f -> f.isFile && f.name.endsWith(".java") }!!
            .filterNot { androidImport.containsMatchIn(it.readText()) }
            .map { it.name }
            .sorted()
        if (actual != declared) {
            throw GradleException(
                "PURE 清单与磁盘上「不 import android 的文件」不一致。\n" +
                    "磁盘上纯 JDK 文件：$actual\n" +
                    "清单声明：$declared\n" +
                    "新增纯逻辑类必须加进 build.gradle.kts 的 pureJdkClasses，否则它不受「不许 import android」保护。"
            )
        }
        println("  ${declared.size} 个纯 JDK 文件，与清单一致")
    }
}

val compilePureJdkJava = tasks.register<JavaCompile>("compilePureJdkJava") {
    group = "build"
    description = "编译纯 JDK 类：classpath 故意为空，import android.* 会直接失败"
    source(pureJdkClasses.map { File(pureJdkClassesDir, it) })
    classpath = files()
    destinationDirectory.set(pureJdkOutput)
    options.encoding = "UTF-8"
}

val compileSelfTestJava = tasks.register<JavaCompile>("compileSelfTestJava") {
    group = "verification"
    description = "编译 test/SelfTest.java，只依赖纯 JDK 类的输出"
    dependsOn(compilePureJdkJava)
    source(file("test/SelfTest.java"))
    classpath = files(pureJdkOutput)
    destinationDirectory.set(selfTestOutput)
    options.encoding = "UTF-8"
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
