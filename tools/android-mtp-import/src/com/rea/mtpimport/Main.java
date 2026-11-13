package com.rea.mtpimport;

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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 导片工具（界面层）。
 *
 * 界面已按 `UI-SPEC` v2 定稿重建（Compose），本类只剩**编排**：
 * USB 权限 → 扫描 → 照片墙 → 传输。10 个界面态由 {@link UiState} 定义，
 * 由 {@link AppModel} 持有，Compose 只读它渲染。
 *
 * 传输（PA，issue #50）由 {@link MtpTransfer} 真正把相机对象读到
 * `getFilesDir()/imported/`；`ImportService` 负责逐文件进度、暂停与取消。
 * demo 仍是独立调试入口，不参与真实 USB 路径。
 */
public final class Main extends ComponentActivity implements ScanTask.Host {

    private static final String TAG = "MTPIMPORT";
    private static final String ACTION_PERM = "com.rea.mtpimport.USB_PERM";

    /** demo 传输模拟的 tick 间隔：36 张约 9 秒走完，够看清进度又不拖太久。 */
    private static final long DEMO_TICK_MS = 250;

    /**
     * onDestroy 等待 worker 退出的总预算（PD / issue #50）。
     *
     * 只做一次有界等待：超过预算就跳过 `session.close()`，不在 UI 线程撞原生传输。
     * 正常取消实测约 0.6 s，所以给 1 s 预算；打不断的原生调用仍会被截断在 1 s。
     */
    private static final long DESTROY_JOIN_MS = 1000L;

    /**
     * #59：会话探活的最长等待。探活在后台线程持 `ioLock` 发 `getObjectInfo`，
     * UI 线程只等这么久；超时即按失步处理（相机挂起时不能无限卡住界面）。
     *
     * #268：这个窗口只约束 **native 调用本身**。试锁用非阻塞 `tryLock()`，拿不到锁
     * 说明有活跃 IO 正持锁（导入 / 缩略图），直接判活，不再把等锁时间算进来。
     */
    private static final long PROBE_JOIN_MS = 1200L;

    /**
     * 本次权限申请的序号（#11）。系统回调是广播，上一次申请迟到的回调
     * 可能落在新一次申请等待期间；只有序号匹配的回调才算数。
     */
    private static final String EXTRA_PERM_SEQ = "com.rea.mtpimport.PERM_SEQ";

    /** USB 插线事件的准入判定（#17）：同一次插拔可能从 intent 与广播投递两次，重复事件不得开第二个会话。 */
    private final AttachGate attachGate = new AttachGate();

    /** 界面状态（Compose 唯一数据源）。 */
    private final AppModel model = new AppModel();

    /** 照片墙缩略图（issue #59）：会话级缓存，所有相机调用走 session.ioLock()。 */
    private final ThumbnailStore thumbnails = new ThumbnailStore();

    /** 当前这一轮扫描的取消令牌（B1）；没有扫描时为 null，由 {@link ScanTask} 的 finally 清掉。 */
    private volatile ScanRun scanRun;

    /** 当前扫描线程：取消时 interrupt，让 3 秒睡眠 / 权限等待立刻退出（第五轮评审 B-1）。 */
    private volatile Thread scanThread;

    /** 主线程读（DETACH 身份比对）/ 扫描线程写（setter 与 closeCancelledSession），volatile 保证可见性（issue #280）。 */
    private volatile MtpSession session;
    private Catalog catalog;
    /**
     * 最近一次扫描的结果（issue #52 清理开关）。
     *
     * 不能拿「catalog 非空」或「清单非空」代替：渐进快照也会写 {@link #catalog}，
     * 而扫描器在 `getObjectHandles` / `getObjectInfo` 返回 null 时会静默跳过对象，
     * 扫描本身不抛异常。完整性标志与丢弃计数由 {@link ScanResult} 一次算清；
     * 扫描开始 / 取消一律置 null，只有扫描正常收尾才赋值。
     */
    private volatile ScanResult lastScan;
    private Selection selection;
    private volatile ImportService service;

    /**
     * 落点决策（issue #28）：默认 `getFilesDir()/imported`；请求相册本期会回退，原因非空。
     * 惰性初始化，避免在 Activity 构造期调用 `getFilesDir()`。
     */
    private DestPolicy.Destination destination;

    /**
     * 被放弃的上一轮（三审 B-3）：`service` 已摘掉，但 worker 可能还阻塞在原生 `importFile` 上。
     * worker 退出前不得重建会话（扫描的 `session.close()` 会撞上那条原生调用）。
     */
    private final OrphanRounds orphanRounds = new OrphanRounds();

    /** 当前这一轮的导入回调（G9a）；恢复暂停轮次时读它的最新进度。 */
    private volatile ImportRoundListener roundListener;

    /** debug 假数据入口；release 构建里没有这个类，恒为 null（U-54）。 */
    private DemoBridge demoBridge;

    /**
     * demo 传输模拟（issue #46）：没有相机时让 `Import all` 真的走进 `IMPORTING`。
     * 只在 `demoActive()` 分支使用；真实传输仍走 {@link ImportService}。
     */
    private DemoRun demoTransfer;

