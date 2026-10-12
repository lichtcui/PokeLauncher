package com.lichtcui.pokelauncher.install

/**
 * 存储预检（纯函数，可 JVM 单测）。
 *
 * 两个系数与鸿蒙版 `LauncherSession.requiredStorage()` **同值**（由 `scripts/gen-shared.mjs` 对账，
 * 漂了会构建失败）：
 * - 首次安装：zip + 解压后的包 ≈ **2.4×**；
 * - 更新：解压到 staging 期间要同时保留旧包 → zip + 旧包 + 新包 ≈ **3.2×**。
 *
 * 下载**开始前**就要拦住，而不是等解压到一半失败（507MB 的包重下代价极高）。
 */
object Storage {

    /** 首次安装（磁盘里没有旧包可退） */
    const val FACTOR_FRESH = 2.4

    /** 更新（解压期间旧包必须还在，才能回退） */
    const val FACTOR_UPDATE = 3.2

    fun requiredBytes(assetBytes: Long, updating: Boolean): Long =
        (assetBytes * (if (updating) FACTOR_UPDATE else FACTOR_FRESH)).toLong()

    /** 空间够返回 null，不够返回明确的中文提示（直接可展示给用户） */
    fun check(availableBytes: Long, assetBytes: Long, updating: Boolean): String? {
        if (assetBytes <= 0) return null // 大小未知（调试包）：不做预检
        val need = requiredBytes(assetBytes, updating)
        if (availableBytes >= need) return null
        return "存储空间不足：需要约 ${need / 1024 / 1024} MB，" +
            "可用 ${availableBytes / 1024 / 1024} MB（含解压与更新期间的双份占用）"
    }
}
