package com.lichtcui.pokelauncher.install

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 续传状态文件 + Range 语义。这是「断点续传不能拼出坏包」的最后一道防线，
 * 每一项都对应一个真实会踩的坑（换源、服务端忽略 Range、分片大于总大小…）。
 */
class DownloadStateTest {

    private val url = "https://gh-proxy.com/https://github.com/a/b/releases/download/v1/game.zip"

    @Test
    fun stateJsonRoundTrip() {
        val state = DownloadState(url, "release-abc1234", 507_000_000, 12_345_678, "\"etag-1\"", 1_700_000_000_000)
        val parsed = DownloadState.parse(state.toJson())
        assertEquals(state, parsed)
    }

    @Test
    fun stateJsonUsesStableFieldNames() {
        // 字段名是 R8 改不了的字符串字面量；改动会让老状态文件全部作废（老分片会被丢弃重下）
        val json = JSONObject(DownloadState(url, "t", 10, 5, "e", 1).toJson())
        assertEquals(url, json.getString("url"))
        assertEquals("t", json.getString("tag"))
        assertEquals(10L, json.getLong("totalBytes"))
        assertEquals(5L, json.getLong("receivedBytes"))
        assertEquals("e", json.getString("etag"))
        assertEquals(1L, json.getLong("updatedAt"))
    }

    @Test
    fun parseReturnsNullForGarbageOrMissingUrl() {
        assertNull(DownloadState.parse("not json"))
        assertNull(DownloadState.parse("{}"))
        assertNull(DownloadState.parse("""{"url":""}"""))
    }

    @Test
    fun pendingDownloadExposesPercentAndHost() {
        val pending = PendingDownload(url, "release-abc1234", 250, 1000)
        assertEquals(25, pending.percent)
        assertEquals("gh-proxy.com", pending.host)
    }

    @Test
    fun pendingPercentIsSafeWithoutTotal() {
        assertEquals(0, PendingDownload(url, "t", 250, 0).percent)
    }

    // ------------------------------------------------------------ ResumePolicy

    private fun state(received: Long, total: Long, stateUrl: String = url) =
        DownloadState(stateUrl, "release-abc1234", total, received, "e", 1)

    @Test
    fun restartWhenThereIsNoStateFile() {
        val plan = ResumePolicy.decide(null, 1024, url, 10_000)
        assertTrue(plan is ResumePlan.Restart)
    }

    @Test
    fun restartWhenDownloadSourceChanged() {
        // 换源后旧分片必然对不上（不同镜像可能来自不同 CDN 缓存 / 半个包）
        val plan = ResumePolicy.decide(state(1024, 10_000, "https://other/x.zip"), 1024, url, 10_000)
        assertTrue(plan is ResumePlan.Restart)
    }

    @Test
    fun restartWhenPartialIsEmptyOrLargerThanTotal() {
        assertTrue(ResumePolicy.decide(state(0, 10_000), 0, url, 10_000) is ResumePlan.Restart)
        assertTrue(ResumePolicy.decide(state(1024, 10_000), 20_000, url, 10_000) is ResumePlan.Restart)
    }

    @Test
    fun appendFromCurrentPartialSize() {
        val plan = ResumePolicy.decide(state(131_072, 10_000_000), 131_072, url, 10_000_000)
        assertEquals(ResumePlan.Append(131_072), plan)
    }

    @Test
    fun alreadyCompleteWhenPartialEqualsTotal() {
        val plan = ResumePolicy.decide(state(10_000, 10_000), 10_000, url, 10_000)
        assertEquals(ResumePlan.AlreadyComplete, plan)
    }

    @Test
    fun usesPassedTotalWhenStateHasNone() {
        // 调试包场景：状态文件里的总大小可能是 0，此时以传入的 total 为准
        val plan = ResumePolicy.decide(state(500, 0), 500, url, 10_000)
        assertEquals(ResumePlan.Append(500), plan)
    }

    // ------------------------------------------------------------ RangePolicy

    @Test
    fun rangeHeaderOnlyWhenAppending() {
        assertNull(RangePolicy.requestHeader(0))
        assertEquals("bytes=131072-", RangePolicy.requestHeader(131_072))
    }

    @Test
    fun appendOffsetOnlyFor206() {
        assertEquals(131_072L, RangePolicy.appendOffset(206, 131_072))
        // 服务端忽略 Range 返回 200（或 416 等）：必须从头重写，否则 zip 一定是坏的
        assertEquals(0L, RangePolicy.appendOffset(200, 131_072))
        assertEquals(0L, RangePolicy.appendOffset(416, 131_072))
    }

    @Test
    fun contentRangeStartParsing() {
        assertEquals(100L, RangePolicy.contentRangeStart("bytes 100-999/1000"))
        assertNull(RangePolicy.contentRangeStart(null))
        assertNull(RangePolicy.contentRangeStart("bytes */1000"))
        assertNull(RangePolicy.contentRangeStart("garbage"))
    }

    @Test
    fun rangeMatchesDetectsMismatch() {
        assertTrue(RangePolicy.rangeMatches("bytes 131072-499999/500000", 131_072))
        assertTrue(!RangePolicy.rangeMatches("bytes 0-499999/500000", 131_072))
        assertTrue(!RangePolicy.rangeMatches(null, 131_072))
    }

    @Test
    fun rangeHeaderConstantsMatchHttpSpec() {
        // 三个 header 名写错会静默退化成「每次全量下载」，必须钉死
        assertEquals("Range", RangePolicy.RANGE_HEADER)
        assertEquals("If-Range", RangePolicy.IF_RANGE_HEADER)
        assertEquals("ETag", RangePolicy.ETAG_HEADER)
    }
}
