package com.lichtcui.pokelauncher.ui

/**
 * 游戏页的纯逻辑（不依赖 Compose，可 JVM 单测）。
 *
 * 与 `ui/HomeState.kt` 同一套路：本机既没有真机也没有模拟器，游戏页只能靠编译 + 单测
 * 把「哪一层浮层该显示」「返回按钮多久淡出」这类判定锁住。真正的画面（WebView、
 * 音频静音、方向）必须真机验证（见 `docs/android-port.md` §4.4）。
 */

/** 游戏页浮层状态（同一时刻只显示一层） */
enum class GameOverlay { NONE, LOADING, ERROR }

/** 返回按钮全显时的不透明度 */
const val BACK_FULL_ALPHA = 1f

/** 返回按钮淡出后的不透明度（对应鸿蒙 `GamePage` 的 `opacity(this.backVisible ? 1 : 0.2)`） */
const val BACK_FADED_ALPHA = 0.2f

/** 返回按钮显示多久后自动淡出（对应鸿蒙 `setTimeout(..., 3000)`） */
const val BACK_FADE_MS = 3000L

/**
 * 游戏页背景色在 `core/SharedUi.kt` 的 `GAME_BG_COLOR`（与鸿蒙 `common/SharedUi.ets` 同一条，
 * 由 `shared/ui.json` 生成）：WebView 透出的底色 / 加载期间的留白。
 * 在此之前两端各写一份（安卓 `0xFF121212`、鸿蒙 `#484050`），谁也不知道谁是对的。
 */

/**
 * 浮层判定：**出错优先于加载中**。
 *
 * 顺序不能反：页面加载失败时 `pageLoaded` 永远是 false，如果先判加载中，用户会一直
 * 盯着「正在载入游戏…」而看不到任何错误提示（鸿蒙那边是两个独立状态，不会互相遮）。
 */
fun gameOverlayOf(pageLoaded: Boolean, fatalError: String): GameOverlay = when {
    fatalError.isNotEmpty() -> GameOverlay.ERROR
    !pageLoaded -> GameOverlay.LOADING
    else -> GameOverlay.NONE
}

/** 错误浮层里的明细文案（与鸿蒙「加载出错: <desc>」同前缀） */
fun loadErrorText(detail: String): String =
    if (detail.isEmpty() || detail == "—") "" else "加载出错: $detail"

/**
 * 存档从快照回填后的提示文案（对应鸿蒙 `GamePage.onPageEnd` 里那句 toast）。
 * `count <= 0` 返回 null = 不提示。
 *
 * 为什么要提示：存档是「本地缺失才回填」，用户会看到进度回到某次快照的状态，
 * 不说明的话会以为存档被吞了。
 */
fun saveRestoredText(count: Int): String? =
    if (count > 0) "检测到本地存档缺失，已从快照恢复 $count 项数据" else null

/**
 * `WebViewFeature.MUTE_AUDIO` 不支持时的兜底静音脚本（注入 `game.sound.mute`）。
 *
 * 引导脚本已经把 Game 实例挂到 `window.__pkGame`（`server/InjectScripts.kt`），
 * 这里只读它的 `sound.mute`；拿不到实例时静默失败（下一个支持 MUTE_AUDIO 的设备不受影响）。
 */
fun muteFallbackScript(muted: Boolean): String =
    "(function(m){try{var g=window.__pkGame;" +
        "if(g&&g.sound&&typeof g.sound.mute!=='undefined'){g.sound.mute=!!m;}}catch(e){}})(" +
        muted +
        ");"
