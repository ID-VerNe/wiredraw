package com.rea.mtpimport;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsetsController;

import com.rea.mtpimport.ui.AppCallbacks;
import com.rea.mtpimport.ui.AppModel;
import com.rea.mtpimport.ui.ComposeHost;

import androidx.activity.ComponentActivity;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 导片工具（界面层）。
 *
 * 界面已按 `UI-SPEC` v2 定稿重建（Compose），本类只剩**编排**：
 * USB 权限 → 扫描 → 照片墙 → 传输。10 个界面态由 {@link UiState} 定义，
 * 由 {@link AppModel} 持有，Compose 只读它渲染。
 *
 * 传输那一环当前仍是 {@link ResumableTransfer.NotImplemented}：点主 CTA 会落到
 * `UNAVAILABLE`（`Not available`），不伪装成传输中（§0.11 第 6 行）。
 */
public final class Main extends ComponentActivity {

    private static final String TAG = "MTPIMPORT";
    private static final String ACTION_PERM = "com.rea.mtpimport.USB_PERM";

    /**
     * 本次权限申请的序号（#11）。系统回调是广播，上一次申请迟到的回调
     * 可能落在新一次申请等待期间；只有序号匹配的回调才算数。
     */
    private static final String EXTRA_PERM_SEQ = "com.rea.mtpimport.PERM_SEQ";

    /** USB 权限申请的等待门（#11）：每次申请重建 latch、只认本次序号的回调。 */
    private final PermGate permGate = new PermGate();

    /** 插线事件的准入判定（#17）：同一次插拔可能从 intent 与广播投递两次，重复事件不得开第二个会话。 */
    private final AttachGate attachGate = new AttachGate();

    /** 界面状态（Compose 唯一数据源）。 */
    private final AppModel model = new AppModel();

    private MtpSession session;
    private Catalog catalog;
    private Selection selection;
    private ImportService service;
    private TransferProgress lastProgress;

    /** debug 假数据入口；release 构建里没有这个类，恒为 null（U-54）。 */
    private DemoBridge demoBridge;

    private final Log log = this::logLine;

    private final AppCallbacks callbacks = new AppCallbacks() {
        @Override
        public void onConnectCamera() {
            handleAttach("点 Connect camera");
        }

        @Override
        public void onAllow() {
            // 假数据模式：`PERMISSION_PENDING` + `Allow` 直接进扫描（不碰 USB，§0.5）。
            if (demoActive()) {
                if (model.getState() == UiState.State.PERMISSION_PENDING) applyDemoName("SCANNING");
                return;
            }
            // 真实路径**不能**走 handleAttach / AttachGate（B1）：扫描线程正卡在
            // `permGate.await` 上，扫描锁要到 scanWorker 的 finally 才释放，admit 必回
            // NOTHING_ALREADY_SCANNING，Allow 就成了死键。这里只重发一次权限申请，
            // 由 PermGate 的新序号唤醒那条正在等待的线程（`begin()` 会唤醒旧 latch）。
            UsbDevice dev = findCamera();
            if (dev == null) {
                log.line("点 Allow → 没找到索尼设备（VID 0x054C）：检查 OTG 线、相机 USB 模式是否 MTP");
                model.showCold();
                return;
            }
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (um.hasPermission(dev)) {
                log.line("点 Allow → 权限已经在手，扫描线程继续");
                return;
            }
            int seq = requestPermission(um, dev);
            log.line("点 Allow → 重发 USB 权限申请（序号 " + seq + "）");
        }

        @Override
        public void onCancel() {
            if (demoActive()) {
                model.backToCatalog();
                return;
            }
            if (service != null && service.isRunning()) service.cancel();
            model.backToCatalog();
        }

        @Override
        public void onPauseResume() {
            if (demoActive()) {
                applyDemoName(model.getState() == UiState.State.PAUSED ? "IMPORTING" : "PAUSED");
                return;
            }
            if (service == null || !service.isRunning()) return;
            if (model.getState() == UiState.State.PAUSED) {
                service.resume();
                model.showImport(
                        lastProgress == null ? 0 : lastProgress.filesDone(),
                        lastProgress == null ? 0 : lastProgress.totalFiles(),
                        lastProgress == null ? 0 : lastProgress.bytesDone(),
                        lastProgress == null ? 0 : lastProgress.totalBytes(),
                        0);
            } else {
                service.pause();
                model.showPaused();
            }
        }

        @Override
        public void onImport() {
            doStart();
        }

        @Override
        public void onOpen() {
            // `Open` 目前只记日志：打开系统相册属于后续版本（§0.4 明确不在此轮范围内）。
            log.line("点 Open（本版本只记录，不跳相册）");
        }

        @Override
        public void onBack() {
            // 退出确认框开着时，返回键先关框（视同 `Resume`）。
            if (model.getExitConfirmOpen()) {
                model.setExitConfirm(false);
                return;
            }
            if (model.getOpenedPhoto() != null) {
                model.closePhoto();
                return;
            }
            // 抽屉判断必须在传输态之前：传输中开着 Details 时按返回应当只关抽屉（B2 附带缺陷）。
            if (model.getDetailsOpen()) {
                model.setDetailsOpen(false);
                return;
            }
            // `IMPORTING` / `PAUSED` 下返回键不得销毁任务（§1.3）：弹 `Pause & exit` / `Resume`。
            if (UiState.isTransfer(model.getState())) {
                model.setExitConfirm(true);
                return;
            }
            // `ERROR` 下返回键不得丢当前选择（§1.3）：回照片墙，选择还在 AppModel 里；
            // `Connect camera` 是唯一**恢复**动作，不是唯一动作，所以返回键照常可用。
            if (model.getState() == UiState.State.ERROR) {
                model.backToCatalog();
                return;
            }
            // 其余状态（含 `UNAVAILABLE`）按 §1.3 直接退出应用。
            finish();
        }

        /**
         * transfer 顶部返回：只关闭视图，任务继续（§1.3 第 2 条）。
         * 与系统返回键分开——后者在 `IMPORTING` 下要弹确认框。
         */
        @Override
        public void onCloseTransfer() {
            model.closeTransferView();
        }

        /** 确认框选 `Pause & exit`：暂停并退出应用，断点由 Journal 保留（§1.3 / §4.5）。 */
        @Override
        public void onPauseExit() {
            model.setExitConfirm(false);
            if (!demoActive() && service != null && service.isRunning()) service.pause();
            finish();
        }

        /** 确认框选 `Resume`：收起确认框；已暂停时顺带继续。 */
        @Override
        public void onResumeExit() {
            model.setExitConfirm(false);
            if (model.getState() == UiState.State.PAUSED) callbacks.onPauseResume();
        }

        @Override
        public void onDemoState(String name) {
            applyDemoName(name);
            model.setDetailsOpen(false);
        }
    };

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
                if (!demoActive()) handleAttach("收到 USB_DEVICE_ATTACHED 广播");
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        // targetSdk 35 在 Android 15 上强制 edge-to-edge；Compose 侧用 systemBarsPadding 让位，
        // 这里只把窗口装饰关掉，判定对象是「inset 之后的容器」（L-01）。
        getWindow().setDecorFitsSystemWindows(false);

