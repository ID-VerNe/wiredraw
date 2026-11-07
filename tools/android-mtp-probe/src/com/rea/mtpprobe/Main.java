package com.rea.mtpprobe;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.mtp.MtpDeviceInfo;
import android.mtp.MtpObjectInfo;
import android.mtp.MtpStorageInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 极简 MTP 宿主探针：验证「安卓手机经 USB 读 a7C 卡内已有 RAW」这条路。
 *
 * 只做只读操作：枚举存储、列对象、下载一张最大文件。
 * 不发删除、不改相机任何设置。
 *
 * 输出：logcat tag=MTPPROBE，同时落一份到 getFilesDir()/report.txt
 * （adb shell run-as com.rea.mtpprobe cat files/report.txt 取回）
 */
public class Main extends Activity {

    static final String TAG = "MTPPROBE";
    static final String ACTION_PERM = "com.rea.mtpprobe.USB_PERM";
    static final int SONY_VID = 1356;

    /** MTP 格式码：Association（文件夹）。 */
    static final int FMT_FOLDER = 0x3001;
    /** MTP 格式码：索尼 ARW。 */
    static final int FMT_ARW = 0xB101;
    /** MTP 格式码：JPEG。 */
    static final int FMT_JPEG = 0x3801;
    /** 1 MiB = 2^20。运行文案沿用 README 的 "MB" 写法；改文案属文档线。 */
    static final double MB = 1048576.0;
    /** 1 GiB = 2^30。运行文案沿用 README 的 "GB" 写法（二进制换算）；改文案属文档线。 */
    static final double GB = 1073741824.0;
    /** 样例清单的文本上限（字符），超过后不再追加，避免报告膨胀。 */
    static final int SAMPLE_TEXT_LIMIT = 1200;
    /** 差集样例的文本上限（字符）。 */
    static final int DIFF_SAMPLE_LIMIT = 400;

    TextView tv;
    final StringBuilder sb = new StringBuilder();
    final CountDownLatch permLatch = new CountDownLatch(1);
    volatile boolean permGranted = false;

