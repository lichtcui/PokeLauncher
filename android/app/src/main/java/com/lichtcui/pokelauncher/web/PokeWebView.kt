package com.lichtcui.pokelauncher.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.lichtcui.pokelauncher.BuildConfig
import com.lichtcui.pokelauncher.core.Const
import com.lichtcui.pokelauncher.ui.muteFallbackScript

/**
 * JS → 原生桥。对应鸿蒙版 `GamePage` 里的 `javaScriptProxy`（PokeSaveGuard）。
 *
 * ⚠️ `@JavascriptInterface` 方法在 **WebView 的 JS 线程**回调，不能直接改 Compose 状态，
 * 必须切回主线程（调用方负责）。方法名受混淆影响，`proguard-rules.pro` 已保留。
 */
private class NativeBridge(
    private val onTiming: (String) -> Unit,
    private val onState: (String) -> Unit,
    private val onSaveExport: (String, String, String) -> Unit
) {
    @JavascriptInterface
    fun reportTiming(json: String) = onTiming(json)

    @JavascriptInterface
    fun reportState(json: String) = onState(json)

    /**
     * 游戏内「导出数据」走到了（`DownloadBridge` 的拦截脚本或兜底脚本）。
     *
     * `base64` / `name` 都来自页面，**不可信**，落盘前统一过 `DownloadBridge.saveToDownloads`；
     * 写盘是阻塞 IO，调用方（`SpikeController`）负责切到 IO 线程后再回调 UI。
     */
    @JavascriptInterface
    fun saveDownload(name: String, mime: String, base64: String) = onSaveExport(name, mime, base64)
}

/**
 * 存档守卫桥（对应鸿蒙版 `GamePage` 的 `javaScriptProxy`，对象名 `PokeSaveGuard`）。
 *
 * ⚠️ 方法名与 `SaveGuard.buildScript()` 里调用的名字一一对应，改动要同步
 * `app/proguard-rules.pro`（`@JavascriptInterface` 方法名由混淆规则保留）。
 * `load()` / `save()` 都是同步返回的：JS 侧在 localStorage 为空时必须能**同步**拿到
 * 快照再回填，异步化会让游戏读到空存档。
 */
private class SaveGuardBridge(private val onRestored: (Int) -> Unit) {
    @JavascriptInterface
    fun load(): String = SaveGuard.loadSnapshot()

    @JavascriptInterface
    fun save(data: String) {
        SaveGuard.submit(data)
    }

    @JavascriptInterface
    fun notifyRestored(count: Int) {
        SaveGuard.notifyRestored(count)
        Log.i(Const.TAG, "SAVEGUARD restored $count keys")
        onRestored(count)
    }
}

