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
import android.os.Handler;
import android.os.Looper;
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

    /** demo 传输模拟的 tick 间隔：36 张约 9 秒走完，够看清进度又不拖太久。 */
    private static final long DEMO_TICK_MS = 250;

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

    /** 当前这一轮扫描的取消令牌（B1）；没有扫描时为 null，由 scanWorker 的 finally 清掉。 */
    private volatile ScanRun scanRun;

    /** 当前扫描线程：取消时 interrupt，让 3 秒睡眠 / 权限等待立刻退出（第五轮评审 B-1）。 */
    private volatile Thread scanThread;

    private MtpSession session;
    private Catalog catalog;
    private Selection selection;
    private ImportService service;
    private TransferProgress lastProgress;

    /** debug 假数据入口；release 构建里没有这个类，恒为 null（U-54）。 */
    private DemoBridge demoBridge;

    /**
     * demo 传输模拟（issue #46）：没有相机时让 `Import all` 真的走进 `IMPORTING`。
     * 只在 `demoActive()` 分支使用；真实传输仍走 {@link ImportService}。
     */
    private DemoRun demoTransfer;
    private long demoBytesPerSecond;
    private final Handler demoTicker = new Handler(Looper.getMainLooper());
    private final Runnable demoStep = new Runnable() {
        @Override
        public void run() {
            if (demoTransfer == null) return;
            boolean finished = demoTransfer.advance();
            model.updateProgress(demoTransfer.doneFiles(), demoTransfer.totalFiles(),
                    demoTransfer.doneBytes(), demoTransfer.totalBytes(), demoBytesPerSecond);
            if (finished) {
                model.showDone(demoTransfer.doneFiles(), demoTransfer.doneBytes());
                log.line("[demo] 传输完成：" + demoTransfer.doneFiles() + " 张 / "
                        + demoTransfer.doneBytes() + " 字节");
                demoTransfer = null;
                return;
            }
            demoTicker.postDelayed(this, DEMO_TICK_MS);
        }
    };

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
                // 权限已经在手时不能只记日志：系统对话框的广播可能永远不来（用户先前已授权、
                // 或授权发生在别的会话），扫描线程仍卡在 `permGate.await` 上，要空等 120 s 才超时。
                // 这里按「内部已知事实」直接把本轮等待判成已授权（PR #41 第三轮评审小项 1）。
                boolean released = permGate.resolve(true);
                log.line("点 Allow → 权限已经在手，"
                        + (released ? "放行扫描线程" : "本轮等待已结束"));
                return;
            }
            int seq = requestPermission(um, dev);
            log.line("点 Allow → 重发 USB 权限申请（序号 " + seq + "）");
        }

        @Override
        public void onCancel() {
            if (demoActive()) {
                stopDemoImport();
                model.backToCatalog();
                return;
            }
            if (service != null && service.isRunning()) service.cancel();
            // 扫描态取消（U-06 / P-02）。这里只置位令牌 + 唤醒两边等待：
            // - 扫描线程的重试/枚举循环都判令牌，会尽快自己退出；
            // - interrupt 让 `Thread.sleep` / `permGate.await` 立刻返回，不必等满 3 秒 / 120 秒。
            // **不在 UI 线程关会话**（第五轮评审 B-1 同源缺陷①）：`MtpDevice.getStorageIds()` /
            // `getObjectInfo()` 既不 checkOpen 也不拿锁，用 `close()` 当中断手段是未验证的原生竞态。
            // 会话统一交给 scanWorker 的 finally 在自己的线程上关闭，随后才释放 attachGate。
            ScanRun run = scanRun;
            if (run != null && !run.isCancelled()) {
                run.cancel();
                permGate.resolve(false);
                Thread worker = scanThread;
                if (worker != null) worker.interrupt();
                log.line("点 Cancel → 已请求取消扫描（会话由扫描线程收尾时关闭）");
            }
            model.backToCatalog();
        }

        @Override
        public void onPauseResume() {
            if (demoActive()) {
                if (demoTransfer == null) {
                    // 冷启动直接 `--es demo_state IMPORTING/PAUSED`：没有 ticker，仍按旧口径静态切换
                    applyDemoName(model.getState() == UiState.State.PAUSED ? "IMPORTING" : "PAUSED");
                    return;
                }
                if (model.getState() == UiState.State.PAUSED) {
                    demoTicker.postDelayed(demoStep, DEMO_TICK_MS);
                    model.showImport(demoTransfer.doneFiles(), demoTransfer.totalFiles(),
                            demoTransfer.doneBytes(), demoTransfer.totalBytes(), demoBytesPerSecond);
                } else {
                    demoTicker.removeCallbacks(demoStep);
                    model.showPaused();
                }
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
            // `Open` 目前只记日志：打开系统相册属于后续版本，不在 `UI-SPEC.md` §0.4 的
            // 「本轮不做」清单里（那里只列遥控 / 删除 / ARW 解码 / Wi-Fi / 云同步），
            // 所以按「待建功能」处理，等后续 issue 排期。
            log.line("点 Open（本版本只记录，不跳相册；待建清单项）");
        }

        @Override
        public void onBack() {
            // 路由决策抽到纯 JDK 的 BackNav（issue #48），用例在 SelfTest 里钉住。
            // 顺序仍是「浮层优先」（PR #41 第三轮评审 P1）：确认框 → 抽屉（Filter / Details）
            // → 照片 → 传输/终态 → 启动态；模态开着时不许顺带退出应用或关掉底下的页面。
            // 与旧实现的关键差别：`DONE` / `UNAVAILABLE` / `PAUSED` 一律回照片墙，不再 `finish()`
            // —— 旧实现只把 `ERROR` 当回照片墙，其余终态直接退出应用（issue #48 主症状）。
            // 这与 `UI-SPEC.md` §1.3 字面的「`DONE` / `UNAVAILABLE` 返回退出」冲突；按流程
            // 不在代码 PR 里改规范，关系记录在 #42。
            BackNav.Action action = BackNav.decide(
                    model.getState(),
                    model.getExitConfirmOpen(),
                    model.getFilterOpen(),
                    model.getDetailsOpen(),
                    model.getOpenedPhoto() != null);
            switch (action) {
                case CLOSE_EXIT_CONFIRM:
                    model.setExitConfirm(false);
                    break;
                case CLOSE_FILTER:
                    model.setFilterOpen(false);
                    break;
                case CLOSE_DETAILS:
                    model.setDetailsOpen(false);
                    break;
                case CLOSE_PHOTO:
                    model.closePhoto();
                    break;
                case OPEN_EXIT_CONFIRM:
                    model.setExitConfirm(true);
                    break;
                case BACK_TO_CATALOG:
                    model.backToCatalog();
                    break;
                case EXIT:
                default:
                    finish();
                    break;
            }
        }

        /**
         * transfer 顶部返回：只关闭视图，任务继续（§1.3 第 2 条）。
         * 与系统返回键分开——后者在 `IMPORTING` 下要弹确认框。
         */
        @Override
        public void onCloseTransfer() {
            model.closeTransferView();
        }

        /**
         * 确认框选 `Pause & exit`：暂停任务并退出**传输视图**，回到照片墙，不退出应用（issue #48）。
         * 断点由 Journal / ImportService 保留（§1.3 / §4.5）；demo 分支只摘 ticker，
         * 保住已完成数值，回照片墙后进度冻结、不再继续涨。
         */
        @Override
        public void onPauseExit() {
            model.setExitConfirm(false);
            if (demoActive()) {
                demoTicker.removeCallbacks(demoStep);
            } else if (service != null && service.isRunning()) {
                service.pause();
            }
            model.backToCatalog();
        }

        /** 确认框选 `Resume`：收起确认框；已暂停时顺带继续。 */
        @Override
        public void onResumeExit() {
            model.setExitConfirm(false);
            if (model.getState() == UiState.State.PAUSED) callbacks.onPauseResume();
        }

        @Override
        public void onDemoState(String name) {
            // 抽屉切态会换掉整块载荷，先停掉正在跑的模拟 ticker，避免两套进度互相覆盖
            stopDemoImport();
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
        // demo 模拟 ticker 必须随 Activity 一起停，否则 Handler 持有 Activity 造成泄漏
        demoTicker.removeCallbacksAndMessages(null);
        demoTransfer = null;
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
                    // 素材是可选的：没有 assets 时 DemoImages.create 返回 null，照片页退回胶囊
                    loadDemoPreview();
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

    /** 反射加载 debug 的 demo 预览来源；release 里没有这个类，静默跳过（U-54）。 */
    private void loadDemoPreview() {
        try {
            Class<?> cls = Class.forName("com.rea.mtpimport.DemoImages");
            Object preview = cls.getMethod("create", Context.class).invoke(null, this);
            if (preview instanceof PhotoPreview) model.setPreviewSource((PhotoPreview) preview);
        } catch (ClassNotFoundException e) {
            // release 构建：没有 demo 素材
        } catch (Throwable t) {
            log.line("demo 预览加载失败：" + t);
        }
    }

    /**
     * demo 的 `Import all`：用假载荷里的批次与字节数启动模拟 ticker（issue #46）。
     * 勾选过就只传勾选数，未勾选按假批次的 36 张走。
     */
    private void startDemoImport() {
        DemoPayload payload = demoBridge == null ? null : demoBridge.forState("IMPORTING");
        if (payload == null || payload.totalFiles <= 0) {
            log.line("[demo] 没有可用的传输载荷");
            return;
        }
        stopDemoImport();
        long perFile = payload.totalBytes / payload.totalFiles;
        int total = model.getSelectedCount() > 0 ? model.getSelectedCount() : payload.totalFiles;
        demoBytesPerSecond = payload.bytesPerSecond;
        // 实现类 DemoTransfer 只在 debug 源集，release 编译期不引用（DemoRun 是 main 侧接口）
        demoTransfer = demoBridge.newRun(total, perFile * total);
        model.showImport(0, total, 0, demoTransfer.totalBytes(), demoBytesPerSecond);
        demoTicker.postDelayed(demoStep, DEMO_TICK_MS);
        log.line("[demo] Import → 模拟传输 " + total + " 张");
    }

    /** 取消 / 切换 demo 态时清掉模拟进度（暂停不调用，暂停要保住当前数值）。 */
    private void stopDemoImport() {
        demoTicker.removeCallbacks(demoStep);
        demoTransfer = null;
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
            case GO: {
                ScanRun run = new ScanRun();
                scanRun = run;
                log.line(trigger + " → 开始扫描");
                model.showScanning(0, 0);
                Thread worker = new Thread(() -> scanWorker(dev, run), "scan-worker");
                scanThread = worker;
                worker.start();
                break;
            }
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

    private void scanWorker(UsbDevice dev, ScanRun run) {
        try {
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (!um.hasPermission(dev) && !awaitPermission(um, dev, run)) {
                if (run.isCancelled()) {
                    log.line("已取消，停止等待 USB 权限");
                    return;
                }
                log.line("USB 权限未授予，退出");
                postIfActive(run, () -> model.showError("未获得 USB 权限"));
                return;
            }
            if (run.isCancelled()) {
                log.line("扫描已取消，跳过会话建立");
                return;
            }
            if (session != null) session.close();
            session = MtpSession.open(um, dev, log);
            if (session == null) {
                postIfActive(run, () -> model.showError("相机未就绪，请拔插数据线"));
                return;
            }
            if (run.isCancelled()) {
                // 取消可能发生在 open() 返回之前：会话不在 UI 线程关，交给 finally 收尾
                // （同一个 MtpDevice 上 close 与 getObjectInfo 并发的竞态未验证）。
                log.line("扫描已取消，放弃本轮会话（由收尾统一关闭）");
                return;
            }

            int[] sids = session.storageIds(log, run);
            if (run.isCancelled()) {
                log.line("扫描已取消，停止本轮");
                return;
            }
            if (sids.length == 0) {
                log.line("拿不到存储：请拔插 USB 线（相机会话结束后约 1 分钟会挂起 USB）");
                postIfActive(run, () -> model.showError("相机存储不可用，请拔插数据线"));
                return;
            }
            for (int sid : sids) log.line("存储：" + session.describeStorage(sid));

            Catalog scanned = CatalogScanner.scan(session.device(), sids, log, run,
                    (done, total) -> postIfActive(run, () -> model.showScanning(done, total)));
            if (!run.accept()) {
                log.line("扫描已取消，丢弃本轮结果（不上照片墙）");
                return;
            }
            catalog = scanned;
            selection = new Selection(catalog);
            log.line(String.format(Locale.US, "扫描完成：文件夹 %d 个、文件 %d 个（合计 %.2f GB，其中 ARW %.2f GB）",
                    scanned.folderCount(), scanned.fileCount(),
                    scanned.fileBytes() / 1073741824.0, scanned.rawBytes() / 1073741824.0));
            postIfActive(run, () -> model.showCatalog(scanned, selection));
        } catch (Throwable t) {
            log.line("扫描异常：" + t);
            postIfActive(run, () -> model.showError("相机连接已断开，请拔插数据线"));
        } finally {
            // 取消时由扫描线程自己收尾关会话（第五轮评审 B-1）：UI 线程不碰 MtpDevice。
            if (run.isCancelled()) {
                MtpSession s = session;
                if (s != null) {
                    log.line("取消收尾：关闭 MTP 会话");
                    s.close();
                    session = null;
                }
            }
            if (scanRun == run) scanRun = null;
            if (scanThread == Thread.currentThread()) scanThread = null;
            attachGate.finish();
        }
    }

    /**
     * 扫描线程往界面投递的唯一入口：取消后丢弃。
     *
     * 取消发生在「已投递、未执行」的窗口里时，只在投递前判一次仍会把界面翻回
     * `CATALOG_READY`，所以判在 lambda 执行时。
     */
    private void postIfActive(ScanRun run, Runnable ui) {
        runOnUiThread(() -> {
            if (run.accept()) ui.run();
        });
    }

    private boolean awaitPermission(UsbManager um, UsbDevice dev, ScanRun run) throws InterruptedException {
        // 闭合「onCancel 落在 showScanning 与 begin() 之间」的 120 秒窗口（第五轮评审 B-1 同源缺陷②）：
        // 取消后绝不发起新的权限申请，否则 resolve(false) 打在还没 begin() 的门上，begin() 一复位又要等满。
        if (run.isCancelled()) return false;
        log.line("尚无 USB 权限，发起申请");
        runOnUiThread(model::showPermission);
        if (run.isCancelled()) return false;
        requestPermission(um, dev);
        permGate.await(120, TimeUnit.SECONDS);
        return permGate.granted() && !run.isCancelled();
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
        // 假数据模式必须排在 `catalog == null` 之前（issue #46）：demo 下 `Main.catalog`
        // 从未赋值（树只装在 AppModel 里），旧顺序会让 Import all 永远停在这一句「先扫描」。
        if (demoActive()) {
            startDemoImport();
            return;
        }
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
