package com.lichtcui.pokelauncher.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.lichtcui.pokelauncher.core.OrientationPref

/**
 * 按当前页面套用屏幕方向（对应鸿蒙版 `WindowHolder.setOrientation` 的用法）：
 * **游戏页**用用户在设置页选的方向；**其余页面**在手机上恒竖屏、平板上跟随系统
 * （鸿蒙版在 `Index.onPageShow` / `Settings.onPageShow` 里做的就是这件事）。
 *
 * 之所以放在根组件而不是 [MainActivity]：页面切换（进/出游戏、进设置页）要立即生效，
 * 而 Activity 只创建一次。
 *
 * `configChanges` 已在 manifest 里声明，所以改 `requestedOrientation` 不会重建 Activity。
 */
@Composable
internal fun ApplyPageOrientation(gamePageActive: Boolean) {
    val context = LocalContext.current
    val activity = context.findActivity()
    LaunchedEffect(gamePageActive, activity) {
        val target = activity ?: return@LaunchedEffect
        val orientation = if (gamePageActive) {
            OrientationPref.toRequestedOrientation(OrientationPref.read())
        } else if (isTablet(context)) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (target.requestedOrientation != orientation) {
            // 某些 ROM 上设置方向会抛异常（多窗口模式下尤其），不能因此崩掉界面
            runCatching { target.requestedOrientation = orientation }
        }
    }
}

/** 平板等大屏：首页/设置页不强制竖屏（对应鸿蒙版 `WindowHolder.isTablet`） */
internal fun isTablet(context: Context): Boolean =
    context.resources.configuration.smallestScreenWidthDp >= 600

/** Compose 拿到的 `LocalContext` 可能是 ContextWrapper，逐层剥到 Activity */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
