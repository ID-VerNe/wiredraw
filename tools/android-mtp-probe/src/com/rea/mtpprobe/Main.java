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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 极简 MTP 宿主探针：验证「安卓手机经 USB 读 a7C 卡内已有 RAW」这条路。
 *
 * 只做只读操作：枚举存储、列对象、下载一张最大文件。
 * 不发删除、不改相机任何设置。
 *
 * 输出：logcat tag=MTPPROBE，同时落一份到 getFilesDir()/report.txt
 * （adb shell run-as com.rea.mtpprobe cat files/report.txt 取回）
 *
 * 结构：{@code probe()} 只做阶段编排，各阶段是下面的私有方法；扫描结果用 {@link ScanResult}
 * 承载、批量下载结果用 {@link Downloaded} 承载，避免一个方法背多组互相独立的局部变量。
 */
public class Main extends Activity {

    static final String TAG = "MTPPROBE";
    static final String ACTION_PERM = "com.rea.mtpprobe.USB_PERM";
    static final String REPORT_NAME = "report.txt";
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
    /** 批量测速的张数上限。 */
    static final int BATCH_MAX = 3;
    /** 普通对象支持面额外抽样的个数上限。 */
    static final int EXTRA_SAMPLE_MAX = 2;

    TextView tv;
    /** 报告行。worker 线程与广播接收器（主线程）都会 append，读写都得持锁。 */
    final List<String> lines = new ArrayList<>();
    /** UI 刷新是否已排队：只用来合并同一批输出，不做节流上限。 */
    final AtomicBoolean uiPending = new AtomicBoolean(false);
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

    // ---------------- 输出 ----------------

    void out(String s) {
        Log.i(TAG, s);
        synchronized (lines) {
            lines.add(s);
        }
        // 只排队一次刷新：同一批输出合并成一次 setText，避免每条都 toString() 一遍（O(n²)）。
        if (uiPending.compareAndSet(false, true)) {
            runOnUiThread(this::refreshUi);
        }
    }

    /**
     * 先清排队标记、再取快照：清标记之后到达的输出会再排一次刷新，
     * 而清标记之前到达的已经在快照里，所以不会漏行。
     */
    private void refreshUi() {
        uiPending.set(false);
        tv.setText(snapshot());
    }

    /** 报告全文快照；持锁拷贝，避免遍历时被 worker 线程并发修改。 */
    String snapshot() {
        synchronized (lines) {
            StringBuilder sb = new StringBuilder(lines.size() * 64);
            for (String s : lines) sb.append(s).append('\n');
            return sb.toString();
        }
    }

    void runProbe() {
        try {
            probe();
        } catch (Throwable t) {
            out("!! 异常: " + t);
            Log.e(TAG, "probe failed", t);
        } finally {
            // 报告只在这里落一次盘：提前退出的分支不再各自 flush，避免同一份内容写多遍。
            flushReport();
        }
    }

