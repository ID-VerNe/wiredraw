// 纯 JDK 自检（#207 从 build.gradle.kts 拆出）：清单、空 classpath 编译、JVM 自检。
//
// 原 check.sh 的两层保护原样迁过来，断言一条不放宽：
//   1. PURE 清单与磁盘上「不 import android 的文件」必须一致（漏列即红）；
//   2. 这批文件在**空 classpath** 下编译 —— 偷偷 import android.* 会直接编译失败；
//   3. test/ 下的全部断言由 JavaExec 跑，入口与断言零改动（项数以运行输出末行为准）。

// 清单的唯一事实来源就是下面这个列表（原 check.sh 的 PURE 数组）。
// 测试代码不在这里：它是 #318 拆出来的 `test/*Cases.java`，由下面的 checkSelfTestLayout 单独守卫。

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
    "Motion.java",
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
    "SpaceRefresh.java",
    "StorageRetry.java",
    "StuckError.java",
    "TopBarSpec.java",
    "TransferProgress.java",
    "TypeTokens.java",
    "UiState.java",
    "UiText.java",
    "UiTransition.java",
    "UsbIdentity.java",
    "WorkerWait.java",
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
    description = "编译 test/ 下的自检源，只依赖纯 JDK 类的输出"
    dependsOn(compilePureJdkJava)
    // #318：按目录收集而不是逐个 source(file(...))。拆分前这里是单文件 source，
    // 新文件漏进 classpath 会让断言静默消失（main 少调一组也照样打印「自检全绿」），
    // 所以收集范围必须由目录决定，并由 checkSelfTestLayout 守住「定义与注册一一对应」。
    source(fileTree("test") { include("**/*.java") })
    classpath = files(pureJdkOutput)
    destinationDirectory.set(selfTestOutput)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
}

/** 测试源；`checkSelfTestLayout` 与编译任务共用同一份收集结果。 */
val selfTestSources = fileTree("test") { include("**/*.java") }

/**
 * #318 的墓碑。拆成多文件后有两类失败是编译期看不见的：
 *   1. `*Cases()` 有定义但 `main` 没注册 —— 断言静默消失，自检仍报全绿；
 *   2. 某个文件重新膨胀回上千行 —— 意味着这次拆分被一点点回退。
 * 这里只做结构校验，不跑断言；项数仍以 selfTest 运行输出末行为准。
 */
tasks.register("checkSelfTestLayout") {
    group = "verification"
    description = "测试源布局：*Cases 定义与 main 注册一一对应，且单文件不超过 800 行（#318）"
    val sources = selfTestSources
    val entry = file("test/SelfTest.java")
    val maxLines = 800
    doLast {
        val declared = sortedSetOf<String>()
        val declaration = Regex("(?m)^\\s*static void (\\w+Cases)\\(")
        (sources.files.sortedBy { it.name }).forEach { f ->
            if (!f.name.endsWith(".java")) {
                throw GradleException("test/ 下出现了非 .java 文件：${f.name}")
            }
            val text = f.readText()
            declaration.findAll(text).forEach { declared.add(it.groupValues[1]) }
            val lineCount = text.count { it == '\n' } + 1
            if (lineCount > maxLines) {
                throw GradleException(
                    "${f.name} 有 $lineCount 行，超过 $maxLines 行上限。\n" +
                        "#318 把 SelfTest.java 按子系统拆开就是为了不让单文件继续膨胀；" +
                        "新增断言请放进对应的 *Cases.java，必要时再新建一组并到 SelfTest.main 注册。"
                )
            }
        }
        val registered = Regex("(?m)^\\s+(\\w+Cases)\\(\\);")
            .findAll(entry.readText()).map { it.groupValues[1] }.toSortedSet()
        val unregistered = declared - registered
        val undefined = registered - declared
        if (unregistered.isNotEmpty() || undefined.isNotEmpty()) {
            throw GradleException(
                "测试组定义与 SelfTest.main 注册不一致。\n" +
                    "有定义但没注册（断言会静默消失）：$unregistered\n" +
                    "注册了但没定义（编译期就会红，这里提前报）：$undefined\n" +
                    "新增一组断言必须两处同时加：*Cases.java 里的 static void xxxCases() 与 SelfTest.main 的一行调用。"
            )
        }
        println("  ${declared.size} 个 *Cases 组与 main 注册一致，单文件上限 $maxLines 行")
    }
}

tasks.register<JavaExec>("selfTest") {
    group = "verification"
    description = "JVM 纯逻辑自检（不需要真机；项数以运行输出末行为准）"
    dependsOn("checkPureJdkList", "checkSelfTestLayout", compilePureJdkJava, compileSelfTestJava)
    // 中文断言名在 Windows 控制台按 cp936 输出会乱码，强制 UTF-8（原 check.sh 同样设置）
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    mainClass.set("com.rea.mtpimport.SelfTest")
    classpath = files(pureJdkOutput, selfTestOutput)
}
