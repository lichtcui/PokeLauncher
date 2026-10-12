package com.lichtcui.pokelauncher.core

import com.lichtcui.pokelauncher.CasesGen
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * zip 里的 `currentVersion.txt` 只存 commit，Release 的 tag 是 `release-<commit>`，
 * 两侧比较前必须先把前缀去掉（对齐鸿蒙版 `normalizeVersion` / `sameVersion`）。
 *
 * ⚠️ 期望值**不写在这里**：唯一真相是 `shared/cases/version.json`（生成物 `CasesGen`），
 * 与鸿蒙 `GameRepository.test` 跑的是同一套向量。
 */
class VersionTest {

    @Test
    fun normalizeMatchesTheSharedVectors() {
        for (c in CasesGen.NORMALIZE_CASES) {
            assertEquals("normalize(${c.input})", c.expect, Version.normalize(c.input))
        }
    }

    @Test
    fun sameMatchesTheSharedVectors() {
        for (c in CasesGen.SAME_CASES) {
            assertEquals("same(${c.local}, ${c.remote})", c.expect, Version.same(c.local, c.remote))
        }
    }

    @Test
    fun sameCoversEmptySides() {
        // 空串一律视为「没法比较」，避免把「资源未安装」误判成「已是最新」
        val empty = CasesGen.SAME_CASES.filter { it.local.isEmpty() || it.remote.isEmpty() }
        assertEquals(true, empty.size >= 2)
    }
}
