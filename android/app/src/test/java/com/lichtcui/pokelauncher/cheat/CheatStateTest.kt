package com.lichtcui.pokelauncher.cheat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 参数调整状态机（内存部分）。
 *
 * JVM 单测里 `Prefs` 未初始化 → `store()` 返回 null → 所有写操作静默跳过，
 * 因此这里验证的是「内存快照 + 脚本生成」的联动；
 * `load()` 在无偏好时会把状态清回默认值，正好当每个用例的重置手段。
 */
class CheatStateTest {

    @Before
    fun reset() {
        CheatState.load()
    }

    @Test
    fun defaultsAreOff() {
        assertFalse(CheatState.isMasterEnabled())
        assertFalse(CheatState.hasAnyEnabled())
        assertEquals("", CheatState.buildBootScript())
    }

    @Test
    fun masterOnWithoutIdsIsStillNothing() {
        CheatState.setMaster(true)
        assertTrue(CheatState.isMasterEnabled())
        assertFalse(CheatState.hasAnyEnabled())
        assertEquals("", CheatState.buildBootScript())
    }

    @Test
    fun togglingEnablesCheat() {
        CheatState.setMaster(true)
        CheatState.toggle("candy")
        assertTrue(CheatState.isEnabled("candy"))
        assertEquals(listOf("candy"), CheatState.enabledIds())
        assertTrue(CheatState.hasAnyEnabled())
        assertTrue(CheatState.buildBootScript().contains("__cheatCandy"))
    }

    @Test
    fun toggleAloneDoesNotEnableWhenMasterOff() {
        // 与鸿蒙版一致：hasAnyEnabled() = 总开关 && 至少一条启用
        CheatState.toggle("candy")
        assertTrue(CheatState.isEnabled("candy"))
        assertFalse(CheatState.hasAnyEnabled())
        assertEquals("", CheatState.buildBootScript())
    }

    @Test
    fun togglingTwiceDisablesCheat() {
        CheatState.setMaster(true)
        CheatState.toggle("candy")
        CheatState.toggle("candy")
        assertFalse(CheatState.isEnabled("candy"))
        assertEquals("", CheatState.buildBootScript())
    }

    @Test
    fun masterOffSuppressesScriptEvenWithIds() {
        CheatState.setMaster(true)
        CheatState.toggle("candy")
        CheatState.setMaster(false)
        assertFalse(CheatState.hasAnyEnabled())
        assertEquals("", CheatState.buildBootScript())
    }

    @Test
    fun clearEnabledKeepsMaster() {
        CheatState.setMaster(true)
        CheatState.toggle("candy")
        CheatState.clearEnabled()
        assertTrue(CheatState.isMasterEnabled())
        assertFalse(CheatState.hasAnyEnabled())
        assertEquals("", CheatState.buildBootScript())
    }

    @Test
    fun paramDefaultsToRegistryDefault() {
        assertEquals(10.0, CheatState.getParam("candy"), 0.0)
    }

    @Test
    fun setParamIsReadBack() {
        CheatState.setMaster(true)
        CheatState.toggle("candy")
        CheatState.setParam("candy", 5.0)
        assertEquals(5.0, CheatState.getParam("candy"), 0.0)
        assertTrue(CheatState.buildBootScript().contains("\"candy\":5"))
    }

    @Test
    fun setParamIsClampedOnRead() {
        CheatState.setMaster(true)
        CheatState.toggle("candy")
        CheatState.setParam("candy", 9999.0)
        assertEquals(100.0, CheatState.getParam("candy"), 0.0)
        assertTrue(CheatState.buildBootScript().contains("\"candy\":100"))
    }

    @Test
    fun setParamLiveAffectsScript() {
        CheatState.setMaster(true)
        CheatState.toggle("candy")
        CheatState.setParamLive("candy", 3.0)
        assertEquals(3.0, CheatState.getParam("candy"), 0.0)
        assertTrue(CheatState.buildBootScript().contains("\"candy\":3"))
    }

    @Test
    fun unknownCheatParamIsZero() {
        assertEquals(0.0, CheatState.getParam("no-such-cheat"), 0.0)
    }

    @Test
    fun enabledIdsSurviveScriptBuild() {
        CheatState.setMaster(true)
        CheatState.toggle("exp")
        CheatState.toggle("catch")
        val js = CheatState.buildBootScript()
        assertTrue(js.contains("__cheatExp"))
        assertTrue(js.contains("__cheatCatch"))
        // 未启用的条目不注入
        assertFalse(js.contains("__cheatMoney"))
    }
}
