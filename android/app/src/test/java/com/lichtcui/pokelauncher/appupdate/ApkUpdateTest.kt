package com.lichtcui.pokelauncher.appupdate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用内自更新的纯逻辑（文件名 / 包体校验 / 状态文案）。
 *
 * 本机没有真机也没有模拟器，下载与安装只能靠这些纯函数取证：
 * - 文件名来自清单里的手写 `apkUrl`，必须挡住路径穿越与超长名字；
 * - 「下载到的其实是 404 页面」只能靠大小 + zip 魔数识破（不能喂给安装器）；
 * - 两个 UI 入口（首页弹窗 / 设置页行）的文案与动作必须由同一组函数决定。
 */
class ApkUpdateTest {

    // ---------------------------------------------------------------- 文件名

    @Test
    fun fileNameTakesLastPathSegment() {
        assertEquals("PokeRogue-1.2.3.apk", apkFileName("https://x.com/a/b/PokeRogue-1.2.3.apk", "1.2.3"))
    }

    @Test
    fun fileNameStripsQueryAndFragment() {
        assertEquals(
            "app-release.apk",
            apkFileName("https://x.com/dl/app-release.apk?token=abc#frag", "1.2.3")
        )
    }

    @Test
    fun fileNameAppendsApkExtensionWhenMissing() {
        assertEquals("download.apk", apkFileName("https://x.com/releases/latest/download", "1.2.3"))
    }

    @Test
    fun fileNameFallsBackToVersionWhenSegmentUnusable() {
        // 目录式 URL：取到的是最后一段目录名（比空名字强，仍落在我们自己的目录里）
        assertEquals("b.apk", apkFileName("https://x.com/a/b/", "1.2.3"))
        assertEquals("x.com.apk", apkFileName("https://x.com/", "1.2.3"))
        // 只有 query，没有任何路径段 → 回退到版本名
        assertEquals("pokerogue-1.2.3.apk", apkFileName("?download=1", "1.2.3"))
        assertEquals("pokerogue-update.apk", apkFileName("", ""))
        // 版本名里的空格/中文会被清洗掉，不能带进文件名
        assertEquals("pokerogue-1.2.3beta.apk", apkFileName("?v=1", "1.2.3 beta 测试版"))
    }

    @Test
    fun fileNameKeepsTraversalOutOfPath() {
        // 取最后一段 + 清洗字符，`..`/`/` 都不可能留在结果里
        assertEquals("evil.apk", apkFileName("https://x.com/a/../../evil.apk", "1"))
        assertEquals("pokerogue-1.apk", apkFileName("https://x.com/a/...", "1"))
        assertFalse(apkFileName("https://x.com/..%2F..%2Fevil.apk", "1").contains("/"))
    }

    @Test
    fun fileNameDropsLeadingDotsAndTruncates() {
        assertEquals("hidden.apk", apkFileName("https://x.com/.hidden", "1"))
        val long = apkFileName("https://x.com/" + "a".repeat(400) + ".apk", "1")
        assertTrue(long.length <= 100)
        assertTrue(long.endsWith(".apk"))
    }

    // ---------------------------------------------------------------- 包体校验

