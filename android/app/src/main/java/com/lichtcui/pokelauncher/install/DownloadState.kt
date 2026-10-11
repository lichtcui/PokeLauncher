package com.lichtcui.pokelauncher.install

import org.json.JSONObject

/**
 * 断点续传状态（**纯数据 + 纯函数**，可 JVM 单测）。
 *
 * 鸿蒙版靠 `request.agent` 系统后台任务接管未完成的下载（App 被杀仍继续）；
 * Android 没有等价物，因此改成：partial zip 与这份状态文件一起落在
 * `filesDir` / `getExternalFilesDir`（**绝不能是 cacheDir**），启动时若两者一致就续传。
 */
data class DownloadState(
    val url: String,
    val tag: String,
    val totalBytes: Long,
    val receivedBytes: Long,
    val etag: String,
    val updatedAt: Long
) {
    fun toJson(): String = JSONObject().apply {
        put("url", url)
        put("tag", tag)
        put("totalBytes", totalBytes)
        put("receivedBytes", receivedBytes)
        put("etag", etag)
        put("updatedAt", updatedAt)
    }.toString()

    companion object {
        /** 解析失败一律返回 null（当成「没有可续传状态」，重新下载） */
        fun parse(json: String): DownloadState? = try {
            val o = JSONObject(json)
            val url = o.optString("url")
            if (url.isEmpty()) {
                null
            } else {
                DownloadState(
                    url = url,
                    tag = o.optString("tag"),
                    totalBytes = o.optLong("totalBytes"),
                    receivedBytes = o.optLong("receivedBytes"),
                    etag = o.optString("etag"),
                    updatedAt = o.optLong("updatedAt")
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** 启动时发现的未完成下载（界面「继续下载」提示用） */
data class PendingDownload(
    val url: String,
    val tag: String,
    val received: Long,
    val total: Long
) {
    val percent: Int
        get() = if (total > 0) ((received * 100 / total).coerceIn(0, 100)).toInt() else 0

    val host: String get() = DownloadUrls.hostOf(url)
}

/** partial zip 的处置方式 */
sealed class ResumePlan {
    /** 追加写入：请求 `Range: bytes=<offset>-` */
    data class Append(val offset: Long) : ResumePlan()

    /** 从头重下（[reason] 只用于日志） */
    data class Restart(val reason: String) : ResumePlan()

    /** partial 已等于总大小：跳过下载直接解压 */
    object AlreadyComplete : ResumePlan()
}

/**
 * 续传判定：**partial 与状态必须一致**，否则丢弃重下。
 * 校验不通过时只说「重下」，绝不拿不一致的 partial 去拼接（会导致 zip 损坏、解压失败）。
 */
object ResumePolicy {

    fun decide(
        state: DownloadState?,
        partialBytes: Long,
        url: String,
        totalBytes: Long
    ): ResumePlan {
        if (state == null) return ResumePlan.Restart("无状态文件")
        if (state.url != url) return ResumePlan.Restart("下载源已变化")
        if (partialBytes <= 0) return ResumePlan.Restart("没有已下载的数据")
        val total = if (totalBytes > 0) totalBytes else state.totalBytes
        if (total > 0 && partialBytes > total) {
            return ResumePlan.Restart("分片($partialBytes)大于总大小($total)")
        }
        if (total > 0 && partialBytes == total) return ResumePlan.AlreadyComplete
        return ResumePlan.Append(partialBytes)
    }
}

/**
 * Range 语义（纯函数）：**服务端忽略 Range 返回 200 时必须从头重下**，
 * 否则会把整个包追加到已有分片后面，拼出一个必然解压失败的 zip。
 */
object RangePolicy {

    const val ETAG_HEADER = "ETag"
    const val IF_RANGE_HEADER = "If-Range"
    const val RANGE_HEADER = "Range"

    /** `bytes=131072-`；offset 为 0 时不需要 Range 头（返回 null） */
    fun requestHeader(offset: Long): String? = if (offset > 0) "bytes=$offset-" else null

    /** 要写入的起始偏移：206 → 追加；200/其他 → 服务端没接受续传，从 0 重写 */
    fun appendOffset(statusCode: Int, offset: Long): Long = if (statusCode == 206) offset else 0L

    /** 解析 `Content-Range: bytes 100-999/1000` 的起始偏移；解析不出来返回 null */
    fun contentRangeStart(header: String?): Long? {
        if (header == null) return null
        val space = header.indexOf(' ')
        if (space < 0) return null
        val range = header.substring(space + 1)
        val dash = range.indexOf('-')
        if (dash <= 0) return null
        return range.substring(0, dash).trim().toLongOrNull()
    }

    /** 206 但起始偏移与服务端返回的不一致时，说明分片对不上，必须重下 */
    fun rangeMatches(contentRange: String?, offset: Long): Boolean {
        val start = contentRangeStart(contentRange) ?: return false
        return start == offset
    }
}