    /**
     * 空间预检（#374 / IA-7）：主线程合并同一帧的多次刷新，后台只算不可变快照，
     * 结果按序号回主线程；可用字节由 `SpaceRefresh` 做 1.5 s TTL 缓存。
     */
    private final SpaceRefresh spaceRefresh = new SpaceRefresh(System::currentTimeMillis);
    private final ExecutorService spaceWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "space-refresh");
        t.setDaemon(true);
        return t;
    });
    private final Handler spaceHandler = new Handler(Looper.getMainLooper());
    private final Runnable spaceTask = this::refreshSpaceCheck;

    private final Handler demoTicker = new Handler(Looper.getMainLooper());
    private final Runnable demoStep = new Runnable() {
        @Override
        public void run() {
            if (demoTransfer == null) return;
            boolean finished = demoTransfer.advance();
            // 进度只送文件计数：字节不建界面状态、不上屏（issue #70；#177 证 offset 不可信）。
            model.updateProgress(demoTransfer.doneFiles(), demoTransfer.totalFiles());
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

    /** USB 权限流程（G9a）：申请序号 / 等待门 / 广播结果判定。 */
    private final UsbPermissionFlow permissionFlow = new UsbPermissionFlow(
            this, ACTION_PERM, EXTRA_PERM_SEQ, log,
            () -> runOnUiThread(model::showPermission));

    /** 导入轮次往界面投递（G9a）：`post` 切 UI 线程，其余方法都假定已在 UI 线程。 */
    private final ImportUi importUi = new ImportUi() {
        @Override
        public void post(Runnable r) {
            runOnUiThread(r);
        }

        @Override
        public void updateProgress(int done, int total) {
            model.updateProgress(done, total);
        }

        @Override
        public void addFailedLine(String line) {
            model.addFailedLine(line);
        }

        @Override
        public void showImportError() {
            model.showError("导入中断，请拔插数据线");
        }

        @Override
        public void finishRound(boolean cancelled, int ok, int skipped, int failed, long bytesDone) {
            // issue #53 / P-07 / U-21：跳过数必须进 Details 抽屉（主视觉不出现）。
            // 先于 FinishOutcome 写入，DONE / ERROR / 取消 / 保持当前视图四种去向都能在抽屉里看到。
            model.showSkipped(skipped);
            // 判定顺序收进纯 JDK 的 FinishOutcome（issue #64）：全失败优先于「视图已关闭」，
            // 避免关闭视图后整轮全失败被 backToCatalog 静默吞掉。
            FinishOutcome.Action action = FinishOutcome.of(
                    cancelled, model.getTransferViewClosed(), ok, skipped, failed);
            switch (action) {
                case LEAVE_TRANSFER_VIEW:
                    // 取消：视图已在 onCancel 里关掉；这里再关一次，防迟到的进度回调
                    // 在 onFinished 之前落地把界面拽回 IMPORTING（#48）。
                    model.leaveTransferView();
                    return;
                case SHOW_ERROR:
                    // 一个都没成：落 ERROR + `Connect camera`（不是 Retry，§1.2 / U-52）。
                    model.showError("导入失败，请拔插数据线");
                    return;
                case STAY_CURRENT_VIEW:
                    // 三审顺带：`Pause & exit`（或顶部返回）后用户已回到照片墙；若暂停恰好落在
                    // 最后一个文件，worker 会在文件边界正常结束。此时 DONE 会把界面从照片墙
                    // 拽回传输页，与 §1.3「关视图后任务继续、不抢回界面」相冲突。
                    log.line("本轮结束但传输视图已关闭：保持当前视图（不跳 DONE）");
                    model.backToCatalog();
                    return;
                case SHOW_DONE:
                    model.showDone(ok + skipped, bytesDone);
                    return;
                case BACK_TO_CATALOG:
                default:
                    // 兜底：新增 Action 必须在此显式处理，否则会静默落到回照片墙。
                    model.backToCatalog();
            }
        }
    };

    /** 入口清零 / 早退（G9a）：`ImportEntry` 保证清零永远早于任何早退。 */
    private final ImportEntry.Sink entrySink = new ImportEntry.Sink() {
        @Override
        public void resetSkipped() {
            model.showSkipped(0);
        }

        @Override
        public void logLine(String line) {
            log.line(line);
        }
    };

    /** 空间预检投递（G9a）：`SpaceGate` 只算，界面状态由这里落。 */
    private final SpaceGate.Sink spaceSink = new SpaceGate.Sink() {
        @Override
        public void setSpaceStatus(boolean low, String detail) {
            model.setSpaceStatus(low, detail);
        }

        @Override
        public void logLine(String line) {
            log.line(line);
        }
    };

    /** 孤儿轮次投递（G9a）。 */
    private final OrphanRounds.Sink orphanSink = new OrphanRounds.Sink() {
        @Override
        public void logLine(String line) {
            log.line(line);
        }

        @Override
        public void showError(String message) {
            model.showError(message);
        }
    };

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
            // `permissionFlow.await` 上，扫描锁要到 ScanTask 的 finally 才释放，admit 必回
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
                // 或授权发生在别的会话），扫描线程仍卡在 `permissionFlow.await` 上，要空等 120 s 才超时。
                // 这里按「内部已知事实」直接把本轮等待判成已授权（PR #41 第三轮评审小项 1）。
                boolean released = permissionFlow.resolve(true);
                log.line("点 Allow → 权限已经在手，"
                        + (released ? "放行扫描线程" : "本轮等待已结束"));
                return;
            }
            int seq = permissionFlow.request(um, dev);
            log.line("点 Allow → 重发 USB 权限申请（序号 " + seq + "）");
        }

        @Override
        public void onCancel() {
            if (demoActive()) {
                stopDemoImport();
                model.backToCatalog();
                return;
            }
            boolean hadImportRun = service != null && service.isRunning();
            if (hadImportRun) service.cancel();
            // 扫描态取消（U-06 / P-02）。这里只置位令牌 + 唤醒两边等待：
            // - 扫描线程的重试/枚举循环都判令牌，会尽快自己退出；
            // - interrupt 让 `Thread.sleep` / `permissionFlow.await` 立刻返回，不必等满 3 秒 / 120 秒。
            // **不在 UI 线程关会话**（第五轮评审 B-1 同源缺陷①）：`MtpDevice.getStorageIds()` /
            // `getObjectInfo()` 既不 checkOpen 也不拿锁，用 `close()` 当中断手段是未验证的原生竞态。
            // 会话统一交给 ScanTask 的 finally 在自己的线程上关闭，随后才释放 attachGate。
            ScanRun run = scanRun;
            if (run != null && !run.isCancelled()) {
                run.cancel();
                permissionFlow.resolve(false);
                Thread worker = scanThread;
                if (worker != null) worker.interrupt();
                log.line("点 Cancel → 已请求取消扫描（会话由扫描线程收尾时关闭）");
            }
            if (!hadImportRun && model.getBackgroundScanning()) {
                // issue #60：取消后不得把部分结果翻成 CATALOG_READY。
                // 以 UI 真状态判定「本轮已渐进发布」，不用跨线程影子标志：
                // 扫描正常完成后 backgroundScanning 已清零，重扫在拿存储窗口取消时
                // 必须保留上一轮的完整照片墙，不能误删。
                catalog = null;
                lastScan = null;
                selection = null;
                model.discardPartialCatalog();
                log.line("扫描取消：丢弃已渐进发布的部分结果");
            }
            if (hadImportRun) {
                // 取消传输后立刻关掉传输视图：worker 被打断时/收尾前的迟到进度不得把界面
                // 拽回 IMPORTING（#48）。
                model.leaveTransferView();
            } else {
                // 扫描取消只回照片墙：**不动** transferViewClosed，否则下一次真实导入的进度
                // 会被误判成「视图已关闭」，永远进不了 IMPORTING。
                // 重扫窗口里 session 可能已经换成了新会话，而旧墙用的是旧句柄：
                // 先摘会话，免得格子用旧 handle 去新会话取图（评审阻断项 B2）。
                thumbnails.detachSession();
                model.backToCatalog();
            }
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
                    model.resumeImport(demoTransfer.doneFiles(), demoTransfer.totalFiles());
                } else {
                    demoTicker.removeCallbacks(demoStep);
                    model.showPaused();
                }
                return;
            }
            if (service == null || !service.isRunning()) return;
            if (model.getState() == UiState.State.PAUSED) {
                // 与 doStart 的暂停分支同口径（三审 B-1）：Pause & exit 后相机可能已挂起/断开，
                // 传输页 Resume 也必须先做只读探活；失败走既有 ERROR / Reconnect，不静默续跑死会话。
                ImportService pausedRound = service;
                ImportPlan pausedPlan = pausedRound.plan();
                if (pausedPlan == null || pausedPlan.count() == 0
                        || !probeSession(pausedPlan.items().get(0).handle, "传输页 Resume 前探活")) {
                    log.line("传输页 Resume 前探活失败（无计划或会话失步）：取消该轮，不 resume");
                    pausedRound.cancel();
                    orphanRounds.adopt(pausedRound);
                    service = null;
                    model.showError(StuckError.SESSION_LOST);
                    return;
                }
                service.resume();
                showImportFromProgress();
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
            // 关键（#48）：置 transferViewClosed 再回照片墙。worker 可能正把当前文件传完，
            // 它迟到的 onProgress 若在 import 视图仍「打开」时落地，会把状态拽回 IMPORTING。
            model.leaveTransferView();
        }

        /** 确认框选 `Resume`：收起确认框；已暂停时顺带继续。 */
        @Override
        public void onResumeExit() {
            model.setExitConfirm(false);
            if (model.getState() == UiState.State.PAUSED) callbacks.onPauseResume();
        }

        @Override
        public void onSelectionChanged() {
            // PD / P-09：勾选或 filter（#83）都会改变计划大小，空间预检必须跟着刷新
            // （由 Compose 在主线程回调）。IA-7：同一帧连发多次只留最后一次，计算不再占 UI 线程。
            scheduleSpaceCheck();
        }

        @Override
        public void onBackToCatalog() {
            // PD / P-09：DONE / ERROR / 取消返回照片墙时同样重算，避免沿用上一轮的预检结果。
            scheduleSpaceCheck();
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
                // 序号对不上或已经回答过的回调属于上一次申请，不得释放本次等待（#11）
                permissionFlow.onResult(i);
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                // 从前只写日志、不扫描（#17 的根因之一）：插线后应用看着像没反应。
                if (!demoActive()) handleAttach("收到 USB_DEVICE_ATTACHED 广播");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                // P1-1（#44 复审）：传输中断线只把顶栏连接指示切 `Disconnected`，保留进度屏；
                // 非传输态不动——`COLD` / `ERROR` 顶栏本来就有连接指示。
                // #267：先按设备身份比对，无关 USB / MTP 设备被拔不得把断线标志误置。
                UsbDevice gone = Build.VERSION.SDK_INT >= 33
                        ? i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                        : i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (!demoActive() && UsbIdentity.shouldMarkDetached(
                        gone == null ? null : gone.getDeviceName(), activeCameraName())) {
                    model.onUsbDetached();
                }
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
        DestPolicy.Destination dest = destination();
        model.applyLocationPath(dest.root.getAbsolutePath());
        // 回退不能静默：请求的落点没落地时，原因必须进日志（默认私有路径下为空）。
        if (dest.fellBack()) log.line(dest.fallbackReason);

        ComposeHost.mount(this, model);
        model.bindThumbnails(thumbnails);

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
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }

        // 真实传输在 doStart() 里按当前会话构造（PA / issue #50）：
        // 没有经过扫描就没有可用的 MtpDevice，onCreate 里不提前注入占位实现。
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
        // #374 / IA-7：先作废在飞的空间预检，销毁后不得再回写界面状态。
        spaceRefresh.cancel();
        spaceHandler.removeCallbacksAndMessages(null);
        spaceWorker.shutdownNow();
        // PD（issue #50）：先请求停止，再在有限预算内 join。
        // worker 没退出前**不得**在 UI 线程 close 会话：那条原生调用可能还占着 MtpDevice。
        ImportService running = service;
        if (running != null) running.cancel();
        ImportService orphan = orphanRounds.current();
        if (orphan != null) orphan.cancel();
        ScanRun scan = scanRun;
        if (scan != null) scan.cancel();
        // 销毁后不会再收到回调（receiver 已注销），提前释放还在等权限的扫描线程，别让它空等 120 秒
        permissionFlow.cancel();
        Thread scanning = scanThread;
        if (scanning != null) scanning.interrupt();

        // #59：先摘会话并清缓存，阻止新的 getThumbnail；在飞的缩略图请求由 ioLock 与 close 串行。
        thumbnails.clear();

        long deadline = System.nanoTime() + DESTROY_JOIN_MS * 1_000_000L;
        boolean idle = awaitIdle(running, deadline)
                & awaitIdle(orphan, deadline)
                & awaitIdle(scanning, deadline);
        if (idle) {
            if (session != null) session.close();
        } else {
            logLine("销毁时仍有 worker 未退出：跳过关闭 MTP 会话（不在 UI 线程撞原生传输）");
        }
        // demo 模拟 ticker 必须随 Activity 一起停，否则 Handler 持有 Activity 造成泄漏
        demoTicker.removeCallbacksAndMessages(null);
        demoTransfer = null;
        super.onDestroy();
    }

    /** onDestroy 的有界等待：用共享截止时刻，三个 worker 加起来也不超过 {@link #DESTROY_JOIN_MS}。 */
    private static boolean awaitIdle(ImportService s, long deadlineNanos) {
        if (s == null) return true;
        long remainingMs = Math.max(1L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
        return s.awaitWorker(remainingMs);
    }

    /**
     * 扫描线程版本；中断请求后只等一次，不循环。
     *
     * 绝对 deadline 契约留在本方法里（剩余时间由此换算），有界 join 的收尾模板
     * 与 `ImportService.awaitWorker` 共用 {@link WorkerWait#finishJoin}。
     */
    private static boolean awaitIdle(Thread t, long deadlineNanos) {
        if (t == null) return true;
        long remainingMs = Math.max(1L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
        return WorkerWait.finishJoin(t, remainingMs);
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
            if (preview instanceof PhotoPreview) {
                // 照片墙与看图页共用同一来源（issue #59 / #61）。
                thumbnails.setDemo((PhotoPreview) preview);
            }
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
        int total = model.getVisibleSelectedCount() > 0
                ? model.getVisibleSelectedCount() : payload.totalFiles;
        // 实现类 DemoTransfer 只在 debug 源集，release 编译期不引用（DemoRun 是 main 侧接口）
        demoTransfer = demoBridge.newRun(total, perFile * total);
        model.showImport(0, total);
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
        UsbDevice dev = findCamera();
        // B1（2026-11-08 复审）：只有**确认到索尼设备**且当前在传输态时才清断线标志；
        // 无关 USB 设备（dev == null）不得把顶栏从 Disconnected 置回在线。状态不变。
        if (dev != null && !demoActive()) model.onUsbAttached();
        requestScan(trigger, dev);
    }

    private UsbDevice findCamera() {
        return MtpSession.findSony((UsbManager) getSystemService(Context.USB_SERVICE), log);
    }

    /**
     * 当前会话那台相机的 `deviceName`（如 `1-1`）；没有会话时返回 null。
     * 只用于 #267 的 DETACH 身份比对，不参与任何界面状态。
     */
    private String activeCameraName() {
        MtpSession s = session;
        if (s == null) return null;
        UsbDevice u = s.usbDevice();
        return u == null ? null : u.getDeviceName();
    }

    /**
     * 所有扫描入口的唯一汇合点：按钮、插线 intent（冷启动 / 前台）、插线广播、冷启动检测。
     *
     * 互斥与去重交给 {@link AttachGate}：同一次插拔可能从 intent 与广播投递两次，
     * 重复事件不得开第二个 MTP 会话（相机会话同一时刻只能有一个）。
     */
    private void requestScan(String trigger, UsbDevice dev) {
        // 取消卡死的 worker 不能把重扫也永久拦住（二审 B-1）：先放弃该轮再走准入判定。
        // 放弃只摘掉引用、不 close 会话；但 worker 未退出前也不重建会话（三审 B-3）。
        abandonStuckService();
        if (orphanRounds.stillRunning(orphanSink)) return;
        AttachGate.Admit admit = attachGate.admit(dev != null, service != null && service.isRunning());
        switch (admit) {
            case GO: {
                ScanRun run = new ScanRun();
                scanRun = run;
                // issue #52：新一轮枚举开始即撤掉上一次的完整结果；只有正常收尾才恢复。
                lastScan = null;
                log.line(trigger + " → 开始扫描");
                model.showScanning(0, 0);
                Thread worker = new Thread(new ScanTask(this, dev, run), "scan-worker");
                scanThread = worker;
                worker.start();
                break;
            }
            case NOTHING_NO_DEVICE:
                log.line(trigger + " → 没找到索尼设备（VID 0x054C）：检查 OTG 线、相机 USB 模式是否 MTP");
                // #285：传输态（IMPORTING / PAUSED）下「设备在场但不是相机」不得把暂停屏打回冷启动页；
                // IMPORTING 靠下次进度回调自愈，PAUSED 没有回调会丢屏。
                if (!UiState.isTransfer(model.getState())) {
                    model.showCold();
                }
                break;
            case NOTHING_TRANSFERRING:
                // #269：本分支只打日志，不取消也不重扫；文案必须如实（旧文案声称会「先取消、再重扫」，
                // 会让真机诊断把「只打日志」误读成真做了取消与重扫）。唯一副作用是
                // handleAttach 已调过 model.onUsbAttached()，即顶栏恢复在线指示。
                log.line(trigger + " → 传输进行中：本轮不打断，也不重扫（只恢复在线指示）");
                break;
            case NOTHING_ALREADY_SCANNING:
                log.line(trigger + " → 扫描已在进行中，忽略本次重复事件");
                break;
        }
    }

    // ---------------- ScanTask.Host ----------------
    //
    // 扫描本身在 {@link ScanTask} 的 worker 线程上跑（G9a）；这里只提供会话、缩略图、
    // 界面投递与收尾，且一律经 postIfActive 保证「取消后丢弃」。

    @Override
    public Log log() {
        return log;
    }

    @Override
    public UsbPermissionFlow permission() {
        return permissionFlow;
    }

    @Override
    public UsbManager usb() {
        return (UsbManager) getSystemService(Context.USB_SERVICE);
    }

    @Override
    public ThumbnailStore thumbnails() {
        return thumbnails;
    }

    @Override
    public MtpSession session() {
        return session;
    }

    @Override
    public void session(MtpSession s) {
        session = s;
    }

    @Override
    public void showScanning(ScanRun run, int done, int total) {
        postIfActive(run, () -> model.showScanning(done, total));
    }

    @Override
    public void showError(ScanRun run, String message) {
        postIfActive(run, () -> model.showError(message));
    }

    @Override
    public void publishPartial(ScanRun run, Catalog snapshot, int done, int total) {
        postIfActive(run, () -> {
            if (model.getBackgroundScanning()) {
                selection.retarget(snapshot);
            } else {
                // 本轮第一次发布：新建空集选择，避免沿用上一轮勾选。
                // 首轮自己不会留下 backgroundScanning=true（完成/取消都会清零）。
                selection = new Selection(snapshot);
            }
            catalog = snapshot;
            model.showPartialCatalog(snapshot, selection, done, total);
            log.line(String.format(Locale.US,
                    "渐进发布：已扫描 %d/%d，照片墙 %d 格",
                    done, total, model.getPhotoCount()));
        });
    }

    @Override
    public void publishFinal(ScanRun run, ScanResult scan) {
        // 收尾也必须在 UI 线程排队：扫描线程先于已投递的渐进快照执行的话，
        // 会把用户在渐进态里的勾选冲掉。UI 队列 FIFO 保证这里晚于所有 onPartial。
        postIfActive(run, () -> {
            catalog = scan.catalog;
            // issue #52：完整扫描结果只在这里落地；渐进快照（onPartial）不置 lastScan。
            lastScan = scan;
            // UI 状态为准：渐进态保留用户勾选；没有渐进发布才重建空集。
            if (model.getBackgroundScanning()) selection.retarget(scan.catalog);
            else selection = new Selection(scan.catalog);
            model.showCatalog(scan.catalog, selection);
            // PD / P-09：照片墙出现后立刻做一次空间预检，主 CTA 该禁用就禁用。
            // IA-7：和勾选变化一样走主线程合并，不在扫描收尾这一帧直接算。
            scheduleSpaceCheck();
        });
    }

    @Override
    public void closeCancelledSession() {
        // 取消时由扫描线程自己收尾关会话（第五轮评审 B-1）：UI 线程不碰 MtpDevice。
        MtpSession s = session;
        if (s == null) return;
        // 先摘会话再关：缩略图线程拿锁后会重判 session，避免 getThumbnail 撞上 close。
        thumbnails.detachSession();
        log.line("取消收尾：关闭 MTP 会话");
        s.close();
        session = null;
    }

    @Override
    public void finishRun(ScanRun run) {
        if (scanRun == run) scanRun = null;
        if (scanThread == Thread.currentThread()) scanThread = null;
        attachGate.finish();
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

    /**
     * 刷新空间预检（PD / P-09 / #374 IA-7）。入口仍在主线程（`spaceTask`），
     * 但 `ImportPlan.of` 与 `StatFs` 都搬到后台；只有最新序号的结果才回主线程落状态。
     *
     * 计划大小随勾选变化，所以照片墙显示后与每次勾选变化各算一次；不足时把
     * `lowSpace` 交给 AppModel（主 CTA 禁用 + 一行 `Low space`），差额进 Details。
     * 计划按当前 filter 的**可见集合**算（issue #83），与主 CTA 的分母同口径；
     * 计划为空（可见集合里没有可导出文件）时不做拦截。
     * 判据与文案收进纯 JDK 的 {@link SpaceGate#refresh}（G9a）。
     */
    private void scheduleSpaceCheck() {
        // 同一帧里组选 / filter 可能连发多次：合并成最后一次，别重复投递后台任务。
        spaceHandler.removeCallbacks(spaceTask);
        spaceHandler.post(spaceTask);
    }

    private void refreshSpaceCheck() {
        if (demoActive() || catalog == null || selection == null) {
            model.setSpaceStatus(false, "");
            return;
        }
        // IA-7：后台线程只能读不可变快照。Selection 的 checked 集合由 UI 线程写，
        // visibleUnits 每次 getter 都可能新建列表，两者都必须在投递前复制一次。
        final Catalog snapCatalog = catalog;
        final Selection snapSelection = selection.snapshot();
        final List<PhotoWall.Unit> snapVisible = new ArrayList<>(model.getVisibleUnits());
        final PhotoWall.Export snapExport = model.getExportMode();
        final long seq = spaceRefresh.submit();
        spaceWorker.execute(() -> {
            ImportPlan plan = ImportPlan.of(snapCatalog, snapSelection, snapVisible, snapExport);
            long free = spaceRefresh.freeBytes(this::freeBytes);
            runOnUiThread(() -> {
                if (!spaceRefresh.isCurrent(seq)) return;
                SpaceGate.refresh(spaceSink, plan, () -> free);
            });
        });
    }

    /**
     * 落点决策（issue #28）：进程内只取一次，整轮开始前定死。默认应用私有目录；
     * PB 分节已把 `kind` 接到写入链路（相册走 MediaStore 流式复制），UI 选择器由 PC 分节接入，
     * 当前仍恒默认私有。回退原因由 {@link #onCreate} 写日志，不能静默。
     */
    private DestPolicy.Destination destination() {
        if (destination == null) {
            destination = DestPolicy.choose(getFilesDir(), DestPolicy.Kind.PRIVATE);
        }
        return destination;
    }

    /**
     * 目标分区的可用字节。用 `getFilesDir()` 而不是 `imported/`：后者可能还没建，
     * `StatFs` 要求路径存在；两者在同一个内部存储卷上。
     *
     * 预检本身失败时按「足够」处理（返回 `Long.MAX_VALUE`）：不能让一次取数异常反过来阻断导入，
     * 真正的空间问题由传输层的写失败兜底。
     */
    private long freeBytes() {
        try {
            return new android.os.StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes();
        } catch (Throwable t) {
            log.line("空间预检取数失败，跳过拦截：" + t);
            return Long.MAX_VALUE;
        }
    }

    /**
     * 点主 CTA 前的空间预检（PD / P-09 / issue #124）。
     *
     * 两处共用：清单变化时、取消旧暂停轮**之前**（空间不足就不取消，保留暂停轮次），
     * 以及新建一轮之前（防「预检之后、点击之前」空间被别的应用吃掉）。
     * 不足时落 `Low space` 并返回 false，调用方直接 return。判据收进 {@link SpaceGate#precheck}。
     *
     * IA-7：这里仍取**实时**可用字节，不走 `SpaceRefresh` 的 TTL 缓存。
     * 这一道就是为了抓住「预检之后、点击之前」空间被别的应用吃掉，缓存会让它失效；
     * 它只在主 CTA 被点时走一次，不是每次勾选的热路径。
     */
    private boolean precheckSpace(ImportPlan plan) {
        return SpaceGate.precheck(spaceSink, plan, freeBytes());
    }

    /**
     * issue #52：清理无主 `.part`（对象已不在目录，或 size 已不被任何目录对象认领）。
     *
     * 两个前提缺一不可：
     *
     * 1. **本次枚举完整**（{@link #lastScan}）。未扫 / 取消 / 丢弃过对象 / 存储取句柄失败都不能清——
     *    残缺枚举说明这一轮还不能确定磁盘上哪些 `.part` 是残渣，贸然删除可能误删正在写的半成品。
     *    完整性判定与跳过原因由
     *    {@link PartResidue#cleanIfComplete} 统一处理，跳过时留一行给用户看的日志。
     *    相机上确实没有可导入文件时（完整枚举、目录为空）仍要清，所以本方法放在 doStart 的空清单早退之前。
     * 2. **没有活动写者**：{@link #service} 在跑（含暂停但 worker 未退）或遗留 worker 还在跑时跳过；
     *    它们都可能正写着 `.part`。
     *
     * 只扫 `outDir` 顶层，不碰成品 / Journal 与停写前留下的 `imported-index.txt`；
     * 删除失败只记日志，不阻断本轮。
     */
    private void cleanupPartResidue() {
        if (catalog == null) return;
        if (service != null && service.isRunning()) return;
        if (orphanRounds.stillRunning(orphanSink)) return;
        File out = destination().root;
        // 没有目录就没有 `.part`；不要为了清理去建目录。
        if (!out.isDirectory()) return;
        PartResidue.cleanIfComplete(out, PartResidue.keepNames(catalog.all()), lastScan, log);
    }

    private void doStart() {
        // issue #127：跳过数属于「本轮」，在入口就清空。清零必须早于下面所有早退分支：
        // 探活失败 / 无会话 / 建目录失败 / 空间不足等落 ERROR 时，Details 抽屉只判
        // `skippedLine.isNotEmpty()`，不能把上一轮的 `Skipped N existing` 留在 ERROR 态里。
        // 「先清零再判分支」收进纯 JDK 的 ImportEntry（G9a），自检直接驱动它断言顺序。
        // 假数据模式必须排在清单判定之前（issue #46）：demo 下 `Main.catalog` 从未赋值
        // （树只装在 AppModel 里），旧顺序会让 Import all 永远停在这一句「先扫描」。
        ImportEntry.Stage stage = ImportEntry.begin(entrySink, demoActive(),
                catalog != null && selection != null);
        if (stage == ImportEntry.Stage.DEMO) {
            startDemoImport();
            return;
        }
        if (stage == ImportEntry.Stage.NEEDS_SCAN) return;

        // issue #52：清理无主 `.part`。开关是「本次枚举是否完整成功」，不是「清单是否为空」：
        // 相机上没有可导入文件时也要清；枚举失败 / 取消时一律不清。详见 cleanupPartResidue()。
        cleanupPartResidue();

        // 空勾选已在 ImportPlan.of 里等价于「当前 filter 可见集合」（#80 / #83），
        // 走到这里只剩可见集合里没有可导出文件。
        ImportPlan plan = ImportPlan.of(catalog, selection,
                model.getVisibleUnits(), model.getExportMode());
        if (plan.isEmpty()) {
            log.line("当前 filter 下没有可导入的照片");
            return;
        }
        if (service != null && service.isRunning()) {
            if (service.isPaused()) {
                if (!service.canResumeWith(plan)) {
                    // issue #57：用户可能在照片墙改了勾选 / filter / 档位，这里算出的 plan 与暂停
                    // 轮次已不是同一批文件。旧清单继续跑违背用户意图，而 CATALOG_READY 按 UI-SPEC
                    // §1.3 / U-17 没有 Cancel 入口，不能把「先 Cancel」当提示；所以直接取消旧轮次、
                    // 按新计划重开一轮。
                    // issue #124：取消前先做空间预检。否则用户改选到更大的集合、空间却不够时，
                    // 旧暂停轮已经被取消，剩余待传列表白丢（Journal 只能保住已落盘的文件）。
                    // 空间不足时保留暂停轮次并落 Low space，补足空间后再点 Import 才取消旧轮。
                    // issue #124：先预检、再取消——不足时保留旧暂停轮（PauseSwitch 固化这个取舍）。
                    if (PauseSwitch.of(precheckSpace(plan)) == PauseSwitch.Action.KEEP_PAUSED) return;
                    // 暂停本身落在文件边界，但暂停时刻可能仍有文件在传（`.part` 尚未落定）；
                    // 取消若落在传输中，半成品由传输层整体删除（从 0 重来）。已完成文件由
                    // Journal 按 `relPath + size` 跳过，不会丢。
                    final ImportService pausedRound = service;
                    final ImportPlan pausedPlan = pausedRound.plan();
                    log.line(String.format(Locale.US,
                            "暂停轮次的清单已变化（旧 %d 个 / 新 %d 个）：取消旧轮次，按当前勾选重新开始",
                            pausedPlan == null ? 0 : pausedPlan.count(), plan.count()));
                    // 先置空引用：旧轮次迟到的 onFinished 会因 `service != round` 被丢弃，
                    // 不会把新轮次的界面冲掉（与放弃卡死同一口径）。
                    service = null;
                    pausedRound.cancel();
                    if (!pausedRound.awaitWorker(1000)) {
                        // 暂停中 worker 停在文件边界，正常 200 ms 内退出；打不断时给 ERROR 出路，
                        // 不放行新一轮（旧 worker 可能仍占着同一个 MtpDevice）。
                        // 该轮已正常 cancel，登记即可（不调 abandon()：正常收尾仍要保存日志）。
                        orphanRounds.adopt(pausedRound);
                        model.showError(StuckError.SESSION_LOST);
                        return;
                    }
                    // 继续往下走：按新 plan 探活并新建一轮。
                } else {
                    // 三审 B-1：`Pause & exit` 后可在照片墙停任意久，相机可能已挂起/断开。
                    // 恢复前必须与新一轮一样做只读探活；失步时 resume 只会让整轮逐个 FAILED，
                    // 而 skipped > 0 时 onFinished 仍会落 DONE，把失败掩盖过去。
                    final ImportService pausedRound = service;
                    if (!probeSession(plan.items().get(0).handle, "恢复暂停轮次前探活")) {
                        log.line("恢复前探活失败：取消该轮（不 resume，避免逐个 FAILED 被 DONE 掩盖）");
                        pausedRound.cancel();
                        // worker 还能自己退出（暂停不是卡死），但要挡掉它的 onFinished：置空引用后
                        // 回调会因 `service != round` 被丢弃，ERROR 不会被 leaveTransferView 冲掉。
                        orphanRounds.adopt(pausedRound);
                        service = null;
                        model.showError(StuckError.SESSION_LOST);
                        return;
                    }
                    // Pause & exit 之后从照片墙回来：恢复同一轮，Journal 已记账的文件不会重传。
                    log.line("继续已暂停的一轮");
                    service.resume();
                    showImportFromProgress();
                    return;
                }
            } else {
                StuckError.Kind stuck = abandonStuckService();
                if (stuck != StuckError.Kind.NONE) {
                    // 二审 B-1 的死路：pfd.close 若打不断原生 importFile（未验），worker 永不退出，
                    // 这里静默 return 会让 Import 变成死键（CATALOG_READY 没有 Cancel 入口）。
                    // 放弃该轮 + 落 ERROR（ERROR 有 Connect camera），用户拔插数据线后可重扫。
                    // issue #116：两种成因在日志里已分开，屏幕也要能区分——挂起是「长时间无响应」，
                    // 取消卡住才说「可能已失步」。两句都满足 UI-SPEC T-ERROR / C-01 / C-14。
                    model.showError(StuckError.message(stuck));
                    return;
                }
                log.line("已有一轮在跑，先等它结束或取消");
                return;
            }
        }
        // 放弃过卡死轮次时，旧 worker 可能仍占着同一个 MtpDevice：不放行新导入（三审 B-3）。
        if (orphanRounds.stillRunning(orphanSink)) return;
        MtpSession s = session;
        if (s == null || s.device() == null) {
            // 扫描成功后会话会保持常开；走到这里说明会话已经被取消/断开。
            log.line("没有可用的 MTP 会话：请重新插线后点 Connect camera");
            model.showError("相机连接已断开，请拔插数据线");
            return;
        }

        // 会话探活（二审 B-1）：上一轮取消中断 importFile 后，MTP 会话可能失步。
        // 复用前先用一次**只读** getObjectInfo 试一下；失败立刻落现有 ERROR 路径，
        // 不要把失步会话带进新一轮（否则要整轮全失败才发现）。只读：不删、不改设置、不触发快门。
        if (!probeSession(plan.items().get(0).handle, "新一轮导入前探活")) {
            model.showError(StuckError.SESSION_LOST);
            return;
        }

        DestPolicy.Destination dest = destination();
        // 状态目录恒私有：Journal / `.part` 只认这里；成品去向由 dest.kind 单独表达
        // （issue #28 分节 PB 拆「状态目录 / 成品根」，换成品去向不会丢导入记录）。
        File out = dest.root;

        // 目标目录按 relPath 建子目录这一步在 MtpTransfer 里做（要处理重名/覆盖与路径越界）。
        try {
            Dirs.ensure(out);
        } catch (IOException e) {
            log.line(e.getMessage());
            model.showError("无法创建导入目录");
            return;
        }

        // PD / P-09：点主 CTA 前必须做一次空间预检。照片墙上的禁用只是提前告知；
        // 这里再判一次，防止「预检之后、点击之前」空间被别的应用吃掉（issue #124 后与
        // 清单变化分支共用 `precheckSpace(ImportPlan)`）。
        if (!precheckSpace(plan)) return;

        // PA（issue #50）：真正搬字节用当前会话的 MtpDevice。会话由扫描建立，
        // 传输期间保持常开；不要在这里 close 再 open（相机会挂起 USB）。
        // issue #28 分节 PB：相册落点注入 GalleryStore；成品进 MediaStore 后不在 out 下，
        // 跳过判定改由注入的成品探针按相册条目尺寸核对（不整文件重下、也不静默跳过）。
        GalleryStore gallery = dest.kind == DestPolicy.Kind.GALLERY
                ? new GalleryStore(getContentResolver(), log) : null;
        final ImportService round = new ImportService(
                log, new MtpTransfer(s.device(), log, s.ioLock(), dest.kind, gallery), out);
        if (gallery != null) {
            // 相册优先、私有兜底：回退私有的成品也要能被账本认出来，否则每轮整文件重下。
            round.setProductProbe(SkipRule.productProbe(gallery::existingSize, out));
        }
        service = round;
        // 回调转发与「迟到回调丢弃」收进纯 JDK 的 ImportRoundListener（G9a）；
        // 新一轮自然从 lastProgress == null 开始，不需要在这里手工清。
        roundListener = new ImportRoundListener(log, importUi, () -> service == round);
        // PD：失败明细属于「本轮」；新一轮开始时清空，避免上一轮的失败混进本次 Details。
        model.clearFailedLines();
        model.showImport(0, plan.count());
        round.start(plan, roundListener);
    }

    /**
     * 取消后 worker 迟迟不退出、**或**未取消但长时间无传输进展时，放弃该轮（二审 B-1 / #63）。
     *
     * 两种成因共用同一出路，但判定不同：
     * - 取消卡住：`isCancelStuck()`，已请求取消 + 超过 3 s 宽限；
     * - 无进展挂起（#63）：`isStalled()`，没取消、没暂停、但一次整文件 `importFile` 已持续
     *   超过 60 s（相机休眠 / USB 掉线；#177 续传停用后只剩整文件路径）。旧实现只认
     *   第一种，于是「没点取消就挂起」的轮次让 `CATALOG_READY` 下的 `Import` 变成死键
     *   （该态按 UI-SPEC §1.3 没有 Cancel 入口）。
     *
     * 正常取消窗口（毫秒级）与正常传输（单文件约 1.1 s）都不会触发，避免误判。
     * 放弃只摘掉 `service` 引用到 {@link OrphanRounds}，**不在这里 close 会话**：没能被打断
     * 的原生调用可能还占着 `MtpDevice`，UI 线程 close 是未验证的原生竞态（与扫描取消同一条口径）。
     * 旧 worker 退出前 {@link OrphanRounds#stillRunning} 会拦住扫描与新导入；退出后由扫描线程
     * 收尾并重建会话。旧轮迟到的 `finally` 用 {@link ImportService#abandon()} 跳过日志保存，
     * 不会冲掉新一轮的 `import-journal.txt`（三审 B-3）。
     *
     * 返回值给出成因（issue #116）：调用方据此在屏幕上区分文案——这两种成因用户能观察到
     * （自己点过 `Cancel` vs 什么都没做就卡住），不该共用一个 `ERROR` 句子。
     */
    private StuckError.Kind abandonStuckService() {
        if (service == null || !service.isRunning()) return StuckError.Kind.NONE;
        boolean cancelStuck = service.isCancelStuck();
        boolean stalled = !cancelStuck && service.isStalled();
        if (!cancelStuck && !stalled) return StuckError.Kind.NONE;
        log.line(cancelStuck
                ? "本轮取消后传输线程仍未退出：放弃该轮（会话可能已失步，需拔插数据线）"
                : "本轮传输长时间无进展：放弃该轮（会话可能已失步，需拔插数据线）");
        final ImportService dead = service;
        // 旧轮 finally 的整文件覆写不得冲掉新一轮的记录（三审 B-3）；abandon 与登记在
        // OrphanRounds 内一起做，避免两处语义走偏。
        // 保留引用：worker 未退出前不放行扫描/新导入，避免 session.close() 撞上原生调用。
        orphanRounds.abandon(dead);
        service = null;
        return cancelStuck ? StuckError.Kind.CANCEL_STUCK : StuckError.Kind.STALLED;
    }

    /**
     * 复用会话前的只读探活（二审 B-1 / 三审 B-1）。
     *
     * `getObjectInfo(handle)` 是 GetObject，只读：不删除、不改设置、不触发快门。
     * 返回 false 表示会话可能已失步，调用方必须落现有 `ERROR` 路径，不能带着失步会话继续传。
     *
     * #59：探活与缩略图 / 导入共用 `ioLock`，且不在 UI 线程直接发原生调用；
     * UI 线程最多等 {@link #PROBE_JOIN_MS}，超时按失步处理（相机挂起时不再无限卡住界面）。
     *
     * #268：试锁是非阻塞的 `tryLock()`，锁被活跃 IO 占用直接判活；结论由 {@link ProbeVerdict}
     * 统一给出，避免「等锁」与「真挂起」混在同一个超时里。
     */
    private boolean probeSession(int handle, String when) {
        final MtpSession s = session;
        if (s == null || s.device() == null) {
            log.line(when + "：没有可用的 MTP 会话");
            return false;
        }
        final boolean[] busy = {false};
        final boolean[] alive = {false};
        final Throwable[] error = {null};
        Thread probe = new Thread(() -> {
            // #268：先非阻塞试锁。拿不到锁说明有活跃 IO（导入 / 缩略图）正持锁，会话显然还活着，
            // 直接判活——旧实现用阻塞 lock()，把等锁时间也算进 PROBE_JOIN_MS，单张 RAW 约 1.14 s，
            // 暂停后立刻 Resume 就会假超时，把本来能续的一轮取消成 ERROR。
            if (!s.ioLock().tryLock()) {
                // #281：持锁 IO 可能属于已摘掉的旧会话；会话换代重叠窗口里，旧会话的在飞 IO
                // 不得把新一代判活。复核身份与拿到锁的分支同口径：仍是当前会话才按活判。
                busy[0] = session == s;
                return;
            }
            try {
                // 扫描取消 / onDestroy 摘掉会话后不再往已关闭/已换代的设备上发命令。
                if (session != s) return;
                alive[0] = s.device() != null && s.device().getObjectInfo(handle) != null;
            } catch (Throwable t) {
                error[0] = t;
            } finally {
                s.ioLock().unlock();
            }
        }, "probe-session");
        probe.start();
        try {
            probe.join(PROBE_JOIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        boolean timedOut = probe.isAlive();
        if (timedOut) {
            log.line(when + "：探活超时（相机可能挂起）");
            probe.interrupt();
        } else if (busy[0]) {
            log.line(when + "：ioLock 被活跃 IO 占用，按会话存活处理（#268）");
        } else if (error[0] != null) {
            log.line(when + "：会话探活异常：" + error[0]);
        } else if (!alive[0]) {
            log.line(when + "：getObjectInfo(" + handle + ") 无返回");
        }
        // #281：hasSession 由调用点真实派生（会话仍是当前、代数未换代且设备在），不再恒真。
        boolean hasSession = session == s && s.device() != null;
        return ProbeVerdict.alive(hasSession, busy[0], !timedOut, error[0] != null, alive[0]);
    }

    /**
     * 用最近一次进度重新进入 `IMPORTING`（恢复暂停轮次用；没有进度就从 0 开始）。
     *
     * 走 RESUME 而不是 IMPORT_START（B3）：Resume 是继续同一轮，**不得**把 USB 拔出置位的
     * `connectionLost` 擦回在线；新导入仍由 {@code showImport} 走 IMPORT_START 复位。
     */
    private void showImportFromProgress() {
        ImportRoundListener listener = roundListener;
        TransferProgress p = listener == null ? null : listener.lastProgress();
        model.resumeImport(
                p == null ? 0 : p.filesDone(),
                p == null ? 0 : p.totalFiles());
    }

    // ---------------- 日志 ----------------

    void logLine(String s) {
        android.util.Log.i(TAG, s);   // 全限定：同包的 Log 已被本工具的日志接口占用
        runOnUiThread(() -> model.addLog(s));
    }
}
