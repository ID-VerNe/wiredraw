package com.rea.mtpimport.ui

import android.app.Activity
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.platform.ComposeView
import com.rea.mtpimport.Motion

/**
 * Java 侧（`Main`）与 Compose 的唯一接缝。
 *
 * `ComposeView.setContent` 的参数是 composable lambda，从 Java 直接写又长又易错；
 * 这里提供一个静态入口，让 `Main.java` 只留一行 `ComposeHost.mount(this, model)`。
 *
 * `V-37`：系统动画倍率（`ANIMATOR_DURATION_SCALE`）也只在这里读一次并写进 [Motion]。
 * 页面各自读 `Settings` 会把「减少动效」实现成 N 份；这里挂在 **application context** 的
 * `ContentObserver` 上，用户在系统设置里改倍率时下一次组合就生效，不必重启 Activity。
 */
object ComposeHost {

    /** Activity 重建会再 mount 一次；application context 上的观察者只注册一次，不重复挂。 */
    private var observing = false

    @JvmStatic
    fun mount(activity: Activity, model: AppModel): ComposeView {
        val resolver = activity.applicationContext.contentResolver
        fun syncMotionScale() {
            Motion.setDurationScale(
                Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            )
        }
        syncMotionScale()
        synchronized(this) {
            if (!observing) {
                observing = true
                resolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                    false,
                    object : ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) {
                            syncMotionScale()
                        }
                    }
                )
            }
        }

        val view = ComposeView(activity)
        view.setContent { ImportApp(model) }
        activity.setContentView(view)
        return view
    }
}
