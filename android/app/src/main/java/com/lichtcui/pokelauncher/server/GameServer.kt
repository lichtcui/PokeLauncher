package com.lichtcui.pokelauncher.server

import android.util.Log
import com.lichtcui.pokelauncher.cheat.CheatState
import com.lichtcui.pokelauncher.core.Const
import com.lichtcui.pokelauncher.core.Mime
import com.lichtcui.pokelauncher.core.Paths
import com.lichtcui.pokelauncher.web.DownloadBridge
import com.lichtcui.pokelauncher.web.SaveGuard
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地 HTTP 服务：把沙箱 `game` 目录以 `http://127.0.0.1:18787` 提供出去。
 *
 * 对应鸿蒙版 `model/LocalHttpServer.ets`。为什么不用 WebView 的
 * `shouldInterceptRequest`：那会退化成逐请求拦截（鸿蒙实测约 55ms/请求），
 * 真实 HTTP 并发更高、延迟更低，并且内核会正常做 HTTP 缓存。
 *
 * **端口必须固定**：localStorage 以 origin 为键，换端口等于丢存档。
 * 因此不做多端口回退——18787 被占用时宁可启动失败并明确报错（[ServerDiag] 给中文提示）。
 * 唯一的例外是**同进程内的残留实例**：`start()` 会先 `stop()`，再重试一次；
 * 第二次仍失败就认定为真·端口占用。
 */
class GameServer(private val gameDirProvider: () -> File) {

    enum class State { STOPPED, RUNNING, FAILED }

    @Volatile
    var state: State = State.STOPPED
        private set

    @Volatile
    var errorText: String = ""
        private set

    /** P0 的 A/B 开关：是否注入 canvas `willReadFrequently` 补丁 */
    @Volatile
    var patchCanvas: Boolean = Const.FORCE_WILL_READ_FREQUENTLY

    @Volatile
    var servedCount: Int = 0
        private set

    @Volatile
    var servedBytes: Long = 0
        private set

    private var server: NanoHTTPD? = null
    private var apiStub: NanoHTTPD? = null

    val baseUrl: String
        get() = if (state == State.RUNNING) "http://127.0.0.1:${Const.GAME_PORT}" else ""

    val indexUrl: String
        get() = "$baseUrl/${Const.INDEX_FILE}"

    /** 启动失败时返回 false，并把原因写进 [errorText]（调用方据此拦截进游戏，避免白屏） */
    fun start(): Boolean {
        if (state == State.RUNNING) return true
        stop()
        var failure: Throwable? = null
        for (attempt in 1..2) {
            failure = bindAll()
            if (failure == null) {
                state = State.RUNNING
                errorText = ""
                Log.i(Const.TAG, "local http server on $baseUrl (canvasPatch=$patchCanvas)")
                return true
            }
            Log.w(Const.TAG, "第 $attempt 次启动本地服务失败：${failure.message}")
            if (attempt == 1) {
                // 最常见的原因是「上一次的实例还占着端口」（进程内重进游戏页、上次 stop 未回收），
                // 所以先彻底关掉再试一次；真被别的 App 占用时第二次仍会失败。
                stopQuietly()
                runCatching { Thread.sleep(RETRY_DELAY_MS) }
            }
        }
        state = State.FAILED
        errorText = ServerDiag.describe(Const.GAME_PORT, failure)
        Log.e(Const.TAG, errorText, failure)
        return false
    }

    /** 绑定两个端口；成功返回 null，失败返回原因（不写 [state] / [errorText]，由 [start] 统一决定） */
    private fun bindAll(): Throwable? {
        val http = GameHttpServer()
        try {
            http.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        } catch (e: Throwable) {
            runCatching { http.stop() }
            return e
        }
        server = http

        // 游戏内 API 地址是 http://localhost:8001：不监听会让客户端连不上而等待超时，
        // 未知路径一律 404，游戏按离线逻辑处理（与鸿蒙版一致）。
        try {
            val stub = ApiStubServer()
            stub.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            apiStub = stub
        } catch (e: Throwable) {
            Log.w(Const.TAG, "listen ${Const.API_STUB_PORT} failed: ${e.message}")
        }
        return null
    }

    fun stop() {
        stopQuietly()
        state = State.STOPPED
    }

    private fun stopQuietly() {
        runCatching { server?.stop() }
        runCatching { apiStub?.stop() }
        server = null
        apiStub = null
    }

    // ---------------------------------------------------------------- 资源服务

    private inner class GameHttpServer : NanoHTTPD("127.0.0.1", Const.GAME_PORT) {

        init {
            // 默认 DefaultAsyncRunner 是「每连接一个线程」且无上限；游戏冷启动会并发拉数百个
            // 资源，这里换成有界池，避免线程数失控。
            setAsyncRunner(PooledAsyncRunner(16))
        }

        override fun serve(session: IHTTPSession): Response {
            val rawPath = session.uri ?: "/"
            val path = Paths.resolveRequestPath(rawPath)
                ?: return text(Response.Status.FORBIDDEN, "Forbidden")

            return try {
                when {
                    path == Const.BOOT_SCRIPT_PATH -> serveBootScript()
                    path == Const.CHEAT_SCRIPT_PATH -> serveCheatScript()
                    path == "/${Const.INDEX_FILE}" -> servePatchedIndex()
                    else -> serveFile(path)
                }
            } catch (e: Exception) {
                Log.e(Const.TAG, "serve $path failed: ${e.message}", e)
                text(Response.Status.INTERNAL_ERROR, "Internal Error")
            }
        }

        /** 虚拟端点：按当前配置动态生成注入脚本（不存在于磁盘） */
        private fun serveBootScript(): Response {
            val body = InjectScripts.bootScript(patchCanvas)
            val res = NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "application/javascript; charset=utf-8", body
            )
            res.addHeader("Cache-Control", "no-cache")
            return res
        }

