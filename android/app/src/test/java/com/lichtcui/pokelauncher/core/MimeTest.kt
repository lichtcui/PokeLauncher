package com.lichtcui.pokelauncher.core

import com.lichtcui.pokelauncher.CasesGen
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MIME 判定错误会直接白屏：Chromium 对 `<script type="module">` 做严格 MIME 校验，
 * `.js` 返回 octet-stream 会被拒绝执行。
 *
 * ⚠️ 映射表与期望值**不写在这里**：唯一真相是 `shared/mime.json`（生成物 `Mime.kt`
 * 与 `CasesGen.MIME_CASES`，与鸿蒙 `Mime.ets` 同一张表）。下面两条边角用例也不重写期望值，
 * 而是拿同一张表自己的结果互相比。
 */
class MimeTest {

    @Test
    fun matchesTheSharedTable() {
        for (c in CasesGen.MIME_CASES) {
            assertEquals(c.path, c.expect, Mime.mimeOf(c.path))
        }
    }

    @Test
    fun extensionMatchIgnoresCase() {
        assertEquals(Mime.mimeOf("sprites/pokemon.png"), Mime.mimeOf("SPRITES/POKEMON.PNG"))
        assertEquals(Mime.mimeOf("game.js"), Mime.mimeOf("Game.JS"))
    }

    @Test
    fun unknownFallsBackToOctetStream() {
        assertEquals(Mime.FALLBACK, Mime.mimeOf("noext"))
        // 只有尾部扩展名参与判定，中间的点不算
        assertEquals(Mime.FALLBACK, Mime.mimeOf("a.js.zzz"))
        assertEquals(Mime.mimeOf("game.js"), Mime.mimeOf("a.zzz.js"))
    }
}
