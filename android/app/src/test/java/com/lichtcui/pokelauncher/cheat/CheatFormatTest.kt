package com.lichtcui.pokelauncher.cheat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参数调整状态的编解码（纯函数），对应鸿蒙版 `CheatState.ets` 的
 * `idsToString` / `parseIds` / `paramsToString` / `parseParams` + 取值夹紧。
 */
class CheatFormatTest {

    private val rate = CheatParam(id = "candy", label = "倍率", min = 1.0, max = 100.0, defaultValue = 10.0, unit = "x")
    private val tier = CheatParam(
        id = "rewardTier", label = "档位", min = 0.0, max = 4.0, defaultValue = 4.0,
        labels = listOf("Common", "Great", "Ultra", "Rogue", "Master")
    )

    // ---------------------------------------------------------------- ids

    @Test
    fun encodeIds_empty() {
        assertEquals("", CheatFormat.encodeIds(emptyList()))
    }

    @Test
    fun encodeIds_joinsWithComma() {
        assertEquals("a,b,c", CheatFormat.encodeIds(listOf("a", "b", "c")))
    }

    @Test
    fun parseIds_emptyString() {
        assertTrue(CheatFormat.parseIds("").isEmpty())
    }

    @Test
    fun parseIds_trimsAndDropsBlanks() {
        assertEquals(listOf("a", "b", "c"), CheatFormat.parseIds("a, b , ,c"))
    }

    @Test
    fun ids_roundTrip() {
        val ids = listOf("candy", "reward-tier")
        assertEquals(ids, CheatFormat.parseIds(CheatFormat.encodeIds(ids)))
    }

    // ---------------------------------------------------------------- params

    @Test
    fun encodeParams_preservesOrder() {
        val m = LinkedHashMap<String, String>()
        m["candy"] = "5"
        m["exp"] = "3"
        assertEquals("candy=5,exp=3", CheatFormat.encodeParams(m))
    }

    @Test
    fun encodeParams_empty() {
        assertEquals("", CheatFormat.encodeParams(emptyMap()))
    }

    @Test
    fun parseParams_emptyString() {
        assertTrue(CheatFormat.parseParams("").isEmpty())
    }

    @Test
    fun parseParams_basic() {
        assertEquals(mapOf("candy" to "5"), CheatFormat.parseParams("candy=5"))
    }

    @Test
    fun parseParams_emptyValueKept() {
        assertEquals(mapOf("candy" to ""), CheatFormat.parseParams("candy="))
    }

    @Test
    fun parseParams_ignoresEntriesWithoutKey() {
        // `=5` 没有 key、`abc` 没有 `=`：与鸿蒙版一致，都丢弃
        assertTrue(CheatFormat.parseParams("=5,abc").isEmpty())
    }

    @Test
    fun parseParams_splitsOnFirstEquals() {
        assertEquals(mapOf("k" to "1=2"), CheatFormat.parseParams("k=1=2"))
    }

    @Test
    fun params_roundTrip() {
        val m = mapOf("candy" to "7", "exp" to "2.5")
        assertEquals(m, CheatFormat.parseParams(CheatFormat.encodeParams(m)))
    }

    // ---------------------------------------------------------------- 取值夹紧

    @Test
    fun resolveNumber_usesDefaultWhenUnset() {
        assertEquals(10.0, CheatFormat.resolveNumber(rate, null), 0.0)
    }

    @Test
    fun resolveNumber_usesDefaultWhenBlank() {
        assertEquals(10.0, CheatFormat.resolveNumber(rate, "   "), 0.0)
    }

    @Test
    fun resolveNumber_usesDefaultWhenNaN() {
        assertEquals(10.0, CheatFormat.resolveNumber(rate, "abc"), 0.0)
    }

    @Test
    fun resolveNumber_parsesValue() {
        assertEquals(7.5, CheatFormat.resolveNumber(rate, "7.5"), 0.0)
    }

    @Test
    fun resolveNumber_clampsToMax() {
        assertEquals(100.0, CheatFormat.resolveNumber(rate, "999"), 0.0)
    }

    @Test
    fun resolveNumber_clampsToMin() {
        assertEquals(1.0, CheatFormat.resolveNumber(rate, "0"), 0.0)
    }

    @Test
    fun resolveNumber_clampsNegative() {
        assertEquals(0.0, CheatFormat.resolveNumber(tier, "-3"), 0.0)
    }

    // ---------------------------------------------------------------- 滑块浮点漂移

    @Test
    fun resolveNumber_snapsSliderFloatDrift() {
        // 真机实测：Compose Slider(steps=98) 拖到「11」时给出 10.999999 -> 显示成 10.999999x
        assertEquals(11.0, CheatFormat.resolveNumber(rate, "10.999999999"), 0.0)
        assertEquals("11x", CheatFormat.displayValue(rate, CheatFormat.resolveNumber(rate, "10.999999999")))
        assertEquals(52.0, CheatFormat.resolveNumber(rate, "51.99999999999999"), 0.0)
    }

    @Test
    fun resolveNumber_keepsRealIntermediateValues() {
        // 容差吸附只吃浮点误差，不能顺手把真实的中间值也改掉（与鸿蒙版取值语义一致）
        assertEquals(7.5, CheatFormat.resolveNumber(rate, "7.5"), 0.0)
        assertEquals(12.34, CheatFormat.resolveNumber(rate, "12.34"), 0.0)
    }

    @Test
    fun resolveNumber_keepsTierLabelsIntact() {
        assertEquals(3.0, CheatFormat.resolveNumber(tier, "3"), 0.0)
        assertEquals("Master", CheatFormat.displayValue(tier, CheatFormat.resolveNumber(tier, "4")))
        assertEquals(0.0, CheatFormat.resolveNumber(tier, "0"), 0.0)
    }

    // ---------------------------------------------------------------- JS 数字字面量

    @Test
    fun numberLiteral_integralHasNoDotZero() {
        assertEquals("10", CheatFormat.numberLiteral(10.0))
        assertEquals("0", CheatFormat.numberLiteral(0.0))
    }

    @Test
    fun numberLiteral_keepsFraction() {
        assertEquals("2.5", CheatFormat.numberLiteral(2.5))
    }

    @Test
    fun numberLiteral_nanBecomesZero() {
        assertEquals("0", CheatFormat.numberLiteral(Double.NaN))
    }

    // ---------------------------------------------------------------- 展示文案

    @Test
    fun displayValue_usesLabelForTier() {
        assertEquals("Master", CheatFormat.displayValue(tier, 4.0))
        assertEquals("Common", CheatFormat.displayValue(tier, 0.0))
    }

    @Test
    fun displayValue_fallsBackToNumberWithUnit() {
        assertEquals("10x", CheatFormat.displayValue(rate, 10.0))
        assertEquals("2.5x", CheatFormat.displayValue(rate, 2.5))
    }

    @Test
    fun displayValue_labelOnlyForExactTier() {
        // 非整数档位不能取 label（避免 2.5 → labels[2] 的错位）
        assertEquals("2.5", CheatFormat.displayValue(tier, 2.5))
    }

    @Test
    fun displayValue_outOfRangeIndexFallsBack() {
        val p = CheatParam(id = "x", label = "x", min = 0.0, max = 9.0, defaultValue = 0.0, labels = listOf("a", "b"))
        assertFalse(CheatFormat.displayValue(p, 5.0).isEmpty())
        assertEquals("5", CheatFormat.displayValue(p, 5.0))
    }
}
