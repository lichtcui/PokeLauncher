package com.lichtcui.pokelauncher.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 镜像地址构造 + 测速排序（对应鸿蒙版 `buildDownloadUrls` / `rankSources`）。
 * 这里只测纯逻辑：真实网络测速必须真机验证。
 */
class DownloadUrlsTest {

    private val mirrors = listOf("https://m1/", "https://m2/")
    private val raw = "https://github.com/a/b/releases/download/v1/game.zip"

    @Test
    fun candidatesAreMirrorsThenDirectFallback() {
        val urls = DownloadUrls.candidates(raw, mirrors, "")
        assertEquals(listOf("https://m1/$raw", "https://m2/$raw", raw), urls)
    }

    @Test
    fun emptyAssetUrlYieldsNothing() {
        assertEquals(emptyList<String>(), DownloadUrls.candidates("", mirrors, ""))
    }

    @Test
    fun debugAssetSkipsMirrors() {
        // 本地调试包地址本身可达，镜像也代理不了；直接单源
        val urls = DownloadUrls.candidates("http://127.0.0.1:8000/game.zip", mirrors, "http://127.0.0.1:8000/game.zip")
        assertEquals(listOf("http://127.0.0.1:8000/game.zip"), urls)
    }

    @Test
    fun splitSeparatesFallback() {
        val (targets, fallback) = DownloadUrls.split(listOf("a", "b", raw))
        assertEquals(listOf("a", "b"), targets)
        assertEquals(raw, fallback)
    }

    @Test
    fun splitOfSingleCandidateHasNoFallback() {
        // 单源（调试包）没有兜底：它失败了就没有别的源，不能因为「没测速结果」被跳过
        val (targets, fallback) = DownloadUrls.split(listOf("only"))
        assertEquals(listOf("only"), targets)
        assertNull(fallback)
    }

    @Test
    fun rankSortsFastToSlowAndAppendsFallbackLast() {
        val ranked = DownloadUrls.rank(
            listOf(ProbeResult("slow", 10.0), ProbeResult("fast", 900.0), ProbeResult("mid", 100.0)),
            raw
        )
        assertEquals(listOf("fast", "mid", "slow", raw), ranked)
    }

    @Test
    fun rankSkipsFailedMirrorsWhenFallbackExists() {
        // 有直连兜底时，测速失败的镜像直接淘汰，别在下载阶段再撞一次死源浪费时间
        val ranked = DownloadUrls.rank(
            listOf(ProbeResult("dead", -1.0), ProbeResult("ok", 50.0)),
            raw
        )
        assertEquals(listOf("ok", raw), ranked)
    }

    @Test
    fun rankKeepsFailedSourceWhenThereIsNoFallback() {
        val ranked = DownloadUrls.rank(listOf(ProbeResult("only", -1.0)), null)
        assertEquals(listOf("only"), ranked)
    }

    @Test
    fun rankIsStableForEqualSpeeds() {
        val ranked = DownloadUrls.rank(
            listOf(ProbeResult("first", 0.0), ProbeResult("second", 0.0)),
            null
        )
        assertEquals(listOf("first", "second"), ranked)
    }

    @Test
    fun hostOfStripsSchemeAndPath() {
        assertEquals("gh-proxy.com", DownloadUrls.hostOf("https://gh-proxy.com/https://github.com/a.zip"))
        assertEquals("127.0.0.1:8000", DownloadUrls.hostOf("http://127.0.0.1:8000/game.zip"))
        assertEquals("bare.host", DownloadUrls.hostOf("bare.host"))
        assertEquals("", DownloadUrls.hostOf(""))
    }

    @Test
    fun kindOfMarksLastCandidateAsDirect() {
        assertEquals("镜像", DownloadUrls.kindOf(0, 4))
        assertEquals("镜像", DownloadUrls.kindOf(2, 4))
        assertEquals("直连", DownloadUrls.kindOf(3, 4))
        assertEquals("直连", DownloadUrls.kindOf(0, 1))
    }

    @Test
    fun mirrorOfWrapsGithubUrlsOnly() {
        // 首页「下载太慢？」里给用户复制的那一行：GitHub 直链套首个镜像
        assertEquals("https://m1/$raw", DownloadUrls.mirrorOf(raw, mirrors))
        // 非 GitHub 地址不套镜像（镜像基本只代理 github.com）
        assertEquals("", DownloadUrls.mirrorOf("https://gitee.com/a/b.zip", mirrors))
        // 没配镜像时为空（调用方据此只显示原始直链）
        assertEquals("", DownloadUrls.mirrorOf(raw, emptyList()))
        // 空地址不报错，也不会变成「镜像前缀 + 空串」
        assertEquals("", DownloadUrls.mirrorOf("", mirrors))
    }
}