/**
 * 游戏 WebView。对应鸿蒙版 `pages/GamePage.ets`。
 *
 * 与鸿蒙同一口径的两条约定：
 * 1. **切后台只静音、不暂停**（鸿蒙注释「保留游戏进程」）：不调 `WebView.onPause()`，
 *    否则 Phaser 的计时器会冻结，回前台会出现追帧/卡顿；代价是后台仍占 CPU，留 P5 再评估。
 * 2. **优先用 `MUTE_AUDIO` 特性**（`WebViewCompat.setAudioMuted`，对应鸿蒙 `setAudioMuted`），
 *    不支持时降级注入 `game.sound.mute`（鸿蒙低版本也拿不到音频会话能力，两边都只能降级）。
 *
 * 存档导入/导出（对应鸿蒙 `WebDownloadDelegate` + `Web` 组件的文件选择）：
 * - 导出主路径是 [DownloadBridge.buildScript] 的 JS 拦截，残留的 `blob:` 下载交给
 *   `setDownloadListener` + [DownloadBridge.blobFallbackScript]（见前者的类注释）；
 * - 导入通过 [onShowFileChooser] 交给 Compose 层的 SAF 选择器（鸿蒙由 `Web` 组件自行弹选择器）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PokeWebView(
    url: String,
    modifier: Modifier = Modifier,
    onLog: (String) -> Unit,
    onTiming: (String) -> Unit,
    onState: (String) -> Unit,
    onPageFinished: (Long) -> Unit,
    onError: (String) -> Unit,
    onSaveRestored: (Int) -> Unit,
    onSaveExport: (String, String, String) -> Unit,
    onShowFileChooser: (WebChromeClient.FileChooserParams, ValueCallback<Array<Uri>>) -> Boolean
) {
    // 回调放进 rememberUpdatedState：否则 AndroidView 的 factory 会捕获首次组合的旧闭包
    val currentLog by rememberUpdatedState(onLog)
    val currentTiming by rememberUpdatedState(onTiming)
    val currentState by rememberUpdatedState(onState)
    val currentFinished by rememberUpdatedState(onPageFinished)
    val currentError by rememberUpdatedState(onError)
    val currentRestored by rememberUpdatedState(onSaveRestored)
    val currentSaveExport by rememberUpdatedState(onSaveExport)
    val currentFileChooser by rememberUpdatedState(onShowFileChooser)

    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageStart by remember { mutableStateOf(0L) }

    // 切后台静音（鸿蒙 onPageHide / onPageShow 的等价物）：由 Activity 生命周期驱动
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> setWebAudioMuted(webView, true)
                Lifecycle.Event.ON_RESUME -> setWebAudioMuted(webView, false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 必须显式销毁：WebView 持有 native 资源，且会被主线程全局列表引用
            webView?.let { wv ->
                // 销毁前先静音：安卓上有「destroy 后音频还在响」的机型，多这一步保险
                setWebAudioMuted(wv, true)
                WebCache.detach(wv)
                runCatching {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.removeAllViews()
                    wv.destroy()
                }
            }
            webView = null
            Log.i(Const.TAG, "webview destroyed")
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        // 存档就是 Web 的 localStorage，必须开
                        domStorageEnabled = true
                        // 不设这个，游戏 BGM 第一次不会响（需要用户手势才允许播放）
                        mediaPlaybackRequiresUserGesture = false
                        // 资源全部走本地 HTTP，无需 WebView 直接访问文件系统
                        allowFileAccess = false
                        allowContentAccess = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                    }
                    // 与鸿蒙版 DEBUG_UI 对应：仅 debug 包开远程调试，正式包关闭避免暴露调试端口
                    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                    addJavascriptInterface(
                        NativeBridge(
                            onTiming = { json -> Log.i(Const.TAG, "TIMING $json"); currentTiming(json) },
                            onState = { json -> Log.i(Const.TAG, "STATE $json"); currentState(json) },
                            onSaveExport = { name, mime, base64 ->
                                Log.i(Const.TAG, "export request: name=$name mime=$mime bytes=${base64.length}")
                                currentSaveExport(name, mime, base64)
                            }
                        ),
                        "PokeNative"
                    )
                    // 存档镜像：localStorage ⇄ filesDir/saves/localstorage.json
                    addJavascriptInterface(
                        SaveGuardBridge { count -> currentRestored(count) },
                        SaveGuard.BRIDGE_NAME
                    )
                    // 登记给 WebCache：资源更新后需要清掉这个实例的 HTTP 缓存
                    WebCache.attach(this)
                    // 存档导出的残留路径：blob: 不走 JS 拦截时（如 window.open）用 XHR 兜底
                    setDownloadListener(DownloadListener { downloadUrl, _, contentDisposition, mimeType, _ ->
                        handleDownloadStart(this, downloadUrl, contentDisposition, mimeType)
                    })
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            pageStart = SystemClock.elapsedRealtime()
                            Log.i(Const.TAG, "page started: $url")
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            val elapsed = SystemClock.elapsedRealtime() - pageStart
                            Log.i(Const.TAG, "page finished in ${elapsed}ms: $url")
                            currentFinished(elapsed)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            val text = "error ${error?.errorCode} ${error?.description} ${request?.url}"
                            // ⚠️ 这个 4 参版本对**所有资源**都会回调（iframe / 图片 / 脚本…），
                            // 只有主框架失败才该整页报错，否则游戏里随便丢一个可选资源
                            // 就会把整页换成错误卡片（子资源失败只记日志）。
                            if (request?.isForMainFrame != true) {
                                Log.w(Const.TAG, "sub-resource failed: $text")
                                return
                            }
                            Log.e(Const.TAG, text)
                            currentError(text)
                        }

                        /**
                         * 主框架拿到 4xx/5xx（典型场景：`index.html` 缺失 → 本地服务的 404）。
                         *
                         * `onReceivedError` 只报**连接级**错误，HTTP 状态码走这个回调 —— 不接的话
                         * 用户看到的是服务端裸文本（`Not Found`）白页，连「重试」都没有。
                         */
                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?
                        ) {
                            super.onReceivedHttpError(view, request, errorResponse)
                            if (request?.isForMainFrame != true) return
                            val code = errorResponse?.statusCode ?: 0
                            if (code < 400) return
                            val text = "http $code ${request.url}"
                            Log.e(Const.TAG, text)
                            currentError(text)
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                            val line = "[${msg.messageLevel()}] ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}"
                            Log.i(Const.TAG, "CONSOLE $line")
                            currentLog(line)
                            return true
                        }

                        /**
                         * 游戏内「导入数据」的 `<input type=file>`。
                         * 真正的选择器在 Compose 层（SAF + `rememberLauncherForActivityResult`），
                         * 这里只把请求转出去；对方返回 false 表示没接住，交回系统默认行为。
                         */
                        override fun onShowFileChooser(
                            view: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            val cb = filePathCallback ?: return false
                            val params = fileChooserParams ?: return false
                            Log.i(Const.TAG, "file chooser requested: mode=${params.mode} accept=${params.acceptTypes.joinToString(",")}")
                            return currentFileChooser(params, cb)
                        }
                    }
                    loadUrl(url)
                }.also { webView = it }
            }
        )
    }
}

