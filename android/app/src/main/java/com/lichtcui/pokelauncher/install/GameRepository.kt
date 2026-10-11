package com.lichtcui.pokelauncher.install

import android.content.Context
import android.util.Log
import com.lichtcui.pokelauncher.core.Const
import com.lichtcui.pokelauncher.core.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 一次下载链路的结果。前台服务据此更新通知与收尾 */
sealed class DownloadOutcome {
    /** 下载 + 解压 + 原子切换全部成功 */
    object Installed : DownloadOutcome()

    /** 用户暂停：partial zip 与状态文件都留着，可续传 */
    object Paused : DownloadOutcome()

    /** 用户取消：partial 与状态已删除 */
    object Cancelled : DownloadOutcome()

    /**
     * 失败。**分片与状态默认保留**，所以「继续」能接着下、只有「换源重试」才从头开始。
     */
    data class Failed(val message: String) : DownloadOutcome()
}

/**
 * 离线资源包仓库：Release 查询 → 镜像并发测速选源 → 断点续传下载 → 解压安装。
 * 对应鸿蒙版 [`GameRepository.ets`](../../../../../../../entry/src/main/ets/model/GameRepository.ets)。
 *
 * 与鸿蒙版的最大差异（**平台能力差异，不是取舍**）：
 * 鸿蒙用 `request.agent` 系统后台任务承载下载，App 被杀后任务继续，重启后 `adoptDownload()` 接管；
 * Android 没有等价物，因此改成「前台服务 + partial zip + `.download.json` 状态文件」，
 * 进程被杀后由下次启动的**用户操作**触发续传（API 34+ 也不允许后台自行拉起 dataSync 前台服务）。
 *
 * 目录约定（全部落在 filesDir / getExternalFilesDir，**绝不放 cacheDir**）：
 * - `<root>/game.zip.part`        正在下载的分片（续传的依据）
 * - `<root>/game.zip.download.json`  与分片配套的状态（url / tag / 总大小 / 已下载 / etag）
 * - `<root>/game.zip`             下载完成后改名到此，交给 [GameInstaller] 解压后删除
 */
