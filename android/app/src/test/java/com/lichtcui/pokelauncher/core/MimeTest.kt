package com.lichtcui.pokelauncher.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MIME 判定错误会直接白屏：Chromium 对 `<script type="module">` 做严格 MIME 校验，
 * `.js` 返回 octet-stream 会被拒绝执行。
 */
class MimeTest {

    @Test
    fun javascriptMustBeTextJavascript() {
        assertEquals("text/javascript", Mime.mimeOf("game.js"))
        assertEquals("text/javascript", Mime.mimeOf("assets/a/b.mjs"))
    }

    @Test
    fun wasmAndJson() {
        assertEquals("application/wasm", Mime.mimeOf("lib.wasm"))
        assertEquals("application/json", Mime.mimeOf("data.json"))
        assertEquals("application/manifest+json", Mime.mimeOf("manifest.webmanifest"))
    }

    @Test
    fun webAssets() {
        assertEquals("text/html", Mime.mimeOf("index.html"))
        assertEquals("text/css", Mime.mimeOf("style.css"))
        assertEquals("image/png", Mime.mimeOf("sprites/pokemon.png"))
        assertEquals("image/svg+xml", Mime.mimeOf("logo.svg"))
        assertEquals("audio/mpeg", Mime.mimeOf("bgm.mp3"))
        assertEquals("font/woff2", Mime.mimeOf("font.woff2"))
    }

    @Test
    fun extensionMatchIgnoresCase() {
        assertEquals("image/png", Mime.mimeOf("A.PNG"))
        assertEquals("text/javascript", Mime.mimeOf("Game.JS"))
    }

    @Test
    fun unknownFallsBackToOctetStream() {
        assertEquals("application/octet-stream", Mime.mimeOf("noext"))
        assertEquals("application/octet-stream", Mime.mimeOf("weird.zzz"))
        // 只有尾部扩展名参与判定，中间的点不算
        assertEquals("application/octet-stream", Mime.mimeOf("a.js.zzz"))
        assertEquals("text/javascript", Mime.mimeOf("a.zzz.js"))
    }
}
