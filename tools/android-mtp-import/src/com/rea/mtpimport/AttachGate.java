package com.rea.mtpimport;

/**
 * 一次插线事件（`ACTION_USB_DEVICE_ATTACHED`）的准入判定（纯 JDK，JVM 自检可钉住）。
 *
 * 为什么要单独抽出来（#17）：
 * 1. 同一次插拔可能从两条路进应用——系统按默认处理者直接 `startActivity`（前台走
 *    `onNewIntent`，冷启动走 `onCreate` 的那份 intent），以及运行时注册的广播接收器。
 *    两条路必须收敛到同一个判定，否则同一次插拔会开两个 MTP 会话（相机会话同一时刻只能有一个）。
 * 2. 扫描互斥原来只靠「按钮置灰」，只挡得住人手点，挡不住事件重复投递。
 *
 * 拒绝路径**不得**占用扫描锁：无设备 / 传输中都不是「正在扫描」，占了锁会把后续真正
 * 可用的 attach 永久拒掉（自检里有对应用例）。
 */
public final class AttachGate {

    /** 准入结果。三个 `NOTHING_*` 都表示「不做事」，分开只为日志能说清为什么没动。 */
    public enum Admit {
        /** 可以开始扫描；同时已持有扫描锁。 */
        GO,
        /** 没找到索尼机身：不扫，也不占锁。 */
        NOTHING_NO_DEVICE,
        /** 传输进行中：不打断传输，不占锁。 */
        NOTHING_TRANSFERRING,
        /** 已经在扫描：忽略本次事件，不占锁。 */
        NOTHING_ALREADY_SCANNING
    }

    private boolean scanning;

    /** 判定这一次 attach 事件能否开始扫描；返回 {@link Admit#GO} 时同时持有扫描锁。 */
    public synchronized Admit admit(boolean devicePresent, boolean transferring) {
        if (!devicePresent) return Admit.NOTHING_NO_DEVICE;
        if (transferring) return Admit.NOTHING_TRANSFERRING;
        if (scanning) return Admit.NOTHING_ALREADY_SCANNING;
        scanning = true;
        return Admit.GO;
    }

    /** 扫描结束（成功或失败）释放锁。 */
    public synchronized void finish() {
        scanning = false;
    }

    public synchronized boolean isScanning() {
        return scanning;
    }
}
