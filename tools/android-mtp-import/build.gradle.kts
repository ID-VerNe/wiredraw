import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.util.Properties
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

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
//   3. SelfTest 的 327 项断言由 JavaExec 跑，入口与断言零改动。
//
// 清单的唯一事实来源就是下面这个列表（原 check.sh 的 PURE 数组）。

val pureJdkClasses = listOf(
    "AttachGate.java",
    "BackNav.java",
    "CancelToken.java",
    "Catalog.java",
    "ColorTokens.java",
    "DemoBridge.java",
    "DemoPayload.java",
    "DemoRun.java",
    "ImportGate.java",
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
    "SpaceCheck.java",
    "StorageRetry.java",
    "TopBarSpec.java",
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
    "DemoTransfer.java",
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
    description = "JVM 纯逻辑自检（327 项，不需要真机）"
    dependsOn("checkPureJdkList", compilePureJdkJava, compileSelfTestJava)
    // 中文断言名在 Windows 控制台按 cp936 输出会乱码，强制 UTF-8（原 check.sh 同样设置）
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    mainClass.set("com.rea.mtpimport.SelfTest")
    classpath = files(pureJdkOutput, selfTestOutput)
}

// ---------------------------------------------------------------- demo 照片占位（issue #46）
//
// Compose 假数据只有元数据，photo 页没有像素。这里在 **debug 构建** 时从本机素材目录
// 降采样生成 `src/debug/assets/demo/photo_XX.jpg`，产物 gitignore；素材目录/文件清单走
// `local.properties`（也可被 `-Pdemo.photo.dir` / `-Pdemo.photo.files` 覆盖）：
//   demo.photo.dir=C:/Users/.../知更鸟
//   demo.photo.files=DSC04882.JPG,DSC04835.JPG,...   # 可选，留空取目录里前 5 张 JPG
//
// 个人照片不入库；目录缺失时清空产物并跳过，别的机器 / CI 上没有素材也能跑 manual 门禁。
// ARW 不进 APK：假树仍保留 ARW 条目与真实大小，详情页预览用同名 JPG 的降采样图。

fun localProperty(key: String): String {
    // 不能走 java.util.Properties：它按 ISO-8859-1 读，中文目录名会变成乱码。
    val f = file("local.properties")
    if (!f.isFile) return ""
    val prefix = "$key="
    return f.readLines(Charsets.UTF_8)
        .firstOrNull { it.trimStart().startsWith(prefix) }
        ?.substringAfter('=')
        ?.trim()
        ?: ""
}

val demoPhotoSource = providers.gradleProperty("demo.photo.dir").orNull?.trim()
    ?: localProperty("demo.photo.dir")
val demoPhotoFiles = providers.gradleProperty("demo.photo.files").orNull?.trim()
    ?: localProperty("demo.photo.files")
val demoPhotoOutput = file("src/debug/assets/demo")

/** 读 JPEG 头部 APP1 的 EXIF Orientation；读不到按 1（正常）。 */
fun readExifOrientation(jpeg: File): Int = try {
    val bytes = jpeg.inputStream().use { it.readNBytes(128 * 1024) }
    val marker = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII)

    var tiff = -1
    for (i in 0..(bytes.size - marker.size)) {
        if (marker.indices.all { bytes[i + it] == marker[it] }) {
            tiff = i + marker.size
            break
        }
    }
    if (tiff < 0 || tiff + 8 > bytes.size) {
        1
    } else {
        val little = bytes[tiff] == 'I'.code.toByte()
        fun u16(o: Int): Int {
            val a = bytes[o].toInt() and 0xFF
            val b = bytes[o + 1].toInt() and 0xFF
            return if (little) a or (b shl 8) else (a shl 8) or b
        }
        fun u32(o: Int): Int = if (little) {
            u16(o) or (u16(o + 2) shl 16)
        } else {
            (u16(o) shl 16) or u16(o + 2)
        }
        val ifd = tiff + u32(tiff + 4)
        if (ifd < 0 || ifd + 2 > bytes.size) {
            1
        } else {
            var value = 1
            val count = u16(ifd)
            for (k in 0 until count) {
                val entry = ifd + 2 + k * 12
                if (entry + 12 > bytes.size) break
                if (u16(entry) == 0x0112) {
                    value = u16(entry + 8)
                    break
                }
            }
            value
        }
    }
} catch (t: Throwable) {
    println("  demo 照片：读 EXIF 失败（${jpeg.name}），按正常方向处理：$t")
    1
}

