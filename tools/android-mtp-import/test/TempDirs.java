package com.rea.mtpimport;

import java.io.File;

/** 临时目录清理（#318 从 SelfTest.java 抽出）。创建点仍在各用例里，#320 再统一收口。 */
final class TempDirs {

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        if (!f.delete()) {
            System.err.println("清理临时文件失败：" + f);
        }
    }
}