class GameRepository(
    val rootDir: File,
    val installer: GameInstaller = GameInstaller(rootDir),
    private val client: OkHttpClient = defaultClient(),
    private val now: () -> Long = System::currentTimeMillis
) {

    val partZipFile: File = File(rootDir, Const.PART_ZIP_NAME)
    val stateFile: File = File(rootDir, Const.DOWNLOAD_STATE_NAME)

    private val pauseFlag = AtomicBoolean(false)
    private val cancelFlag = AtomicBoolean(false)

    @Volatile
    private var activeCall: Call? = null

    /** 当前正在进行的下载尝试（暂停时要据此落盘状态） */
    @Volatile
    private var attempt: Attempt? = null

    private var meter = ProgressMeter()
    private var lastEmitAt = 0L
    private var lastSaveAt = 0L

    private class Attempt(
        val url: String,
        val tag: String,
        val total: Long,
        val etag: String,
        val index: Int,
        val count: Int
    )

    // ------------------------------------------------------------------ 只读查询

    fun hasGameFiles(): Boolean = installer.isReady()

    fun readVersion(): String = installer.readVersion()

    fun availableBytes(): Long = installer.availableBytes()

    /** 当前分片大小（续传进度 / 「继续下载」按钮用） */
    fun partialBytes(): Long = fileLength(partZipFile)

    /** 存储预检：下载**开始前**就拦住，而不是等解压到一半失败 */
    fun storageProblem(assetSize: Long): String? =
        // partial 已占用的空间会被复用，先加回来，避免续传时判得比实际更严
        Storage.check(installer.availableBytes() + fileLength(partZipFile), assetSize)

    /** 有可续传的未完成下载（启动时用于提示「继续下载」）；不可用的残留会被顺手清掉 */
    fun inspectPending(): PendingDownload? {
        val state = readState() ?: return null
        val partial = fileLength(partZipFile)
        val plan = ResumePolicy.decide(state, partial, state.url, state.totalBytes)
        if (plan is ResumePlan.Restart) {
            Log.i(Const.TAG, "pending download unusable (${plan.reason})，丢弃")
            discardPartial()
            return null
        }
        return PendingDownload(state.url, state.tag, partial, state.totalBytes)
    }

    // ------------------------------------------------------------------ Release 查询

    /**
     * 取资源包信息。`Const.DEBUG_ASSET_URL` 非空时直接用它（本地小测试包），否则查 GitHub Release。
     * @param force 忽略缓存（设置页/「检查更新」用）
     */
    @Throws(IOException::class)
    fun resolveRelease(force: Boolean = false): ReleaseInfo {
        if (Const.DEBUG_ASSET_URL.isNotEmpty()) {
            Log.w(Const.TAG, "DEBUG_ASSET_URL in use: ${Const.DEBUG_ASSET_URL}")
            return ReleaseInfo.debug(Const.DEBUG_ASSET_URL)
        }
        if (!force) cachedRelease?.let { return it }
        val info = fetchRelease(Const.DEFAULT_ASSET)
        cachedRelease = info
        return info
    }

    /** 查询 Release 中指定资产（先直连 GitHub API，失败回退 API 镜像） */
    @Throws(IOException::class)
    fun fetchRelease(assetName: String = Const.DEFAULT_ASSET): ReleaseInfo {
        val candidates = listOf(Const.RELEASE_API, Const.API_MIRROR + Const.RELEASE_API)
        var lastError: Exception? = null
        for (api in candidates) {
            try {
                val text = requestText(api, "application/vnd.github+json")
                val info = ReleaseParser.parse(text, assetName)
                Log.i(Const.TAG, "release ${info.tag} asset=${info.assetName} size=${info.assetSize}")
                return info
            } catch (e: Exception) {
                lastError = e
                Log.w(Const.TAG, "fetchRelease via $api failed: ${e.message}")
            }
        }
        throw IOException("查询资源包信息失败：${lastError?.message ?: "未知错误"}", lastError)
    }

    private fun requestText(url: String, accept: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Const.USER_AGENT)
            .header("Accept", accept)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string() ?: throw IOException("响应为空")
        }
    }

    // ------------------------------------------------------------------ 选源

    /** 构造下载候选地址：`Const.DOWNLOAD_MIRRORS` 逐个前缀 + GitHub 直连兜底 */
    fun buildDownloadUrls(assetUrl: String): List<String> =
        DownloadUrls.candidates(assetUrl, Const.DOWNLOAD_MIRRORS, Const.DEBUG_ASSET_URL)

    /**
     * 并发测速排序：快 → 慢 → 失败（直连兜底不参与测速，只做最后一道保险）。
     * 结果按 url 列表做 5 分钟缓存（同日重试/更新不必重复探测），下载失败时由
     * [invalidateRankCache] 立即失效。
     */
    suspend fun rankSources(urls: List<String>): List<String> {
        val key = urls.joinToString("|")
        val cached = rankCacheUrls
        if (rankCacheKey == key && now() - rankCacheAt < RANK_TTL_MS) {
            Log.i(Const.TAG, "rank cache hit")
            return cached
        }
        val (targets, fallback) = DownloadUrls.split(urls)
        val probed = withContext(Dispatchers.IO) {
            targets.map { target -> async { ProbeResult(target, probe(target)) } }.awaitAll()
        }
        probed.forEach { result ->
            Log.i(
                Const.TAG,
                "probe ${result.url} speed=" + String.format(Locale.US, "%.2f", result.speed / 1024) + " KB/ms"
            )
        }
        val sorted = DownloadUrls.rank(probed, fallback)
        rankCacheKey = key
        rankCacheAt = now()
        rankCacheUrls = sorted
        return sorted
    }

    /** 返回 bytes/ms；失败或超时返回 -1 */
    private fun probe(url: String): Double {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Const.USER_AGENT)
            .header(RangePolicy.RANGE_HEADER, "bytes=0-${PROBE_BYTES - 1}")
            .build()
        val call = client.newCall(request)
        val start = System.currentTimeMillis()
        return try {
            // 单个源 4s 硬上限：某个镜像卡住时不能拖慢整体测速（对齐鸿蒙版 PROBE_TIMEOUT_MS）
            call.timeout().timeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            call.execute().use { response ->
                if (!response.isSuccessful) return -1.0
                val body = response.body ?: return -1.0
                val buffer = ByteArray(32 * 1024)
                var read = 0L
                body.byteStream().use { input ->
                    while (read < PROBE_BYTES) {
                        val want = minOf(buffer.size.toLong(), PROBE_BYTES - read).toInt()
                        val n = input.read(buffer, 0, want)
                        if (n <= 0) break
                        read += n
                    }
                }
                val elapsed = System.currentTimeMillis() - start
                // 镜像若忽略 Range 返回 200 全量，这里也只读了 128KB，耗时仍只反映这 128KB
                if (read > 0 && elapsed > 0) read.toDouble() / elapsed else -1.0
            }
        } catch (e: Exception) {
            Log.w(Const.TAG, "probe $url failed: ${e.message}")
            -1.0
        }
    }

    // ------------------------------------------------------------------ 下载

    /**
     * 完整链路：测速选源 → 依次尝试下载（**只在下载失败时换源**）→ 成功则解压安装。
     *
     * @param fresh true = 丢弃已有分片从头下（「重试/换源重试」）；false = 有可续传分片就续传
     * @param emit 状态回调，随时可能从 IO 线程调用（节流后的快照）
     */
    suspend fun run(
        info: ReleaseInfo,
        fresh: Boolean,
        emit: (DownloadStatus) -> Unit
    ): DownloadOutcome {
        resetControl()
        meter = ProgressMeter()
        emit(
            DownloadStatus(
                phase = DownloadPhase.PROBING,
                total = info.assetSize,
                tag = info.tag,
                message = "正在测速，选择最快的下载源…"
            )
        )

        storageProblem(info.assetSize)?.let { return DownloadOutcome.Failed(it) }

        val candidates = buildDownloadUrls(info.assetUrl)
        if (candidates.isEmpty()) return DownloadOutcome.Failed("没有可用的下载地址")

        val urls = runCatching { rankSources(candidates) }
            .getOrElse {
                Log.w(Const.TAG, "rankSources failed: ${it.message}")
                candidates
            }

        // 续传时把上次的源提到最前：否则测速挑了另一个镜像，400MB 的进度就白费了
        val resumeUrl = if (fresh) null else resumeUrlFor(urls, info.assetSize)
        val ordered = if (resumeUrl == null) urls else listOf(resumeUrl) + urls.filter { it != resumeUrl }
        if (fresh) discardPartial()

        var lastError: String? = null
        for ((index, url) in ordered.withIndex()) {
            if (isCancelled()) return finishCancelled()
            if (isPaused()) return finishPaused()

            if (index > 0) invalidateRankCache() // 上一个源失败了，排序已不可信
            val state = readState()
            val plan = ResumePolicy.decide(state, fileLength(partZipFile), url, info.assetSize)
            // 换源后旧分片必然对不上：丢弃，从 0 开始
            if (index > 0 && plan !is ResumePlan.AlreadyComplete) discardPartial()

            if (plan is ResumePlan.AlreadyComplete) {
                Log.i(Const.TAG, "partial 已等于总大小，跳过下载直接解压")
                return install(info, emit)
            }
            val offset = (plan as? ResumePlan.Append)?.offset ?: 0L
            if (offset > 0) Log.i(Const.TAG, "resume from $offset bytes (${state?.url})")
            emit(
                DownloadStatus(
                    phase = DownloadPhase.DOWNLOADING,
                    received = offset,
                    total = info.assetSize,
                    sourceUrl = url,
                    sourceIndex = index,
                    sourceCount = ordered.size,
                    tag = info.tag,
                    message = if (offset > 0) "继续下载（已完成 ${Format.bytes(offset)}）" else "连接中…"
                )
            )

            try {
                downloadTo(
                    Attempt(
                        url = url,
                        tag = info.tag,
                        total = info.assetSize,
                        etag = state?.etag.orEmpty(),
                        index = index,
                        count = ordered.size
                    ),
                    offset,
                    emit
                )
            } catch (e: Exception) {
                if (isCancelled()) return finishCancelled()
                if (isPaused()) return finishPaused()
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(Const.TAG, "download attempt ${index + 1}/${ordered.size} failed: $lastError")
                continue
            }

            if (isCancelled()) return finishCancelled()
            if (isPaused()) return finishPaused()
            // 下载成功：不管解压成败都要清掉状态文件（zip 已经是完整的了）
            clearState()
            return install(info, emit)
        }
        val kept = fileLength(partZipFile)
        val message = "下载失败：${lastError ?: "所有下载源都不可用"}"
        Log.w(Const.TAG, "$message（已保留 ${Format.bytes(kept)} 分片，可续传）")
        return DownloadOutcome.Failed(message)
    }

    /** 暂停：断开连接但保留分片与状态，稍后 `run(fresh = false)` 可续传 */
    fun pause() {
        pauseFlag.set(true)
        persistActiveState()
        activeCall?.cancel()
    }

    /** 取消：断开连接并丢弃分片与状态（对齐鸿蒙版 `cancelDownload`） */
    fun cancel() {
        cancelFlag.set(true)
        activeCall?.cancel()
        discardPartial()
    }

    /** 清掉全部下载残留（设置页的「删除本地数据」用） */
    fun clearDownloadFiles() {
        cancel()
        discardPartial()
    }

    private fun resetControl() {
        pauseFlag.set(false)
        cancelFlag.set(false)
        lastEmitAt = 0
        lastSaveAt = 0
        attempt = null
    }

    private fun isPaused(): Boolean = pauseFlag.get()

    private fun isCancelled(): Boolean = cancelFlag.get()

    private fun finishPaused(): DownloadOutcome {
        persistActiveState()
        Log.i(Const.TAG, "download paused at ${fileLength(partZipFile)} bytes")
        return DownloadOutcome.Paused
    }

    private fun finishCancelled(): DownloadOutcome {
        discardPartial()
        Log.i(Const.TAG, "download cancelled")
        return DownloadOutcome.Cancelled
    }

    /**
     * 单次下载尝试：写 `<root>/game.zip.part`，`offset > 0` 时用 `Range` 追加。
     *
     * **服务端忽略 Range（返回 200）时必须从头重下**：直接把整包追加到已有分片后面
     * 会拼出一个必然解压失败的 zip。
     */
    private suspend fun downloadTo(
        attempt: Attempt,
        offset: Long,
        emit: (DownloadStatus) -> Unit
    ) = withContext(Dispatchers.IO) {
        this@GameRepository.attempt = attempt
        val request = Request.Builder()
            .url(attempt.url)
            .header("User-Agent", Const.USER_AGENT)
            .apply {
                RangePolicy.requestHeader(offset)?.let { header(RangePolicy.RANGE_HEADER, it) }
                // If-Range：资源被换掉（tag 变了）时服务端会回 200，我们据此从头重下而不是拼坏包
                if (offset > 0 && attempt.etag.isNotEmpty()) {
                    header(RangePolicy.IF_RANGE_HEADER, attempt.etag)
                }
            }
            .build()

        val call = client.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("响应为空")

                if (offset > 0 && response.code == 206 &&
                    !RangePolicy.rangeMatches(response.header("Content-Range"), offset)
                ) {
                    throw IOException("服务端 Content-Range 与续传位置不一致")
                }
                val writeOffset = RangePolicy.appendOffset(response.code, offset)
                if (offset > 0 && writeOffset == 0L) {
                    Log.w(Const.TAG, "服务端忽略 Range（HTTP ${response.code}），从头重下")
                }

                val serverEtag = response.header(RangePolicy.ETAG_HEADER).orEmpty()
                val contentLength = body.contentLength()
                val total = attempt.total.takeIf { it > 0 }
                    ?: (if (contentLength > 0) contentLength + writeOffset else 0L)

                partZipFile.parentFile?.mkdirs()
                RandomAccessFile(partZipFile, "rw").use { raf ->
                    if (writeOffset == 0L) raf.setLength(0) // 从头下：清掉残留
                    raf.seek(writeOffset)
                    var received = writeOffset
                    body.byteStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            if (isPaused() || isCancelled()) throw IOException("已中断")
                            val n = input.read(buffer)
                            if (n < 0) break
                            raf.write(buffer, 0, n)
                            received += n
                            reportProgress(attempt, received, total, serverEtag, emit)
                        }
                    }
                    if (total > 0 && received < total) {
                        throw IOException("下载不完整：$received/$total")
                    }
                    Log.i(Const.TAG, "downloaded $received bytes from ${attempt.url}")
                }
            }
        } finally {
            activeCall = null
            this@GameRepository.attempt = null
        }
    }

    /** 节流上报进度（UI/通知都用这份快照），并定期落盘续传状态 */
    private fun reportProgress(
        attempt: Attempt,
        received: Long,
        total: Long,
        etag: String,
        emit: (DownloadStatus) -> Unit
    ) {
        val at = now()
        val speed = meter.update(received, at)
        val percent = if (total > 0) ((received * 100 / total).coerceIn(0, 100)).toInt() else 0
        if (meter.shouldEmit(lastEmitAt, at, percent, isPaused())) {
            lastEmitAt = at
            emit(
                DownloadStatus(
                    phase = DownloadPhase.DOWNLOADING,
                    received = received,
                    total = total,
                    speedBps = speed,
                    etaSeconds = meter.etaSeconds(total, received),
                    sourceUrl = attempt.url,
                    sourceIndex = attempt.index,
                    sourceCount = attempt.count,
                    tag = attempt.tag,
                    message = if (speed > 1024) "" else "连接中…"
                )
            )
        }
        if (at - lastSaveAt >= STATE_SAVE_INTERVAL_MS) {
            lastSaveAt = at
            saveState(attempt.url, attempt.tag, total, received, etag.ifEmpty { attempt.etag })
        }
    }

    // ------------------------------------------------------------------ 解压安装

    private suspend fun install(info: ReleaseInfo, emit: (DownloadStatus) -> Unit): DownloadOutcome {
        val zip = installer.localZipFile
        if (zip.exists() && !zip.delete()) Log.w(Const.TAG, "旧的 game.zip 删除失败")
        if (!partZipFile.renameTo(zip)) {
            return DownloadOutcome.Failed("无法启用下载好的资源包（重命名失败）")
        }
        emit(
            DownloadStatus(
                phase = DownloadPhase.EXTRACTING,
                received = zip.length(),
                total = info.assetSize,
                tag = info.tag,
                message = "正在解压 PokeRogue 资源…"
            )
        )
        return try {
            withContext(Dispatchers.IO) {
                installer.installFromZipFile(zip) { count, bytes ->
                    // 解压进度没有百分比，只能报「多少个文件 / 多少 MB」
                    emit(
                        DownloadStatus(
                            phase = DownloadPhase.EXTRACTING,
                            received = zip.length(),
                            total = info.assetSize,
                            tag = info.tag,
                            message = "正在解压：$count 个文件，${Format.bytes(bytes)}"
                        )
                    )
                }
            }
            // 解压成功后立刻删掉 507MB 的 zip：设备空间本来就紧张
            if (zip.delete()) Log.i(Const.TAG, "资源已安装，删除 zip 释放空间")
            Log.i(Const.TAG, "download + install complete, version=${installer.readVersion()}")
            DownloadOutcome.Installed
        } catch (e: Exception) {
            // 解压失败与下载失败不同：换源重下等于白下整个包，直接上抛由界面提示
            Log.e(Const.TAG, "extract failed: ${e.message}")
            DownloadOutcome.Failed("解压失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ 状态文件

    private fun readState(): DownloadState? {
        if (!stateFile.isFile) return null
        return try {
            DownloadState.parse(stateFile.readText())
        } catch (e: Exception) {
            Log.w(Const.TAG, "read download state failed: ${e.message}")
            null
        }
    }

    private fun saveState(url: String, tag: String, total: Long, received: Long, etag: String) {
        try {
            stateFile.writeText(
                DownloadState(url, tag, total, received, etag, now()).toJson()
            )
        } catch (e: Exception) {
            Log.w(Const.TAG, "save download state failed: ${e.message}")
        }
    }

    private fun persistActiveState() {
        val current = attempt ?: return
        saveState(current.url, current.tag, current.total, fileLength(partZipFile), current.etag)
    }

    private fun clearState() {
        try {
            if (stateFile.isFile) stateFile.delete()
        } catch (e: Exception) {
            Log.w(Const.TAG, "clear download state failed: ${e.message}")
        }
    }

    private fun discardPartial() {
        clearState()
        try {
            if (partZipFile.isFile) partZipFile.delete()
        } catch (e: Exception) {
            Log.w(Const.TAG, "discard partial zip failed: ${e.message}")
        }
    }

    /** 找出可用于续传的源（必须在候选列表里且分片可用） */
    private fun resumeUrlFor(urls: List<String>, totalBytes: Long): String? {
        val state = readState() ?: return null
        if (!urls.contains(state.url)) return null
        val plan = ResumePolicy.decide(state, fileLength(partZipFile), state.url, totalBytes)
        if (plan is ResumePlan.Restart) {
            Log.i(Const.TAG, "无法续传（${plan.reason}），丢弃分片")
            discardPartial()
            return null
        }
        return state.url
    }

    private fun fileLength(file: File): Long = try {
        if (file.isFile) file.length() else 0L
    } catch (e: Exception) {
        0L
    }

    companion object {
        /** 测速读取的字节数（128KB） */
        const val PROBE_BYTES = 131072L

        /** 单个源的测速硬上限：某个镜像卡住时不能等满 readTimeout 才开始下载 */
        const val PROBE_TIMEOUT_MS = 4000L

        /** 测速结果短 TTL：同日重试/更新不必重复探测同一组镜像 */
        const val RANK_TTL_MS = 5 * 60 * 1000L

        /** 续传状态落盘间隔 */
        const val STATE_SAVE_INTERVAL_MS = 2000L

        /**
         * Release 结果缓存（模块级）：打靶页、首页、设置页各自 new 一个仓库时，
         * 用实例缓存会各请求一次 GitHub API；共享缓存后同一 asset 只查一次。
         */
        @Volatile
        private var cachedRelease: ReleaseInfo? = null

        @Volatile
        private var rankCacheKey: String = ""

        @Volatile
        private var rankCacheAt: Long = 0L

        @Volatile
        private var rankCacheUrls: List<String> = emptyList()

        /** 下载失败说明该排序已不可信，丢弃缓存让下次重新测速 */
        fun invalidateRankCache() {
            rankCacheKey = ""
            rankCacheUrls = emptyList()
        }

        /** 测试/重置用：清掉两个模块级缓存 */
        fun resetCaches() {
            cachedRelease = null
            invalidateRankCache()
        }

        /**
         * App 的持久化根目录。507MB 的 zip 与 1.5GB 的解压结果**不能放内部存储**，
         * 优先外部私有目录；同时它也在 `cacheDir` 之外（系统「清除缓存」不能碰）。
         */
        fun rootDirOf(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
