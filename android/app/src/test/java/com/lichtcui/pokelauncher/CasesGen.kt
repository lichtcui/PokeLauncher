package com.lichtcui.pokelauncher

import com.lichtcui.pokelauncher.install.ProbeResult

/**
 * 跨端行为向量（纯数据）。
 *
 * ⚠️ **本文件由 `scripts/gen-shared.mjs` 自动生成，不要手改**：唯一真相在仓库根 `shared/cases/` 下的 JSON。
 * 期望值只写一份，两端测试遍历同一套向量——这样才能抓住「一端改了、另一端没改」的分叉。
 */
object CasesGen {

    data class PathCase(val input: String, val expect: String?)

    data class LongCase(val input: Long, val expect: String)

    data class DurationCase(val input: Double, val expect: String)

    data class ErrorDetailCase(val message: String?, val code: Int?, val expect: String)

    data class NormalizeCase(val input: String, val expect: String)

    data class SameCase(val local: String, val remote: String, val expect: Boolean)

    data class MimeCase(val path: String, val expect: String)

    data class CacheCase(val path: String, val expect: String)

    data class DownloadCandidateCase(
        val assetUrl: String,
        val mirrors: List<String>,
        val debugAssetUrl: String,
        val expect: List<String>
    )

    data class DownloadSplitCase(
        val candidates: List<String>,
        val expectProbed: List<String>,
        val expectFallback: String?
    )

    data class DownloadRankCase(
        val probed: List<ProbeResult>,
        val fallback: String?,
        val expect: List<String>
    )

    data class DownloadHostCase(val url: String, val expect: String)

    data class DownloadKindCase(val index: Int, val count: Int, val expect: String)

    data class DownloadMirrorCase(val url: String, val mirrors: List<String>, val expect: String)

    /** 本地 HTTP 服务的路径判定（安全边界） */
    val PATH_CASES = listOf(
        PathCase("/index.html", "/index.html"),
        PathCase("/", "/index.html"),
        PathCase("", "/index.html"),
        PathCase("/js/main.js", "/js/main.js"),
        PathCase("/assets/index-CVsVm5Lx.js", "/assets/index-CVsVm5Lx.js"),
        PathCase("/data.folder/file.js", "/data.folder/file.js"),
        PathCase("/a+b", "/a+b"),
        PathCase("/a%20b", "/a b"),
        PathCase("/%E4%B8%AD.txt", "/中.txt"),
        PathCase("/index.html?v=1", "/index.html"),
        PathCase("/main.js#x", "/main.js"),
        PathCase("/index.html?x=1#y", "/index.html"),
        PathCase("/../etc/passwd", null),
        PathCase("/js/../main.js", null),
        PathCase("/%2e%2e/x", null),
        PathCase("/%2E%2E/index.html", null),
        PathCase("/%2e./x", null),
        PathCase("/.%2e/x", null),
        PathCase("/..%2fx", null),
        PathCase("/js/%2e%2e/main.js", null),
        PathCase("/%252e%252e/", "/%2e%2e/"),
        PathCase("/%zz", "/%zz"),
        PathCase("/%4", "/%4"),
        PathCase("/%E4%2e%2e/", "/%E4%2e%2e/")
    )

    /** `Format.bytes` */
    val BYTES_CASES = listOf(
        LongCase(0L, "0 B"),
        LongCase(-1L, "0 B"),
        LongCase(512L, "1 KB"),
        LongCase(1023L, "1 KB"),
        LongCase(1024L, "1 KB"),
        LongCase(1536L, "2 KB"),
        LongCase(1048575L, "1024 KB"),
        LongCase(1048576L, "1.0 MB"),
        LongCase(1572864L, "1.5 MB"),
        LongCase(1073741824L, "1.0 GB"),
        LongCase(1717986918L, "1.6 GB"),
        LongCase(531759104L, "507.1 MB")
    )

    /** `Format.speed` */
    val SPEED_CASES = listOf(
        LongCase(0L, ""),
        LongCase(-5L, ""),
        LongCase(1024L, "1 KB/s"),
        LongCase(1048576L, "1.0 MB/s"),
        LongCase(531759104L, "507.1 MB/s")
    )

    /** `Format.duration`（NaN / Infinity 在 JSON 里表达不了，由测试自己补） */
    val DURATION_CASES = listOf(
        DurationCase(0.0, ""),
        DurationCase(-1.0, ""),
        DurationCase(1.0, "约 1 秒"),
        DurationCase(30.0, "约 30 秒"),
        DurationCase(59.4, "约 59 秒"),
        DurationCase(59.5, "约 60 秒"),
        DurationCase(60.0, "约 1 分钟"),
        DurationCase(89.0, "约 1 分钟"),
        DurationCase(90.0, "约 2 分钟"),
        DurationCase(119.0, "约 2 分钟"),
        DurationCase(120.0, "约 2 分钟"),
        DurationCase(3599.0, "约 1 小时"),
        DurationCase(3600.0, "约 1 小时"),
        DurationCase(3660.0, "约 1 小时 1 分钟"),
        DurationCase(7200.0, "约 2 小时"),
        DurationCase(1000000000.0, "约 277777 小时 47 分钟")
    )

