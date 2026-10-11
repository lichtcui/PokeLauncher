package com.lichtcui.pokelauncher.cheat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/__cheats__.js` 的内容生成（对应鸿蒙版 `CheatState.ets` 的 `buildBootScript()`）。
 * 这里断言的是「注入脚本的结构契约」——游戏内行为只能在真机上验证。
 */
class CheatScriptsTest {

    @Test
    fun masterOff_producesNothing() {
        assertEquals("", CheatScripts.buildBootScript(false, listOf("candy"), mapOf("candy" to "5")))
    }

    @Test
    fun noEnabledIds_producesNothing() {
        assertEquals("", CheatScripts.buildBootScript(true, emptyList(), emptyMap()))
    }

    @Test
    fun unknownId_producesNothing() {
        // 偏好里可能残留已被删除的条目 id：不应产出空壳脚本
        assertEquals("", CheatScripts.buildBootScript(true, listOf("no-such-cheat"), emptyMap()))
    }

    @Test
    fun wrapsInIifeWithParams() {
        val js = CheatScripts.buildBootScript(true, listOf("candy"), mapOf("candy" to "5"))
        assertTrue(js.startsWith("(function(){var __CHEAT_PARAMS__="))
        assertTrue(js.endsWith("})();"))
        assertTrue(js.contains("var __CHEAT_PARAMS__={\"candy\":5};"))
    }

    @Test
    fun includesSceneRuntime() {
        val js = CheatScripts.buildBootScript(true, listOf("candy"), emptyMap())
        assertTrue(js.contains("var __cs=null,__ccb=[];"))
        assertTrue(js.contains("window.__pkGameHooks.push("))
    }

    @Test
    fun paramUsesDefaultWhenMissing() {
        val js = CheatScripts.buildBootScript(true, listOf("exp"), emptyMap())
        assertTrue(js.contains("\"exp\":10"))
    }

    @Test
    fun paramIsClampedToMax() {
        val js = CheatScripts.buildBootScript(true, listOf("money"), mapOf("money" to "9999"))
        assertTrue(js.contains("\"money\":100"))
    }

    @Test
    fun paramIsClampedToMin() {
        val js = CheatScripts.buildBootScript(true, listOf("money"), mapOf("money" to "0"))
        assertTrue(js.contains("\"money\":1"))
    }

    @Test
    fun paramIsClampedToMinForTier() {
        val js = CheatScripts.buildBootScript(true, listOf("reward-tier"), mapOf("rewardTier" to "-5"))
        assertTrue(js.contains("\"rewardTier\":0"))
    }

    @Test
    fun onlyEnabledCheatScriptsAreInjected() {
        val js = CheatScripts.buildBootScript(true, listOf("exp"), emptyMap())
        assertTrue(js.contains("__cheatExp"))
        assertFalse(js.contains("__cheatCandy"))
        assertFalse(js.contains("__cheatMoney"))
    }

    @Test
    fun eachScriptIsWrappedInTryCatch() {
        val js = CheatScripts.buildBootScript(true, listOf("candy", "catch"), emptyMap())
        assertTrue(js.contains("}catch(e){console.log(\"CHEAT err candy \"+e);}"))
        assertTrue(js.contains("}catch(e){console.log(\"CHEAT err catch \"+e);}"))
    }

    @Test
    fun cheatWithoutParamProducesEmptyParamsObject() {
        val js = CheatScripts.buildBootScript(true, listOf("catch"), emptyMap())
        assertTrue(js.contains("var __CHEAT_PARAMS__={};"))
        assertTrue(js.contains("__onPhase(\"AttemptCapturePhase\""))
    }

    @Test
    fun enabledIdsOrderFollowsRegistry() {
        // 注册表顺序（candy 在 money 之前）决定拼接顺序，与偏好里的顺序无关
        val js = CheatScripts.buildBootScript(true, listOf("money", "candy"), emptyMap())
        val candy = js.indexOf("__cheatCandy")
        val money = js.indexOf("__cheatMoney")
        assertTrue(candy in 1..<money)
    }

    @Test
    fun allParamsAreEmittedForMultiParamCheats() {
        val js = CheatScripts.buildBootScript(
            true, listOf("candy", "exp", "money", "reward-tier"),
            mapOf("candy" to "1", "exp" to "2", "money" to "3", "rewardTier" to "4")
        )
        assertTrue(js.contains("\"candy\":1"))
        assertTrue(js.contains("\"exp\":2"))
        assertTrue(js.contains("\"money\":3"))
        assertTrue(js.contains("\"rewardTier\":4"))
    }
}
