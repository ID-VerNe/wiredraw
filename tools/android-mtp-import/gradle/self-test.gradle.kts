// 纯 JDK 自检（#207 从 build.gradle.kts 拆出）：清单、空 classpath 编译、JVM 自检。
//
// 原 check.sh 的两层保护原样迁过来，断言一条不放宽：
//   1. PURE 清单与磁盘上「不 import android 的文件」必须一致（漏列即红）；
//   2. 这批文件在**空 classpath** 下编译 —— 偷偷 import android.* 会直接编译失败；
//   3. SelfTest 的全部断言由 JavaExec 跑，入口与断言零改动（项数以运行输出末行为准）。
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
    "DestPolicy.java",
    "Dims.java",
    "Dirs.java",
    "FinishOutcome.java",
    "ImportEntry.java",
    "ImportGate.java",
    "ImportListener.java",
    "ImportPlan.java",
    "ImportRoundListener.java",
    "ImportService.java",
    "ImportUi.java",
    "Journal.java",
    "Log.java",
    "OrphanRounds.java",
    "PartResidue.java",
    "PauseSwitch.java",
    "PermGate.java",
    "PhotoWall.java",
    "ProbeVerdict.java",
    "RangeRead.java",
    "ResumableTransfer.java",
    "RoundFiles.java",
    "ScanResult.java",
    "ScanRun.java",
    "Selection.java",
    "SkipRule.java",
    "SpaceCheck.java",
    "SpaceGate.java",
    "StorageRetry.java",
    "StuckError.java",
    "TopBarSpec.java",
    "TransferProgress.java",
    "TypeTokens.java",
    "UiState.java",
    "UiText.java",
    "UiTransition.java",
    "UsbIdentity.java",
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
                    "新增纯逻辑类必须加进 gradle/self-test.gradle.kts 的 " +
                    "pureJdkClasses / pureJdkDebugClasses，否则它不受「不许 import android」保护。"
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

tasks.register<JavaExec>("selfTest") {
    group = "verification"
    description = "JVM 纯逻辑自检（不需要真机；项数以运行输出末行为准）"
    dependsOn("checkPureJdkList", compilePureJdkJava, compileSelfTestJava)
    // 中文断言名在 Windows 控制台按 cp936 输出会乱码，强制 UTF-8（原 check.sh 同样设置）
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    mainClass.set("com.rea.mtpimport.SelfTest")
    classpath = files(pureJdkOutput, selfTestOutput)
}
