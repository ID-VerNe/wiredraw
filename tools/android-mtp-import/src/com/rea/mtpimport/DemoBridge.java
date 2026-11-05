package com.rea.mtpimport;

/**
 * 假数据模式的 main 侧接口。实现类只存在于 debug 源集，Main 用反射加载，
 * release 里既没有实现类也没有 `demo_state` 字面量（U-54）。
 */
public interface DemoBridge {

    /** 冷启动时 `--es demo_state <STATE>` 指定的态；没有该 extra 时返回 null。 */
    DemoPayload current();

    /** 应用内切态（详情抽屉里的 demo 入口）；未知态返回 null。 */
    DemoPayload forState(String state);
}
