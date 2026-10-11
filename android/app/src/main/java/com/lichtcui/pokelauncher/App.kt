package com.lichtcui.pokelauncher

import android.app.Application
import android.util.Log
import android.webkit.WebView
import com.lichtcui.pokelauncher.cheat.CheatState
import com.lichtcui.pokelauncher.core.Const
import com.lichtcui.pokelauncher.core.Prefs
import com.lichtcui.pokelauncher.web.SaveGuard
import com.lichtcui.pokelauncher.web.WebCache

/**
 * 对应鸿蒙版 `entryability/EntryAbility` 的进程级初始化。
 *
 * 加载/解密游戏资源仍由首页动作触发；这里只做「必须在任何请求之前就绪」的事：
 * 日志 tag、WebView 远程调试开关（仅 debug）、偏好（参数调整状态）、存档镜像目录。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 与鸿蒙版一致：整个 App 的日志统一 tag，便于 hilog/logcat 过滤
        Log.i(Const.TAG, "PokeLauncher ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) start")

        // debug 包才开远程调试：正式包开着等于把游戏页面暴露给同网段调试工具
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        // ⚠️ 顺序要求：偏好 → 参数调整状态 → 存档镜像
        // 本地服务第一个 /index.html 请求就会读 CheatState.hasAnyEnabled()，
        // 没载入完会注入错误的 index.html（少一个 /__cheats__.js 标签）且只能靠重进页面补救。
        Prefs.init(this)
        CheatState.load()
        SaveGuard.init(filesDir)
        WebCache.init(this)
        Log.i(
            Const.TAG,
            "init done: cheats=${CheatState.hasAnyEnabled()} (${CheatState.enabledIds().size} 项), " +
                "saveGuard=${SaveGuard.isReady()}, saveSnapshot=${SaveGuard.loadSnapshot().length} chars"
        )
    }
}