    /** `Format.errorDetail` */
    val ERROR_DETAIL_CASES = listOf(
        ErrorDetailCase(null, null, "未知错误"),
        ErrorDetailCase("", null, "未知错误"),
        ErrorDetailCase(null, 12, "错误码 12"),
        ErrorDetailCase("boom", 12, "boom"),
        ErrorDetailCase("", 7, "错误码 7")
    )

    /** `Version.normalize` */
    val NORMALIZE_CASES = listOf(
        NormalizeCase("release-abc1234", "abc1234"),
        NormalizeCase("abc1234", "abc1234"),
        NormalizeCase("", ""),
        NormalizeCase("v1.2.3", "v1.2.3"),
        NormalizeCase("my-release-1", "my-release-1"),
        NormalizeCase("release-", ""),
        NormalizeCase("release-release-x", "release-x")
    )

    /** `Version.same` */
    val SAME_CASES = listOf(
        SameCase("abc1234", "release-abc1234", true),
        SameCase("release-abc1234", "release-abc1234", true),
        SameCase("abc1234", "release-def5678", false),
        SameCase("", "release-abc1234", false),
        SameCase("abc1234", "", false),
        SameCase("", "", false),
        SameCase("release-", "", false)
    )

    /** `Mime.mimeOf`（由 mime.json 生成） */
    val MIME_CASES = listOf(
        MimeCase("/x.html", "text/html"),
        MimeCase("/x.htm", "text/html"),
        MimeCase("/x.js", "text/javascript"),
        MimeCase("/x.mjs", "text/javascript"),
        MimeCase("/x.css", "text/css"),
        MimeCase("/x.json", "application/json"),
        MimeCase("/x.webmanifest", "application/manifest+json"),
        MimeCase("/x.wasm", "application/wasm"),
        MimeCase("/x.png", "image/png"),
        MimeCase("/x.jpg", "image/jpeg"),
        MimeCase("/x.jpeg", "image/jpeg"),
        MimeCase("/x.gif", "image/gif"),
        MimeCase("/x.webp", "image/webp"),
        MimeCase("/x.svg", "image/svg+xml"),
        MimeCase("/x.ico", "image/x-icon"),
        MimeCase("/x.bmp", "image/bmp"),
        MimeCase("/x.mp3", "audio/mpeg"),
        MimeCase("/x.ogg", "audio/ogg"),
        MimeCase("/x.m4a", "audio/mp4"),
        MimeCase("/x.wav", "audio/wav"),
        MimeCase("/x.mp4", "video/mp4"),
        MimeCase("/x.webm", "video/webm"),
        MimeCase("/x.ttf", "font/ttf"),
        MimeCase("/x.otf", "font/otf"),
        MimeCase("/x.woff", "font/woff"),
        MimeCase("/x.woff2", "font/woff2"),
        MimeCase("/x.txt", "text/plain"),
        MimeCase("/x.xml", "text/xml"),
        MimeCase("/no-extension", "application/octet-stream"),
        MimeCase("/x.unknownext", "application/octet-stream"),
        MimeCase("/UPPER.PNG", "image/png")
    )

    /** 本地 HTTP 服务的 `Cache-Control` */
    val CACHE_CASES = listOf(
        CacheCase("/index.html", "no-cache"),
        CacheCase("/app/index.html", "no-cache"),
        CacheCase("/index.htm", "no-cache"),
        CacheCase("/site.webmanifest", "no-cache"),
        CacheCase("/manifest.webmanifest", "no-cache"),
        CacheCase("/service-worker.js", "no-cache"),
        CacheCase("/app/service-worker.js", "no-cache"),
        CacheCase("/INDEX.HTML", "no-cache"),
        CacheCase("/Service-Worker.JS", "no-cache"),
        CacheCase("/main.js", "max-age=31536000"),
        CacheCase("/worker.js", "max-age=31536000"),
        CacheCase("/html.txt", "max-age=31536000"),
        CacheCase("/assets/bg.png", "max-age=31536000"),
        CacheCase("/data/en.json", "max-age=31536000"),
        CacheCase("/", "max-age=31536000")
    )