/** 按 EXIF Orientation（1–8）把像素转正。相机竖拍常靠 tag 旋转，不转会躺倒。 */
fun orientImage(src: BufferedImage, orientation: Int): BufferedImage {
    val w = src.width.toDouble()
    val h = src.height.toDouble()
    val (dw, dh) = if (orientation in 5..8) h.toInt() to w.toInt() else src.width to src.height
    val matrix = when (orientation) {
        2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w, 0.0)
        3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w, h)
        4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h)
        5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
        6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h, 0.0)
        7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, h, w)
        8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w)
        else -> return src
    }
    val dst = BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB)
    val g = dst.createGraphics()
    g.drawImage(src, matrix, null)
    g.dispose()
    return dst
}

/** 最长边压到 maxEdge 以内（只缩小不放大）。 */
fun scaleToMaxEdge(src: BufferedImage, maxEdge: Int): BufferedImage {
    val longest = maxOf(src.width, src.height)
    if (longest <= maxEdge) return src
    val ratio = maxEdge.toDouble() / longest
    val w = (src.width * ratio).toInt().coerceAtLeast(1)
    val h = (src.height * ratio).toInt().coerceAtLeast(1)
    val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = dst.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.drawImage(src, 0, 0, w, h, null)
    g.dispose()
    return dst
}

fun writeJpeg(img: BufferedImage, out: File, quality: Float) {
    val writer = ImageIO.getImageWritersByFormatName("jpg").next()
    val param = writer.defaultWriteParam.apply {
        compressionMode = ImageWriteParam.MODE_EXPLICIT
        compressionQuality = quality
    }
    ImageIO.createImageOutputStream(out).use { stream ->
        writer.output = stream
        writer.write(null, IIOImage(img, null, null), param)
    }
    writer.dispose()
}

val generateDemoPhotos = tasks.register("generateDemoPhotos") {
    group = "build"
    description = "把本机真实照片降采样到 src/debug/assets/demo（产物 gitignore；目录缺失时跳过）"
    doLast {
        // 先清空：素材目录被移除后不能留着上次的产物继续进 APK
        demoPhotoOutput.deleteRecursively()
        if (demoPhotoSource.isEmpty() || !File(demoPhotoSource).isDirectory) {
            println("  demo 照片：未配置 demo.photo.dir 或目录不存在，跳过（photo 页退回无图胶囊）")
            return@doLast
        }
        val sourceDir = File(demoPhotoSource)
        val picks: List<File> = if (demoPhotoFiles.isNotEmpty()) {
            demoPhotoFiles.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { File(sourceDir, it) }
        } else {
            sourceDir.listFiles { f: File -> f.isFile && f.extension.equals("jpg", true) }
                ?.sortedBy { it.name }?.take(5) ?: emptyList()
        }
        val valid = picks.filter { it.isFile }
        if (valid.isEmpty()) {
            println("  demo 照片：demo.photo.files 里没有可读的 JPG，跳过（photo 页退回无图胶囊）")
            return@doLast
        }
        demoPhotoOutput.mkdirs()
        valid.forEachIndexed { index, photo ->
            val src = ImageIO.read(photo)
            if (src == null) {
                println("  demo 照片：跳过无法解码的 ${photo.name}")
                return@forEachIndexed
            }
            val upright = orientImage(src, readExifOrientation(photo))
            val scaled = scaleToMaxEdge(upright, 1440)
            val out = File(demoPhotoOutput, "photo_%02d.jpg".format(index + 1))
            writeJpeg(scaled, out, 0.8f)
            println("  demo 照片：${photo.name} → ${out.name}（${scaled.width}×${scaled.height}，${out.length() / 1024} KB）")
        }
    }
}

// 素材生成只影响 debug 的 assets 目录；挂在 preBuild 上保证 assembleDebug 前产物就位。
// release 也会触发一次（没有 local.properties 时直接 skip），不会把照片打进 release。
tasks.named("preBuild") { dependsOn(generateDemoPhotos) }
