package com.lichtcui.pokelauncher.core

/**
 * 跨端共享的用户可见文案与展示常量（纯数据，无 UI 依赖）。
 *
 * ⚠️ **本文件由 `scripts/gen-shared.mjs` 自动生成，不要手改**：唯一真相在仓库根 `shared/ui.json`。
 * 改文案跑 `node scripts/gen-shared.mjs`，两端（鸿蒙 / 安卓）一起更新。
 *
 * 为什么这几个字符串要共享：它们以前各端各写一份（首页流程标题 / 进度行文案、存储不足提示、
 * 完成 / 失败通知、游戏页背景色），分叉了没有任何机械护栏 —— 两端测试全绿，用户看到的却不同。
 * 生成器那边的 `checkUiUsage` 会反向要求业务代码真的引用这里，别再内联回去。
 */

object SharedUi {
    /** 进度行里的剩余时间前缀（「剩余01:23」） */
    const val TEXT_ETA_PREFIX: String = "剩余"

    /** 存储不足提示；参数依次是「需要的大小」「可用的大小」（已格式化） */
    fun textStorageLow(required: String, free: String): String =
        "存储空间不足：需要约 $required，可用 $free（含解压与更新期间的双份占用）"

    /** 选源测速阶段的流程标题 / 进度行说明 */
    const val TEXT_PROBING_TITLE: String = "正在测速，选择最快的下载源…"

    /** 已连上源、还没拿到第一批数据时的进度行说明 */
    const val TEXT_CONNECTING: String = "连接中…"

    /** 下载中的流程标题 / 前台通知（任务）标题 */
    const val TEXT_DOWNLOAD_TITLE: String = "正在下载 PokeRogue 资源"

    /** 解压中的流程标题 / 通知标题（需要省略号的地方由调用方追加「…」） */
    const val TEXT_EXTRACT_TITLE: String = "正在解压 PokeRogue 资源"

    /** 资源就绪后、进游戏前的流程标题 */
    const val TEXT_READY_TITLE: String = "PokeRogue 资源准备就绪"

    /** 暂停时的流程标题 / 进度行说明 */
    const val TEXT_PAUSED: String = "已暂停"

    /** 完成通知标题 */
    const val TEXT_NOTIFY_COMPLETE_TITLE: String = "PokeRogue 资源已就绪"

    /** 完成通知正文（点击进游戏） */
    const val TEXT_NOTIFY_COMPLETE_TEXT: String = "下载并安装完成，打开 App 即可离线游玩"

    /** 失败通知标题 */
    const val TEXT_NOTIFY_FAILED_TITLE: String = "PokeRogue 资源下载失败"

    /** 失败通知正文兜底（没有具体原因时） */
    const val TEXT_NOTIFY_FAILED_TEXT: String = "点击重试"

    /** 游戏页 / WebView 透出的背景色（安卓侧为 Int ARGB） */
    val GAME_BG_COLOR: Int = 0xFF484050.toInt()
}
