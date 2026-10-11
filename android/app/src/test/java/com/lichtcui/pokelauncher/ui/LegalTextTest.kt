package com.lichtcui.pokelauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 法律页正文（纯数据）的守门测试。
 *
 * 两个目的：
 * 1. 结构完整性——章节 id 不重复、标题/段落都不为空（漏字段在界面上就是一个空白块）；
 * 2. **审核红线**——所有用户可见文案不得出现「作弊」字样（应用商店审核风险），
 *    该功能统一叫「参数调整」。这条以前只靠人肉 review，现在由测试兜住。
 */
class LegalTextTest {

    /** 与鸿蒙版 `pages/Legal.ets` 的章节集合与顺序一致 */
    private val expectedIds = listOf(
        "positioning",
        "copyright",
        "network",
        "responsibility",
        "disclaimer",
        "compliance",
        "study",
        "prohibited",
        "license",
        "feedback"
    )

    private val allUserVisibleText: List<String>
        get() = buildList {
            add(LEGAL_BANNER)
            add(LEGAL_UPDATED_AT)
            for (section in LEGAL_SECTIONS) {
                add(section.id)
                add(section.title)
                addAll(section.body)
            }
        }

    @Test
    fun sectionsMatchHarmonyVersion() {
        assertEquals(expectedIds, LEGAL_SECTIONS.map { it.id })
    }

    @Test
    fun idsAreUnique() {
        val ids = LEGAL_SECTIONS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun titlesAndBodiesAreNotBlank() {
        for (section in LEGAL_SECTIONS) {
            assertTrue("章节 ${section.id} 标题为空", section.title.isNotBlank())
            assertTrue("章节 ${section.id} 没有正文", section.body.isNotEmpty())
            for (paragraph in section.body) {
                assertTrue("章节 ${section.id} 有空段落", paragraph.isNotBlank())
            }
        }
    }

    @Test
    fun bannerAndTimestampArePresent() {
        assertTrue(LEGAL_BANNER.length > 10)
        assertTrue(LEGAL_UPDATED_AT.startsWith("最近更新："))
    }

    @Test
    fun neverUsesBannedWord() {
        for (text in allUserVisibleText) {
            assertFalse("用户可见文案不得出现「作弊」：$text", text.contains("作弊"))
        }
    }

    @Test
    fun complianceSectionUsesApprovedWording() {
        // 合规建议里必须用「参数调整」称呼该功能（与设置页/参数调整页同一套文案）
        val compliance = LEGAL_SECTIONS.first { it.id == "compliance" }
        val text = compliance.body.joinToString("\n")
        assertTrue(text.contains("参数调整"))
    }

    @Test
    fun mentionsBothPlatforms() {
        // 安卓与鸿蒙共用这份文案：不能写成「本鸿蒙应用」这类只对单平台成立的表述
        val text = LEGAL_SECTIONS.joinToString("\n") { it.title + "\n" + it.body.joinToString("\n") }
        assertFalse(text.contains("鸿蒙版客户端"))
        assertTrue(text.contains("离线") || text.contains("本应用"))
    }
}