    @Test
    fun apkMagicAcceptsZipHeaders() {
        assertTrue(apkLooksValid(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertTrue(apkLooksValid(byteArrayOf(0x50, 0x4B, 0x05, 0x06)))
        assertTrue(apkLooksValid(byteArrayOf(0x50, 0x4B, 0x07, 0x08)))
    }

    @Test
    fun apkMagicRejectsHtmlAndShortHeads() {
        assertFalse(apkLooksValid("<!DOCTYPE html>".toByteArray()))
        assertFalse(apkLooksValid(byteArrayOf(0x50, 0x4B)))
        assertFalse(apkLooksValid(byteArrayOf(0x50, 0x4B, 0x01, 0x02)))
        assertFalse(apkLooksValid(ByteArray(0)))
    }

    @Test
    fun apkAcceptanceRequiresSizeAndMagic() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        assertFalse(apkFileAccepted(MIN_APK_BYTES - 1, zip))
        assertTrue(apkFileAccepted(MIN_APK_BYTES, zip))
        assertTrue(apkFileAccepted(20L * 1024 * 1024, zip))
        assertFalse(apkFileAccepted(20L * 1024 * 1024, "<html>404</html>".toByteArray()))
    }

    // ---------------------------------------------------------------- 状态与文案

    @Test
    fun actionMappingCoversEveryPhase() {
        assertEquals(ApkUpdateAction.CANCEL, apkActionOf(ApkUpdatePhase.DOWNLOADING))
        assertEquals(ApkUpdateAction.CANCEL, apkActionOf(ApkUpdatePhase.VERIFYING))
        assertEquals(ApkUpdateAction.INSTALL, apkActionOf(ApkUpdatePhase.READY))
        assertEquals(ApkUpdateAction.START, apkActionOf(ApkUpdatePhase.IDLE))
        assertEquals(ApkUpdateAction.START, apkActionOf(ApkUpdatePhase.FAILED))
    }

    @Test
    fun rowTextsForIdleIncludeVersionAndForce() {
        val idle = ApkUpdateState()
        assertEquals("去更新（1.2.3）", apkRowLabel(idle, "1.2.3"))
        assertEquals("去更新", apkRowLabel(idle, ""))
        assertEquals("必须更新", apkRowValue(idle, forceUpdate = true))
        assertEquals("", apkRowValue(idle, forceUpdate = false))
        // 取消下载后回到 IDLE：右侧不重复，提示落在详情行
        val cancelled = idle.copy(message = APK_CANCELLED_TEXT)
        assertEquals("", apkRowValue(cancelled, forceUpdate = false))
        assertEquals(APK_CANCELLED_TEXT, cancelled.detailText())
    }

    @Test
    fun rowTextsDuringDownloadShowPercent() {
        val downloading = ApkUpdateState(
            phase = ApkUpdatePhase.DOWNLOADING,
            doneBytes = 50,
            totalBytes = 200,
            versionName = "1.2.3"
        )
        assertEquals("正在下载更新包…", apkRowLabel(downloading, "1.2.3"))
        assertEquals("25%", apkRowValue(downloading, forceUpdate = true))
        assertEquals(25, downloading.progressPercent)
        assertTrue(downloading.downloading)
    }

    @Test
    fun rowTextsForVerifyingReadyAndFailed() {
        val verifying = ApkUpdateState(phase = ApkUpdatePhase.VERIFYING)
        assertEquals("正在校验更新包…", apkRowLabel(verifying, "1.2.3"))
        assertEquals("", apkRowValue(verifying, forceUpdate = false)) // 校验阶段没有可见数字

        val ready = ApkUpdateState(phase = ApkUpdatePhase.READY, fileName = "a.apk")
        assertEquals("安装更新", apkRowLabel(ready, "1.2.3"))
        assertEquals("已下载完成", apkRowValue(ready, forceUpdate = false))
        assertEquals(ApkUpdateState.READY_DETAIL, ready.detailText())

        val failed = ApkUpdateState(phase = ApkUpdatePhase.FAILED, message = INVALID_APK_TEXT)
        assertEquals("重试更新", apkRowLabel(failed, "1.2.3"))
        assertEquals(INVALID_APK_TEXT, apkRowValue(failed, forceUpdate = false))
        assertTrue(failed.detailText().startsWith("更新失败："))
    }

    @Test
    fun progressPercentHandlesUnknownTotalAndClamps() {
        assertEquals(0, ApkUpdateState(phase = ApkUpdatePhase.DOWNLOADING).progressPercent)
        assertEquals(
            100,
            ApkUpdateState(phase = ApkUpdatePhase.DOWNLOADING, doneBytes = 999, totalBytes = 100)
                .progressPercent
        )
        // total 未知时右侧退回绝对字节数（不能显示「0%」骗人）
        val noTotal = ApkUpdateState(phase = ApkUpdatePhase.DOWNLOADING, doneBytes = 2 * 1024 * 1024)
        assertEquals("2.0 MB", apkRowValue(noTotal, forceUpdate = false))
    }

    @Test
    fun detailTextIsEmptyForIdleWithoutMessage() {
        assertEquals("", ApkUpdateState().detailText())
        assertEquals("", ApkUpdateState(phase = ApkUpdatePhase.DOWNLOADING).detailText())
        assertEquals(APK_CANCELLED_TEXT, ApkUpdateState(message = APK_CANCELLED_TEXT).detailText())
    }
}
