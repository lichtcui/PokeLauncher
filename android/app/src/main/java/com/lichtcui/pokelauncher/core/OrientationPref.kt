package com.lichtcui.pokelauncher.core

import android.content.pm.ActivityInfo

/**
 * 屏幕方向偏好（对应鸿蒙版 `model/OrientationPref.ets`）。
 *
 * 语义与鸿蒙版一致：**这个偏好只影响游戏页**，启动器自己的页面在手机上恒竖屏、
 * 平板上跟随系统（实现在 `ui/PageOrientation.kt`）。
 *
 * 落在 [Prefs] 的同一个偏好库里（键名与鸿蒙版相同，两边各自独立存储、不共享文件）。
 */
object OrientationPref {

    const val PORTRAIT = "portrait"
    const val LANDSCAPE = "landscape"
    const val SYSTEM = "system"

    /** 默认竖屏（保持现状） */
    const val DEFAULT = PORTRAIT

    private const val KEY = "orientation"

    /**
     * 未知值一律回退默认值：偏好可能来自旧版本（以后删档位、改命名），
     * 读到一个不认识的字符串不应该让游戏页拿不到方向。
     */
    fun normalize(value: String): String = when (value) {
        PORTRAIT, LANDSCAPE, SYSTEM -> value
        else -> DEFAULT
    }

    fun read(): String = normalize(Prefs.getString(KEY))

    fun save(value: String) {
        Prefs.putString(KEY, normalize(value))
    }

    /**
     * 偏好值 → `Activity.requestedOrientation`。
     * 横屏用 `SENSOR_LANDSCAPE`：允许随传感器翻转左右，与鸿蒙版的 `AUTO_ROTATION_LANDSCAPE` 对齐。
     */
    fun toRequestedOrientation(value: String): Int = when (normalize(value)) {
        LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    /** 界面上的三个分段标签，顺序即展示顺序（设置页与鸿蒙版一致） */
    val SEGMENTS: List<Pair<String, String>> = listOf(
        PORTRAIT to "竖屏",
        LANDSCAPE to "横屏",
        SYSTEM to "跟随系统"
    )
}
