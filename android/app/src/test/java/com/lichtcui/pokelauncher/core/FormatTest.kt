package com.lichtcui.pokelauncher.core

import com.lichtcui.pokelauncher.CasesGen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Format] 与鸿蒙版 `common/Format.ets` 文案必须一致（两端界面看起来是同一个 App）。
 *
 * ⚠️ 期望值**不写在这里**：唯一真相是 `shared/cases/format.json`（生成物 `CasesGen`），
 * 与鸿蒙 `Format.test` 跑的是同一套向量。
 */
class FormatTest {

    @Test
    fun bytesMatchesTheSharedVectors() {
        for (c in CasesGen.BYTES_CASES) {
            assertEquals("bytes(${c.input})", c.expect, Format.bytes(c.input))
        }
    }

    @Test
    fun speedMatchesTheSharedVectors() {
        for (c in CasesGen.SPEED_CASES) {
            assertEquals("speed(${c.input})", c.expect, Format.speed(c.input))
        }
    }

    @Test
    fun durationMatchesTheSharedVectors() {
        for (c in CasesGen.DURATION_CASES) {
            assertEquals("duration(${c.input})", c.expect, Format.duration(c.input))
        }
    }

    @Test
    fun durationRejectsNonFiniteInput() {
        // NaN / Infinity 在 JSON 里表达不了，这两条只能各端手写
        assertEquals("", Format.duration(Double.NaN))
        assertEquals("", Format.duration(Double.POSITIVE_INFINITY))
    }

    @Test
    fun errorDetailMatchesTheSharedVectors() {
        for (c in CasesGen.ERROR_DETAIL_CASES) {
            assertEquals("errorDetail(${c.message}, ${c.code})", c.expect, Format.errorDetail(c.message, c.code))
        }
    }

    @Test
    fun bytesAlwaysUsesDotAsDecimalSeparator() {
        // 某些 locale 下 %.1f 会输出 "1,0 MB"，因此 Format 内部固定 Locale.US
        val text = Format.bytes((1.5 * 1024 * 1024).toLong())
        assertTrue(text, text.contains("."))
    }

    @Test
    fun timeStampIsHourMinuteSecondMillisecond() {
        val text = Format.timeStamp(0)
        assertTrue(text, Regex("""^\d{2}:\d{2}:\d{2}\.\d{3}$""").matches(text))
    }
}
