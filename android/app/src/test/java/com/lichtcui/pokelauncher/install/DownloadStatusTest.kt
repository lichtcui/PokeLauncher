package com.lichtcui.pokelauncher.install

import com.lichtcui.pokelauncher.core.Format
import com.lichtcui.pokelauncher.core.SharedUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 状态快照 / 进度平滑 / 通知文案（三者都是纯逻辑，可以在 JVM 上跑） */
class DownloadStatusTest {

    private val sourceUrl = "https://gh-proxy.com/https://github.com/a/b/releases/download/v1/game.zip"

    @Test
    fun percentIsClampedAndZeroWhenTotalUnknown() {
        assertEquals(0, DownloadStatus(total = 0, received = 100).percent)
        assertEquals(25, DownloadStatus(total = 1000, received = 250).percent)
        assertEquals(100, DownloadStatus(total = 1000, received = 2000).percent)
        assertEquals(0, DownloadStatus(total = 1000, received = -5).percent)
    }

    @Test
    fun phaseFlagsDriveTheButtons() {
        val downloading = DownloadStatus(phase = DownloadPhase.DOWNLOADING)
        assertTrue(downloading.isActive)
        assertTrue(downloading.canPause)
        assertTrue(downloading.canCancel)
        assertFalse(downloading.canResume)

        val paused = DownloadStatus(phase = DownloadPhase.PAUSED, total = 1000, received = 400)
        assertFalse(paused.isActive) // 暂停后可以重新发起（继续下载）
        assertTrue(paused.canResume)
        assertTrue(paused.canCancel)
        assertFalse(paused.canPause)

        for (phase in listOf(DownloadPhase.PROBING, DownloadPhase.EXTRACTING)) {
            val status = DownloadStatus(phase = phase)
            assertTrue(phase.name, status.isActive)
            assertTrue(status.canCancel)
            assertFalse(status.canPause) // 测速/解压阶段没有「暂停」语义
        }

        for (phase in listOf(DownloadPhase.IDLE, DownloadPhase.DONE, DownloadPhase.FAILED, DownloadPhase.CANCELLED)) {
            assertFalse(phase.name, DownloadStatus(phase = phase).isActive)
            assertFalse(phase.name, DownloadStatus(phase = phase).canCancel)
        }
    }

    @Test
    fun sourceKindAndHostComeFromUrlOrder() {
        val status = DownloadStatus(
            phase = DownloadPhase.DOWNLOADING,
            sourceUrl = sourceUrl,
            sourceIndex = 3,
            sourceCount = 4
        )
        assertEquals("gh-proxy.com", status.host)
        assertEquals("直连", status.sourceKind)
    }

    // ------------------------------------------------------------ ProgressMeter

    @Test
    fun meterFirstSampleOnlyStartsTheClock() {
        val meter = ProgressMeter()
        assertEquals(0.0, meter.update(1000, 1000), 0.001)
        assertEquals(0.0, meter.speedBps, 0.001)
    }

    @Test
    fun meterSmoothsSpeedAndIgnoresTooShortWindow() {
        val meter = ProgressMeter()
        meter.update(1000, 1000) // 起表
        meter.update(1200, 1100) // dt=100ms < 300ms：忽略
        assertEquals(0.0, meter.speedBps, 0.001)
        // dt=1000ms，增量 2000B → 瞬时 2000 B/s，首次直接采用
        assertEquals(2000.0, meter.update(3000, 2000), 0.001)
        // dt=1000ms，增量 1500B → 1500*0.4 + 2000*0.6 = 1800 B/s
        assertEquals(1800.0, meter.update(4500, 3000), 0.001)
    }

    @Test
    fun meterRestartsWhenByteCountGoesBackwards() {
        // 换源/服务端忽略 Range 时字节数会变小：只重起采样窗口，绝不产生负速度
        val meter = ProgressMeter()
        meter.update(1000, 1000)
        meter.update(5000, 2000) // 4000 B/s
        assertEquals(4000.0, meter.speedBps, 0.001)
        meter.update(100, 3000) // 退回到 100 字节：采样窗口重起
        assertEquals(4000.0, meter.speedBps, 0.001)
        // dt=400ms，增量 500B → 1250*0.4 + 4000*0.6 = 2900 B/s
        assertEquals(2900.0, meter.update(600, 3400), 0.001)
    }

    @Test
    fun etaUsesSmoothedSpeed() {
        val meter = ProgressMeter()
        meter.update(0, 1000)
        meter.update(1_000_000, 2000) // 1 MB/s
        assertEquals(7.0, meter.etaSeconds(8_000_000, 1_000_000), 0.001)
        assertEquals(0.0, meter.etaSeconds(1_000_000, 1_000_000), 0.001) // 已下完
        assertEquals(0.0, ProgressMeter().etaSeconds(8_000_000, 0), 0.001) // 速度未知
    }

    @Test
    fun emitThrottling() {
        val meter = ProgressMeter()
        assertTrue(meter.shouldEmit(lastEmitAt = 0, nowMs = 1000, percent = 1))
        assertFalse(meter.shouldEmit(lastEmitAt = 1000, nowMs = 1100, percent = 1))
        assertTrue(meter.shouldEmit(lastEmitAt = 1000, nowMs = 1200, percent = 1))
        // 暂停与 100% 必须立即刷新，否则用户会觉得按钮/通知没响应
        assertTrue(meter.shouldEmit(lastEmitAt = 1000, nowMs = 1010, percent = 1, paused = true))
        assertTrue(meter.shouldEmit(lastEmitAt = 1000, nowMs = 1010, percent = 100))
    }

