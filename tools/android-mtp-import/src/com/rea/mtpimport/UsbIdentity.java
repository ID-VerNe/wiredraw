package com.rea.mtpimport;

/**
 * USB 设备身份判定的唯一归属（#267）。
 *
 * 纯 JDK：只吃 int / String，不 import android.*，这样 `SelfTest` 能用普通 JVM 钉住判定规则。
 * Android 侧（`MtpSession.findSony` 与 `Main` 的 `receiver`）负责从 `UsbDevice` / `Intent`
 * 提取字段再交给这里，避免同一套规则在 Android 与界面两侧各写一遍。
 */
public final class UsbIdentity {

    /** 索尼的 USB Vendor ID。 */
    public static final int SONY_VID = 0x054C;

    /** USB 接口类：Still Image（PTP / MTP 相机）。 */
    public static final int CLASS_STILL_IMAGE = 6;

    /** USB 接口类：Vendor Specific（Android MTP 复合设备常见）。 */
    public static final int CLASS_VENDOR_SPECIFIC = 0xFF;

    private UsbIdentity() {
    }

    /**
     * 判定是不是「索尼相机」的 USB 设备。
     *
     * 只认 VID 会把同厂商的 U 盘 / 读卡器 / 复合设备的非 MTP 接口一起放进来（#267），
     * 所以再加一层接口类判定：接口里含 Still Image（6）或 Vendor Specific（0xFF）才算相机。
     *
     * 接口信息为空时**回退为只看 VID**：真机偶尔拿不到接口列表，宁可放行也不能把相机挡掉
     * ——挡掉的代价是「插线没反应」，比误判更糟。
     */
    public static boolean isSonyCamera(int vendorId, int[] interfaceClasses) {
        if (vendorId != SONY_VID) return false;
        if (interfaceClasses == null || interfaceClasses.length == 0) return true;
        for (int c : interfaceClasses) {
            if (c == CLASS_STILL_IMAGE || c == CLASS_VENDOR_SPECIFIC) return true;
        }
        return false;
    }

    /**
     * 判定「拔掉的这台设备」是不是当前会话那台相机。
     *
     * `goneName` 为 null 表示广播没带 `EXTRA_DEVICE`：无法排除，按旧行为置断线（B1 的真机
     * 路径不能因为一次空 extra 就失效）。`activeName` 为 null 表示当前没有会话，是否置断线
     * 交给 {@code UiState.detachMarksTransfer} 的状态守卫。
     *
     * 用 `deviceName`（如 `1-1`）而不是 `deviceId`：后者重插会变，前者在同一次连接内稳定。
     */
    public static boolean shouldMarkDetached(String goneName, String activeName) {
        if (goneName == null || activeName == null) return true;
        return goneName.equals(activeName);
    }
}
