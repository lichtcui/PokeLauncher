package com.lichtcui.pokelauncher.core

/**
 * 全局常量。与鸿蒙版 `common/Const.ets` 共用同一套取值，
 * 发版时两边必须同步（尤其是镜像列表与游戏资源来源）。
 */
object Const {
    const val TAG = "PokeLauncher"

    /** 游戏资源服务端口。**改端口会丢存档**（localStorage 以 origin 为键），故固定且不做回退 */
    const val GAME_PORT = 18787

    /** 游戏内 API 地址 `http://localhost:8001`，这里也监听它避免客户端等待超时 */
    const val API_STUB_PORT = 8001

    /** 沙箱内游戏目录名 */
    const val GAME_DIR_NAME = "game"

    /** 解压 staging 目录 / 旧包备份目录 / 完成标记（原子安装状态机用） */
    const val GAME_NEW_DIR_NAME = "game.new"
    const val GAME_OLD_DIR_NAME = "game.old"
    const val EXTRACT_MARKER = ".extract-complete"

    /** GitHub Release API（离线资源包来源） */
    const val RELEASE_API = "https://api.github.com/repos/Admiral-Billy/pokerogue/releases/latest"
    const val USER_AGENT = "PokeLauncher-Android"

    /**
     * 下载镜像前缀（按顺序尝试），拼接在原始 GitHub URL 之前。
     * 顺序按实测速度排（与鸿蒙版一致，2026-09 探测）。
     */
    val DOWNLOAD_MIRRORS = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/"
    )

    /** API 镜像（部分镜像不支持 api.github.com） */
    const val API_MIRROR = "https://gh-proxy.com/"

    /** 默认资源包资产名 */
    const val DEFAULT_ASSET = "game.zip"

    /**
     * 强制 2D canvas 使用 willReadFrequently。
     * Chromium 通病：Phaser 为每个 Text 调 getImageData，有 GPU 读回固定开销，
     * 游戏启动创建约 2 万个 Text 会累计数秒。鸿蒙实测 28s → 4s，Android 同样适用。
     * P0 阶段这是**可切换的 A/B 开关**，用于验证收益是否真实存在。
     */
    const val FORCE_WILL_READ_FREQUENTLY = true

    /**
     * WebView Chromium 主版本底线。低于此版本不放行，直接拦下引导升级
     * （游戏依赖现代 Chromium；国产 ROM 没有 Play 自动更新，这是真实风险）。
     */
    const val MIN_CHROMIUM_MAJOR = 100

    /** 注入脚本的虚拟端点（不存在于磁盘，请求时动态生成） */
    const val BOOT_SCRIPT_PATH = "/__pk_boot.js"

    /** 本地服务供 WebView 加载的入口页 */
    const val INDEX_FILE = "index.html"
}
