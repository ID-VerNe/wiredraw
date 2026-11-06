package com.rea.mtpimport;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.mtp.MtpDevice;
import android.mtp.MtpDeviceInfo;
import android.mtp.MtpStorageInfo;
import android.os.Looper;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MTP 会话生命周期。这段逻辑是从已验证的探针里抽出来的
 * （探针 `com.rea.mtpprobe.Main#probe()` 的 openDevice → MtpDevice.open → getStorageIds 重试段），
 * 坑必须原样保留：
 *
 * 1. 相机空闲会挂起 USB：`getStorageIds()` 返回空、`open()` 卡 10 秒。
 *    这不是模式问题，重试 8 次 / 每次 3 秒；仍拿不到就得让用户拔插线。
 * 2. 会话结束约 1 分钟后相机会再次挂起，所以**扫描与传输共用同一个会话**，
 *    不要中途 close 再 open。
 */
public final class MtpSession {

    /** 索尼的 USB Vendor ID。 */
    public static final int SONY_VID = 0x054C;

    private static final int STORAGE_RETRIES = 8;
    private static final long STORAGE_RETRY_MS = 3000;
    /** UI 线程 close 时等缩略图 IO 的上限（#59）；超时就跳过 close，不让主线程卡死。 */
    private static final long CLOSE_WAIT_MS = 500;

    private final UsbDeviceConnection conn;
    private final MtpDevice mtp;

    /**
     * 会话级 I/O 锁：同一 `MtpDevice` 不允许并行发命令。导入（`importFile` /
     * `getPartialObject`）与照片墙缩略图（`getThumbnail`，issue #59）共用这把锁，
     * 公平锁保证缩略图不会在长时间导入期间被饿死。
     */
    private final ReentrantLock ioLock = new ReentrantLock(true);

    private MtpSession(UsbDeviceConnection conn, MtpDevice mtp) {
        this.conn = conn;
        this.mtp = mtp;
    }

    public ReentrantLock ioLock() {
        return ioLock;
    }

    /** 在已连接的 USB 设备里挑一台索尼机身；没有就返回 null。 */
    public static UsbDevice findSony(UsbManager um, Log log) {
        UsbDevice found = null;
        for (UsbDevice d : um.getDeviceList().values()) {
            log.line(String.format(Locale.US, "USB 设备 %s VID=0x%04X PID=0x%04X class=%d/%d/%d",
                    d.getDeviceName(), d.getVendorId(), d.getProductId(),
                    d.getDeviceClass(), d.getDeviceSubclass(), d.getDeviceProtocol()));
            if (d.getVendorId() == SONY_VID) found = d;
        }
        return found;
    }

    /**
     * 建立会话。失败返回 null（调用点负责提示「检查 USB 模式 / 拔插线」），
     * 不抛异常——相机那头的问题基本都是「再试一次」能解决的。
     */
    public static MtpSession open(UsbManager um, UsbDevice dev, Log log) {
        UsbDeviceConnection conn = um.openDevice(dev);
        if (conn == null) {
            log.line("openDevice() 返回 null：USB 权限没拿到，或设备被系统抢占");
            return null;
        }
        MtpDevice mtp = new MtpDevice(dev);
        if (!mtp.open(conn)) {
            log.line("MtpDevice.open() 失败：相机可能不是 MTP 模式，或被别的 App 占用");
            conn.close();
            return null;
        }
        MtpSession s = new MtpSession(conn, mtp);
        log.line("会话已建立：" + s.describe());
        return s;
    }

    public MtpDevice device() {
        return mtp;
    }

    private String describe() {
        try {
            MtpDeviceInfo di = mtp.getDeviceInfo();
            if (di == null) return "(getDeviceInfo 为空)";
            return di.getManufacturer() + " / " + di.getModel() + " / ver=" + di.getVersion();
        } catch (Throwable t) {
            return "(getDeviceInfo 异常: " + t + ")";
        }
    }

    /**
     * 拿存储 ID，带唤醒重试。空数组表示彻底失败 / 已取消，调用点应提示用户拔插 USB 线。
     *
     * 重试循环本身在纯 JDK 的 {@link StorageRetry} 里：每一轮都判 {@link ScanRun}，
     * 取消后最多再睡 1 个 3 秒就退出（第五轮评审 B-1，原来会睡满约 24 秒）。
     */
    public int[] storageIds(Log log, ScanRun run) {
        return StorageRetry.fetch(mtp::getStorageIds, Thread::sleep, run,
                STORAGE_RETRIES, STORAGE_RETRY_MS, log);
    }

    public String describeStorage(int sid) {
        MtpStorageInfo si = mtp.getStorageInfo(sid);
        if (si == null) return "storage 0x" + Integer.toHexString(sid);
        return String.format(Locale.US, "%s  容量 %.1f GB / 可用 %.1f GB",
                si.getDescription(),
                si.getMaxCapacity() / 1073741824.0, si.getFreeSpace() / 1073741824.0);
    }

    public void close() {
        // 与缩略图 / 导入共用同一把锁：close 必须等在飞的 getThumbnail 结束，
        // 否则 mtp.close() 会与不同步的 getThumbnail 并行（Android 35 的 close 同步，
        // getThumbnail 不同步）。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // UI 线程不能无限等：相机挂起时 getThumbnail 可能卡住，等太久就是 ANR。
            boolean locked;
            try {
                locked = ioLock.tryLock(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                locked = false;
            }
            if (!locked) {
                android.util.Log.w("MtpImport", "缩略图 IO 未结束：UI 线程跳过 close（避免 ANR）");
                return;
            }
        } else {
            ioLock.lock();
        }
        try {
            try {
                mtp.close();
            } catch (Throwable ignored) {
                // 已断开时 close 抛异常无所谓
            }
            try {
                conn.close();
            } catch (Throwable ignored) {
            }
        } finally {
            ioLock.unlock();
        }
    }
}
