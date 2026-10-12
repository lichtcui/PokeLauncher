package com.lichtcui.pokelauncher.core

/**
 * 扩展名 → MIME 映射（本地 HTTP 服务的 Content-Type）。
 *
 * ⚠️ **本文件由 `scripts/gen-shared.mjs` 自动生成，不要手改**：唯一真相在仓库根 `shared/mime.json`。
 *
 * 关键：WebView 基于 Chromium，对 `<script type="module">` 做严格 MIME 校验，
 * `.js` 返回错 MIME 会导致白屏，必须返回 `text/javascript`。
 */
object Mime {

    /** 无扩展名 / 未知扩展名的退化值 */
    const val FALLBACK = "application/octet-stream"

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
        if (dot < 0) return FALLBACK
        val ext = path.substring(dot + 1).lowercase()
        return MAP[ext] ?: FALLBACK
    }
}
