package com.rea.mtpprobe;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.mtp.MtpDevice;
import android.mtp.MtpDeviceInfo;
import android.mtp.MtpObjectInfo;
import android.mtp.MtpStorageInfo;

import java.io.IOException;

/**
 * 只读门面（G12 / P1-1 / issue #222）。
 *
 * 本包唯一持有 {@link MtpDevice} 的类。`AGENTS.md` §2 红线 2 要求探针不得下发写操作；
 * 把裸设备句柄收在这里之后，其余源码在**编译期**就拿不到 `deleteObject` / `sendObject` /
 * `setDevicePropValue` 这类入口，只读从「注释约定」变成「结构不可达」。
 *
 * 允许下发的 opcode 只有这些（与下方方法一一对应，新增方法前先回 issue 讨论）：
 * GetDeviceInfo / OpenSession / CloseSession / GetStorageIDs / GetStorageInfo /
 * GetObjectHandles / GetObjectInfo / GetObjectInfo(0xFFFFC001) / GetPartialObject /
 * GetObject（`importFile` 整对象下载）/ 索尼私有引导序列。
 *
 * `build.sh` 的静态检查会拦下「`MtpDevice` 出现在本文件之外」与「写 / 破坏性 API」，
 * 所以这里不需要再维护一份运行期白名单数组（那会变成第二份事实源）。
 */
final class ReadOnlyMtp {

    private final MtpDevice mtp;

    private ReadOnlyMtp(MtpDevice mtp) {
        this.mtp = mtp;
    }

    /**
     * 建会话。失败返回 null —— 连接（conn）由调用方负责关闭，本类不持它。
     */
    static ReadOnlyMtp open(UsbDevice dev, UsbDeviceConnection conn) {
        MtpDevice mtp = new MtpDevice(dev);
        return mtp.open(conn) ? new ReadOnlyMtp(mtp) : null;
    }

    MtpDeviceInfo getDeviceInfo() {
        return mtp.getDeviceInfo();
    }

    int[] getStorageIds() {
        return mtp.getStorageIds();
    }

    MtpStorageInfo getStorageInfo(int storageId) {
        return mtp.getStorageInfo(storageId);
    }

    int[] getObjectHandles(int storageId, int format, int parent) {
        return mtp.getObjectHandles(storageId, format, parent);
    }

    MtpObjectInfo getObjectInfo(int handle) {
        return mtp.getObjectInfo(handle);
    }

    /** GetPartialObject：按 offset / 长度分块读，只读。 */
    long getPartialObject(int handle, long offset, long size, byte[] buffer) throws IOException {
        return mtp.getPartialObject(handle, offset, size, buffer);
    }

    /** GetObject：把相机侧对象整文件下到手机私有目录，只读。 */
    boolean importFile(int handle, String path) {
        return mtp.importFile(handle, path);
    }

    void close() {
        mtp.close();
    }
}
