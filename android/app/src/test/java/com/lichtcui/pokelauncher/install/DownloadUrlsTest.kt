package com.lichtcui.pokelauncher.install

import com.lichtcui.pokelauncher.CasesGen
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 镜像地址构造 + 测速排序（对应鸿蒙版 `common/DownloadUrls.ets`）。
 *
 * ⚠️ 期望值**不写在这里**：唯一真相是 `shared/cases/download.json`（生成物 `CasesGen`），
 * 与鸿蒙 `DownloadUrls.test.ets` 跑的是同一套向量。
 * 这里只测纯逻辑：真实网络测速必须真机验证。
 */
class DownloadUrlsTest {

    @Test
    fun candidatesMatchSharedVectors() {
        CasesGen.DOWNLOAD_CANDIDATE_CASES.forEach { c ->
            assertEquals(c.assetUrl, c.expect, DownloadUrls.candidates(c.assetUrl, c.mirrors, c.debugAssetUrl))
        }
    }

    @Test
    fun splitMatchesSharedVectors() {
        CasesGen.DOWNLOAD_SPLIT_CASES.forEach { c ->
            val (probed, fallback) = DownloadUrls.split(c.candidates)
            assertEquals(c.candidates.toString(), c.expectProbed, probed)
            assertEquals(c.candidates.toString(), c.expectFallback, fallback)
        }
    }

    @Test
    fun rankMatchesSharedVectors() {
        CasesGen.DOWNLOAD_RANK_CASES.forEach { c ->
            assertEquals(c.probed.toString(), c.expect, DownloadUrls.rank(c.probed, c.fallback))
        }
    }

    @Test
    fun hostOfMatchesSharedVectors() {
        CasesGen.DOWNLOAD_HOST_CASES.forEach { c ->
            assertEquals(c.url, c.expect, DownloadUrls.hostOf(c.url))
        }
    }

    @Test
    fun kindOfMatchesSharedVectors() {
        CasesGen.DOWNLOAD_KIND_CASES.forEach { c ->
            assertEquals("idx=${c.index} count=${c.count}", c.expect, DownloadUrls.kindOf(c.index, c.count))
        }
    }

    @Test
    fun mirrorOfMatchesSharedVectors() {
        CasesGen.DOWNLOAD_MIRROR_CASES.forEach { c ->
            assertEquals(c.url, c.expect, DownloadUrls.mirrorOf(c.url, c.mirrors))
        }
    }
}
