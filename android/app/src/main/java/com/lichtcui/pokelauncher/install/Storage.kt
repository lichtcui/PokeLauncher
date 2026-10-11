package com.lichtcui.pokelauncher.install

/**
 * 存储预检（纯函数，可 JVM 单测）。
 *
 * 按 **3.2×** 估算：下载期间要同时容纳「已下载的 zip + 解压中的新包 + 更新期间的旧包」。
 * 下载**开始前**就要拦住，而不是等解压到一半失败（507MB 的包重下代价极高）。
 */
object Storage {

    const val FACTOR = 3.2

    fun requiredBytes(assetBytes: Long): Long = (assetBytes * FACTOR).toLong()

    /** 空间够返回 null，不够返回明确的中文提示（直接可展示给用户） */
    fun check(availableBytes: Long, assetBytes: Long): String? {
        if (assetBytes <= 0) return null // 大小未知（调试包）：不做预检
        val need = requiredBytes(assetBytes)
        if (availableBytes >= need) return null
        return "存储空间不足：需要约 ${need / 1024 / 1024} MB，" +
            "可用 ${availableBytes / 1024 / 1024} MB（含解压与更新期间的双份占用）"
    }
}
