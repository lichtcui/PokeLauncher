package com.lichtcui.pokelauncher.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
import com.lichtcui.pokelauncher.BuildConfig
import com.lichtcui.pokelauncher.core.Const

/**
 * JS → 原生桥。对应鸿蒙版 `GamePage` 里的 `javaScriptProxy`（PokeSaveGuard）。
 *
 * ⚠️ `@JavascriptInterface` 方法在 **WebView 的 JS 线程**回调，不能直接改 Compose 状态，
 * 必须切回主线程（调用方负责）。方法名受混淆影响，`proguard-rules.pro` 已保留。
 */
private class NativeBridge(
    private val onTiming: (String) -> Unit,
    private val onState: (String) -> Unit
) {
    @JavascriptInterface
    fun reportTiming(json: String) = onTiming(json)

    @JavascriptInterface
    fun reportState(json: String) = onState(json)
}

/**
 * 存档守卫桥（对应鸿蒙版 `GamePage` 的 `javaScriptProxy`，对象名 `PokeSaveGuard`）。
 *
 * ⚠️ 方法名与 `SaveGuard.buildScript()` 里调用的名字一一对应，改动要同步
 * `app/proguard-rules.pro`（`@JavascriptInterface` 方法名由混淆规则保留）。
 * `load()` / `save()` 都是同步返回的：JS 侧在 localStorage 为空时必须能**同步**拿到
 * 快照再回填，异步化会让游戏读到空存档。
 */
private class SaveGuardBridge {
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
    }
}

/**
 * 游戏 WebView。对应鸿蒙版 `pages/GamePage.ets`（P0 阶段只保留「能加载 + 可观测」的部分，
 * 下载代理 / 静音 / 方向 / 返回按钮在 P3 补齐）。
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
    onError: (String) -> Unit
) {
    // 回调放进 rememberUpdatedState：否则 AndroidView 的 factory 会捕获首次组合的旧闭包
    val currentLog by rememberUpdatedState(onLog)
    val currentTiming by rememberUpdatedState(onTiming)
    val currentState by rememberUpdatedState(onState)
    val currentFinished by rememberUpdatedState(onPageFinished)
    val currentError by rememberUpdatedState(onError)

    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageStart by remember { mutableStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose {
            // 必须显式销毁：WebView 持有 native 资源，且会被主线程全局列表引用
            webView?.let { wv ->
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
                            onState = { json -> Log.i(Const.TAG, "STATE $json"); currentState(json) }
                        ),
                        "PokeNative"
                    )
                    // 存档镜像：localStorage ⇄ filesDir/saves/localstorage.json
                    addJavascriptInterface(SaveGuardBridge(), SaveGuard.BRIDGE_NAME)
                    // 登记给 WebCache：资源更新后需要清掉这个实例的 HTTP 缓存
                    WebCache.attach(this)
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
                    }
                    loadUrl(url)
                }.also { webView = it }
            }
        )
    }
}
