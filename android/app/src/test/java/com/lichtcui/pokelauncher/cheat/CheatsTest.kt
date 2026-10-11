package com.lichtcui.pokelauncher.cheat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注册表自身的结构约束（对应鸿蒙版 `model/Cheats.ets`）。
 *
 * 这些断言的作用是「新增条目时踩坑的地方会直接红」：
 * id 重复、参数越界、JS 里混进 `$`（Kotlin raw string 会把 `$` 当模板插值）、
 * 分组漏配等。
 */
class CheatsTest {

    @Test
    fun registryNotEmpty() {
        assertTrue(CHEATS.size >= 7)
    }

    @Test
    fun idsAreUnique() {
        val ids = CHEATS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun namesAndDescsAreNotBlank() {
        for (c in CHEATS) {
            assertTrue("${c.id} name", c.name.isNotBlank())
            assertTrue("${c.id} desc", c.desc.isNotBlank())
        }
    }

    @Test
    fun everyCheatHasScript() {
        for (c in CHEATS) {
            assertTrue("${c.id} script", c.script.isNotBlank())
        }
    }

    @Test
    fun everyScriptRegistersAHook() {
        for (c in CHEATS) {
            assertTrue(
                "${c.id} 必须通过 __onScene/__onPhase 注册（否则注入后不会生效）",
                c.script.contains("__onScene(") || c.script.contains("__onPhase(")
            )
        }
    }

    @Test
    fun scriptsContainNoDollarSign() {
        // Kotlin raw string 里的 $ 会被当模板插值（`$foo` / `${...}`），写 JS 时必须避开
        for (c in CHEATS) {
            assertFalse("${c.id} script 含 '$'", c.script.contains('$'))
        }
    }

    @Test
    fun scriptsContainNoTripleQuote() {
        // 同理：会提前结束 Kotlin 的 """ 字面量
        for (c in CHEATS) {
            assertFalse("${c.id} script 含 \"\"\"", c.script.contains("\"\"\""))
        }
    }

    @Test
    fun paramIdsAreUniqueAndDeclared() {
        val paramIds = CHEATS.mapNotNull { it.param?.id }
        assertEquals(paramIds.size, paramIds.toSet().size)
        for (c in CHEATS) {
            val p = c.param ?: continue
            assertTrue("${c.id} param id", p.id.isNotBlank())
            assertTrue("${c.id} param label", p.label.isNotBlank())
        }
    }

    @Test
    fun paramRangesAreSane() {
        for (c in CHEATS) {
            val p = c.param ?: continue
            assertTrue("${c.id} min<max", p.min < p.max)
            assertTrue("${c.id} default 必须在 [min,max] 内", p.defaultValue in p.min..p.max)
            assertTrue("${c.id} step>0", p.step > 0.0)
        }
    }

    @Test
    fun labelCountMatchesParamRange() {
        for (c in CHEATS) {
            val p = c.param ?: continue
            if (p.labels.isEmpty()) continue
            val expected = (p.max - p.min).toInt() + 1
            assertEquals("${c.id} labels 个数应与档位数一致", expected, p.labels.size)
        }
    }

    @Test
    fun paramsAreReadThroughCheatParamsObject() {
        // 声明了 param 的脚本必须真的读 __CHEAT_PARAMS__，否则参数是「假旋钮」
        for (c in CHEATS) {
            val p = c.param ?: continue
            assertTrue("${c.id} script 未读取 __CHEAT_PARAMS__.${p.id}", c.script.contains("__CHEAT_PARAMS__." + p.id))
        }
    }

    @Test
    fun groupsCoverEveryCheat() {
        val groups = CHEAT_GROUPS.map { it.id }.toSet()
        for (c in CHEATS) {
            assertTrue("${c.id} 分组 ${c.group.id} 未在 CHEAT_GROUPS 中", groups.contains(c.group.id))
        }
    }

    @Test
    fun groupsHaveTitleAndSubtitle() {
        for (g in CHEAT_GROUPS) {
            assertTrue(g.title.isNotBlank())
            assertTrue(g.subtitle.isNotBlank())
        }
    }

    @Test
    fun rewardTierHasFiveLabels() {
        val p = CHEATS.first { it.id == "reward-tier" }.param!!
        assertEquals(listOf("Common", "Great", "Ultra", "Rogue", "Master"), p.labels)
    }
}
