package com.rea.mtpimport;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static com.rea.mtpimport.TestSupport.*;

/** 源码文本护栏的公共件（#318 从 SelfTest.java 抽出）：读源码、递归扫文本、命中行解析。 */
final class SourceGuard {

    /**
     * 仓库根（gradle 的 selfTest 以工程目录为工作目录）。
     *
     * 全仓**唯一**一处 `user.dir`：`src/` 根与完整源码路径都由它派生，不再让调用方各自拼字面量。
     * 换工作目录或改产物布局时只改这里（#319）。
     */
    static final File REPO_ROOT = new File(System.getProperty("user.dir"));

    /** 产品源码根 `src/`：递归扫描与单文件读取共用同一个根。 */
    static final File SRC_ROOT = new File(REPO_ROOT, "src");

    /** 按 `src/` 下的相对路径取源码文件；读不到由调用方判红，不在这里兜底。 */
    static File source(String relPath) {
        return new File(SRC_ROOT, relPath);
    }

    /**
     * 读源码，读不到返回 null。**这是纯 JDK 自检里读源码的唯一入口**（#319）：
     * 六处内联 `Files.readString` 一律走这里，免得 IO 兜底散成多份。
     */
    static String readSourceOrNull(File f) {
        try {
            return Files.readString(f.toPath());
        } catch (IOException e) {
            return null;
        }
    }

    /** 断言 `needle` 只在 `allow` 一个文件里出现；其它文件命中即红。 */
    static void checkOnlyFromUiText(File srcRoot, String allow, String needle) {
        List<String> hits = new ArrayList<>();
        int scanned = scanSourceText(srcRoot, needle, allow, hits);
        check("文案只由 UiText 产出：" + needle, scanned > 0 && hits.isEmpty(),
                "扫描 " + scanned + " 个文件，命中：\n" + String.join("\n", hits));
    }

    // 命中行格式为「路径:行号 行内容」，hitLineOf 与 scanSourceText 共用这一约定。
    /**
     * 从 {@link #scanSourceText} 的命中行（`路径:行号 行内容`）取行号；解析失败返回 -1。
     * 非贪婪匹配第一段「冒号 + 数字 + 空白」，避开 Windows 盘符里的冒号与行内容里的数字。
     */
    static int hitLineOf(String hit) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^.+?:(\\d+)\\s").matcher(hit);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * 递归扫描 `src/` 下所有 .java/.kt 里含 `needle` 的行；`allow` 是唯一允许命中的文件（null = 不豁免）。
     *
     * 返回实际扫描到的文件数：`src/` 缺失或为空时返回 0，调用方必须判红，不能 fail-open。
     */
    static int scanSourceText(File dir, String needle, String allow, List<String> hits) {
        if (!dir.isDirectory()) return 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        int scanned = 0;
        for (File k : kids) {
            if (k.isDirectory()) {
                scanned += scanSourceText(k, needle, allow, hits);
                continue;
            }
            if (!k.getName().endsWith(".java") && !k.getName().endsWith(".kt")) continue;
            scanned++;
            String rel = k.getPath().replace('\\', '/');
            if (allow != null && rel.contains(allow)) continue;
            try {
                List<String> lines = Files.readAllLines(k.toPath());
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains(needle)) {
                        hits.add(rel + ":" + (i + 1) + " " + lines.get(i).trim());
                    }
                }
            } catch (IOException e) {
                hits.add(rel + "（读取失败: " + e + "）");
            }
        }
        return scanned;
    }
}