    // ------------------------------------------------------------ 文案

    @Test
    fun textForIdleDoneFailedAndCancelled() {
        assertEquals("未开始", DownloadText.describe(DownloadStatus()))
        assertEquals("下载并安装完成", DownloadText.describe(DownloadStatus(phase = DownloadPhase.DONE)))
        assertEquals("已取消", DownloadText.describe(DownloadStatus(phase = DownloadPhase.CANCELLED)))
        assertEquals(
            "下载失败：HTTP 403",
            DownloadText.describe(DownloadStatus(phase = DownloadPhase.FAILED, error = "下载失败：HTTP 403"))
        )
        // 没有 error 时回退 message，最后才是兜底文案，避免界面出现空的「下载失败：」
        assertEquals(
            "所有下载源都不可用",
            DownloadText.describe(DownloadStatus(phase = DownloadPhase.FAILED, message = "所有下载源都不可用"))
        )
        assertEquals("下载失败", DownloadText.describe(DownloadStatus(phase = DownloadPhase.FAILED)))
    }

    @Test
    fun textForDownloadingShowsPercentSizeSpeedEtaAndSource() {
        val text = DownloadText.describe(
            DownloadStatus(
                phase = DownloadPhase.DOWNLOADING,
                received = 2_500_000,
                total = 10_000_000,
                speedBps = 1_048_576.0,
                etaSeconds = 120.0,
                sourceUrl = sourceUrl,
                sourceIndex = 0,
                sourceCount = 4
            )
        )
        assertTrue(text, text.contains("25%"))
        assertTrue(text, text.contains("2.4 MB / 9.5 MB"))
        assertTrue(text, text.contains("1.0 MB/s"))
        // 「剩余」前缀来自 shared/ui.json（两端同一条），这里只锁拼接结果
        assertTrue(text, text.contains(SharedUi.TEXT_ETA_PREFIX + "约 2 分钟"))
        assertTrue(text, text.contains("gh-proxy.com（镜像 1/4）"))
    }

    @Test
    fun textForDownloadingWithoutTotalOrSpeed() {
        val text = DownloadText.describe(
            DownloadStatus(phase = DownloadPhase.DOWNLOADING, received = 1024 * 1024)
        )
        assertEquals("已下载 1.0 MB", text)
    }

    @Test
    fun textForPausedAndExtracting() {
        val paused = DownloadText.describe(
            DownloadStatus(phase = DownloadPhase.PAUSED, received = 2_500_000, total = 10_000_000)
        )
        assertEquals(SharedUi.TEXT_PAUSED + " · 25% · 2.4 MB / 9.5 MB", paused)
        assertEquals(
            "正在解压：120 个文件，1.0 MB",
            DownloadText.describe(
                DownloadStatus(phase = DownloadPhase.EXTRACTING, message = "正在解压：120 个文件，1.0 MB")
            )
        )
        assertEquals(
            SharedUi.TEXT_EXTRACT_TITLE + "…",
            DownloadText.describe(DownloadStatus(phase = DownloadPhase.EXTRACTING))
        )
    }

    @Test
    fun textForProbingUsesMessageWithFallback() {
        assertEquals(
            "正在测速…",
            DownloadText.describe(DownloadStatus(phase = DownloadPhase.PROBING, message = "正在测速…"))
        )
        assertTrue(DownloadText.describe(DownloadStatus(phase = DownloadPhase.PROBING)).contains("测速"))
    }

    // ------------------------------------------------------------ 总线

    @Test
    fun busPublishesAndUpdates() {
        DownloadBus.publish(DownloadStatus(phase = DownloadPhase.PROBING, tag = "t1"))
        assertEquals(DownloadPhase.PROBING, DownloadBus.status.value.phase)
        DownloadBus.update { it.copy(phase = DownloadPhase.PAUSED, message = SharedUi.TEXT_PAUSED) }
        assertEquals(DownloadPhase.PAUSED, DownloadBus.status.value.phase)
        assertEquals("t1", DownloadBus.status.value.tag) // update 只改指定字段
        DownloadBus.publish(DownloadStatus())
    }

    // ------------------------------------------------------------ 存储预检

    @Test
    fun storageFactorsMatchHarmony() {
        // 与鸿蒙 `LauncherSession.requiredStorage()` 的 `updating ? 3.2 : 2.4` 同值
        // （值本身由 scripts/gen-shared.mjs 对账，这里锁住「两个分支确实分别用了这两个数」）
        assertEquals(3.2, Storage.FACTOR_UPDATE, 1e-9)
        assertEquals(2.4, Storage.FACTOR_FRESH, 1e-9)
        val asset = 100L
        assertEquals(320L, Storage.requiredBytes(asset, updating = true))
        assertEquals(240L, Storage.requiredBytes(asset, updating = false))
    }

    @Test
    fun storageCheckBoundaryAndMessage() {
        val asset = 507_000_000L
        for (updating in listOf(false, true)) {
            val need = Storage.requiredBytes(asset, updating)
            assertNull(Storage.check(need, asset, updating))
            val problem = Storage.check(need - 1, asset, updating)
            assertTrue(problem != null)
            assertTrue(problem!!, problem!!.contains("存储空间不足"))
            // 尺寸文案与两端共用的格式化器一致（以前这里硬写 `need/1024/1024 MB`）
            assertTrue(problem, problem.contains(Format.bytes(need)))
            assertTrue(problem, problem.contains(Format.bytes(need - 1)))
        }
        // 大小未知（调试包）不做预检，否则会被误拦
        assertNull(Storage.check(0, 0, updating = false))
    }
}
