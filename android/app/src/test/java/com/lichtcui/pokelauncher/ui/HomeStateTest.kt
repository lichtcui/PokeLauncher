package com.lichtcui.pokelauncher.ui

import com.lichtcui.pokelauncher.install.DownloadPhase
import com.lichtcui.pokelauncher.install.DownloadStatus
import com.lichtcui.pokelauncher.core.SharedUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页四态 + 精灵球阶段 + 流程文案的纯逻辑测试。
 *
 * 这几段本来是 `pages/Index.ets` 里散在 `@State` 上的判定，安卓侧故意先抽成纯函数：
 * 它是「下载中切后台、回来还能点到继续」「更新资源时别提前显示已就绪」这类
 * 真机才会暴露的坑，而 Compose 组件在本机跑不起来（没有模拟器），
 * 只能靠这层纯逻辑 + 编译把判定锁住。
 */
class HomeStateTest {

    private fun status(phase: DownloadPhase) = DownloadStatus(phase = phase)

    // ---------------------------------------------------------------- 四态

    @Test
    fun checkingWinsOverEverything() {
        // 冷启动读盘期间即使磁盘上已经有资源，也先显示「正在检查」
        assertEquals(HomeState.CHECKING, homeStateOf(initializing = true, inFlow = true, gameReady = true))
        assertEquals(HomeState.CHECKING, homeStateOf(initializing = true, inFlow = false, gameReady = false))
    }

    @Test
    fun flowWinsOverReady() {
        // 更新资源包时旧包还在（gameReady=true），必须显示下载流程而不是就绪页
        assertEquals(HomeState.FLOW, homeStateOf(initializing = false, inFlow = true, gameReady = true))
    }

    @Test
    fun readyAndNotDownloaded() {
        assertEquals(HomeState.READY, homeStateOf(initializing = false, inFlow = false, gameReady = true))
        assertEquals(
            HomeState.NOT_DOWNLOADED,
            homeStateOf(initializing = false, inFlow = false, gameReady = false)
        )
    }

    // ---------------------------------------------------------------- 流程可见性

    @Test
    fun flowCoversEveryActivePhase() {
        // 进行中的三个阶段
        assertTrue(inFlowOf(status(DownloadPhase.PROBING), busyInstalling = false, holdReady = false))
        assertTrue(inFlowOf(status(DownloadPhase.DOWNLOADING), busyInstalling = false, holdReady = false))
        assertTrue(inFlowOf(status(DownloadPhase.EXTRACTING), busyInstalling = false, holdReady = false))
    }

    @Test
    fun pausedStillCountsAsFlow() {
        // 关键：暂停后服务还挂着，界面必须留着「继续」按钮，否则用户回不到下载
        assertTrue(inFlowOf(status(DownloadPhase.PAUSED), busyInstalling = false, holdReady = false))
        assertFalse(DownloadStatus(phase = DownloadPhase.PAUSED).isActive)
    }

    @Test
    fun terminalPhasesLeaveFlow() {
        assertFalse(inFlowOf(status(DownloadPhase.IDLE), busyInstalling = false, holdReady = false))
        assertFalse(inFlowOf(status(DownloadPhase.DONE), busyInstalling = false, holdReady = false))
        assertFalse(inFlowOf(status(DownloadPhase.FAILED), busyInstalling = false, holdReady = false))
        assertFalse(inFlowOf(status(DownloadPhase.CANCELLED), busyInstalling = false, holdReady = false))
    }

    @Test
    fun installingAndBurstKeepFlow() {
        // 本地 zip 导入（与下载无关的另一条安装途径）
        assertTrue(inFlowOf(status(DownloadPhase.IDLE), busyInstalling = true, holdReady = false))
        // 下载完成后的 900ms 停留，让「资源准备就绪」被看见
        assertTrue(inFlowOf(status(DownloadPhase.DONE), busyInstalling = false, holdReady = true))
    }

    // ---------------------------------------------------------------- 精灵球阶段

    @Test
    fun prepPhaseFollowsDownloadPhase() {
        val gameReady = false
        assertEquals(PrepPhase.IDLE, prepPhaseOf(DownloadPhase.IDLE, false, gameReady))
        assertEquals(PrepPhase.PROBING, prepPhaseOf(DownloadPhase.PROBING, false, gameReady))
        assertEquals(PrepPhase.DOWNLOADING, prepPhaseOf(DownloadPhase.DOWNLOADING, false, gameReady))
        assertEquals(PrepPhase.DOWNLOADING, prepPhaseOf(DownloadPhase.PAUSED, false, gameReady))
        assertEquals(PrepPhase.EXTRACTING, prepPhaseOf(DownloadPhase.EXTRACTING, false, gameReady))
        assertEquals(PrepPhase.READY, prepPhaseOf(DownloadPhase.DONE, false, gameReady))
        // 失败/取消回到静止
        assertEquals(PrepPhase.IDLE, prepPhaseOf(DownloadPhase.FAILED, false, gameReady))
        assertEquals(PrepPhase.IDLE, prepPhaseOf(DownloadPhase.CANCELLED, false, gameReady))
        // 磁盘上已有资源但下载服务空闲：就绪
        assertEquals(PrepPhase.READY, prepPhaseOf(DownloadPhase.IDLE, false, gameReady = true))
    }

    @Test
    fun installingBeatsDownloadPhase() {
        // 导入中即使下载服务处于暂停态，也要显示「解压安装中」
        assertEquals(PrepPhase.EXTRACTING, prepPhaseOf(DownloadPhase.PAUSED, busyInstalling = true, gameReady = true))
    }

