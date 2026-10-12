package com.lichtcui.pokelauncher.server

/**
 * 本地 HTTP 服务的缓存策略（响应头 `Cache-Control`）。
 *
 * 与鸿蒙版 `common/HttpCache.ets` 是**同一套口径**（以前两边各写一份、靠注释维系：
 * 「与鸿蒙版 cacheControl() 一致」）。期望值只写一份：`shared/cases/http.json`，
 * 两端测试遍历同一批向量（`CasesGen.CACHE_CASES`）。
 *
 * 口径（入参是已经过 [com.lichtcui.pokelauncher.core.Paths.resolveRequestPath] 的路径）：
 * - HTML / htm / webmanifest / service-worker.js → `no-cache`；
 * - 否则长缓存一年。
 *
 * 大小写不敏感是有意的：`INDEX.HTML` 被标成长缓存 = 注入内容改了一年都看不到。
 */
object HttpCache {

    private const val NO_CACHE = "no-cache"
    private const val LONG_CACHE = "max-age=31536000"

    fun cacheControl(path: String): String {
        val lower = path.lowercase()
        return if (lower.endsWith(".html") || lower.endsWith(".htm") ||
            lower.endsWith(".webmanifest") || lower.endsWith("service-worker.js")
        ) {
            NO_CACHE
        } else {
            LONG_CACHE
        }
    }
}
