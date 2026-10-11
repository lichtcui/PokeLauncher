package com.lichtcui.pokelauncher.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注入链的顺序契约（`<head>` 之后的三个 script 标签）。
 *
 * 顺序错了不会报错，但会静默失效：
 * - 存档守卫晚于游戏脚本 → 回填的存档来不及被读取；
 * - `/__cheats__.js` 晚于引导脚本 → 场景捕获钩子注册不上（`window.__pkGameHooks` 已错过派发）；
 * - 引导脚本不是最后一个 → 它持有的 `window.Phaser` 属性描述符会被后来的 `defineProperty` 覆盖，
 *   P0 的 `phaser/game/ready` 计时埋点就再也不触发。
 */
class InjectScriptsTest {

    private val saveGuard = "<script id=\"sg\"></script>"

    @Test
    fun orderIsSaveGuardCheatsBoot() {
        val html = InjectScripts.indexInjections(patchCanvas = true, saveGuardScript = saveGuard, cheatsEnabled = true)
        val iSave = html.indexOf("id=\"sg\"")
        val iCheat = html.indexOf("/__cheats__.js")
        val iBoot = html.indexOf("/__pk_boot.js")
        assertTrue(iSave in 0..<iCheat)
        assertTrue(iCheat < iBoot)
    }

    @Test
    fun cheatsTagIsOmittedWhenNothingEnabled() {
        val html = InjectScripts.indexInjections(patchCanvas = true, saveGuardScript = saveGuard, cheatsEnabled = false)
        assertFalse(html.contains("/__cheats__.js"))
        assertTrue(html.contains("/__pk_boot.js"))
        assertTrue(html.contains("id=\"sg\""))
    }

    @Test
    fun canvasFlagIsForwarded() {
        assertTrue(
            InjectScripts.indexInjections(true, saveGuard, false).contains("?canvas=1")
        )
        assertTrue(
            InjectScripts.indexInjections(false, saveGuard, false).contains("?canvas=0")
        )
    }

    @Test
    fun emptySaveGuardScriptIsAllowed() {
        val html = InjectScripts.indexInjections(patchCanvas = true, saveGuardScript = "", cheatsEnabled = false)
        assertTrue(html.startsWith("<script src=\"/__pk_boot.js"))
    }

    @Test
    fun bootScriptExposesSharedGameHookRegistry() {
        val js = InjectScripts.bootScript(patchCanvas = true)
        // 复用而非重定数组：/__cheats__.js 先执行并 push 了自己的回调
        assertTrue(js.contains("window.__pkGameHooks=window.__pkGameHooks||[];"))
        assertTrue(js.contains("hks[i](inst)"))
    }

    @Test
    fun bootScriptWrapsPhaserExactlyOnce() {
        val js = InjectScripts.bootScript(patchCanvas = true)
        assertEquals(1, Regex("Object\\.defineProperty\\(window,'Phaser'").findAll(js).count())
        assertTrue(js.contains("__pkWrapped"))
    }

    @Test
    fun bootScriptPatchesCanvasOnlyWhenEnabled() {
        assertTrue(InjectScripts.bootScript(true).contains("willReadFrequently=true"))
        assertFalse(InjectScripts.bootScript(false).contains("willReadFrequently=true"))
        assertTrue(InjectScripts.bootScript(false).contains("canvas patch disabled"))
    }

    @Test
    fun bootScriptReportsTimingStages() {
        val js = InjectScripts.bootScript(patchCanvas = true)
        assertTrue(js.contains("report('head')"))
        assertTrue(js.contains("report('phaser')"))
        assertTrue(js.contains("report('game')"))
        assertTrue(js.contains("report('ready')"))
    }

    @Test
    fun cheatsScriptTagHasNoCacheBusterButIsNoCacheOnServer() {
        // 标签不带 query（与鸿蒙版一致），靠服务端 Cache-Control: no-cache 保证每次拿到新脚本
        val html = InjectScripts.indexInjections(true, saveGuard, true)
        assertTrue(html.contains("<script src=\"/__cheats__.js\"></script>"))
    }
}