    /** 下载候选地址（镜像前缀 + 直连兜底） */
    val DOWNLOAD_CANDIDATE_CASES = listOf(
        DownloadCandidateCase("https://github.com/a/b/releases/download/v1/game.zip", listOf("https://m1/", "https://m2/"), "", listOf("https://m1/https://github.com/a/b/releases/download/v1/game.zip", "https://m2/https://github.com/a/b/releases/download/v1/game.zip", "https://github.com/a/b/releases/download/v1/game.zip")),
        DownloadCandidateCase("https://github.com/a/b/releases/download/v1/game.zip", listOf(), "", listOf("https://github.com/a/b/releases/download/v1/game.zip")),
        DownloadCandidateCase("http://127.0.0.1:8000/game.zip", listOf("https://m1/", "https://m2/"), "http://127.0.0.1:8000/game.zip", listOf("http://127.0.0.1:8000/game.zip")),
        DownloadCandidateCase("", listOf("https://m1/", "https://m2/"), "", listOf()),
        DownloadCandidateCase("https://github.com/a/b/releases/download/v1/game.zip", listOf("https://m1/"), "", listOf("https://m1/https://github.com/a/b/releases/download/v1/game.zip", "https://github.com/a/b/releases/download/v1/game.zip")),
        DownloadCandidateCase("http://127.0.0.1:8000/other.zip", listOf("https://m1/"), "http://127.0.0.1:8000/game.zip", listOf("https://m1/http://127.0.0.1:8000/other.zip", "http://127.0.0.1:8000/other.zip"))
    )

    /** 候选地址拆分：参与测速的源 + 直连兜底 */
    val DOWNLOAD_SPLIT_CASES = listOf(
        DownloadSplitCase(listOf("https://m1/x", "https://m2/x", "https://github.com/x"), listOf("https://m1/x", "https://m2/x"), "https://github.com/x"),
        DownloadSplitCase(listOf("https://m1/x", "https://github.com/x"), listOf("https://m1/x"), "https://github.com/x"),
        DownloadSplitCase(listOf("http://127.0.0.1:8000/game.zip"), listOf("http://127.0.0.1:8000/game.zip"), null),
        DownloadSplitCase(listOf(), listOf(), null)
    )

    /** 测速排序：快 → 慢 → 失败，兜底最后 */
    val DOWNLOAD_RANK_CASES = listOf(
        DownloadRankCase(listOf(ProbeResult("slow", 10.0), ProbeResult("fast", 900.0), ProbeResult("mid", 100.0)), "https://github.com/x", listOf("fast", "mid", "slow", "https://github.com/x")),
        DownloadRankCase(listOf(ProbeResult("dead", -1.0), ProbeResult("timeout", 0.0), ProbeResult("ok", 50.0)), "https://github.com/x", listOf("ok", "https://github.com/x")),
        DownloadRankCase(listOf(ProbeResult("only", -1.0)), null, listOf("only")),
        DownloadRankCase(listOf(ProbeResult("first", 5.0), ProbeResult("second", 5.0), ProbeResult("third", 5.0)), null, listOf("first", "second", "third")),
        DownloadRankCase(listOf(ProbeResult("dead1", -1.0), ProbeResult("dead2", -1.0)), "https://github.com/x", listOf("https://github.com/x")),
        DownloadRankCase(listOf(), "https://github.com/x", listOf("https://github.com/x")),
        DownloadRankCase(listOf(), null, listOf())
    )

    /** 域名提取（日志里只显示 host） */
    val DOWNLOAD_HOST_CASES = listOf(
        DownloadHostCase("https://gh-proxy.com/https://github.com/a/b.zip", "gh-proxy.com"),
        DownloadHostCase("http://127.0.0.1:8000/game.zip", "127.0.0.1:8000"),
        DownloadHostCase("bare.host/path", "bare.host"),
        DownloadHostCase("", ""),
        DownloadHostCase("https://", "")
    )

    /** 候选角色：最后一个候选是直连，其余是镜像 */
    val DOWNLOAD_KIND_CASES = listOf(
        DownloadKindCase(0, 4, "镜像"),
        DownloadKindCase(2, 4, "镜像"),
        DownloadKindCase(3, 4, "直连"),
        DownloadKindCase(0, 1, "直连"),
        DownloadKindCase(0, 0, "直连")
    )

    /** 加速镜像前缀套用（首页「下载太慢？」） */
    val DOWNLOAD_MIRROR_CASES = listOf(
        DownloadMirrorCase("https://github.com/a/b/releases/download/v1/game.zip", listOf("https://m1/", "https://m2/"), "https://m1/https://github.com/a/b/releases/download/v1/game.zip"),
        DownloadMirrorCase("https://gitee.com/a/b.zip", listOf("https://m1/"), ""),
        DownloadMirrorCase("https://github.com.evil.com/a/b.zip", listOf("https://m1/"), ""),
        DownloadMirrorCase("https://github.com/a/b.zip", listOf(), ""),
        DownloadMirrorCase("", listOf("https://m1/"), "")
    )
}
