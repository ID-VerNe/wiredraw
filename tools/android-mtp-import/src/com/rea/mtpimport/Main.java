package com.rea.mtpimport;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Insets;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;
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

    /**
     * 本次权限申请的序号（#11）。系统回调是广播，上一次申请迟到的回调
     * 可能落在新一次申请等待期间；只有序号匹配的回调才算数。
     */
    private static final String EXTRA_PERM_SEQ = "com.rea.mtpimport.PERM_SEQ";

    private static final int MAX_ROWS = 800;

    /**
     * `COLD` 的单行状态文案。§4.5 的 `T-COLD` 是「标题 + 正文」两段式，
     * 首屏还没重做（§0.11 第 7 行），这里先给一句不撒谎的摘要。
     */
    private static final String COPY_COLD = "未检测到相机 · 插上数据线会自动检测";

    private final StringBuilder logBuf = new StringBuilder();
    private TextView logView;
    private TextView summaryView;
    private TextView progressView;
    private ProgressBar progressBar;
    private LinearLayout rowBox;
    private Button scanBtn;
    private Button startBtn;
    private Button pauseBtn;

    /** USB 权限申请的等待门（#11）：每次申请重建 latch、只认本次序号的回调。 */
    private final PermGate permGate = new PermGate();

    /** 插线事件的准入判定（#17）：同一次插拔可能从 intent 与广播投递两次，重复事件不得开第二个会话。 */
    private final AttachGate attachGate = new AttachGate();

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
                boolean granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                int seq = i.getIntExtra(EXTRA_PERM_SEQ, 0);
                // 序号对不上或已经回答过的回调属于上一次申请，不得释放本次等待（#11）
                boolean accepted = permGate.onResult(seq, granted);
                log.line("USB 权限回调：granted=" + granted + (accepted ? "" : " · 旧请求，已忽略"));
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                // 从前只写日志、不扫描（#17 的根因之一）：插线后应用看着像没反应。
                handleAttach("收到 USB_DEVICE_ATTACHED 广播");
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

        // 冷启动两条入口都要接（#17）：系统按默认处理者用 attach intent 拉起，或用户手动点图标。
        // 相机已连着就直接进扫描；没连就显示 COLD 文案，之后插线由 onNewIntent / 广播再接一次。
        autoScanOnLaunch();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 前台插线：系统按默认处理者把 attach intent 直接投给本 Activity。
        // 覆写之前这里走基类的空实现，intent 被直接丢掉——这就是「前台插线不自动扫描」的根因。
        handleAttach("系统插线拉起");
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        if (service != null) service.cancel();
        if (session != null) session.close();
        // 销毁后不会再收到回调（receiver 已注销），提前释放还在等权限的扫描线程，别让它空等 120 秒
        permGate.cancel();
        super.onDestroy();
    }

    // ---------------- 界面 ----------------

    private View buildUi() {
        // targetSdk 35 在 Android 15 上强制 edge-to-edge（窗口属性里能看到 EDGE_TO_EDGE_ENFORCED），
        // 内容默认从 [0,0] 起排；这里显式关掉装饰拟合，并把 systemBars 让位加到根容器内边距上。
        getWindow().setDecorFitsSystemWindows(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int padH = dp(16);
        final int padV = dp(8);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(padH + bars.left, padV + bars.top, padH + bars.right, padV + bars.bottom);
            return insets;
        });

        logView = new TextView(this);
        logView.setTextSize(10);
        logView.setMaxLines(12);
        root.addView(logView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        scanBtn = new Button(this);
        // 插线已能自动检测（#17），手动入口按 §4.5 恢复为「重新检测」，不再是「连接并扫描」。
        scanBtn.setText("重新检测");
        scanBtn.setOnClickListener(v -> handleAttach("点「重新检测」"));
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
        summaryView.setText(COPY_COLD);
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

    /** dp → px：本工程 UI 全代码构建，尺寸一律走 dp，避免 560 dpi 之类的密度差异写死像素。 */
    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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
            summaryView.setText(COPY_COLD);
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

    /** 把目录树摊平成一行行 CheckBox；只渲染前 MAX_ROWS 行，避免上千个 View 卡死界面。 */
    private void renderRows() {
        if (catalog == null) return;
        rowBox.removeAllViews();
        Catalog.Flat flat = catalog.flatten(MAX_ROWS);
        for (Catalog.Entry e : flat.rows) {
            rowBox.addView(makeRow(e));
        }
        // 未显示数必须按全树总数算，不能用截断集合的差值（#15：曾恒显示 200，真实缺口 726）
        int remaining = flat.remaining();
        if (remaining > 0) {
            TextView more = new TextView(this);
            more.setText("…… 还有 " + remaining + " 项未显示（可先勾上层目录）");
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

    // ---------------- 动作 ----------------

    /** 冷启动检测：相机已连着就直接进扫描，否则状态区给出可读的 COLD 文案，不静默。 */
    private void autoScanOnLaunch() {
        UsbDevice dev = findCamera();
        if (dev == null) {
            summaryView.setText(COPY_COLD);
            return;
        }
        requestScan("冷启动检测", dev);
    }

    /** 插线事件入口：广播、attach intent 与手动按钮都从这里进。 */
    private void handleAttach(String trigger) {
        requestScan(trigger, findCamera());
    }

    private UsbDevice findCamera() {
        return MtpSession.findSony((UsbManager) getSystemService(Context.USB_SERVICE), log);
    }

    /**
     * 所有扫描入口的唯一汇合点：按钮、插线 intent（冷启动 / 前台）、插线广播、冷启动检测。
     *
     * 互斥与去重交给 {@link AttachGate}：同一次插拔可能从 intent 与广播投递两次，
     * 重复事件不得开第二个 MTP 会话（相机会话同一时刻只能有一个）。
     */
    private void requestScan(String trigger, UsbDevice dev) {
        AttachGate.Admit admit = attachGate.admit(dev != null, service != null && service.isRunning());
        switch (admit) {
            case GO:
                log.line(trigger + " → 开始扫描");
                summaryView.setText("正在扫描…");
                scanBtn.setEnabled(false);
                new Thread(() -> scanWorker(dev), "scan-worker").start();
                break;
            case NOTHING_NO_DEVICE:
                log.line(trigger + " → 没找到索尼设备（VID 0x054C）：检查 OTG 线、相机 USB 模式是否 MTP");
                summaryView.setText(COPY_COLD);
                break;
            case NOTHING_TRANSFERRING:
                log.line(trigger + " → 传输进行中，先取消再重扫");
                break;
            case NOTHING_ALREADY_SCANNING:
                log.line(trigger + " → 扫描已在进行中，忽略本次重复事件");
                break;
        }
    }

    private void scanWorker(UsbDevice dev) {
        try {
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (!um.hasPermission(dev) && !awaitPermission(um, dev)) {
                log.line("USB 权限未授予，退出");
                runOnUiThread(() -> summaryView.setText("USB 权限未授予 · 点「重新检测」重新申请"));
                return;
            }
            if (session != null) session.close();
            session = MtpSession.open(um, dev, log);
            if (session == null) return;

            int[] sids = session.storageIds(log);
            if (sids.length == 0) {
                log.line("拿不到存储：请拔插 USB 线后重试（相机会话结束后约 1 分钟会挂起 USB）");
                runOnUiThread(() -> summaryView.setText("未拿到存储 · 拔插数据线后点「重新检测」"));
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
            attachGate.finish();
            runOnUiThread(() -> scanBtn.setEnabled(true));
        }
    }

    private boolean awaitPermission(UsbManager um, UsbDevice dev) throws InterruptedException {
        log.line("尚无 USB 权限，发起申请");
        runOnUiThread(() -> summaryView.setText("等待 USB 授权 · 请在系统弹窗点「允许」"));
        int seq = permGate.begin();
        // requestCode 必须用 seq（#11 复评）：PendingIntent 记录按 requestCode 区分，
        // 固定用 0 + FLAG_UPDATE_CURRENT 时所有申请共用一条记录，后一次 begin() 会把
        // extras 里的 seq 覆写成新值——上一轮迟到的回调于是带着「当前」seq 到达，
        // 序号判定被绕过，又回到「迟到回调被当成当前回调」。每条申请一条独立记录后，
        // 迟到回调只会携带它自己那一轮的 seq，被 PermGate.onResult 丢弃。
        Intent pi = new Intent(ACTION_PERM).setPackage(getPackageName()).putExtra(EXTRA_PERM_SEQ, seq);
        // 必须 MUTABLE，别改成 IMMUTABLE（#11 复评，防复发）：系统是拿 fillIn 把
        // EXTRA_PERMISSION_GRANTED 塞进这条广播的，PendingIntentRecord.sendInner 在
        // immutable 时会跳过 fillIn —— 那样即使用户点「允许」，回调读到的也恒为 false，
        // 授权永远失败。记录已按 seq 唯一，不需要 FLAG_UPDATE_CURRENT；
        // 也不要把 MUTABLE 与 IMMUTABLE 同时给，会抛 IllegalArgumentException。
        um.requestPermission(dev, PendingIntent.getBroadcast(this, seq, pi, PendingIntent.FLAG_MUTABLE));
        permGate.await(120, TimeUnit.SECONDS);
        return permGate.granted();
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
