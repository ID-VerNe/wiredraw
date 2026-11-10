package com.rea.mtpimport;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.rea.mtpimport.ResumableTransfer.Kind;

import static com.rea.mtpimport.TestSupport.*;
import static com.rea.mtpimport.TestFakes.*;

/** 测试样本与 IO 工具（#318 从 SelfTest.java 抽出）：目录树样本、假文件写入、字节比对。 */
final class Fixtures {

    // ---------------- 一轮导入的落盘文件（#219 / A2；索引已按 #20 裁决 B 停写） ----------------

    static void indexMkdirs(File dir) throws IOException {
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("mkdir failed: " + dir);
        }
    }

    /** 造一个指定字节数的文件；0 字节用来验证「不做成品」。 */
    static File writeSizedFile(File f, int size) throws IOException {
        indexMkdirs(f.getParentFile());
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[size]);
        }
        return f;
    }

    // ---------------- 多存储同名冲突（#54） ----------------

    /** 两个存储卷、同一 relPath、同一尺寸：最容易静默丢数据的场景。 */
    static Catalog conflictCatalog() {
        Catalog c = new Catalog();
        c.add(new Catalog.Entry(1, 0, Catalog.FMT_FOLDER, 0, "DCIM", 1));
        c.add(new Catalog.Entry(2, 1, Catalog.FMT_FOLDER, 0, "100MSDCF", 1));
        c.add(new Catalog.Entry(3, 2, Catalog.FMT_ARW, 300, "DSC02183.ARW", 1));
        c.add(new Catalog.Entry(11, 0, Catalog.FMT_FOLDER, 0, "DCIM", 2));
        c.add(new Catalog.Entry(12, 11, Catalog.FMT_FOLDER, 0, "100MSDCF", 2));
        c.add(new Catalog.Entry(13, 12, Catalog.FMT_ARW, 300, "DSC02183.ARW", 2));
        return c.build();
    }

    /**
     * 落满尺寸的 `.part` 再 rename 到 outDir/<relPath>，模拟真实传输的收尾。
     * 续传停用（#177）后，Journal 的跳过必须能在磁盘上看到成品，暂停/取消用例要用这条。
     */
    static ResumableTransfer.Result writeFakeFile(ImportPlan.Item item, String partPath) {
        try {
            File part = new File(partPath);
            indexMkdirs(part.getParentFile());
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(new byte[(int) item.size]);
            }
            File dest = new File(part.getParentFile(), item.relPath);
            indexMkdirs(dest.getParentFile());
            if (!part.renameTo(dest)) {
                return new ResumableTransfer.Result(Kind.FAILED, "rename failed");
            }
            return ResumableTransfer.Result.of(Kind.OK);
        } catch (IOException e) {
            return new ResumableTransfer.Result(Kind.FAILED, e.toString());
        }
    }

    /** 只落一个假 `.part`（不产生成品），供 FakeTransfer 的 FLUKY / HANGING 模式复用。 */
    static ResumableTransfer.Result writeFakePart(ImportPlan.Item item, String partPath) {
        try {
            File part = new File(partPath);
            File parent = part.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return new ResumableTransfer.Result(Kind.FAILED, "mkdir failed");
            }
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(new byte[(int) Math.min(item.size, 16)]);
            }
            return ResumableTransfer.Result.of(Kind.OK);
        } catch (IOException e) {
            return new ResumableTransfer.Result(Kind.FAILED, e.toString());
        }
    }

    static boolean payloadMatches(File file, int size) throws IOException {
        if (!file.isFile() || file.length() != size) return false;
        byte[] actual = Files.readAllBytes(file.toPath());
        for (int i = 0; i < size; i++) {
            if (actual[i] != (byte) (i % 251)) return false;
        }
        return true;
    }

    static void writeSizedBytes(File f, int size, byte value) throws IOException {
        indexMkdirs(f.getParentFile());
        byte[] buf = new byte[size];
        Arrays.fill(buf, value);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(buf);
        }
    }

    // ---------------- 假数据模式（DemoTree，U-54） ----------------

    static int countWithArw(PhotoWall wall) {
        int n = 0;
        for (PhotoWall.Unit u : wall.units()) if (u.hasArw()) n++;
        return n;
    }

    static int countWithJpg(PhotoWall wall) {
        int n = 0;
        for (PhotoWall.Unit u : wall.units()) if (u.hasJpg()) n++;
        return n;
    }

    static List<PhotoWall.Unit> unitsWithArw(PhotoWall wall) {
        List<PhotoWall.Unit> out = new ArrayList<>();
        for (PhotoWall.Unit u : wall.units()) if (u.hasArw()) out.add(u);
        return out;
    }

    // ---------------- 样本与工具 ----------------

    /**
     * <pre>
     * 0 (root)
     * ├─ 1 DCIM/                    folder
     * │   ├─ 2 100MSDCF/            folder
     * │   │   ├─ 3 DSC02183.ARW     300
     * │   │   └─ 4 DSC02183.JPG     100
     * │   └─ 6 DSC02999.ARW         50    （直接在 DCIM 下，测非两层的路径）
     * └─ 5 C0001.MP4                60    （顶层文件）
     * </pre>
     */
    static Catalog sample() {
        Catalog c = new Catalog();
        c.add(new Catalog.Entry(1, 0, Catalog.FMT_FOLDER, 0, "DCIM"));
        c.add(new Catalog.Entry(2, 1, Catalog.FMT_FOLDER, 0, "100MSDCF"));
        c.add(new Catalog.Entry(3, 2, Catalog.FMT_ARW, 300, "DSC02183.ARW"));
        c.add(new Catalog.Entry(4, 2, Catalog.FMT_JPEG, 100, "DSC02183.JPG"));
        c.add(new Catalog.Entry(6, 1, Catalog.FMT_ARW, 50, "DSC02999.ARW"));
        c.add(new Catalog.Entry(5, 0, Catalog.FMT_JPEG, 60, "C0001.JPG"));
        return c.build();
    }

    /**
     * #62 组选用例：两个日期目录，一个 2 张 ARW（另含 1 张同名 JPG），一个 1 张 ARW。
     */
    static Catalog groupSample() {
        Catalog c = new Catalog();
        c.add(new Catalog.Entry(1, 0, Catalog.FMT_FOLDER, 0, "20260607"));
        c.add(new Catalog.Entry(3, 1, Catalog.FMT_ARW, 300, "DSC02183.ARW"));
        c.add(new Catalog.Entry(4, 1, Catalog.FMT_ARW, 100, "DSC02184.ARW"));
        c.add(new Catalog.Entry(5, 1, Catalog.FMT_JPEG, 50, "DSC02183.JPG"));
        c.add(new Catalog.Entry(6, 0, Catalog.FMT_FOLDER, 0, "20260608"));
        c.add(new Catalog.Entry(8, 6, Catalog.FMT_ARW, 60, "DSC02190.ARW"));
        return c.build();
    }

    /**
     * 造一棵「每个文件夹下挂若干个文件」的大树，用于截断计数用例。
     * 所有对象直接挂在 0 号根或根文件夹下，深度优先顺序稳定。
     */
    static Catalog flatCatalog(int folders, int files) {
        Catalog c = new Catalog();
        for (int i = 0; i < folders; i++) {
            c.add(new Catalog.Entry(1000 + i, 0, Catalog.FMT_FOLDER, 0, String.format("D%04d", i)));
        }
        for (int i = 0; i < files; i++) {
            c.add(new Catalog.Entry(2000 + i, 1000 + (i % folders), Catalog.FMT_ARW, 24_000_000L,
                    String.format("P%04d.ARW", i)));
        }
        return c.build();
    }
}
