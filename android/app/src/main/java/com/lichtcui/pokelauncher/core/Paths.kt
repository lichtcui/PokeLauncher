package com.lichtcui.pokelauncher.core

import java.io.ByteArrayOutputStream

/**
 * 请求路径处理。纯函数、无 Android 依赖，**安全边界**，必须有单测
 * （对齐鸿蒙版 `LocalHttpServer.resolveRequestPath` 的位置与职责）。
 */
object Paths {

    /**
     * 规范化请求路径并做穿越判定：剥离 query/fragment → 解码 → 判 `..`。
     * 返回 null 表示应拒绝（403）。
     */
    fun resolveRequestPath(rawPath: String): String? {
        var path = rawPath

        val query = path.indexOf('?')
        if (query >= 0) path = path.substring(0, query)
        val fragment = path.indexOf('#')
        if (fragment >= 0) path = path.substring(0, fragment)

        path = percentDecode(path)

        if (path.isEmpty() || path == "/") path = "/${Const.INDEX_FILE}"
        // 解码后再判：`%2e%2e` 也要被拦住
        if (path.contains("..")) return null
        return path
    }

    /**
     * 百分号解码，语义对齐 JS 的 `decodeURIComponent`：
     * - **不**把 `+` 当空格（`java.net.URLDecoder.decode` 会，所以不能直接用它）；
     * - 遇到非法编码时保留解码前原串（与鸿蒙版一致，后续 `..` 判定仍生效）。
     */
    fun percentDecode(raw: String): String {
        if (raw.indexOf('%') < 0) return raw

        val out = ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            // 需要 raw[i+1] 与 raw[i+2] 都存在
            if (c == '%' && i + 2 < raw.length) {
                val hi = Character.digit(raw[i + 1], 16)
                val lo = Character.digit(raw[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    val value = hi * 16 + lo
                    out.write(value)
                    i += 3
                    continue
                }
                return raw // 非法百分号编码：退回原串
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
