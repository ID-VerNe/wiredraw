package com.rea.mtpimport.ui

import android.app.Activity
import androidx.compose.ui.platform.ComposeView

/**
 * Java 侧（`Main`）与 Compose 的唯一接缝。
 *
 * `ComposeView.setContent` 的参数是 composable lambda，从 Java 直接写又长又易错；
 * 这里提供一个静态入口，让 `Main.java` 只留一行 `ComposeHost.mount(this, model)`。
 */
object ComposeHost {

    @JvmStatic
    fun mount(activity: Activity, model: AppModel): ComposeView {
        val view = ComposeView(activity)
        view.setContent { ImportApp(model) }
        activity.setContentView(view)
        return view
    }
}
