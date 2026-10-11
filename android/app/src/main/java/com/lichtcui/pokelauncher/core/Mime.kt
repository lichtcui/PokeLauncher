package com.lichtcui.pokelauncher.core

/**
 * 扩展名 → MIME 映射。
 *
 * 关键：WebView 基于 Chromium，对 `<script type="module">` 有严格 MIME 校验，
 * `.js` 返回错误 MIME 会导致白屏，必须返回 `text/javascript`。
 */
object Mime {

    private val MAP = mapOf(
        "html" to "text/html",
        "htm" to "text/html",
        "js" to "text/javascript",
        "mjs" to "text/javascript",
        "css" to "text/css",
        "json" to "application/json",
        "webmanifest" to "application/manifest+json",
        "wasm" to "application/wasm",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "svg" to "image/svg+xml",
        "ico" to "image/x-icon",
        "bmp" to "image/bmp",
        "mp3" to "audio/mpeg",
        "ogg" to "audio/ogg",
        "m4a" to "audio/mp4",
        "wav" to "audio/wav",
        "mp4" to "video/mp4",
        "webm" to "video/webm",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "txt" to "text/plain",
        "xml" to "text/xml"
    )

    fun mimeOf(path: String): String {
        val dot = path.lastIndexOf('.')
        if (dot < 0) return "application/octet-stream"
        val ext = path.substring(dot + 1).lowercase()
        return MAP[ext] ?: "application/octet-stream"
    }
}
