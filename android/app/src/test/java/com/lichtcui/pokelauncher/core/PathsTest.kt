package com.lichtcui.pokelauncher.core

import com.lichtcui.pokelauncher.CasesGen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Paths] 是本地服务的安全边界（路径穿越判定），必须有覆盖。
 *
 * ⚠️ 期望值**不写在这里**：唯一真相是 `shared/cases/paths.json`（生成物 `CasesGen`），
 * 与鸿蒙 `LocalHttpServer.test` 跑的是同一套向量。
 */
class PathsTest {

    @Test
    fun matchesTheSharedVectors() {
        for (c in CasesGen.PATH_CASES) {
            assertEquals("input=${c.input}", c.expect, Paths.resolveRequestPath(c.input))
        }
    }

    @Test
    fun stillContainsTraversalRejections() {
        // 防止有人把向量里的拒绝项删光，变成「全部放行」还全绿
        val rejected = CasesGen.PATH_CASES.count { it.expect == null }
        assertTrue("rejected=$rejected", rejected >= 8)
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
        // 非法百分号编码退回原串，后续 `..` 判定仍然生效
        assertEquals("%zz", Paths.percentDecode("%zz"))
        assertEquals("%4", Paths.percentDecode("%4"))
        assertEquals("%", Paths.percentDecode("%"))
        // 合法编码正常解码；`/..%2fx` 被拦住这件事由 shared/cases/paths.json 的向量覆盖
        assertEquals("../x", Paths.percentDecode("..%2fx"))
    }

    @Test
    fun percentDecodeIsNoopWithoutPercent() {
        assertEquals("/assets/a.js", Paths.percentDecode("/assets/a.js"))
        assertEquals("", Paths.percentDecode(""))
    }

    @Test
    fun percentDecodeRejectsInvalidUtf8LikeDecodeUriComponent() {
        // `decodeURIComponent('%E4')` 抛 URIError → 鸿蒙保留原串；安卓这边必须一致，
        // 否则同一个 URL 两端行为不同（旧实现会解成 U+FFFD）
        assertEquals("%E4", Paths.percentDecode("%E4"))
        // 混在合法串里的非法字节同样整串退回（不是部分解码）
        assertEquals("a%E4%20b", Paths.percentDecode("a%E4%20b"))
    }
}
