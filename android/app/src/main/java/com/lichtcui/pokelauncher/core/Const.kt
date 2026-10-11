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
     * 本地调试包地址：非空时离线资源包改用这个 URL 下载（跳过 Release 查询与镜像测速），
     * 用于本地放一个小测试包、几秒内跑完「测速 → 下载 → 解压 → 就绪」全链路。
     * 与鸿蒙版 `common/Const.ets` 的 `DEBUG_ASSET_URL` 同名同义，正式发布必须为空串。
     */
    const val DEBUG_ASSET_URL = ""

    /**
     * 断点续传用的临时文件名。
     * ⚠️ 这两个文件与「已下载字节数」都必须落在 filesDir / getExternalFilesDir：
     * cacheDir 会被系统「清除缓存」清掉，507MB 的进度不能赌这个。
     */
    const val PART_ZIP_NAME = "game.zip.part"
    const val DOWNLOAD_STATE_NAME = "game.zip.download.json"

    /** zip 内的资源版本标记（对应 GitHub Release 的 commit） */
    const val VERSION_FILE_NAME = "currentVersion.txt"

    /**
     * 下载前台服务的通知频道与通知 id（完成/失败用 id+1）。
     * 频道名在 `res/values/strings.xml`，不走这里是避免和资源文件两份文案。
     */
    const val DOWNLOAD_CHANNEL_ID = "pokerogue-download"
    const val DOWNLOAD_NOTIFICATION_ID = 1001

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

    /**
     * 「参数调整」注入脚本的虚拟端点（与鸿蒙版 `/__cheats__.js` 同名）。
     * ⚠️ UI 上叫「参数调整」：`作弊` 字样会触发应用商店审核风险，代码/文档沿用 cheat 命名。
     */
    const val CHEAT_SCRIPT_PATH = "/__cheats__.js"

    /**
     * 存档快照目录（`filesDir/saves/`）。
     * 用途是 Auto Backup / 存档导出兜底，**不**是 localstorage 的本体
     * （Android 的 localStorage 本来就在应用数据目录，不在 cacheDir）。
     */
    const val SAVE_DIR_NAME = "saves"
    const val SAVE_FILE_NAME = "localstorage.json"
    const val SAVE_BAK_NAME = "localstorage.bak.json"

    /** 本地服务供 WebView 加载的入口页 */
    const val INDEX_FILE = "index.html"
}