    final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ACTION_PERM.equals(a)) {
                permGranted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                out("权限回调: granted=" + permGranted);
                permLatch.countDown();
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                out("收到 USB_DEVICE_ATTACHED");
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        tv = new TextView(this);
        tv.setTextSize(11);
        tv.setGravity(Gravity.START);
        tv.setPadding(24, 24, 24, 24);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        setContentView(sv);

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_PERM);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }

        new Thread(this::runProbe, "mtp-probe").start();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    void out(String s) {
        Log.i(TAG, s);
        sb.append(s).append('\n');
        runOnUiThread(() -> tv.setText(sb.toString()));
    }

    void runProbe() {
        try {
            probe();
        } catch (Throwable t) {
            out("!! 异常: " + t);
            Log.e(TAG, "probe failed", t);
            flushReport();
        }
    }

    void probe() throws Exception {
        out("=== MTP 探针启动 " + Build.MANUFACTURER + " " + Build.MODEL
                + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") ===");

        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDevice dev = null;
        for (UsbDevice d : um.getDeviceList().values()) {
            out(String.format(Locale.US, "发现 USB 设备: %s VID=0x%04X PID=0x%04X class=%d/%d/%d",
                    d.getDeviceName(), d.getVendorId(), d.getProductId(),
                    d.getDeviceClass(), d.getDeviceSubclass(), d.getDeviceProtocol()));
            if (d.getVendorId() == SONY_VID) dev = d;
        }
        if (dev == null) {
            out("没有找到索尼设备（VID 0x054C）。检查 OTG 线 / 相机 USB 模式。");
            flushReport();
            return;
        }

        if (!um.hasPermission(dev)) {
            out("尚无 USB 权限，发起申请 —— 请在弹窗点「允许/确定」");
            Intent pi = new Intent(ACTION_PERM).setPackage(getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            um.requestPermission(dev, PendingIntent.getBroadcast(this, 0, pi, flags));
            permLatch.await(120, TimeUnit.SECONDS);
            if (!permGranted) {
                out("权限未授予，退出。");
                flushReport();
                return;
            }
        } else {
            out("已有 USB 权限（系统按 device_filter 自动授予）");
        }

        UsbDeviceConnection conn = um.openDevice(dev);
        if (conn == null) {
            out("openDevice 返回 null，无法建立连接。");
            flushReport();
            return;
        }

        ReadOnlyMtp mtp = ReadOnlyMtp.open(dev, conn);
        if (mtp == null) {
            out("MtpDevice.open() 失败 —— 相机可能不是 MTP 模式，或被别的 App 占用。");
            conn.close();
            flushReport();
            return;
        }
        out("MtpDevice.open() 成功");

        try {
            MtpDeviceInfo di = mtp.getDeviceInfo();
            if (di != null) {
                out("设备: " + di.getManufacturer() + " / " + di.getModel()
                        + " / SN=" + di.getSerialNumber() + " / ver=" + di.getVersion());
                int[] ops = di.getOperationsSupported();
                out("报告支持 " + (ops == null ? 0 : ops.length) + " 个操作");
            }
        } catch (Throwable t) {
            out("getDeviceInfo() 异常（相机多半在休眠）: " + t);
        }

        // 相机空闲休眠时 open() 会拖到 10 秒且 getStorageIds() 返回空。
        // 这不是模式问题，等相机醒来就好，所以这里重试而不是直接判死。
        int[] sids = null;
        for (int attempt = 1; attempt <= 8; attempt++) {
            sids = mtp.getStorageIds();
            if (sids != null && sids.length > 0) break;
            out("第 " + attempt + "/8 次 getStorageIds() 为空 —— 相机可能休眠了，"
                    + "请按一下相机按键或半按快门唤醒；3 秒后重试");
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
        }
        if (sids == null || sids.length == 0) {
            out("始终拿不到存储。请把 USB 线拔掉重插一次再运行本探针。");
            mtp.close(); conn.close(); flushReport();
            return;
        }
        out("拿到 " + sids.length + " 个存储");

        List<int[]> allHandles = new ArrayList<>();
        int[] arwProbe = null;   // FMT_ARW 过滤出来的句柄，用于和全量枚举做差集
        for (int sid : sids) {
            MtpStorageInfo si = mtp.getStorageInfo(sid);
            if (si != null) {
                out(String.format(Locale.US,
                        "存储 0x%08X  \"%s\"  卷=%s  容量=%.1f GB  可用=%.1f GB",
                        sid, si.getDescription(), si.getVolumeIdentifier(),
                        si.getMaxCapacity() / GB, si.getFreeSpace() / GB));
            }
            int[] hs = mtp.getObjectHandles(sid, 0, 0);
            int n = hs == null ? 0 : hs.length;
            out(String.format(Locale.US, "  对象总数 = %d", n));
            if (hs != null) for (int h : hs) allHandles.add(new int[]{sid, h});

            // 优化项：让相机侧按格式过滤，只回 ARW 句柄。
            // MTP 的 GetObjectHandles 支持 format / parent 两个过滤参数，
            // 用上它就不用把卡里每个对象都拉一遍 ObjectInfo。
            long tf = System.nanoTime();
            int[] arwOnly = mtp.getObjectHandles(sid, FMT_ARW, 0);
            double ft = (System.nanoTime() - tf) / 1e9;
            int arwOnlyN = arwOnly == null ? -1 : arwOnly.length;
            out(String.format(Locale.US,
                    "  [优化] getObjectHandles(format=0x%04X) → %d 个 ARW，耗时 %.2f 秒",
                    FMT_ARW, arwOnlyN, ft));

            // 校验这个 format 过滤到底靠不靠谱：抽样取 ObjectInfo 看真实格式分布
            if (arwOnly != null && arwOnlyN > 0) {
                java.util.Map<Integer, Integer> hist = new java.util.TreeMap<>();
                int step = Math.max(1, arwOnlyN / 40);   // 抽 40 个够了
                for (int k = 0; k < arwOnlyN; k += step) {
                    MtpObjectInfo oi2 = mtp.getObjectInfo(arwOnly[k]);
                    if (oi2 == null) { hist.merge(-1, 1, Integer::sum); continue; }
                    hist.merge(oi2.getFormat(), 1, Integer::sum);
                }
                StringBuilder hb = new StringBuilder();
                for (java.util.Map.Entry<Integer, Integer> en : hist.entrySet()) {
                    hb.append(String.format(Locale.US, "0x%04X×%d ", en.getKey(), en.getValue()));
                }
                out("  [校验] 抽样 " + (arwOnlyN / step) + " 个的格式分布: " + hb);
                if (arwProbe == null) arwProbe = arwOnly;
            }
        }

        // 逐个取 ObjectInfo，统计格式
        int folders = 0, arw = 0, jpeg = 0, other = 0;
        long arwBytes = 0;
        StringBuilder samples = new StringBuilder();
        // 收集所有 ARW，用于挑最大的几张做批量测速
        List<Object[]> arwList = new ArrayList<>(); // {handle, size, name}
        // PC 探针（issue #67）：另收几张 JPEG，验证 getPartialObject 对非 ARW 对象是否同样可用
        List<Object[]> jpegList = new ArrayList<>(); // {handle, size, name}

        int scanned = 0;
        long scanStart = System.nanoTime();
        for (int[] sh : allHandles) {
            MtpObjectInfo oi = mtp.getObjectInfo(sh[1]);
            if (oi == null) continue;
            scanned++;
            if (scanned % 300 == 0) {
                out(String.format(Locale.US, "  ...已扫描 %d/%d，用时 %.1f 秒",
                        scanned, allHandles.size(), (System.nanoTime() - scanStart) / 1e9));
            }
            int fmt = oi.getFormat();
            long size = oi.getCompressedSizeLong();
            String name = oi.getName() == null ? "" : oi.getName();
            if (fmt == FMT_FOLDER) {
                folders++;
            } else if (fmt == FMT_ARW || name.toUpperCase(Locale.US).endsWith(".ARW")) {
                arw++;
                arwBytes += size;
                arwList.add(new Object[]{sh[1], size, name});
            } else if (fmt == FMT_JPEG || name.toUpperCase(Locale.US).endsWith(".JPG")) {
                jpeg++;
                jpegList.add(new Object[]{sh[1], size, name});
            } else {
                other++;
            }
            if (samples.length() < SAMPLE_TEXT_LIMIT && fmt != FMT_FOLDER) {
                samples.append(String.format(Locale.US, "  h=0x%08X %-14s %6.2f MB  fmt=0x%04X %dx%d%n",
                        sh[1], name, size / MB, fmt,
                        oi.getImagePixWidth(), oi.getImagePixHeight()));
            }
        }
        out("已读取 " + scanned + " 个对象的 ObjectInfo");
        out(String.format(Locale.US, "分类: 文件夹=%d  ARW=%d (合计 %.1f GB)  JPEG=%d  其它=%d",
                folders, arw, arwBytes / GB, jpeg, other));

        // 差集：format 过滤看到的 vs 全量枚举看到的，两边是否同一个集合
        if (arwProbe != null) {
            java.util.Set<Integer> fullSet = new java.util.HashSet<>();
            for (int[] sh : allHandles) fullSet.add(sh[1]);
            int missing = 0, extra = 0;
            StringBuilder ex = new StringBuilder();
            for (int h : arwProbe) {
                if (!fullSet.contains(h)) {
                    missing++;
                    if (ex.length() < DIFF_SAMPLE_LIMIT) {
                        MtpObjectInfo oi3 = mtp.getObjectInfo(h);
                        ex.append(String.format(Locale.US, "h=0x%08X %s; ",
                                h, oi3 == null ? "?" : oi3.getName()));
                    }
                }
            }
            java.util.Set<Integer> probeSet = new java.util.HashSet<>();
            for (int h : arwProbe) probeSet.add(h);
            for (int[] sh : allHandles) if (!probeSet.contains(sh[1])) extra++;
            out(String.format(Locale.US,
                    "[差集] 全量枚举 %d 个 / format过滤 %d 个：过滤里有 %d 个不在全量里；全量里有 %d 个不在过滤里",
                    allHandles.size(), arwProbe.length, missing, extra));
            if (missing > 0) out("[差集] 仅过滤可见的样例: " + ex);
        }
        if (samples.length() > 0) out("样例：\n" + samples);

        if (arwList.isEmpty()) {
            out("卡里没有 ARW。若刚格式化过卡或卡里只有 JPG，这是正常的。");
            mtp.close(); conn.close(); flushReport();
            return;
        }

        // 按大小降序，取最大的 3 张做批量测速
        arwList.sort((a, b) -> Long.compare((Long) b[1], (Long) a[1]));
        int batch = Math.min(3, arwList.size());
        File dir = getFilesDir();
        long totalBytes = 0;
        long batchStart = System.nanoTime();
        // PC 探针要对同一张已整文件下载的文件做分块对照，先记住第一张的句柄与落盘路径
        File firstFull = null;
        int firstHandle = -1;
        long firstSize = 0;
        String firstName = null;

        out("---- 批量下载，测稳定吞吐（共 " + batch + " 张）----");
        for (int i = 0; i < batch; i++) {
            Object[] e = arwList.get(i);
            int handle = (Integer) e[0];
            long size = (Long) e[1];
            String name = (String) e[2];

            File dst = new File(dir, "probe_" + name);
            if (dst.exists()) dst.delete();

            long t0 = System.nanoTime();
            boolean ok = mtp.importFile(handle, dst.getAbsolutePath());
            double sec = (System.nanoTime() - t0) / 1e9;

            if (!ok || !dst.exists()) {
                out(String.format(Locale.US, "  [%d/%d] %s 下载失败", i + 1, batch, name));
                continue;
            }
            long got = dst.length();
            totalBytes += got;
            out(String.format(Locale.US,
                    "  [%d/%d] h=0x%08X %-14s %6.2f MB / %.2f 秒 = %5.2f MB/s  大小%s",
                    i + 1, batch, handle, name, got / MB, sec,
                    (got / MB) / (sec <= 0 ? 1 : sec),
                    size == got ? "✅一致" : "❌期望" + size));

            if (i == 0) {
                firstFull = dst;
                firstHandle = handle;
                firstSize = size;
                firstName = name;
                // 只对第一张做完整性校验
                out("  MD5 = " + md5(dst));
                byte[] head = new byte[16];
                try (java.io.FileInputStream in = new java.io.FileInputStream(dst)) {
                    int r = in.read(head);
                    StringBuilder hx = new StringBuilder();
                    for (int j = 0; j < Math.max(r, 0); j++) hx.append(String.format("%02X ", head[j]));
                    out("  文件头: " + hx
                            + ((r >= 4 && head[0] == 0x49 && head[1] == 0x49
                            && head[2] == 0x2A && head[3] == 0x00) ? "✅ 合法 ARW(TIFF)" : "❌ 头部不对"));
                }
                out("  落盘: " + dst.getAbsolutePath());
            }
        }

        double batchSec = (System.nanoTime() - batchStart) / 1e9;
        out(String.format(Locale.US,
                "批量合计: %.2f MB / %.2f 秒 = %.2f MB/s（含文件系统写入）",
                totalBytes / MB, batchSec,
                (totalBytes / MB) / (batchSec <= 0 ? 1 : batchSec)));

        // PC 探针：验证 getPartialObject 的 offset / length 语义（issue #67，#50 PC 分节）。
        // 只读：只对已枚举到的对象分块读取，不发任何写 opcode。
        if (firstFull != null) {
            List<Object[]> others = new ArrayList<>();
            // 对照组 1：本轮刚 importFile 过的另外两张（验证「是否只有第一张能用」）
            for (int i = 1; i < batch; i++) others.add(arwList.get(i));
            // 对照组 2：本轮没碰过的对象（验证普通对象支持面）
            int added = 0;
            for (int i = batch; i < arwList.size() && added < 2; i++) {
                others.add(arwList.get(i));
                added++;
            }
            if (!jpegList.isEmpty()) others.add(jpegList.get(0));
            new PartialProbe(mtp, this::out).run(firstHandle, firstSize, firstName, firstFull, others);
        }

        mtp.close();
        conn.close();
        out("=== 探针结束 ===");
        flushReport();
    }

    void flushReport() {
        try {
            File f = new File(getFilesDir(), "report.txt");
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(sb.toString().getBytes("UTF-8"));
            }
            Log.i(TAG, "报告已写入 " + f.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "写报告失败", e);
        }
    }

    static String md5(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte x : md.digest()) s.append(String.format("%02x", x));
        return s.toString();
    }
}
