package com.lichtcui.pokelauncher.core

import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 纯展示格式化（无副作用，可 JVM 单测）。
 * 逐字对齐鸿蒙版 `common/Format.ets`，保证两端文案一致。
 */
object Format {

    /** 字节数 → 人类可读：0 B / 356 KB / 8.2 MB / 1.1 GB */
    fun bytes(n: Long): String {
        if (n <= 0) return "0 B"
        val gb = n.toDouble() / 1024 / 1024 / 1024
        // 固定 Locale.US：某些语言环境下 %.1f 会输出 "1,1" 而不是 "1.1"
        if (gb >= 1) return String.format(Locale.US, "%.1f GB", gb)
        val mb = n.toDouble() / 1024 / 1024
        if (mb >= 1) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.0f KB", n.toDouble() / 1024)
    }

    /** 速度：8.2 MB/s / 356 KB/s；非正数返回空串 */
    fun speed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return ""
        return bytes(bytesPerSec) + "/s"
    }

    /** 剩余时间（中文概数）：约 25 秒 / 约 3 分钟 / 约 1 小时 5 分钟；无效返回空串 */
    fun duration(seconds: Double): String {
        if (seconds <= 0 || !seconds.isFinite()) return ""
        if (seconds < 60) return "约 ${max(1, seconds.roundToInt())} 秒"
        val minutes = (seconds / 60).roundToInt()
        if (minutes < 60) return "约 $minutes 分钟"
        val hours = floor(minutes / 60.0).toInt()
        val rest = minutes % 60
        return "约 $hours 小时" + if (rest > 0) " $rest 分钟" else ""
    }

    /** 错误详情：优先 message，回退错误码，避免界面出现「下载失败：」这样的空尾 */
    fun errorDetail(message: String?, code: Int?): String {
        if (!message.isNullOrEmpty()) return message
        if (code != null) return "错误码 $code"
        return "未知错误"
    }

    /** 日志行时间戳：`HH:mm:ss.SSS`（24 小时制，不受系统 12/24 设置影响） */
    fun timeStamp(now: Long = System.currentTimeMillis()): String =
        java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(java.util.Date(now))
}
