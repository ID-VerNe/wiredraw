package com.rea.mtpimport;

import android.content.Intent;

/**
 * 假数据模式的 debug 入口（U-54）。release 源集里没有这个类，也没有 `demo_state` 字面量，
 * 所以 release APK 上 `aapt dump` / 字符串检查都搜不到入口。
 *
 * 用法：
 * <pre>
 * adb shell am start -n com.rea.mtpimport/.Main --es demo_state IMPORTING
 * </pre>
 * 不传 `demo_state` 时 {@link #create(Intent)} 返回 null，应用走真实 USB 路径。
 *
 * 之所以单独放一个类、再让 {@link Main} 反射加载，是为了让 release 构建里连
 * 「编译期引用」都不存在：Main 只认 {@link DemoBridge} 接口。
 */
public final class DemoEntry implements DemoBridge {

    /** 冷启动 extra 名（只存在于 debug 源集）。 */
    private static final String EXTRA_STATE = "demo_state";

    private final String rawState;

    private DemoEntry(String rawState) {
        this.rawState = rawState;
    }

    /** 有 `demo_state` 且取值合法时返回实现，否则返回 null。 */
    public static DemoBridge create(Intent intent) {
        if (intent == null) return null;
        String raw = intent.getStringExtra(EXTRA_STATE);
        if (raw == null || raw.trim().isEmpty()) return null;
        if (UiState.parse(raw) == null) return null;
        return new DemoEntry(raw);
    }

    @Override
    public DemoPayload current() {
        return payloadOf(rawState);
    }

    @Override
    public DemoPayload forState(String state) {
        return payloadOf(state);
    }

    private static DemoPayload payloadOf(String name) {
        UiState.State state = UiState.parse(name);
        return state == null ? null : DemoTree.payload(state);
    }
}
