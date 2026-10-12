package com.lichtcui.pokelauncher.server

import com.lichtcui.pokelauncher.CasesGen
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 本地服务的 `Cache-Control`：HTML / service-worker 被缓存住的症状是
 * 「改了注入内容却看不到变化」，只有真机才看得见——所以由向量守着。
 *
 * ⚠️ 期望值**不写在这里**：唯一真相是 `shared/cases/http.json`（生成物 `CasesGen`），
 * 与鸿蒙 `LocalHttpServer.test` 跑的是同一套向量。
 */
class HttpCacheTest {

    @Test
    fun matchesTheSharedVectors() {
        for (c in CasesGen.CACHE_CASES) {
            assertEquals("path=${c.path}", c.expect, HttpCache.cacheControl(c.path))
        }
    }
}