        /** 虚拟端点：「参数调整」运行时（对应鸿蒙版 `/__cheats__.js`） */
        private fun serveCheatScript(): Response {
            val body = CheatState.buildBootScript()
            Log.i(Const.TAG, "served ${Const.CHEAT_SCRIPT_PATH} (${body.length} bytes)")
            val res = NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "application/javascript; charset=utf-8", body
            )
            res.addHeader("Cache-Control", "no-cache")
            return res
        }

        /**
         * 读出 index.html，在 `<head>` 之后注入「存档导出拦截 + 存档守卫 + 参数调整 + 引导脚本」。
         * 注入顺序见 [InjectScripts.indexInjections]。
         */
        private fun servePatchedIndex(): Response {
            val file = File(gameDirProvider(), Const.INDEX_FILE)
            if (!file.isFile) return text(Response.Status.NOT_FOUND, "Not Found")

            val cheatsEnabled = CheatState.hasAnyEnabled()
            val html = file.readText(Charsets.UTF_8)
            val inject = InjectScripts.indexInjections(
                patchCanvas = patchCanvas,
                saveGuardScript = SaveGuard.buildScript(),
                cheatsEnabled = cheatsEnabled,
                downloadScript = DownloadBridge.buildScript()
            )
            val headIdx = html.indexOf("<head>")
            val patched = if (headIdx >= 0) {
                html.substring(0, headIdx + 6) + inject + html.substring(headIdx + 6)
            } else {
                inject + html
            }

            Log.i(
                Const.TAG,
                "served patched index.html (canvasPatch=$patchCanvas, cheats=$cheatsEnabled, " +
                    "saveGuard=${SaveGuard.isReady()})"
            )
            val res = NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, "text/html; charset=utf-8", patched
            )
            // 资源包更新后必须拿到新页面，否则会继续引用旧资源路径
            res.addHeader("Cache-Control", "no-cache")
            return res
        }

        private fun serveFile(path: String): Response {
            val gameDir = gameDirProvider()
            val file = File(gameDir, path)

            // 二次校验：resolveRequestPath 已判 `..`，这里再确认最终路径没跑出 game 目录
            if (!file.canonicalPath.startsWith(gameDir.canonicalPath + File.separator)) {
                return text(Response.Status.FORBIDDEN, "Forbidden")
            }
            if (!file.isFile) return text(Response.Status.NOT_FOUND, "Not Found")

            val length = file.length()
            noteServed(length)
            val res = NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK, Mime.mimeOf(file.name), FileInputStream(file), length
            )
            res.addHeader("Cache-Control", HttpCache.cacheControl(path))
            res.addHeader("Access-Control-Allow-Origin", "*")
            return res
        }

        private fun text(status: Response.IStatus, body: String): Response =
            NanoHTTPD.newFixedLengthResponse(status, "text/plain; charset=utf-8", body)
    }

    /**
     * 游戏内 API stub：只需「有东西在 8001 上应答」，未知路径 404 即可。
     * 与鸿蒙版行为一致（游戏按离线处理）。
     */
    private inner class ApiStubServer : NanoHTTPD("127.0.0.1", Const.API_STUB_PORT) {
        init {
            setAsyncRunner(PooledAsyncRunner(4))
        }

        override fun serve(session: IHTTPSession): Response =
            NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Not Found")
    }

    /**
     * 有界线程池版 [NanoHTTPD.AsyncRunner]。keep-alive 下每个连接占一个线程，
     * 浏览器同源并发通常为 6 个左右，16 个槽位足够且能兜住异常情况。
     */
    private class PooledAsyncRunner(threads: Int) : NanoHTTPD.AsyncRunner {

        private val executor = Executors.newFixedThreadPool(threads) { r ->
            Thread(r, "pk-http").apply { isDaemon = true }
        }
        private val running: MutableList<NanoHTTPD.ClientHandler> =
            Collections.synchronizedList(ArrayList())

        override fun closeAll() {
            val snapshot = synchronized(running) { ArrayList(running) }
            snapshot.forEach { runCatching { it.close() } }
            synchronized(running) { running.clear() }
        }

        override fun closed(clientHandler: NanoHTTPD.ClientHandler) {
            running.remove(clientHandler)
        }

        override fun exec(code: NanoHTTPD.ClientHandler) {
            running.add(code)
            try {
                executor.execute(code)
            } catch (e: RejectedExecutionException) {
                running.remove(code)
                runCatching { code.close() }
            }
        }
    }

    private fun noteServed(size: Long) {
        val count = servedCountRef.incrementAndGet()
        val bytes = servedBytesRef.addAndGet(size)
        servedCount = count
        servedBytes = bytes
        val now = System.currentTimeMillis()
        if (now - lastStatAt > 3000) {
            lastStatAt = now
            Log.i(Const.TAG, "served $count files, ${bytes / 1024} KB")
        }
    }

    private val servedCountRef = AtomicInteger(0)
    private val servedBytesRef = AtomicLong(0)

    @Volatile
    private var lastStatAt: Long = 0L

    private companion object {
        /** 端口被占用时「关掉自己的残留实例 → 重试」之间的等待 */
        const val RETRY_DELAY_MS = 150L
    }
}
