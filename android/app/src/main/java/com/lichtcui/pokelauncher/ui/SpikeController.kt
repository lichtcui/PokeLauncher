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
import com.lichtcui.pokelauncher.appupdate.ApkUpdateCancelled
import com.lichtcui.pokelauncher.appupdate.ApkUpdatePhase
import com.lichtcui.pokelauncher.appupdate.ApkUpdateState
import com.lichtcui.pokelauncher.appupdate.ApkUpdater
import com.lichtcui.pokelauncher.appupdate.INVALID_APK_TEXT
import com.lichtcui.pokelauncher.appupdate.NO_APK_URL_TEXT
import com.lichtcui.pokelauncher.appupdate.apkFileName
import com.lichtcui.pokelauncher.core.Const
import com.lichtcui.pokelauncher.core.Format
import com.lichtcui.pokelauncher.core.Prefs
import com.lichtcui.pokelauncher.install.DownloadBus
import com.lichtcui.pokelauncher.install.DownloadPhase
import com.lichtcui.pokelauncher.install.DownloadService
import com.lichtcui.pokelauncher.install.DownloadStatus
import com.lichtcui.pokelauncher.install.GameInstaller
import com.lichtcui.pokelauncher.install.GameRepository
import com.lichtcui.pokelauncher.install.PendingDownload
import com.lichtcui.pokelauncher.install.ReleaseInfo
import com.lichtcui.pokelauncher.server.GameServer
import com.lichtcui.pokelauncher.web.DownloadBridge
import com.lichtcui.pokelauncher.web.WebCache
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

    /**
     * 本地 zip 导入/安装中（与「下载」无关的另一条安装途径）。
     * 首页据此把「导入中」也当成安装阶段显示（与下载共用精灵球动画），
     * 但不能拿 [busy] 代：删除本地数据也是 busy，那个不该显示成安装中。
     */
    var installing by mutableStateOf(false)
        private set

    var gameReady by mutableStateOf(false)
        private set

    /**
     * 启动时的首帧判定（读沙箱目录 / 版本 / 可用空间都是 IO）。
     * 不加这个标志的话，已安装资源的用户会先看到一闪而过的「未安装资源」页。
     */
    var initializing by mutableStateOf(true)
        private set

    /** 可用的内部存储字节数（首页存储预检用；预检本身是纯函数 [Storage.check]） */
    var freeBytes by mutableStateOf(0L)
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

    /** 最近一次「存档从快照回填」的条数（0 = 没回填过） */
    var saveRestoredCount by mutableStateOf(0)
        private set

    /**
     * 回填事件自增序号：游戏页据此弹一次性提示。
     * 用序号而不是条数，是因为「两次回填的条数恰好一样」时状态值不变，
     * `LaunchedEffect(count)` 就不会重跑，用户会看不到第二次提示。
     */
    var saveRestoredSeq by mutableStateOf(0)
        private set

    /** 最近一次存档导出的结果文案（空串 = 还没导出过） */
    var saveExportText by mutableStateOf("")
        private set

    /** 导出事件自增序号（与 [saveRestoredSeq] 同理：两次导出文案可能一样） */
    var saveExportSeq by mutableStateOf(0)
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

    /**
     * 设置页「更新到新版本」置起的待办标记（对应鸿蒙版 `AppStorage('pendingUpdate')`）：
     * 回首页后由首页发起下载。放标志而不是直接开下载，是为了让「申请通知权限 → 预检 → 启动服务」
     * 这条链路只留在首页一处（权限请求必须要 Activity 的 launcher）。
     */
    var pendingUpdate by mutableStateOf(false)
        private set

    val gameUrl: String get() = server.indexUrl
    /** 进游戏的门禁：游戏就绪 + 服务器在跑 + Chromium 版本达标 */
    /**
     * 能否进游戏（首页「开始运行」/打靶页「进入游戏」的按钮可用性）。
     *
     * 判定在 [canEnterGameOf]：**只看资源与 WebView 版本，不看本地服务是否已 RUNNING**。
     * 服务由 [enterGame] 现场启动，若把「服务已在跑」也算进来，冷启动（资源已就绪但服务未起）
     * 时按钮会被禁掉且无处可点——真机实测到的死锁。
     */
    val canEnterGame: Boolean
        get() = canEnterGameOf(gameReady, chromiumMajor, Const.MIN_CHROMIUM_MAJOR)

    init {
        log("P0 spike start; filesDir=${installer.rootDir.absolutePath}")
        detectWebView()
        observeDownloadBus()
        // recover / 磁盘统计都要遍历大目录，放到 IO 线程
        scope.launch {
            try {
                // recover() 只能在启动路径跑一次：它会清掉/接管 stagingDir，
                // 而下载服务在别处正拿 stagingDir 解压（见 loadDirState 的注释）
                withContext(Dispatchers.IO) { installer.recover() }
                // 然后一次 IO 取回全部首帧状态（未完成下载 + 版本 + 可用空间）
                applyDirState(loadDirState())
                checkRelease()
            } finally {
                // 上面全部落定才撤掉「正在检查」：否则会用空状态闪一下「未安装 / 可用空间缺失」
                initializing = false
            }
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
                    // 服务端刚换了资源包：WebView 磁盘缓存里的旧 index.html / 旧 bundle 必须清掉，
                    // 否则重进游戏拿到的还是上一版（与鸿蒙版 LauncherSession.enterReady 同位置）
                    if (WebCache.clear("install from download")) log("已清除 WebView 缓存")
                    if (serverState == GameServer.State.RUNNING) restartServer()
                }
                if (status.phase == DownloadPhase.FAILED || status.phase == DownloadPhase.CANCELLED) {
                    refreshDownloadState()
                }
            }
        }
    }

    /**
     * 资源包版本变了就清一次 WebView 缓存。
     *
     * 为啥不只靠「安装完成」那一次清（鸿蒙版只有那一次）：资源包的 JS bundle 走的是
     * `max-age=31536000` 长缓存，而下载可以在后台服务里装完——那时游戏页可能根本没开过，
     * 「装完就清」的时机就错过了，下次进游戏会吃到上一版的 bundle（表现为「改了没生效」）。
     * 这里把「已清缓存的版本」记在偏好里，冷启动时对一下，两种时机都能覆盖。
     */
    private fun maybeClearCacheAfterUpgrade(version: String) {
        if (version.isEmpty()) return
        if (Prefs.getString(KEY_LAST_CACHED_VERSION) == version) return
        Prefs.putString(KEY_LAST_CACHED_VERSION, version)
        if (WebCache.clear("game version -> $version")) {
            log("资源版本为 $version，已清除 WebView 缓存")
        }
    }

    /** 刷新「未完成下载 / 已装版本 / 可用空间」这几个只读状态 */
    fun refreshDownloadState() {
        scope.launch { applyDirState(loadDirState()) }
    }

    /**
     * 磁盘盘点：未完成下载 / 已装版本 / 可用空间。
     *
     * ⚠️ 这里**故意不调 `installer.recover()`**：`recover()` 会删掉/接管 `stagingDir`，
     * 而下载服务可能正在同一个目录里解压（用户点一下「刷新」就会把它踩没）。
     * 恢复只放在启动路径（[init]）跑一次。
     */
    private suspend fun loadDirState(): DirState = withContext(Dispatchers.IO) {
        DirState(
            pending = repository.inspectPending(),
            version = installer.readVersion(),
            ready = installer.isReady(),
            freeBytes = installer.availableBytes()
        )
    }

    /** 把一次磁盘盘点的结果写回 UI 状态（Compose 状态只能在主线程改） */
    private fun applyDirState(s: DirState) {
        pendingDownload = s.pending
        installedVersion = s.version
        gameReady = s.ready
        freeBytes = s.freeBytes
        log("game ready=${s.ready}")
        maybeClearCacheAfterUpgrade(s.version)
        s.pending?.let {
            log(
                "发现未完成的下载：${it.tag} 已完成 " +
                    "${Format.bytes(it.received)}（${it.percent}%），可续传"
            )
        }
    }

    /** 一次磁盘盘点的结果（[loadDirState] 一次 IO 取回，避免多次切线程） */
    private data class DirState(
        val pending: PendingDownload?,
        val version: String,
        val ready: Boolean,
        val freeBytes: Long
    )

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

    /** 设置页「更新到新版本」：标记后返回首页，由首页发起下载（见 [pendingUpdate]） */
    fun requestUpdate() {
        pendingUpdate = true
        log("收到「更新到新版本」：回首页后开始下载")
    }

    /** 首页取走标记（只消费一次） */
    fun consumePendingUpdate() {
        pendingUpdate = false
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
        installing = true
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
                // 同上：资源换过就必须清 WebView 缓存
                if (WebCache.clear("install: $label")) log("已清除 WebView 缓存")
                // 资源换了，必须重启服务让 index.html 重新注入
                if (serverState == GameServer.State.RUNNING) restartServer()
            } catch (e: Throwable) {
                log("install failed: ${e.message}")
            } finally {
                busy = false
                installing = false
                busyText = ""
            }
        }
    }

    /**
     * 删除本地资源 / 存档之外的下载残留（设置页「删除本地数据」与打靶页「删除资源」共用）。
     *
     * 目录删完要写回 Compose 状态，所以整件事放 [scope]（主线程）上串起来，
     * 只有真正碰磁盘的那步（1.5GB 的目录树）切到 IO——否则界面会卡住一两秒。
     *
     * @param onDone 完成回调（设置页用它收起「正在删除…」并返回首页）
     */
    fun deleteGame(onDone: () -> Unit = {}) {
        if (busy) {
            log("有任务在进行，忽略删除请求")
            return
        }
        busy = true
        busyText = "正在删除本地数据…"
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    installer.deleteGame()
                    repository.clearDownloadFiles()
                }
                // 资源没了，「已清缓存的版本」也要跟着作废：重新装回同一版本时还得再清一次
                // （必须在 refreshDownloadState() 之前清，否则会把旧版本号又写回去）
                Prefs.putString(KEY_LAST_CACHED_VERSION, "")
                // 与鸿蒙版 Settings 的「删除本地数据」一致：数据删了就顺手把缓存也清掉
                if (WebCache.clear("game deleted")) log("已清除 WebView 缓存")
                log("game deleted")
                // 一次把 gameReady / installedVersion / pendingDownload / freeBytes 全部对齐磁盘
                refreshDownloadState()
            } catch (e: Throwable) {
                log("delete failed: ${e.message}")
            } finally {
                busy = false
                busyText = ""
                onDone()
            }
        }
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

    /**
     * 存档快照回填（`PokeSaveGuard.notifyRestored`）。
     *
     * 调用方在 **WebView 的 JS 线程**（`@JavascriptInterface`），所以要切回主线程再写状态。
     */
    fun onSaveRestored(count: Int) {
        mainHandler.post {
            saveRestoredCount = count
            saveRestoredSeq++
        }
    }

    /**
     * 游戏内「导出数据」（`PokeNative.saveDownload`，对应鸿蒙 `WebDownloadDelegate`）。
     *
     * 调用方在 **WebView 的 JS 线程**，且 base64 解码 + MediaStore 写入都是阻塞 IO，
     * 所以先切 IO 线程写文件，再回主线程发布给游戏页弹 Toast。
     */
    fun onSaveExport(name: String, mime: String, base64: String) {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { DownloadBridge.saveToDownloads(app, name, base64, mime) }
                    .getOrElse { t ->
                        Log.w(Const.TAG, "export failed: ${t.message}")
                        DownloadBridge.exportFailText(t.message ?: t.javaClass.simpleName)
                    }
            }
            saveExportText = text
            saveExportSeq++
        }
    }

    // ------------------------------------------------------------ 应用内自更新

    /**
     * 自更新下载状态（P4）。下载跑在 [scope] 里而不是前台服务：安装包只有几 MB、
     * 而且必须用户盯着才能点「安装」，用 `dataSync` 前台服务反而会把 App 拖进
     * Android 15 的 6h/24h 配额（资源包的 507MB 才需要那套）。代价：下载中切后台/被杀
     * 会断，重进设置页得重新点（文件名固定，会覆盖掉半截的 `.part`）。
     */
    var apkState by mutableStateOf(ApkUpdateState())
        private set

    @Volatile
    private var apkCancelRequested = false

    /**
     * 开始应用内更新：下载 [url] 到 `apk/` 目录 → 校验是不是真安装包 → 置 [ApkUpdatePhase.READY]。
     *
     * 到 READY 为止不碰系统安装器（那一步需要 Activity/Ctx 与「安装未知应用」开关，由 UI 层发起）。
     * 重复点击是幂等的：已在下载中就直接忽略。
     */
    fun startApkUpdate(url: String, versionName: String = "") {
        if (apkState.downloading || apkState.phase == ApkUpdatePhase.VERIFYING) return
        if (url.isBlank()) {
            apkState = ApkUpdateState(
                phase = ApkUpdatePhase.FAILED,
                versionName = versionName,
                message = NO_APK_URL_TEXT
            )
            return
        }
        val name = apkFileName(url, versionName)
        apkCancelRequested = false
        apkState = ApkUpdateState(
            phase = ApkUpdatePhase.DOWNLOADING,
            fileName = name,
            versionName = versionName
        )
        Log.i(Const.TAG, "apk update: start file=$name version=$versionName url=$url")
        scope.launch {
            try {
                val dest = ApkUpdater.targetFile(app, name)
                val size = withContext(Dispatchers.IO) {
                    ApkUpdater.downloadApk(
                        url = url,
                        dest = dest,
                        onProgress = { done, total ->
                            apkState = apkState.copy(doneBytes = done, totalBytes = total)
                        },
                        isCancelled = { apkCancelRequested }
                    )
                }
                apkState = apkState.copy(phase = ApkUpdatePhase.VERIFYING, doneBytes = size, totalBytes = size)
                val ok = withContext(Dispatchers.IO) {
                    val verified = ApkUpdater.verify(dest)
                    val pruned = if (verified) ApkUpdater.pruneOldApks(ApkUpdater.apkDir(app), name) else 0
                    if (pruned > 0) Log.i(Const.TAG, "apk update: pruned $pruned old files")
                    verified
                }
                apkState = if (ok) {
                    Log.i(Const.TAG, "apk update: ready file=$name bytes=$size")
                    apkState.copy(phase = ApkUpdatePhase.READY, message = "")
                } else {
                    Log.w(Const.TAG, "apk update: invalid package bytes=$size")
                    runCatching { dest.delete() }
                    apkState.copy(phase = ApkUpdatePhase.FAILED, message = INVALID_APK_TEXT)
                }
            } catch (c: ApkUpdateCancelled) {
                Log.i(Const.TAG, "apk update: cancelled")
                apkState = ApkUpdateState(
                    phase = ApkUpdatePhase.IDLE,
                    versionName = versionName,
                    message = c.message ?: ""
                )
            } catch (t: Throwable) {
                Log.w(Const.TAG, "apk update failed: ${t.message}")
                apkState = apkState.copy(
                    phase = ApkUpdatePhase.FAILED,
                    message = t.message ?: t.javaClass.simpleName
                )
            }
        }
    }

    /** 取消自更新下载（下载循环每块检查一次，最多 64KB 后生效） */
    fun cancelApkUpdate() {
        if (apkState.downloading) apkCancelRequested = true
    }

    /** 清理状态（安装器已接手 / 用户返回时调用，避免界面停在「已下载完成」） */
    fun resetApkUpdate() {
        apkCancelRequested = true
        apkState = ApkUpdateState()
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

    private companion object {
        /** 已为其清过 WebView 缓存的资源包版本（见 [maybeClearCacheAfterUpgrade]） */
        const val KEY_LAST_CACHED_VERSION = "lastCachedGameVersion"
    }
}
