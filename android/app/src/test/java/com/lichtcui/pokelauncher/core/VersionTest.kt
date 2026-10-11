package com.lichtcui.pokelauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * zip 里的 `currentVersion.txt` 只存 commit，Release 的 tag 是 `release-<commit>`，
 * 两侧比较前必须先把前缀去掉（对齐鸿蒙版 `normalizeVersion`）。
 */
class VersionTest {

    @Test
    fun normalizeStripsReleasePrefix() {
        assertEquals("abc1234", Version.normalize("release-abc1234"))
        assertEquals("v1.2.3", Version.normalize("v1.2.3"))
        assertEquals("", Version.normalize(""))
    }

    @Test
    fun normalizeDoesNotTouchPrefixInTheMiddle() {
        assertEquals("my-release-1", Version.normalize("my-release-1"))
    }

    @Test
    fun sameComparesAfterNormalize() {
        assertTrue(Version.same("abc1234", "release-abc1234"))
        assertTrue(Version.same("release-abc1234", "release-abc1234"))
        assertFalse(Version.same("abc1234", "release-def5678"))
    }

    @Test
    fun sameIsFalseForEmptySide() {
        // 空串一律视为「没法比较」，避免把「资源未安装」误判成「已是最新」
        assertFalse(Version.same("", "release-abc1234"))
        assertFalse(Version.same("abc1234", ""))
        assertFalse(Version.same("", ""))
    }
}
