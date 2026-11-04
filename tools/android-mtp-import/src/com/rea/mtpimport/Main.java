package com.rea.mtpimport;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 导片工具界面（骨架）。
 *
 * 三块：顶部日志 / 中间目录树（勾选）/ 底部进度与操作。
 * 传输那一环当前是 {@link ResumableTransfer.NotImplemented}，所以点「开始导入」会
 * 明确停在「未实现」，进度条不会动——这是本轮的计划行为，README 里也写了。
 */
public final class Main extends Activity {

    private static final String TAG = "MTPIMPORT";
    private static final String ACTION_PERM = "com.rea.mtpimport.USB_PERM";
    private static final int MAX_ROWS = 800;

    private final StringBuilder logBuf = new StringBuilder();
    private TextView logView;
    private TextView summaryView;
    private TextView progressView;
    private ProgressBar progressBar;
    private LinearLayout rowBox;
    private Button scanBtn;
    private Button startBtn;
    private Button pauseBtn;

    private final CountDownLatch permLatch = new CountDownLatch(1);
    private volatile boolean permGranted;
    private volatile boolean permAnswered;

    private MtpSession session;
    private Catalog catalog;
    private Selection selection;
    private ImportService service;
    private TransferProgress lastProgress;

    private final Log log = this::logLine;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ACTION_PERM.equals(a)) {
                permGranted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                permAnswered = true;
                log.line("USB 权限回调：granted=" + permGranted);
                permLatch.countDown();
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                log.line("收到 USB_DEVICE_ATTACHED");
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_PERM);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }

        service = new ImportService(log, new ResumableTransfer.NotImplemented(log), getFilesDir());
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        if (service != null) service.cancel();
        if (session != null) session.close();
        super.onDestroy();
    }

    // ---------------- 界面 ----------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        logView = new TextView(this);
        logView.setTextSize(10);
        logView.setMaxLines(12);
        root.addView(logView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        scanBtn = new Button(this);
        scanBtn.setText("连接并扫描");
        scanBtn.setOnClickListener(v -> doScan());
        Button allBtn = new Button(this);
        allBtn.setText("全选");
        allBtn.setOnClickListener(v -> {
            if (selection != null) {
                selection.selectAll();
                renderRows();
                refreshSummary();
            }
        });
        Button noneBtn = new Button(this);
        noneBtn.setText("清空");
        noneBtn.setOnClickListener(v -> {
            if (selection != null) {
                selection.clear();
                renderRows();
                refreshSummary();
            }
        });
        bar.addView(scanBtn);
        bar.addView(allBtn);
        bar.addView(noneBtn);
        root.addView(bar);

        summaryView = new TextView(this);
        summaryView.setTextSize(12);
        summaryView.setText("未连接");
        root.addView(summaryView);

        ScrollView sv = new ScrollView(this);
        rowBox = new LinearLayout(this);
        rowBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(rowBox);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(sv, lp);

        progressView = new TextView(this);
        progressView.setTextSize(11);
        progressView.setText("等待开始");
        root.addView(progressView);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setProgress(0);
        root.addView(progressBar);

        LinearLayout ops = new LinearLayout(this);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        startBtn = new Button(this);
        startBtn.setText("开始导入");
        startBtn.setOnClickListener(v -> doStart());
        pauseBtn = new Button(this);
        pauseBtn.setText("暂停");
        pauseBtn.setOnClickListener(v -> {
            if (service == null || !service.isRunning()) return;
            if ("暂停".contentEquals(pauseBtn.getText())) {
                service.pause();
                pauseBtn.setText("继续");
            } else {
                service.resume();
                pauseBtn.setText("暂停");
            }
        });
        ops.addView(startBtn);
        ops.addView(pauseBtn);
        root.addView(ops);

        return root;
    }

    void logLine(String s) {
        android.util.Log.i(TAG, s);   // 全限定：同包的 Log 已被本工具的日志接口占用
        runOnUiThread(() -> {
            int keep = Math.max(0, logBuf.length() - 4000);
            if (keep > 0) logBuf.delete(0, keep);
            logBuf.append(s).append('\n');
            if (logView != null) logView.setText(logBuf.toString());
        });
    }

    private void refreshSummary() {
        if (catalog == null) {
            summaryView.setText("未连接");
            return;
        }
        int files = selection == null ? 0 : selection.fileHandles().size();
        long bytes = 0;
        if (selection != null) {
            for (int h : selection.fileHandles()) {
                Catalog.Entry e = catalog.get(h);
                if (e != null) bytes += e.size;
            }
        }
        summaryView.setText(String.format(Locale.US,
                "卡内 %d 个文件夹 / %d 个文件（%.2f GB）· 已勾选 %d 个（%.2f GB）",
                catalog.folderCount(), catalog.fileCount(),
                catalog.fileBytes() / 1073741824.0, files, bytes / 1073741824.0));
    }

    /** 把目录树摊平成一行行 CheckBox；超过 MAX_ROWS 就截断，避免上千个 View 卡死界面。 */
    private void renderRows() {
        if (catalog == null) return;
        rowBox.removeAllViews();
        List<Catalog.Entry> flat = new ArrayList<>();
        flatten(catalog.roots(), 0, flat);
        int shown = Math.min(flat.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            Catalog.Entry e = flat.get(i);
            rowBox.addView(makeRow(e));
        }
        if (flat.size() > MAX_ROWS) {
            TextView more = new TextView(this);
            more.setText("…… 还有 " + (flat.size() - MAX_ROWS) + " 项未显示（可先勾上层目录）");
            more.setTextSize(10);
            rowBox.addView(more);
        }
    }

    private View makeRow(Catalog.Entry e) {
        CheckBox cb = new CheckBox(this);
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < depthOf(e); i++) label.append("    ");
        label.append(e.name);
        if (e.isFolder()) {
            label.append('/');
        } else {
            label.append(String.format(Locale.US, "  %.1f MB", e.size / 1048576.0));
        }
        cb.setText(label.toString());
        cb.setTextSize(11);
        cb.setChecked(selection != null && selection.isChecked(e.handle));
        cb.setOnClickListener(v -> {
            if (selection == null) return;
            selection.setChecked(e.handle, cb.isChecked());
            renderRows();
            refreshSummary();
        });
        return cb;
    }

    private int depthOf(Catalog.Entry e) {
        String p = e.relPath == null ? "" : e.relPath;
        int d = 0;
        for (int i = 0; i < p.length(); i++) if (p.charAt(i) == '/') d++;
        return d;
    }

    private void flatten(List<Catalog.Entry> list, int depth, List<Catalog.Entry> out) {
        for (Catalog.Entry e : list) {
            if (out.size() >= MAX_ROWS + 200) return;
            out.add(e);
            if (e.isFolder()) flatten(catalog.childrenOf(e.handle), depth + 1, out);
        }
    }

    // ---------------- 动作 ----------------

    private void doScan() {
        if (service != null && service.isRunning()) {
            log.line("传输进行中，先取消再重扫");
            return;
        }
        scanBtn.setEnabled(false);
        new Thread(this::scanWorker, "scan-worker").start();
    }

    private void scanWorker() {
        try {
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            UsbDevice dev = MtpSession.findSony(um, log);
            if (dev == null) {
                log.line("没找到索尼设备（VID 0x054C）：检查 OTG 线、相机 USB 模式是否 MTP");
                return;
            }
            if (!um.hasPermission(dev) && !awaitPermission(um, dev)) {
                log.line("USB 权限未授予，退出");
                return;
            }
            if (session != null) session.close();
            session = MtpSession.open(um, dev, log);
            if (session == null) return;

            int[] sids = session.storageIds(log);
            if (sids.length == 0) {
                log.line("拿不到存储：请拔插 USB 线后重试（相机会话结束后约 1 分钟会挂起 USB）");
                return;
            }
            for (int sid : sids) log.line("存储：" + session.describeStorage(sid));

            Catalog scanned = CatalogScanner.scan(session.device(), sids, log,
                    (done, total) -> runOnUiThread(() ->
                            summaryView.setText("扫描中 " + done + "/" + total)));
            catalog = scanned;
            selection = new Selection(catalog);
            runOnUiThread(() -> {
                renderRows();
                refreshSummary();
            });
        } catch (Throwable t) {
            log.line("扫描异常：" + t);
        } finally {
            runOnUiThread(() -> scanBtn.setEnabled(true));
        }
    }

    private boolean awaitPermission(UsbManager um, UsbDevice dev) throws InterruptedException {
        log.line("尚无 USB 权限，发起申请");
        Intent pi = new Intent(ACTION_PERM).setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        um.requestPermission(dev, PendingIntent.getBroadcast(this, 0, pi, flags));
        permLatch.await(120, TimeUnit.SECONDS);
        return permAnswered && permGranted;
    }

    private void doStart() {
        if (catalog == null || selection == null) {
            log.line("先扫描");
            return;
        }
        ImportPlan plan = ImportPlan.of(catalog, selection);
        if (plan.isEmpty()) {
            log.line("没有勾选任何文件");
            return;
        }
        File out = new File(getFilesDir(), "imported");

        // 目标目录按 relPath 建子目录这一步留给真实现（要处理重名/覆盖策略），
        // 本轮先把目录建出来，让「计划 → 落盘位置」的链路是通的。
        if (!out.exists() && !out.mkdirs()) log.line("建目标目录失败：" + out);

        progressBar.setProgress(0);
        progressView.setText("准备中…");
        service.start(plan, new ImportListener() {
            @Override
            public void onPlanReady(ImportPlan p) {
                log.line("计划：" + p.count() + " 个文件 / " + p.totalBytes() + " 字节");
            }

            @Override
            public void onProgress(TransferProgress progress, ImportPlan.Item current) {
                lastProgress = progress;
                long now = System.nanoTime();
                runOnUiThread(() -> {
                    progressBar.setProgress((int) Math.round(progress.fraction() * 1000));
                    progressView.setText(progress.summary(now) + " · 当前 " + current.relPath);
                });
            }

            @Override
            public void onFileFinished(ImportPlan.Item item, ResumableTransfer.Kind kind,
                                       String note) {
                log.line("[" + kind + "] " + item.relPath + (note.isEmpty() ? "" : " · " + note));
            }

            @Override
            public void onFinished(boolean cancelled, String summary) {
                runOnUiThread(() -> {
                    progressView.setText(summary);
                    pauseBtn.setText("暂停");
                    if (lastProgress != null) {
                        progressBar.setProgress((int) Math.round(lastProgress.fraction() * 1000));
                    }
                });
            }

            @Override
            public void onError(String message, Throwable t) {
                log.line("错误：" + message + (t == null ? "" : " · " + t));
            }
        });
    }
}