    @Test
    fun shakingOnlyWhileRunning() {
        assertTrue(isShaking(PrepPhase.PROBING, paused = false))
        assertTrue(isShaking(PrepPhase.DOWNLOADING, paused = false))
        assertTrue(isShaking(PrepPhase.EXTRACTING, paused = false))
        assertTrue(isShaking(PrepPhase.READY, paused = false))
        // 静止 / 暂停：停在正中
        assertFalse(isShaking(PrepPhase.IDLE, paused = false))
        assertFalse(isShaking(PrepPhase.DOWNLOADING, paused = true))
        assertFalse(isShaking(PrepPhase.IDLE, paused = true))
    }

    // ---------------------------------------------------------------- 文案与常量

    @Test
    fun flowTitleMatchesHarmonyWording() {
        // 四段文案本身来自 shared/ui.json（两端同一条），这里锁的是「阶段 → 哪一条」
        assertEquals(SharedUi.TEXT_DOWNLOAD_TITLE, flowTitleOf(PrepPhase.DOWNLOADING, paused = false))
        assertEquals(SharedUi.TEXT_PAUSED, flowTitleOf(PrepPhase.DOWNLOADING, paused = true))
        assertEquals(SharedUi.TEXT_EXTRACT_TITLE + "…", flowTitleOf(PrepPhase.EXTRACTING, paused = false))
        assertEquals(SharedUi.TEXT_READY_TITLE, flowTitleOf(PrepPhase.READY, paused = false))
        // 本地导入的进度文案优先（形如「本地 game.zip：34 个文件，120MB」）
        assertEquals(
            "本地 game.zip：34 个文件，120MB",
            flowTitleOf(PrepPhase.EXTRACTING, paused = false, installingText = "本地 game.zip：34 个文件，120MB")
        )
        // 暂停与解压的文案不能被 importingText 顶掉时保持原样
        assertEquals(SharedUi.TEXT_PAUSED, flowTitleOf(PrepPhase.DOWNLOADING, paused = true, installingText = ""))
    }

    @Test
    fun progressHiddenWhileExtractingOrReady() {
        assertFalse(hidesProgress(PrepPhase.DOWNLOADING))
        assertFalse(hidesProgress(PrepPhase.PROBING))
        assertTrue(hidesProgress(PrepPhase.EXTRACTING))
        assertTrue(hidesProgress(PrepPhase.READY))
    }

    @Test
    fun animationConstantsMatchHarmony() {
        // 与鸿蒙版 PokeballLoader.ets 同值：换向间隔 450ms / 幅度 ±14° / 过渡 420ms
        assertEquals(450L, SHAKE_INTERVAL_MS)
        assertEquals(14f, SHAKE_ANGLE)
        assertEquals(420, SHAKE_ANIM_MS)
        // 解压完成后停留 900ms（鸿蒙版 BURST_MS；那边的 SHRINK_MS 安卓用不上）
        assertEquals(900L, BURST_MS)
    }

    // ─── 能否进游戏（真机回归：冷启动时服务还没起，按钮不能是禁用的） ───

    @Test
    fun canEnterGameIgnoresRunningServer() {
        // 资源就绪 + WebView 达标 → 可以进游戏。本地服务由 enterGame() 现场启动，
        // 所以这里根本没有「服务是否已在 RUNNING」这个入参——
        // 曾经把它算进来，导致冷启动（资源就绪、服务未起）时按钮被禁掉且无处可点。
        assertTrue(canEnterGameOf(gameReady = true, chromiumMajor = 114, minChromiumMajor = 100))
        assertTrue(canEnterGameOf(gameReady = true, chromiumMajor = 100, minChromiumMajor = 100))
    }

    @Test
    fun canEnterGameNeedsResourcesAndWebView() {
        assertFalse(canEnterGameOf(gameReady = false, chromiumMajor = 114, minChromiumMajor = 100))
        assertFalse(canEnterGameOf(gameReady = true, chromiumMajor = 99, minChromiumMajor = 100))
        // 没检测到 WebView 时 chromiumMajor 是 0
        assertFalse(canEnterGameOf(gameReady = true, chromiumMajor = 0, minChromiumMajor = 100))
    }

    @Test
    fun blockReasonIsNullWhenReadyEvenIfServerNeverStarted() {
        // 关键：服务没起不算「进不去」，否则首页会红字劝退一个其实点得动的按钮
        assertNull(
            enterGameBlockReasonOf(
                gameReady = true, serverError = "", chromiumMajor = 114, minChromiumMajor = 100
            )
        )
    }

    @Test
    fun blockReasonCoversResourcesWebViewAndServerFailure() {
        assertTrue(blockReason(false, "", 114, 100)?.contains("离线资源未就绪") == true)
        assertTrue(blockReason(true, "", 99, 100)?.contains("WebView 版本过低") == true)
        assertTrue(blockReason(true, "bind failed", 114, 100)?.contains("本地服务启动失败") == true)
    }

    @Test
    fun blockReasonPrefersEarlierProblems() {
        // 资源都没装上时，服务报错不是用户此刻该关心的事
        assertTrue(blockReason(false, "bind failed", 114, 100)?.contains("离线资源未就绪") == true)
        assertTrue(blockReason(true, "bind failed", 99, 100)?.contains("WebView 版本过低") == true)
    }

    private fun blockReason(gameReady: Boolean, serverError: String, chromium: Int, min: Int): String? =
        enterGameBlockReasonOf(gameReady, serverError, chromium, min)
}
