package com.lichtcui.pokelauncher.core

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 屏幕方向偏好：归一化 / 落盘降级 / 到 `requestedOrientation` 的映射。
 *
 * ⚠️ 这些用例跑在 JVM 上，[Prefs] 没有 `init`（没有 Context）→ 读写全部降级为
 * 「不持久化」：这正是要验的一条路径（首次进入设置页、或 App 启动早期读偏好时
 * 不能因为拿不到偏好库就崩）。
 */
class OrientationPrefTest {

    @Test
    fun knownValuesPassThrough() {
        assertEquals("portrait", OrientationPref.normalize("portrait"))
        assertEquals("landscape", OrientationPref.normalize("landscape"))
        assertEquals("system", OrientationPref.normalize("system"))
    }

    @Test
    fun unknownOrEmptyFallsBackToPortrait() {
        // 旧版本可能存过别的值；认不出来时不能返回空串（否则游戏页拿不到方向）
        assertEquals(OrientationPref.PORTRAIT, OrientationPref.normalize(""))
        assertEquals(OrientationPref.PORTRAIT, OrientationPref.normalize("Portrait"))
        assertEquals(OrientationPref.PORTRAIT, OrientationPref.normalize("sensor"))
        assertEquals(OrientationPref.PORTRAIT, OrientationPref.normalize("landscape "))
        assertEquals(OrientationPref.DEFAULT, OrientationPref.normalize("???"))
    }

    @Test
    fun mapsToRequestedOrientation() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, OrientationPref.toRequestedOrientation("portrait"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, OrientationPref.toRequestedOrientation("landscape"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, OrientationPref.toRequestedOrientation("system"))
    }

    @Test
    fun unknownValueMapsToPortraitNotUnspecified() {
        // 兜底必须是「竖屏」而不是「跟随系统」：横竖屏切换会重建 Activity，猜错代价高
        val mapping = OrientationPref.toRequestedOrientation("nonsense")
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, mapping)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, OrientationPref.toRequestedOrientation(""))
    }

    @Test
    fun segmentsAreStableAndOrdered() {
        // 分段控件的展示顺序 = 与鸿蒙版一致：竖屏 / 横屏 / 跟随系统
        assertEquals(
            listOf("portrait", "landscape", "system"),
            OrientationPref.SEGMENTS.map { it.first }
        )
        assertEquals(
            listOf("竖屏", "横屏", "跟随系统"),
            OrientationPref.SEGMENTS.map { it.second }
        )
    }

    @Test
    fun readWithoutPrefsStoreFallsBackToDefault() {
        // 没 init Prefs → store() 为 null → 读不到就是默认值
        assertEquals(OrientationPref.DEFAULT, OrientationPref.read())
    }

    @Test
    fun saveWithoutPrefsStoreDoesNotThrow() {
        // 无偏好库时写操作必须是静默 no-op（不能崩、不能抛）
        OrientationPref.save("landscape")
        OrientationPref.save("nonsense")
        assertEquals(OrientationPref.DEFAULT, OrientationPref.read())
    }
}
