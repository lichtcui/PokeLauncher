package com.lichtcui.pokelauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Format] 与鸿蒙版 `common/Format.ets` 文案必须一致（两端界面看起来是同一个 App） */
class FormatTest {

    @Test
    fun bytesUsesSameUnitsAsHarmony() {
        assertEquals("0 B", Format.bytes(0))
        assertEquals("0 B", Format.bytes(-1))
        assertEquals("1 KB", Format.bytes(1024))
        assertEquals("356 KB", Format.bytes(365_000))
        assertEquals("1.0 MB", Format.bytes(1024L * 1024))
        assertEquals("8.2 MB", Format.bytes((8.2 * 1024 * 1024).toLong()))
        assertEquals("1.0 GB", Format.bytes(1024L * 1024 * 1024))
    }

    @Test
    fun bytesAlwaysUsesDotAsDecimalSeparator() {
        // 某些 locale 下 %.1f 会输出 "1,0 MB"，因此 Format 内部固定 Locale.US
        val text = Format.bytes((1.5 * 1024 * 1024).toLong())
        assertTrue(text, text.contains("."))
    }

    @Test
    fun speedEmptyForNonPositive() {
        assertEquals("", Format.speed(0))
        assertEquals("", Format.speed(-5))
        assertEquals("1.0 MB/s", Format.speed(1024L * 1024))
    }

    @Test
    fun durationInChinese() {
        assertEquals("", Format.duration(0.0))
        assertEquals("", Format.duration(Double.NaN))
        assertEquals("约 1 秒", Format.duration(0.4))
        assertEquals("约 25 秒", Format.duration(25.0))
        assertEquals("约 3 分钟", Format.duration(185.0))
        assertEquals("约 1 小时 5 分钟", Format.duration(3900.0))
        assertEquals("约 2 小时", Format.duration(7200.0))
    }

    @Test
    fun errorDetailFallsBackToCode() {
        assertEquals("网络不可达", Format.errorDetail("网络不可达", 7))
        assertEquals("错误码 7", Format.errorDetail(null, 7))
        assertEquals("错误码 7", Format.errorDetail("", 7))
        assertEquals("未知错误", Format.errorDetail(null, null))
    }

    @Test
    fun timeStampIsHourMinuteSecondMillisecond() {
        val text = Format.timeStamp(0)
        assertTrue(text, Regex("""^\d{2}:\d{2}:\d{2}\.\d{3}$""").matches(text))
    }
}
