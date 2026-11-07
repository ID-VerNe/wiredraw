package com.rea.mtpimport;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

/**
 * 一轮扫描任务（G9a，从 `Main.scanWorker` 整体搬出，行为逐行照搬）。
 *
 * 线程：本类只在 `scan-worker` 线程上跑；**绝不在 UI 线程碰 `MtpDevice`**。
 * 界面投递统一经 {@link Host}，由 `Main` 负责切线程与「取消后丢弃」。
 */
final class ScanTask implements Runnable {

    /** 扫描任务需要的宿主能力；由 `Main` 实现（会话 / 缩略图 / 界面发布 / 收尾）。 */
    interface Host {
        Log log();

        UsbPermissionFlow permission();

        UsbManager usb();

        ThumbnailStore thumbnails();

        MtpSession session();

        void session(MtpSession s);

        void showScanning(ScanRun run, int done, int total);

        void showError(ScanRun run, String message);

        /** 渐进快照发布（UI 线程执行，含勾选归并）。 */
        void publishPartial(ScanRun run, Catalog snapshot, int done, int total);

        /** 完整结果落地（UI 线程执行，含空集重建与空间预检）。 */
        void publishFinal(ScanRun run, ScanResult scan);

        /** 取消时由扫描线程自己收尾关会话。 */
        void closeCancelledSession();

        /** 清掉本轮令牌 / 线程引用并释放 attachGate。 */
        void finishRun(ScanRun run);
    }

    private final Host host;
    private final UsbDevice dev;
    private final ScanRun run;

    ScanTask(Host host, UsbDevice dev, ScanRun run) {
        this.host = host;
        this.dev = dev;
        this.run = run;
    }

    @Override
    public void run() {
        try {
            UsbManager um = host.usb();
            if (!um.hasPermission(dev) && !host.permission().await(um, dev, run)) {
                if (run.isCancelled()) {
                    host.log().line("已取消，停止等待 USB 权限");
                    return;
                }
                host.log().line("USB 权限未授予，退出");
                host.showError(run, "未获得 USB 权限");
                return;
            }
            if (run.isCancelled()) {
                host.log().line("扫描已取消，跳过会话建立");
                return;
            }
            MtpSession existing = host.session();
            if (existing != null) {
                // 先摘会话再关（与取消路径同一顺序）：缩略图拿锁后会重判 session。
                host.thumbnails().detachSession();
                existing.close();
            }
            // 句柄只在当前会话里有效；重开前把旧缩略图整块释放。
            host.thumbnails().clear();
            MtpSession opened = MtpSession.open(um, dev, host.log());
            if (opened == null) {
                host.showError(run, "相机未就绪，请拔插数据线");
                return;
            }
            host.session(opened);
            host.thumbnails().setSession(opened);
            if (run.isCancelled()) {
                // 取消可能发生在 open() 返回之前：会话不在 UI 线程关，交给 finally 收尾
                // （同一个 MtpDevice 上 close 与 getObjectInfo 并发的竞态未验证）。
                host.log().line("扫描已取消，放弃本轮会话（由收尾统一关闭）");
                return;
            }

            int[] sids = opened.storageIds(host.log(), run);
            if (run.isCancelled()) {
                host.log().line("扫描已取消，停止本轮");
                return;
            }
            if (sids.length == 0) {
                host.log().line("拿不到存储：请拔插 USB 线（相机会话结束后约 1 分钟会挂起 USB）");
                host.showError(run, "相机存储不可用，请拔插数据线");
                return;
            }
            for (int sid : sids) host.log().line("存储：" + opened.describeStorage(sid));

            ScanResult scan = CatalogScanner.scan(opened.device(), sids, host.log(), run,
                    new CatalogScanner.ScanProgress() {
                        @Override
                        public void onScan(int done, int total) {
                            host.showScanning(run, done, total);
                        }

                        @Override
                        public void onPartial(Catalog snapshot, int done, int total) {
                            host.publishPartial(run, snapshot, done, total);
                        }
                    });
            if (!run.accept()) {
                host.log().line("扫描已取消，丢弃本轮结果（不上照片墙）");
                return;
            }
            // 明细只由 CatalogScanner 打一条（issue #211 / A7）；这里只标阶段，避免一次扫描两条同格式。
            host.log().line("扫描收尾：进入 CATALOG_READY");
            if (!scan.complete) {
                // issue #52：残缺目录仍可显示，但不能当 `.part` 保留集；把原因提前说清。
                host.log().line(scan.skipReason());
            }
            // 收尾也必须在 UI 线程排队：扫描线程先于已投递的渐进快照执行的话，
            // 会把用户在渐进态里的勾选冲掉。UI 队列 FIFO 保证这里晚于所有 onPartial。
            host.publishFinal(run, scan);
        } catch (Throwable t) {
            host.log().line("扫描异常：" + t);
            host.showError(run, "相机连接已断开，请拔插数据线");
        } finally {
            // 取消时由扫描线程自己收尾关会话（第五轮评审 B-1）：UI 线程不碰 MtpDevice。
            if (run.isCancelled()) host.closeCancelledSession();
            host.finishRun(run);
        }
    }
}
