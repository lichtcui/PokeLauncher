package com.lichtcui.pokelauncher.install

import com.lichtcui.pokelauncher.core.Const

/** 一次测速的结果。[speed] 单位 bytes/ms（与鸿蒙版一致），<= 0 表示失败或超时 */
data class ProbeResult(val url: String, val speed: Double)

/**
 * 下载候选地址的构造与排序（纯函数，可 JVM 单测）。
 * 对应鸿蒙版 `GameRepository.buildDownloadUrls` / `rankSources`。
 */
object DownloadUrls {

    /**
     * 候选地址：镜像前缀逐个拼接，**最后**回退 GitHub 直连。
     * 本地调试包不走镜像（地址本身可达，且镜像基本只代理 github.com）。
     */
    fun candidates(
        assetUrl: String,
        mirrors: List<String> = Const.DOWNLOAD_MIRRORS,
        debugAssetUrl: String = Const.DEBUG_ASSET_URL
    ): List<String> {
        if (assetUrl.isEmpty()) return emptyList()
        if (debugAssetUrl.isNotEmpty() && assetUrl == debugAssetUrl) return listOf(assetUrl)
        val urls = ArrayList<String>(mirrors.size + 1)
        for (mirror in mirrors) urls.add(mirror + assetUrl)
        urls.add(assetUrl)
        return urls
    }

    /**
     * 按测速结果排序：快 → 慢 → 失败。
     *
     * - 有直连兜底（[fallbackUrl] 非空）时**跳过测速失败的镜像**，避免下载阶段再撞一次死源；
     * - 单源（调试包）不跳过：它是唯一候选，仍要尝试下载；
     * - 直连兜底**不参与测速**（白读 128KB 且通常最慢），永远排在最后；
     * - 同速保持传入顺序（`sortedByDescending` 是稳定排序）。
     */
    fun rank(probed: List<ProbeResult>, fallbackUrl: String?): List<String> {
        val sorted = probed.sortedByDescending { it.speed }
        val out = ArrayList<String>(sorted.size + 1)
        for (result in sorted) {
            if (fallbackUrl != null && result.speed <= 0) continue
            out.add(result.url)
        }
        if (fallbackUrl != null) out.add(fallbackUrl)
        return out
    }

    /** 拆分参与测速的镜像 与 直连兜底：单源时兜底为空（与鸿蒙版 `hasFallback` 判定一致） */
    fun split(candidates: List<String>): Pair<List<String>, String?> =
        if (candidates.size > 1) candidates.dropLast(1) to candidates.last() else candidates to null

    /** `https://gh-proxy.com/https://github.com/a/b.zip` → `gh-proxy.com`（界面/日志只显示 host） */
    fun hostOf(url: String): String {
        val schemeAt = url.indexOf("://")
        val rest = if (schemeAt >= 0) url.substring(schemeAt + 3) else url
        val slash = rest.indexOf('/')
        return if (slash >= 0) rest.substring(0, slash) else rest
    }

    /** 候选列表里的角色：最后一个候选是 GitHub 直连，其余是镜像 */
    fun kindOf(index: Int, count: Int): String = if (index < count - 1) "镜像" else "直连"

    /** GitHub 直链前缀：只有这种地址才套加速镜像 */
    const val GITHUB_PREFIX = "https://github.com/"

    /**
     * 给 GitHub 原始直链套上首个加速镜像（首页「下载太慢？」里展示给用户复制）。
     * 非 GitHub 地址或没有配置镜像时返回空串（调用方据此只显示原始直链）。
     * 与鸿蒙版 `pages/Index.mirrorOf` 同规则。
     */
    fun mirrorOf(url: String, mirrors: List<String> = Const.DOWNLOAD_MIRRORS): String =
        if (mirrors.isEmpty() || !url.startsWith(GITHUB_PREFIX)) "" else mirrors[0] + url
}