/**
 * `DownloadListener` 的落地逻辑：只负责 `blob:` 的兜底（主路径是 JS 拦截，见 [DownloadBridge]）。
 *
 * http(s) 直链不在这里下载：游戏资源全部走本地 HTTP 服务，网页侧不需要自己下载文件；
 * 真的出现这种情况也只记日志，避免引入一条绕过下载服务/断点续传的旁路。
 */
private fun handleDownloadStart(
    webView: WebView,
    url: String?,
    contentDisposition: String?,
    mimeType: String?
) {
    val target = url ?: return
    if (!target.startsWith("blob:")) {
        Log.w(Const.TAG, "web download ignored (not blob): $target")
        return
    }
    val name = DownloadBridge.fileNameFromDisposition(contentDisposition)
    val script = DownloadBridge.blobFallbackScript(target, name, mimeType ?: "")
    Log.i(Const.TAG, "blob download fallback: $target")
    webView.evaluateJavascript(script, null)
}

/**
 * 切后台/回前台时静音 Web 音频（对应鸿蒙 `GamePage` 的 `onPageHide` / `onPageShow`）。
 *
 * 两条路径：
 * 1. `MUTE_AUDIO` 特性可用（Chromium 77+ / androidx.webkit 1.2+）→ `WebViewCompat.setAudioMuted`，
 *    与鸿蒙 `controller.setAudioMuted` 同语义；
 * 2. 不可用 → 注入 [muteFallbackScript] 直接改 Phaser 的 `game.sound.mute`（鸿蒙侧低版本
 *    同样拿不到音频会话能力，降级逻辑两边一致）。
 *
 * 只在主线程调用（生命周期回调本来就是主线程）；失败只记日志，不能影响游戏页渲染。
 */
private fun setWebAudioMuted(webView: WebView?, muted: Boolean) {
    val wv = webView ?: return
    try {
        // 特性判定必须写在条件里（写成局部变量 lint 的 RequiresFeature 就看不出来了）
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) {
            WebViewCompat.setAudioMuted(wv, muted)
        } else {
            wv.evaluateJavascript(muteFallbackScript(muted), null)
        }
        Log.i(Const.TAG, "web audio muted=$muted")
    } catch (t: Throwable) {
        // WebView 实现千奇百怪（尤其定制 ROM），静音失败绝不能把游戏页搞崩
        Log.w(Const.TAG, "mute web audio failed: muted=$muted, ${t.message}")
    }
}
