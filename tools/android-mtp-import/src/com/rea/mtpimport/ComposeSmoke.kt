package com.rea.mtpimport

import androidx.compose.runtime.Composable

/**
 * Compose 编译链探针（issue #36）。
 *
 * 本轮只迁移构建体系、不改 UI，所以这里没有任何布局，也没有调用点；
 * 它的唯一作用是让 Compose 编译器真的参与一次构建 —— 如果依赖或编译器没接好，
 * `assembleDebug` 会在编译这个文件时失败，而不是等到以后写界面才发现。
 */
@Composable
fun composeSmoke() {
}
