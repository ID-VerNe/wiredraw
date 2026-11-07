package com.rea.mtpimport;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.util.concurrent.TimeUnit;

/**
 * USB 权限流程（G9a，从 `Main` 抽出）：申请序号、等待门、广播结果判定。
 *
 * 只做权限，不碰会话；`PERMISSION_PENDING` 的界面提示由 {@code showPending} 注入
 * （生产实现仍走 `runOnUiThread`）。
 */
final class UsbPermissionFlow {

    private final PermGate gate = new PermGate();
    private final Context context;
    private final String actionPerm;
    private final String extraSeq;
    private final Log log;
    private final Runnable showPending;

    UsbPermissionFlow(Context context, String actionPerm, String extraSeq,
                      Log log, Runnable showPending) {
        this.context = context;
        this.actionPerm = actionPerm;
        this.extraSeq = extraSeq;
        this.log = log;
        this.showPending = showPending;
    }

    /** 以「内部已知结果」结束本轮等待（见 {@link PermGate#resolve(boolean)}）。 */
    boolean resolve(boolean granted) {
        return gate.resolve(granted);
    }

    /** Activity 销毁时放弃等待。 */
    void cancel() {
        gate.cancel();
    }

    /** 广播回调：序号对不上或已经回答过的回调属于上一次申请，不得释放本次等待（#11）。 */
    void onResult(Intent intent) {
        boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
        int seq = intent.getIntExtra(extraSeq, 0);
        boolean accepted = gate.onResult(seq, granted);
        log.line("USB 权限回调：granted=" + granted + (accepted ? "" : " · 旧请求，已忽略"));
    }

    /**
     * 冷启动扫描线程的等待：无权限时申请并等回答。
     *
     * 闭合「onCancel 落在 showScanning 与 begin() 之间」的 120 秒窗口（第五轮评审 B-1 同源缺陷②）：
     * 取消后绝不发起新的权限申请，否则 resolve(false) 打在还没 begin() 的门上，begin() 一复位又要等满。
     */
    boolean await(UsbManager um, UsbDevice dev, ScanRun run) throws InterruptedException {
        if (run.isCancelled()) return false;
        log.line("尚无 USB 权限，发起申请");
        showPending.run();
        if (run.isCancelled()) return false;
        request(um, dev);
        gate.await(120, TimeUnit.SECONDS);
        return gate.granted() && !run.isCancelled();
    }

    /**
     * 发起一次权限申请（带序号），返回本次序号。
     *
     * 首次申请（{@link #await}）与 `PERMISSION_PENDING` 里点 `Allow` 的重发共用这一段，
     * 避免两条路径的 PendingIntent 细节走偏——B1 的根因就是「Allow 走了另一条路」。
     */
    int request(UsbManager um, UsbDevice dev) {
        int seq = gate.begin();
        // requestCode 必须用 seq（#11 复评）：PendingIntent 记录按 requestCode 区分，
        // 固定用 0 + FLAG_UPDATE_CURRENT 时所有申请共用一条记录，后一次 begin() 会把
        // extras 里的 seq 覆写成新值——上一轮迟到的回调于是带着「当前」seq 到达，
        // 序号判定被绕过，又回到「迟到回调被当成当前回调」。每条申请一条独立记录后，
        // 迟到回调只会携带它自己那一轮的 seq，被 PermGate.onResult 丢弃。
        Intent pi = new Intent(actionPerm).setPackage(context.getPackageName())
                .putExtra(extraSeq, seq);
        // 必须 MUTABLE，别改成 IMMUTABLE（#11 复评，防复发）：系统是拿 fillIn 把
        // EXTRA_PERMISSION_GRANTED 塞进这条广播的，PendingIntentRecord.sendInner 在
        // immutable 时会跳过 fillIn —— 那样即使用户点「允许」，回调读到的也恒为 false，
        // 授权永远失败。记录已按 seq 唯一，不需要 FLAG_UPDATE_CURRENT；
        // 也不要把 MUTABLE 与 IMMUTABLE 同时给，会抛 IllegalArgumentException。
        um.requestPermission(dev,
                PendingIntent.getBroadcast(context, seq, pi, PendingIntent.FLAG_MUTABLE));
        return seq;
    }
}