        model.setCallbacks(callbacks);
        model.applyDeviceName("a7C");
        model.applyLocationPath(new File(getFilesDir(), "imported").getAbsolutePath());

        ComposeHost.mount(this, model);

        // 深色图标必须在 setContentView 之后设：`getInsetsController()` 依赖已挂上的 DecorView，
        // 在 onCreate 里抢在 setContentView 之前调会拿到 null（Mi 9 / Android 15 上实测直接崩溃）。
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        }

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_PERM);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }

        service = new ImportService(log, new ResumableTransfer.NotImplemented(log), getFilesDir());

        // 假数据入口只在 debug 里存在；有 `--es demo_state` 就直接进那个状态，不碰 USB。
        loadDemo(getIntent());
        if (!demoActive()) autoScanOnLaunch();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 前台插线：系统按默认处理者把 attach intent 直接投给本 Activity。
        // 覆写之前基类空实现会把 intent 丢掉——这就是「前台插线不自动扫描」的根因（#17）。
        if (loadDemo(intent)) return;
        if (!demoActive()) handleAttach("系统插线拉起");
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

    @Override
    public void onBackPressed() {
        callbacks.onBack();
    }

    // ---------------- 假数据 ----------------

    /**
     * 反射加载 debug 的 {@code DemoEntry}：release 源集里没有这个类，
     * `Class.forName` 会抛 `ClassNotFoundException`，这里静默跳过。
     */
    private boolean loadDemo(Intent intent) {
        try {
            Class<?> cls = Class.forName("com.rea.mtpimport.DemoEntry");
            Object bridge = cls.getMethod("create", Intent.class).invoke(null, intent);
            if (bridge instanceof DemoBridge) {
                demoBridge = (DemoBridge) bridge;
                DemoPayload payload = demoBridge.current();
                if (payload != null) {
                    applyDemo(payload);
                    return true;
                }
            }
        } catch (ClassNotFoundException e) {
            // release 构建：没有假数据模式，走真实 USB 路径。
        } catch (Throwable t) {
            log.line("假数据入口加载失败：" + t);
        }
        return false;
    }

    private boolean demoActive() {
        return model.getDemoActive();
    }

    private void applyDemoName(String name) {
        if (demoBridge == null) return;
        applyDemo(demoBridge.forState(name));
    }

    private void applyDemo(DemoPayload payload) {
        if (payload == null) return;
        runOnUiThread(() -> model.applyDemo(payload));
    }

    // ---------------- 动作 ----------------

    /** 冷启动检测：相机已连着就直接进扫描，否则落到 `COLD`（`No camera` + `Connect camera`）。 */
    private void autoScanOnLaunch() {
        UsbDevice dev = findCamera();
        if (dev == null) {
            model.showCold();
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
                model.showScanning(0, 0);
                new Thread(() -> scanWorker(dev), "scan-worker").start();
                break;
            case NOTHING_NO_DEVICE:
                log.line(trigger + " → 没找到索尼设备（VID 0x054C）：检查 OTG 线、相机 USB 模式是否 MTP");
                model.showCold();
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
                runOnUiThread(() -> model.showError("未获得 USB 权限"));
                return;
            }
            if (session != null) session.close();
            session = MtpSession.open(um, dev, log);
            if (session == null) {
                runOnUiThread(() -> model.showError("相机未就绪，请拔插数据线"));
                return;
            }

            int[] sids = session.storageIds(log);
            if (sids.length == 0) {
                log.line("拿不到存储：请拔插 USB 线（相机会话结束后约 1 分钟会挂起 USB）");
                runOnUiThread(() -> model.showError("相机存储不可用，请拔插数据线"));
                return;
            }
            for (int sid : sids) log.line("存储：" + session.describeStorage(sid));

            Catalog scanned = CatalogScanner.scan(session.device(), sids, log,
                    (done, total) -> runOnUiThread(() -> model.showScanning(done, total)));
            catalog = scanned;
            selection = new Selection(catalog);
            log.line(String.format(Locale.US, "扫描完成：文件夹 %d 个、文件 %d 个（合计 %.2f GB，其中 ARW %.2f GB）",
                    scanned.folderCount(), scanned.fileCount(),
                    scanned.fileBytes() / 1073741824.0, scanned.rawBytes() / 1073741824.0));
            runOnUiThread(() -> model.showCatalog(scanned, selection));
        } catch (Throwable t) {
            log.line("扫描异常：" + t);
            runOnUiThread(() -> model.showError("相机连接已断开，请拔插数据线"));
        } finally {
            attachGate.finish();
        }
    }

    private boolean awaitPermission(UsbManager um, UsbDevice dev) throws InterruptedException {
        log.line("尚无 USB 权限，发起申请");
        runOnUiThread(model::showPermission);
        requestPermission(um, dev);
        permGate.await(120, TimeUnit.SECONDS);
        return permGate.granted();
    }

    /**
     * 发起一次权限申请（带序号），返回本次序号。
     *
     * 首次申请（{@link #awaitPermission}）与 `PERMISSION_PENDING` 里点 `Allow` 的重发共用这一段，
     * 避免两条路径的 PendingIntent 细节走偏——B1 的根因就是「Allow 走了另一条路」。
     */
    private int requestPermission(UsbManager um, UsbDevice dev) {
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
        return seq;
    }

    private void doStart() {
        if (catalog == null || selection == null) {
            log.line("先扫描");
            return;
        }
        // 假数据模式：直接把界面切到 `IMPORTING`（12/36），不碰真实传输。
        if (demoActive()) {
            applyDemoName("IMPORTING");
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

        model.showImport(0, plan.count(), 0, plan.totalBytes(), 0);
        service.start(plan, new ImportListener() {
            @Override
            public void onPlanReady(ImportPlan p) {
                log.line("计划：" + p.count() + " 个文件 / " + p.totalBytes() + " 字节");
            }

            @Override
            public void onProgress(TransferProgress progress, ImportPlan.Item current) {
                lastProgress = progress;
                long now = System.nanoTime();
                runOnUiThread(() -> model.updateProgress(
                        progress.filesDone(), progress.totalFiles(),
                        progress.bytesDone(), progress.totalBytes(),
                        (long) progress.bytesPerSec(now)));
            }

            @Override
            public void onFileFinished(ImportPlan.Item item, ResumableTransfer.Kind kind,
                                       String note) {
                log.line("[" + kind + "] " + item.relPath + (note.isEmpty() ? "" : " · " + note));
            }

            @Override
            public void onFinished(boolean cancelled, String summary) {
                runOnUiThread(() -> {
                    if (cancelled) {
                        model.backToCatalog();
                    } else if (summary != null && summary.contains("未实现")) {
                        // 传输实现未就绪 → `Not available`，不伪装成传输中（§0.11 第 6 行）。
                        model.showUnavailable();
                    } else if (lastProgress != null) {
                        model.showDone(lastProgress.filesDone(), lastProgress.bytesDone());
                    } else {
                        model.backToCatalog();
                    }
                });
            }

            @Override
            public void onError(String message, Throwable t) {
                log.line("错误：" + message + (t == null ? "" : " · " + t));
                runOnUiThread(() -> model.showError("导入中断，请拔插数据线"));
            }
        });
    }

    // ---------------- 日志 ----------------

    void logLine(String s) {
        android.util.Log.i(TAG, s);   // 全限定：同包的 Log 已被本工具的日志接口占用
        runOnUiThread(() -> model.addLog(s));
    }
}
