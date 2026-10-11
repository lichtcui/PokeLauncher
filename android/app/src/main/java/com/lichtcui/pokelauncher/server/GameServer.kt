package com.lichtcui.pokelauncher.server

import android.util.Log
import com.lichtcui.pokelauncher.core.Const
import com.lichtcui.pokelauncher.core.Mime
import com.lichtcui.pokelauncher.core.Paths
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
 * 因此不做多端口回退——18787 被占用时宁可启动失败并明确报错。
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
        return try {
            val http = GameHttpServer()
            http.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            server = http

            // 游戏内 API 地址是 http://localhost:8001：不监听会让客户端连不上而等待超时，
            // 未知路径一律 404，游戏按离线逻辑处理（与鸿蒙版一致）。
            try {
                val stub = ApiStubServer()
                stub.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                apiStub = stub
            } catch (e: Exception) {
                Log.w(Const.TAG, "listen ${Const.API_STUB_PORT} failed: ${e.message}")
            }

            state = State.RUNNING
            errorText = ""
            Log.i(Const.TAG, "local http server on $baseUrl (canvasPatch=$patchCanvas)")
            true
        } catch (e: Exception) {
            server = null
            apiStub = null
            state = State.FAILED
            errorText = "本地服务启动失败（端口 ${Const.GAME_PORT}）：${e.message ?: e.javaClass.simpleName}"
            Log.e(Const.TAG, errorText, e)
            false
        }
    }

    fun stop() {
        runCatching { server?.stop() }
        runCatching { apiStub?.stop() }
        server = null
        apiStub = null
        state = State.STOPPED
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

        /** 读出 index.html，在 `<head>` 之后注入引导脚本（必须早于游戏自身脚本） */
        private fun servePatchedIndex(): Response {
            val file = File(gameDirProvider(), Const.INDEX_FILE)
            if (!file.isFile) return text(Response.Status.NOT_FOUND, "Not Found")

            val html = file.readText(Charsets.UTF_8)
            val inject = InjectScripts.indexInjections(patchCanvas)
            val headIdx = html.indexOf("<head>")
            val patched = if (headIdx >= 0) {
                html.substring(0, headIdx + 6) + inject + html.substring(headIdx + 6)
            } else {
                inject + html
            }

            Log.i(Const.TAG, "served patched index.html (canvasPatch=$patchCanvas)")
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
            res.addHeader("Cache-Control", cacheControl(path))
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

    /** 与鸿蒙版 `cacheControl()` 一致：HTML / manifest / SW 不缓存，其余长缓存 */
    private fun cacheControl(path: String): String {
        return if (path.endsWith(".html") || path.endsWith(".htm") ||
            path.endsWith(".webmanifest") || path.endsWith("service-worker.js")
        ) {
            "no-cache"
        } else {
            "max-age=31536000"
        }
    }
}
