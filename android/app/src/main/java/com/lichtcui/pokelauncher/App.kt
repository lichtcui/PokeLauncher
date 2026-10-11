package com.lichtcui.pokelauncher

import android.app.Application
import android.util.Log
import android.webkit.WebView
import com.lichtcui.pokelauncher.core.Const

/**
 * 对应鸿蒙版 `entryability/EntryAbility` 的进程级初始化。
 *
 * P0 阶段只做两件事：记启动日志、按构建类型开关 WebView 远程调试。
 * 后续（P1+）这里会预热本地 HTTP 服务，避免首页点「进入游戏」时才启动端口。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 与鸿蒙版一致：整个 App 的日志统一 tag，便于 hilog/logcat 过滤
        Log.i(Const.TAG, "PokeLauncher ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) start")

        // debug 包才开远程调试：正式包开着等于把游戏页面暴露给同网段调试工具
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }
}
