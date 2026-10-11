package com.lichtcui.pokelauncher.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.lichtcui.pokelauncher.core.Const
import java.lang.ref.WeakReference

/**
 * 清除 WebView 的 HTTP 缓存（对应鸿蒙版 `common/WebCache.ets` 的 `clearWebCache()`）。
 *
 * 时机与鸿蒙版一致：资源包安装完成后、删除本地数据之后——否则 WebView 会把上一版
 * 游戏资源（`index.html` / 大 JS bundle）从磁盘缓存里取回来，表现为「装了新版还是老界面」。
 *
 * ## 两条红线
 * 1. **绝不用 `clearStorage` / `WebStorage.deleteAllData()`**：那会连 localStorage
 *    （= 玩家的存档）一起抹掉。这里只调 `clearCache(true)`。
 * 2. `clearCache` 必须**主线程**调用（WebView 的通用约束）。
 *
 * 优先用当前存活的 WebView 实例；游戏页没打开时临时造一个再销毁
 * （HTTP 缓存是全 App 共享的，任何一个实例清都等于清全局）；
 * 临时实例失败只记日志——清缓存失败不该阻断进入游戏。
 */
object WebCache {

    @Volatile
    private var appContext: Context? = null

    private var liveRef: WeakReference<WebView>? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** App 启动时调用（临时实例方案需要 Context） */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 游戏页 WebView 创建后登记（用于直接清那个实例） */
    fun attach(view: WebView) {
        liveRef = WeakReference(view)
    }

    /** 游戏页 WebView 销毁后注销（避免清一个已 destroy 的实例） */
    fun detach(view: WebView) {
        if (liveRef?.get() === view) liveRef = null
    }

    fun hasLiveView(): Boolean = liveRef?.get() != null

    /**
     * 清缓存。
     * @return true = 已在主线程同步清完；false = 不在主线程（已转投主线程）或没有可用实例
     */
    fun clear(reason: String): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.w(Const.TAG, "webcache clear defer to main thread: $reason")
            mainHandler.post { clearNow(reason) }
            return false
        }
        return clearNow(reason)
    }

    private fun clearNow(reason: String): Boolean {
        liveRef?.get()?.let { live ->
            return try {
                live.clearCache(true)
                Log.i(Const.TAG, "webcache cleared (live webview): $reason")
                true
            } catch (e: Throwable) {
                Log.w(Const.TAG, "webcache clear failed on live webview: ${e.message}")
                false
            }
        }

        val ctx = appContext
        if (ctx == null) {
            Log.w(Const.TAG, "webcache clear skipped (no context): $reason")
            return false
        }

        var temp: WebView? = null
        return try {
            // 只用 applicationContext 造一个一次性实例：不 attach 到任何窗口，不加载任何 URL
            temp = WebView(ctx)
            temp.clearCache(true)
            Log.i(Const.TAG, "webcache cleared (temp webview): $reason")
            true
        } catch (e: Throwable) {
            Log.w(Const.TAG, "webcache clear failed on temp webview: ${e.message}")
            false
        } finally {
            runCatching { temp?.destroy() }
        }
    }
}
