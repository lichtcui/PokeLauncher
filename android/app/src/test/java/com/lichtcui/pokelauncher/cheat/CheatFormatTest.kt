package com.lichtcui.pokelauncher.cheat

import com.lichtcui.pokelauncher.CasesGen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参数调整状态的编解码（纯函数），对应鸿蒙版 `CheatState.ets` 的
 * `idsToString` / `parseIds` / `paramsToString` / `parseParams`。
 *
 * 取值（夹紧 / 浮点吸附）与展示（labels 档位名 / 单位）的期望值**不写在这里**：
 * 唯一真相是 `shared/cases/cheat.json`（生成物 `CasesGen.CHEAT_VALUE_CASES`），
 * 与鸿蒙 `CheatFormat.test.ets` + `scripts/check-harmony-vectors.mjs` 跑的是同一套向量。
 */
class CheatFormatTest {

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

    // ---------------------------------------------------------------- 取值 / 展示（共享向量）

    @Test
    fun valueVectors_matchSharedCases() {
        // 覆盖：未设置 / 空串 / 无法解析 / 上下夹紧 / 滑块浮点吸附 / 真实中间值 / 省略 step /
        // 省略 unit / labels 命中与越界。这几条分支以前两端各写一份、后果只有真机看得出来。
        assertTrue("向量太少", CasesGen.CHEAT_VALUE_CASES.size >= 12)
        CasesGen.CHEAT_VALUE_CASES.forEach { c ->
            val v = CheatFormat.resolveNumber(c.param, c.raw)
            assertEquals("resolveNumber(${c.param.id}, ${c.raw})", c.expectValue, v, 0.0)
            assertEquals("numberLiteral($v)", c.expectLiteral, CheatFormat.numberLiteral(v))
            assertEquals("displayValue(${c.param.id}, $v)", c.expectDisplay, CheatFormat.displayValue(c.param, v))
        }
    }

    // NaN / Infinity 在 JSON 里表达不了，这两条各端自己补（鸿蒙 CheatFormat.test.ets 里也有）

    @Test
    fun numberLiteral_nonFiniteBecomesZero() {
        assertEquals("0", CheatFormat.numberLiteral(Double.NaN))
        assertEquals("0", CheatFormat.numberLiteral(Double.POSITIVE_INFINITY))
        assertEquals("0", CheatFormat.numberLiteral(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun displayValue_outOfRangeLabelIndexFallsBack() {
        // labels 只有 "a"/"b"：超出范围不能越界取档位名（与鸿蒙 displayValue 同一判据）
        val p = CheatParam(
            id = "x", label = "x", min = 0.0, max = 9.0, defaultValue = 0.0,
            labels = listOf("a", "b")
        )
        assertEquals("5", CheatFormat.displayValue(p, 5.0))
        assertEquals("9", CheatFormat.displayValue(p, 9.0))
    }
}
