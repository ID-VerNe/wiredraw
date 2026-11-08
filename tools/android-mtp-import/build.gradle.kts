import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 导片工具构建（issue #36）：AGP + Kotlin + Compose 编译器。
//
// 构建体系在本 PR（#40）里接了 Compose 三屏：`Main.java` 已降到编排层，
// 界面全部由 `src/com/rea/mtpimport/ui/*.kt` 渲染（`ComposeHost` 是唯一挂载点）。
// `ComposeSmoke.kt` 只保留编译链冒烟用途（`assembleDebug` 时验证 Compose 编译器就位），
// 不再代表界面实现。
//
// #207 把非 Android 的构建职责拆到 `gradle/`：纯 JDK 自检、产物验签、demo 照片生成，
// 以及它们共用的 `local.properties` 解析。本文件只留 Android/Kotlin/依赖声明。
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
    // 纯 JDK 的 DemoTree.java 反过来要进 selfTest 的 classpath
    // （清单在 gradle/self-test.gradle.kts 的 pureJdkDebugClasses）。
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

// 职责拆分（#207 的 S-P2-12）：
//   gradle/local-properties.gradle.kts  单一解析入口，结果放 extra["localProperties"]
//   gradle/self-test.gradle.kts         纯 JDK 清单 + 空 classpath 编译 + JVM 自检
//   gradle/verify-apk.gradle.kts        用 SDK 的 apksigner 实测验签
//   gradle/demo-photos.gradle.kts       debug 假数据照片降采样（素材缺失时跳过）
// local-properties 必须先 apply：另外三个脚本从 extra 读它解析出的 Map。
apply(from = "gradle/local-properties.gradle.kts")
apply(from = "gradle/self-test.gradle.kts")
apply(from = "gradle/verify-apk.gradle.kts")
apply(from = "gradle/demo-photos.gradle.kts")
