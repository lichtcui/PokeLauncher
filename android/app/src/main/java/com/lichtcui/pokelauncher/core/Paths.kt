package com.lichtcui.pokelauncher.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

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
     * 百分号解码，语义对齐 JS 的 `decodeURIComponent`（鸿蒙侧直接用的就是它）：
     * - **不**把 `+` 当空格（`java.net.URLDecoder.decode` 会，所以不能直接用它）；
     * - 遇到非法百分号编码（`%zz` / `%4`）→ 保留解码前原串；
     * - 解出的字节不是合法 UTF-8（如 `%E4`）→ **同样保留原串**。
     *
     * 第三条是两端曾经分叉的地方：`decodeURIComponent('%E4')` 会抛 `URIError`，鸿蒙的 catch
     * 保留原串（于是没有字面 `..`、放行后按字面文件名 404）；而安卓旧实现把它解成 U+FFFD，
     * 虽然同样不会误放行，但行为已经和另一端不同（也丢失了真实文件名）。
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
        return decodeStrictUtf8(out.toByteArray()) ?: raw
    }

    /**
     * 严格 UTF-8 解码：遇到不完整/非法的字节序列返回 null
     * （对应 `decodeURIComponent` 的 `URIError`，不是 Java 默认解码器的 U+FFFD 替换）。
     */
    private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }
}