    void flushReport() {
        try {
            File f = new File(getFilesDir(), REPORT_NAME);
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(snapshot().getBytes("UTF-8"));
            }
            Log.i(TAG, "报告已写入 " + f.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "写报告失败", e);
        }
    }

    // ---------------- 阶段编排 ----------------

    void probe() throws Exception {
        out("=== MTP 探针启动 " + Build.MANUFACTURER + " " + Build.MODEL
                + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") ===");

        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDevice dev = findSonyDevice(um);
        if (dev == null) {
            out("没有找到索尼设备（VID 0x054C）。检查 OTG 线 / 相机 USB 模式。");
            return;
        }
        if (!awaitPermission(um, dev)) return;

        UsbDeviceConnection conn = um.openDevice(dev);
        if (conn == null) {
            out("openDevice 返回 null，无法建立连接。");
            return;
        }

        ReadOnlyMtp mtp = ReadOnlyMtp.open(dev, conn);
        if (mtp == null) {
            out("MtpDevice.open() 失败 —— 相机可能不是 MTP 模式，或被别的 App 占用。");
            conn.close();
            return;
        }
        out("MtpDevice.open() 成功");

        try {
            printDeviceInfo(mtp);
            int[] sids = readStorageIds(mtp);
            if (sids == null) {
                out("始终拿不到存储。请把 USB 线拔掉重插一次再运行本探针。");
                return;
            }
            ScanResult scan = scanObjects(mtp, sids);
            reportDiff(mtp, scan);
            if (scan.samples.length() > 0) out("样例：\n" + scan.samples);
            if (scan.arwList.isEmpty()) {
                out("卡里没有 ARW。若刚格式化过卡或卡里只有 JPG，这是正常的。");
                return;
            }
            Downloaded dl = downloadBatch(mtp, scan.arwList);
            if (dl.firstFull != null) runPartialProbe(mtp, scan, dl);
            out("=== 探针结束 ===");
        } finally {
            mtp.close();
            conn.close();
        }
    }

    private UsbDevice findSonyDevice(UsbManager um) {
        UsbDevice found = null;
        for (UsbDevice d : um.getDeviceList().values()) {
            out(String.format(Locale.US, "发现 USB 设备: %s VID=0x%04X PID=0x%04X class=%d/%d/%d",
                    d.getDeviceName(), d.getVendorId(), d.getProductId(),
                    d.getDeviceClass(), d.getDeviceSubclass(), d.getDeviceProtocol()));
            if (d.getVendorId() == SONY_VID) found = d;
        }
        return found;
    }

    private boolean awaitPermission(UsbManager um, UsbDevice dev) throws InterruptedException {
        if (um.hasPermission(dev)) {
            out("已有 USB 权限（系统按 device_filter 自动授予）");
            return true;
        }
        out("尚无 USB 权限，发起申请 —— 请在弹窗点「允许/确定」");
        Intent pi = new Intent(ACTION_PERM).setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        um.requestPermission(dev, PendingIntent.getBroadcast(this, 0, pi, flags));
        permLatch.await(120, TimeUnit.SECONDS);
        if (!permGranted) {
            out("权限未授予，退出。");
            return false;
        }
        return true;
    }

    private void printDeviceInfo(ReadOnlyMtp mtp) {
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
    }

    /** 相机空闲休眠时 open() 会拖到 10 秒且 getStorageIds() 返回空，等相机醒来就好，所以重试。 */
    private int[] readStorageIds(ReadOnlyMtp mtp) throws InterruptedException {
        int[] sids = null;
        for (int attempt = 1; attempt <= 8; attempt++) {
            sids = mtp.getStorageIds();
            if (sids != null && sids.length > 0) break;
            out("第 " + attempt + "/8 次 getStorageIds() 为空 —— 相机可能休眠了，"
                    + "请按一下相机按键或半按快门唤醒；3 秒后重试");
            Thread.sleep(3000);
        }
        if (sids == null || sids.length == 0) return null;
        out("拿到 " + sids.length + " 个存储");
        return sids;
    }

    /**
     * 枚举存储 → 全量句柄 → format 过滤对照 → 逐个 ObjectInfo 分类。
     * 结果全部装进 {@link ScanResult}，后续阶段只读它，不再碰设备。
     */
    private ScanResult scanObjects(ReadOnlyMtp mtp, int[] sids) {
        ScanResult r = new ScanResult();
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
            if (hs != null) for (int h : hs) r.allHandles.add(new int[]{sid, h});

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
                Map<Integer, Integer> hist = new TreeMap<>();
                int step = Math.max(1, arwOnlyN / 40);   // 抽 40 个够了
                for (int k = 0; k < arwOnlyN; k += step) {
                    MtpObjectInfo oi2 = mtp.getObjectInfo(arwOnly[k]);
                    if (oi2 == null) { hist.merge(-1, 1, Integer::sum); continue; }
                    hist.merge(oi2.getFormat(), 1, Integer::sum);
                }
                StringBuilder hb = new StringBuilder();
                for (Map.Entry<Integer, Integer> en : hist.entrySet()) {
                    hb.append(String.format(Locale.US, "0x%04X×%d ", en.getKey(), en.getValue()));
                }
                out("  [校验] 抽样 " + (arwOnlyN / step) + " 个的格式分布: " + hb);
                if (r.arwProbe == null) r.arwProbe = arwOnly;
            }
        }

        long scanStart = System.nanoTime();
        for (int[] sh : r.allHandles) {
            MtpObjectInfo oi = mtp.getObjectInfo(sh[1]);
            if (oi == null) continue;
            r.scanned++;
            if (r.scanned % 300 == 0) {
                out(String.format(Locale.US, "  ...已扫描 %d/%d，用时 %.1f 秒",
                        r.scanned, r.allHandles.size(), (System.nanoTime() - scanStart) / 1e9));
            }
            int fmt = oi.getFormat();
            long size = oi.getCompressedSizeLong();
            String name = oi.getName() == null ? "" : oi.getName();
            if (fmt == FMT_FOLDER) {
                r.folders++;
            } else if (fmt == FMT_ARW || name.toUpperCase(Locale.US).endsWith(".ARW")) {
                r.arw++;
                r.arwBytes += size;
                r.arwList.add(new ObjectRef(sh[1], size, name));
            } else if (fmt == FMT_JPEG || name.toUpperCase(Locale.US).endsWith(".JPG")) {
                r.jpeg++;
                r.jpegList.add(new ObjectRef(sh[1], size, name));
            } else {
                r.other++;
            }
            if (r.samples.length() < SAMPLE_TEXT_LIMIT && fmt != FMT_FOLDER) {
                r.samples.append(String.format(Locale.US, "  h=0x%08X %-14s %6.2f MB  fmt=0x%04X %dx%d%n",
                        sh[1], name, size / MB, fmt,
                        oi.getImagePixWidth(), oi.getImagePixHeight()));
            }
        }
        out("已读取 " + r.scanned + " 个对象的 ObjectInfo");
        out(String.format(Locale.US, "分类: 文件夹=%d  ARW=%d (合计 %.1f GB)  JPEG=%d  其它=%d",
                r.folders, r.arw, r.arwBytes / GB, r.jpeg, r.other));
        return r;
    }

    /** 差集：format 过滤看到的 vs 全量枚举看到的，两边是否同一个集合。 */
    private void reportDiff(ReadOnlyMtp mtp, ScanResult r) {
        if (r.arwProbe == null) return;
        Set<Integer> fullSet = new HashSet<>();
        for (int[] sh : r.allHandles) fullSet.add(sh[1]);
        int missing = 0, extra = 0;
        StringBuilder ex = new StringBuilder();
        for (int h : r.arwProbe) {
            if (!fullSet.contains(h)) {
                missing++;
                if (ex.length() < DIFF_SAMPLE_LIMIT) {
                    MtpObjectInfo oi3 = mtp.getObjectInfo(h);
                    ex.append(String.format(Locale.US, "h=0x%08X %s; ",
                            h, oi3 == null ? "?" : oi3.getName()));
                }
            }
        }
        Set<Integer> probeSet = new HashSet<>();
        for (int h : r.arwProbe) probeSet.add(h);
        for (int[] sh : r.allHandles) if (!probeSet.contains(sh[1])) extra++;
        out(String.format(Locale.US,
                "[差集] 全量枚举 %d 个 / format过滤 %d 个：过滤里有 %d 个不在全量里；全量里有 %d 个不在过滤里",
                r.allHandles.size(), r.arwProbe.length, missing, extra));
        if (missing > 0) out("[差集] 仅过滤可见的样例: " + ex);
    }

    /**
     * 按大小降序取最大的几张做批量测速。
     * 会对 {@code arwList} 原地排序；返回值带着第一张的句柄/路径，PC 探针要用它做基准。
     */
    private Downloaded downloadBatch(ReadOnlyMtp mtp, List<ObjectRef> arwList) throws Exception {
        Downloaded d = new Downloaded();
        arwList.sort((a, b) -> Long.compare(b.size, a.size));
        d.batch = Math.min(BATCH_MAX, arwList.size());
        File dir = getFilesDir();

        out("---- 批量下载，测稳定吞吐（共 " + d.batch + " 张）----");
        long batchStart = System.nanoTime();
        for (int i = 0; i < d.batch; i++) {
            ObjectRef e = arwList.get(i);
            File dst = new File(dir, "probe_" + e.name);
            if (dst.exists()) dst.delete();

            long t0 = System.nanoTime();
            boolean ok = mtp.importFile(e.handle, dst.getAbsolutePath());
            double sec = (System.nanoTime() - t0) / 1e9;

            if (!ok || !dst.exists()) {
                out(String.format(Locale.US, "  [%d/%d] %s 下载失败", i + 1, d.batch, e.name));
                continue;
            }
            long got = dst.length();
            d.totalBytes += got;
            out(String.format(Locale.US,
                    "  [%d/%d] h=0x%08X %-14s %6.2f MB / %.2f 秒 = %5.2f MB/s  大小%s",
                    i + 1, d.batch, e.handle, e.name, got / MB, sec,
                    (got / MB) / (sec <= 0 ? 1 : sec),
                    e.size == got ? "✅一致" : "❌期望" + e.size));

            if (i == 0) {
                d.firstFull = dst;
                d.firstHandle = e.handle;
                d.firstSize = e.size;
                d.firstName = e.name;
                // 只对第一张做完整性校验
                out("  MD5 = " + ProbeFiles.md5(dst));
                out("  " + ProbeFiles.headLine(dst));
                out("  落盘: " + dst.getAbsolutePath());
            }
        }
        d.batchSec = (System.nanoTime() - batchStart) / 1e9;
        out(String.format(Locale.US,
                "批量合计: %.2f MB / %.2f 秒 = %.2f MB/s（含文件系统写入）",
                d.totalBytes / MB, d.batchSec,
                (d.totalBytes / MB) / (d.batchSec <= 0 ? 1 : d.batchSec)));
        return d;
    }

    /**
     * PC 探针：验证 getPartialObject 的 offset / length 语义（issue #67，#50 PC 分节）。
     * 只读：只对已枚举到的对象分块读取，不发任何写 opcode。
     *
     * 对照组 1：本轮刚 importFile 过的另外两张（验证「是否只有第一张能用」）；
     * 对照组 2：本轮没碰过的对象（验证普通对象支持面）。
     */
    private void runPartialProbe(ReadOnlyMtp mtp, ScanResult scan, Downloaded d) {
        List<ObjectRef> others = new ArrayList<>();
        for (int i = 1; i < d.batch; i++) others.add(scan.arwList.get(i));
        int added = 0;
        for (int i = d.batch; i < scan.arwList.size() && added < EXTRA_SAMPLE_MAX; i++) {
            others.add(scan.arwList.get(i));
            added++;
        }
        if (!scan.jpegList.isEmpty()) others.add(scan.jpegList.get(0));
        new PartialProbe(mtp, this::out).run(d.firstHandle, d.firstSize, d.firstName, d.firstFull, others);
    }

    // ---------------- 阶段结果 ----------------

    /** 扫描阶段的全部产物；后续阶段只读这个对象。 */
    static final class ScanResult {
        /** 全量枚举到的 {storageId, handle}。 */
        final List<int[]> allHandles = new ArrayList<>();
        final List<ObjectRef> arwList = new ArrayList<>();
        final List<ObjectRef> jpegList = new ArrayList<>();
        final StringBuilder samples = new StringBuilder();
        /** format 过滤报出的 ARW 句柄；null 表示本轮没有可对照的过滤结果。 */
        int[] arwProbe;
        int scanned;
        int folders;
        int arw;
        int jpeg;
        int other;
        long arwBytes;
    }

    /** 批量下载阶段的产物；{@link #firstFull} 为 null 表示一张都没落盘。 */
    static final class Downloaded {
        File firstFull;
        int firstHandle = -1;
        long firstSize;
        String firstName;
        int batch;
        long totalBytes;
        double batchSec;
    }
}
