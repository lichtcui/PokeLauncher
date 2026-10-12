package com.lichtcui.pokelauncher.ui

import com.lichtcui.pokelauncher.core.SharedUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 游戏页纯逻辑测试（浮层判定 / 返回按钮淡出常量 / 存档回填提示 / 静音兜底脚本）。
 *
 * 本机没有模拟器，Compose 画面跑不起来，`GameOverlay` 这类判定只能靠这层单测锁住：
 * 「加载失败时别让用户一直看『正在载入…』」这类顺序问题，写反了编译器不会报错。
 */
class GameStateTest {

    // ---------------------------------------------------------------- 浮层

    @Test
    fun loadingBeforePageFinished() {
        assertEquals(GameOverlay.LOADING, gameOverlayOf(pageLoaded = false, fatalError = ""))
    }

    @Test
    fun noneAfterPageFinished() {
        assertEquals(GameOverlay.NONE, gameOverlayOf(pageLoaded = true, fatalError = ""))
    }

    @Test
    fun errorWinsOverLoading() {
        // 加载失败时 pageLoaded 永远是 false，判定顺序反了就会永远停在「正在载入游戏…」
        assertEquals(GameOverlay.ERROR, gameOverlayOf(pageLoaded = false, fatalError = "error -2 ERR_CONNECTION_REFUSED"))
    }

    @Test
    fun errorOnLoadedPageStillShows() {
        // 页面加载完成后子资源报错（onReceivedError）也要浮出来
        assertEquals(GameOverlay.ERROR, gameOverlayOf(pageLoaded = true, fatalError = "error 404 /assets/x.png"))
    }

    @Test
    fun loadErrorTextPrefixed() {
        assertEquals("加载出错: error -2 boom", loadErrorText("error -2 boom"))
        assertEquals("", loadErrorText(""))
        // 面板空值时用的占位符不该出现在浮层里
        assertEquals("", loadErrorText("—"))
    }

    // ---------------------------------------------------------------- 存档回填

    @Test
    fun saveRestoredTextOnlyForPositiveCount() {
        assertNull(saveRestoredText(0))
        assertNull(saveRestoredText(-1))
        assertEquals("检测到本地存档缺失，已从快照恢复 3 项数据", saveRestoredText(3))
    }

    // ---------------------------------------------------------------- 返回按钮

    @Test
    fun backButtonConstants() {
        // 数值对应鸿蒙 GamePage：setTimeout 3000ms、opacity(this.backVisible ? 1 : 0.2)
        assertEquals(3000L, BACK_FADE_MS)
        assertEquals(1f, BACK_FULL_ALPHA, 0f)
        assertEquals(0.2f, BACK_FADED_ALPHA, 0f)
    }

    // ---------------------------------------------------------------- 静音

    @Test
    fun muteFallbackScriptTogglesPhaserMute() {
        val on = muteFallbackScript(true)
        val off = muteFallbackScript(false)
        assertTrue(on.contains("window.__pkGame"))
        assertTrue(on.contains("g.sound.mute"))
        assertTrue(on.endsWith("(true);"))
        assertTrue(off.endsWith("(false);"))
        // 兜底脚本要能静默失败：拿不到 Game 实例（还在引导）时不能抛错
        assertTrue(on.contains("catch(e)"))
        // 注入脚本一律不能带 $（Kotlin 模板/JS 模板字符串都会踩到）
        assertFalse(on.contains("$"))
        assertFalse(off.contains("$"))
    }

    // ---------------------------------------------------------------- 背景色

    @Test
    fun gameBackgroundIsOpaqueAndDark() {
        // 色值两端共用 `shared/ui.json` 的 GAME_BG_COLOR（#484050）：以前安卓 0xFF121212、
        // 鸿蒙 #484050，各写一份、谁也不知道该对齐谁。
        val argb = SharedUi.GAME_BG_COLOR
        val alpha = (argb ushr 24) and 0xFF
        assertEquals(0xFF, alpha)
        val red = (argb ushr 16) and 0xFF
        val green = (argb ushr 8) and 0xFF
        val blue = argb and 0xFF
        // 加载期间 WebView 还没出画面，底色必须是暗的（否则会闪一下惨白）
        assertTrue("r=$red", red < 0x80)
        assertTrue("g=$green", green < 0x80)
        assertTrue("b=$blue", blue < 0x80)
    }
}
