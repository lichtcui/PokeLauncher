package com.lichtcui.pokelauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [Paths] 是本地服务的安全边界（路径穿越判定），必须有覆盖。
 * 用例逐条对齐鸿蒙版 `LocalHttpServer` 的行为，两端保持一致。
 */
class PathsTest {

    @Test
    fun rootDirectoryServesIndex() {
        assertEquals("/index.html", Paths.resolveRequestPath("/"))
        assertEquals("/index.html", Paths.resolveRequestPath(""))
    }

    @Test
    fun stripsQueryAndFragment() {
        assertEquals("/assets/a.js", Paths.resolveRequestPath("/assets/a.js?v=2"))
        assertEquals("/assets/a.js", Paths.resolveRequestPath("/assets/a.js#hash"))
        assertEquals("/index.html", Paths.resolveRequestPath("/?x=1"))
    }

    @Test
    fun rejectsTraversal() {
        assertNull(Paths.resolveRequestPath("/../etc/passwd"))
        assertNull(Paths.resolveRequestPath("/assets/../../secret"))
        // 解码之后再判定：编码过的 `..` 同样要拦住
        assertNull(Paths.resolveRequestPath("/%2e%2e/etc/passwd"))
        assertNull(Paths.resolveRequestPath("/%2E%2E/secret"))
    }

    @Test
    fun keepsOrdinaryPaths() {
        assertEquals("/assets/sprites/pokemon.png", Paths.resolveRequestPath("/assets/sprites/pokemon.png"))
        assertEquals("/a.b.c.js", Paths.resolveRequestPath("/a.b.c.js"))
    }

    @Test
    fun percentDecodeMatchesDecodeUriComponent() {
        assertEquals("a b", Paths.percentDecode("a%20b"))
        assertEquals("..", Paths.percentDecode("%2e%2e"))
        assertEquals("中", Paths.percentDecode("%E4%B8%AD"))
        // URLDecoder 会把 `+` 解成空格，decodeURIComponent 不会——所以不能直接用 URLDecoder
        assertEquals("a+b", Paths.percentDecode("a+b"))
    }

    @Test
    fun percentDecodeKeepsInvalidInput() {
        // 非法编码退回原串，后续 `..` 判定仍然生效
        assertEquals("%zz", Paths.percentDecode("%zz"))
        assertEquals("%4", Paths.percentDecode("%4"))
        assertEquals("%", Paths.percentDecode("%"))
        // 合法编码正常解码，再由 resolveRequestPath 拦住
        assertEquals("../x", Paths.percentDecode("..%2fx"))
        assertNull(Paths.resolveRequestPath("/..%2fx"))
    }

    @Test
    fun percentDecodeIsNoopWithoutPercent() {
        assertEquals("/assets/a.js", Paths.percentDecode("/assets/a.js"))
        assertEquals("", Paths.percentDecode(""))
    }
}
