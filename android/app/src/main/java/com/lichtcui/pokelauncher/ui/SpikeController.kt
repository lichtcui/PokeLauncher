package com.lichtcui.pokelauncher.ui

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.lichtcui.pokelauncher.core.Const
import com.lichtcui.pokelauncher.core.Format
import com.lichtcui.pokelauncher.install.DownloadBus
import com.lichtcui.pokelauncher.install.DownloadPhase
import com.lichtcui.pokelauncher.install.DownloadService
import com.lichtcui.pokelauncher.install.DownloadStatus
import com.lichtcui.pokelauncher.install.GameInstaller
import com.lichtcui.pokelauncher.install.GameRepository
import com.lichtcui.pokelauncher.install.PendingDownload
import com.lichtcui.pokelauncher.install.ReleaseInfo
import com.lichtcui.pokelauncher.server.GameServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 一条注入脚本回传的计时点 */
data class TimingMark(val key: String, val ms: Long)

/**
 * P0 打靶页的状态与动作。用普通类（而非 ViewModel）以避开工厂/反射，
 * 由 `MainActivity` 持有；`configChanges` 已声明旋转不重建，状态不会丢。
 */
class SpikeController(private val app: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    val installer = GameInstaller(GameRepository.rootDirOf(app))
    val server = GameServer { installer.gameDir }

    /** 仓库只做「查询/下载」；解压安装仍落在共用的 [installer] 上（两边同一份文件系统约定） */
    private val repository = GameRepository(installer.rootDir, installer)

    // ------------------------------------------------------------ 可观测状态

    var logs by mutableStateOf<List<String>>(emptyList())
        private set
    var timings by mutableStateOf<List<TimingMark>>(emptyList())
        private set
    var stateSnapshots by mutableStateOf<List<String>>(emptyList())
        private set

    var patchCanvas by mutableStateOf(Const.FORCE_WILL_READ_FREQUENTLY)
        private set

    var webViewVersion by mutableStateOf("检测中…")
        private set
    var webViewPackageName by mutableStateOf("")
        private set
    var chromiumMajor by mutableStateOf(0)
        private set
    var muteAudioSupported by mutableStateOf(false)
        private set

    var serverState by mutableStateOf(GameServer.State.STOPPED)
        private set
    var serverError by mutableStateOf("")
        private set

    var busy by mutableStateOf(false)
        private set
    var busyText by mutableStateOf("")
        private set

    var gameReady by mutableStateOf(false)
        private set
    var showGame by mutableStateOf(false)
        private set

    /** 自增即强制重组出一个新的 WebView（Compose 的 `key()` 依赖它） */
    var gameSession by mutableStateOf(0)
        private set

    var lastPageLoadMs by mutableStateOf(-1L)
        private set
    var pageLoaded by mutableStateOf(false)
        private set
    var fatalPageError by mutableStateOf("")
        private set

    /** P0 判据 7：注入补丁是否真的生效（getImageData 次数应远小于 2 万） */
    var getImageDataCount by mutableStateOf(0)
        private set
    var canvasCount by mutableStateOf(0)
        private set

    // ------------------------------------------------------------ P1 下载状态

    /** 下载状态快照：唯一写入方是 `DownloadService`（通过进程级 [DownloadBus]） */
    var download by mutableStateOf(DownloadBus.status.value)
        private set

    /** 启动时发现的未完成下载（有的话界面提示「继续下载」） */
    var pendingDownload by mutableStateOf<PendingDownload?>(null)
        private set

    /** 远程 Release 信息（tag / 大小），用于下载前的存储预检与界面展示 */
    var releaseInfo by mutableStateOf<ReleaseInfo?>(null)
        private set
    var checkingRelease by mutableStateOf(false)
        private set

    /** 已安装资源版本（zip 内 currentVersion.txt） */
    var installedVersion by mutableStateOf("")
        private set

    /** 下载报错（存储预检 / 查询失败等，由界面直接展示） */
    var downloadError by mutableStateOf("")
        private set

    val gameUrl: String get() = server.indexUrl

    /** 进游戏的门禁：游戏就绪 + 服务器在跑 + Chromium 版本达标 */
    val canEnterGame: Boolean
        get() = gameReady && serverState == GameServer.State.RUNNING && chromiumMajor >= Const.MIN_CHROMIUM_MAJOR

    init {
        log("P0 spike start; filesDir=${installer.rootDir.absolutePath}")
        detectWebView()
        observeDownloadBus()
        // recover / 磁盘统计都要遍历大目录，放到 IO 线程
        scope.launch {
            val ready = withContext(Dispatchers.IO) { installer.recover(); installer.isReady() }
            gameReady = ready
            log("game ready=$gameReady")
            log("free space=${installer.availableBytes() / 1024 / 1024}MB")
            val zip = installer.localZipFile
            if (zip.isFile) {
                log("local zip found: ${zip.absolutePath} (${zip.length() / 1024 / 1024}MB) — 可直接点「安装本地 game.zip」")
            }
            refreshDownloadState()
            checkRelease()
        }
    }

    // ------------------------------------------------------------ 下载

    /** 订阅下载状态：服务可能在别的进程生命周期里跑着我们不在场，这里只管回写 UI */
    private fun observeDownloadBus() {
        scope.launch {
            DownloadBus.status.collect { status ->
                val previous = download.phase
                download = status
                if (status.phase == DownloadPhase.DONE && previous != DownloadPhase.DONE) {
                    log("下载并安装完成")
                    refreshDownloadState()
                    if (serverState == GameServer.State.RUNNING) restartServer()
                }
                if (status.phase == DownloadPhase.FAILED || status.phase == DownloadPhase.CANCELLED) {
                    refreshDownloadState()
                }
            }
        }
    }

    /** 刷新「未完成下载 / 已装版本」这两个只读状态 */
    fun refreshDownloadState() {
        scope.launch {
            val (pending, version, ready) = withContext(Dispatchers.IO) {
                Triple(repository.inspectPending(), installer.readVersion(), installer.isReady())
            }
            pendingDownload = pending
            installedVersion = version
            gameReady = ready
            pending?.let {
                log(
                    "发现未完成的下载：${it.tag} 已完成 " +
                        "${Format.bytes(it.received)}（${it.percent}%），可续传"
                )
            }
        }
    }

    /** 查询远程 Release（有网络才成功，失败只在日志/卡片里提示，不拦任何操作） */
    fun checkRelease(force: Boolean = false) {
        if (checkingRelease) return
        checkingRelease = true
        downloadError = ""
        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { repository.resolveRelease(force) }
                releaseInfo = info
                log("release ${info.tag} asset=${info.assetSize / 1024 / 1024}MB url=${info.assetUrl}")
            } catch (e: Throwable) {
                downloadError = "查询资源包信息失败：${e.message ?: "网络不可用"}"
                log("resolveRelease failed: ${e.message}")
            } finally {
                checkingRelease = false
            }
        }
    }

    /**
     * 开始 / 继续下载。
     * @param fresh true 表示丢弃已有分片从头下（换源重试），false 表示有分片就续传
     */
    fun startDownload(fresh: Boolean) {
        if (download.isActive) {
            log("已有下载在进行，忽略")
            return
        }
        // 507MB 的包不能下到一半才发现空间不够：先按 3.2× 预检
        val expected = releaseInfo?.assetSize?.takeIf { it > 0 } ?: pendingDownload?.total ?: 0L
        repository.storageProblem(expected)?.let {
            downloadError = it
            log("存储预检未通过：$it")
            return
        }
        downloadError = ""
        log(if (fresh) "换源重试：丢弃已有分片，重新测速选源" else "开始下载资源包（有分片则续传）")
        DownloadService.start(app, fresh)
    }

    fun pauseDownload() {
        log("暂停下载")
        DownloadService.pause(app)
    }

    fun cancelDownload() {
        log("取消下载（丢弃已下载的分片）")
        DownloadService.cancel(app)
    }

    /** 删除本地资源：连未完成的下载分片一起清掉 */
    fun clearDownloadFiles() {
        repository.clearDownloadFiles()
        pendingDownload = null
        log("已清除下载残留")
    }

    // ------------------------------------------------------------ 日志

    fun log(msg: String) {
        Log.i(Const.TAG, msg)
        if (Looper.myLooper() == Looper.getMainLooper()) appendLog(msg) else mainHandler.post { appendLog(msg) }
    }

    private fun appendLog(msg: String) {
        val stamp = com.lichtcui.pokelauncher.core.Format.timeStamp()
        logs = (logs + "$stamp $msg").takeLast(400)
    }

    fun clearLogs() {
        logs = emptyList()
    }

    // ------------------------------------------------------------ 设备能力

    private fun detectWebView() {
        try {
            val pkg = WebViewCompat.getCurrentWebViewPackage(app)
            webViewVersion = pkg?.versionName ?: "未知"
            webViewPackageName = pkg?.packageName ?: ""
            chromiumMajor = webViewVersion.substringBefore('.').toIntOrNull() ?: 0
            log("WebView ${webViewPackageName} ${webViewVersion} (Chromium $chromiumMajor)")
        } catch (e: Throwable) {
            webViewVersion = "检测失败"
            log("detect WebView failed: ${e.message}")
        }
        muteAudioSupported = try {
            WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)
        } catch (e: Throwable) {
            false
        }
        log("MUTE_AUDIO supported=$muteAudioSupported")
    }

    // ------------------------------------------------------------ 服务器

    fun toggleCanvasPatch(enabled: Boolean) {
        patchCanvas = enabled
        server.patchCanvas = enabled
        log("canvas patch = $enabled（已运行页面需重新进入游戏才生效）")
    }

    fun startServer() {
        if (server.start()) {
            serverState = server.state
            serverError = ""
            log("server started: ${server.baseUrl}")
        } else {
            serverState = server.state
            serverError = server.errorText
        }
    }

    fun restartServer() {
        server.stop()
        serverState = GameServer.State.STOPPED
        startServer()
    }

    // ------------------------------------------------------------ 安装资源

    fun installLocalZip() {
        val zip = installer.localZipFile
        if (!zip.isFile) {
            log("未找到 ${zip.absolutePath}；请用 adb push game.zip 到该路径，或点「选择 zip 导入」")
            return
        }
        runInstall("本地 game.zip") { progress ->
            installer.installFromZipFile(zip, progress)
        }
    }

    fun installFromUri(uri: Uri) {
        val name = uri.lastPathSegment ?: uri.toString()
        runInstall("导入 $name") { progress ->
            val input = app.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("无法打开所选文件：$uri")
            input.use { installer.installFromStream(it, progress) }
        }
    }

    private fun runInstall(
        label: String,
        block: (onProgress: (Int, Long) -> Unit) -> Unit
    ) {
        if (busy) return
        busy = true
        busyText = "$label：准备中…"
        log("install start: $label")
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    block { done, bytes ->
                        // 回调在 IO 线程：进度文案必须切回主线程写状态
                        val text = "$label：$done 个文件，${bytes / 1024 / 1024}MB"
                        mainHandler.post { busyText = text }
                    }
                }
                gameReady = installer.isReady()
                log("install done: $label, ready=$gameReady")
                refreshDownloadState()
                // 资源换了，必须重启服务让 index.html 重新注入
                if (serverState == GameServer.State.RUNNING) restartServer()
            } catch (e: Throwable) {
                log("install failed: ${e.message}")
            } finally {
                busy = false
                busyText = ""
            }
        }
    }

    fun deleteGame() {
        installer.deleteGame()
        gameReady = false
        installedVersion = ""
        clearDownloadFiles()
        log("game deleted")
    }

    // ------------------------------------------------------------ 进 / 出游戏

    fun enterGame() {
        if (!gameReady) {
            log("游戏资源未就绪，先安装 game.zip")
            return
        }
        if (serverState != GameServer.State.RUNNING) {
            if (!server.start()) {
                serverState = server.state
                serverError = server.errorText
                log("服务器未就绪，无法进入游戏")
                return
            }
            serverState = server.state
            serverError = ""
        }
        pageLoaded = false
        lastPageLoadMs = -1
        fatalPageError = ""
        timings = emptyList()
        stateSnapshots = emptyList()
        showGame = true
        gameSession++
        log("enter game: $gameUrl")
    }

    /** 重载：重启本地服务（重新注入 index.html）并重建 WebView */
    fun reloadGame() {
        restartServer()
        pageLoaded = false
        lastPageLoadMs = -1
        fatalPageError = ""
        timings = emptyList()
        gameSession++
        log("reload game")
    }

    fun exitGame() {
        showGame = false
        log("exit game")
    }

    fun onPageFinished(ms: Long) {
        lastPageLoadMs = ms
        pageLoaded = true
    }

    fun onPageError(text: String) {
        fatalPageError = text
    }

    // ------------------------------------------------------------ 注入脚本回传

    fun onTiming(json: String) {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        val key = obj.optString("k")
        val ms = obj.optLong("ms")
        if (key.isEmpty()) return
        val next = timings.toMutableList()
        next.removeAll { it.key == key }
        next.add(TimingMark(key, ms))
        timings = next
    }

    fun onState(json: String) {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        getImageDataCount = obj.optInt("getImageData")
        canvasCount = obj.optInt("canvasCount")
        val ready = obj.optBoolean("ready")
        stateSnapshots = (stateSnapshots + "gid=$getImageDataCount canvas=$canvasCount ready=$ready").takeLast(20)
    }
}
