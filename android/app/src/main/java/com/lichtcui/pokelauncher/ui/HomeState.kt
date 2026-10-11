package com.lichtcui.pokelauncher.ui

import com.lichtcui.pokelauncher.install.DownloadPhase
import com.lichtcui.pokelauncher.install.DownloadStatus

/**
 * 首页状态机与精灵球动画的纯逻辑（无 Compose 依赖，可 JVM 单测）。
 *
 * 对应鸿蒙版的 `components/PokeballLoader.ets`（[PrepPhase] + 时长常量）
 * 与 `model/LauncherSession.ets` 的 `LauncherStage` + `flowTitle()`。
 * 之所以把这几段从 Compose 文件里拆出来：`ui/` 下的 `@Composable` 无法单测，
 * 而「四态判定」和「阶段 → 文案」是最容易写错、又最不需要真机就能验的部分。
 */

// ─────────────────────────── 首页四态 ───────────────────────────

/**
 * 首页显示哪一块（对应鸿蒙版 `LauncherStage`）。
 *
 * 鸿蒙版有五个值（Checking / NotDownloaded / Downloading / Extracting / Ready），
 * 这里合并成四态：下载与解压共用同一块视图（精灵球动画 + 进度），只是文案不同，
 * 鸿蒙版拆成两个 stage 是为了切页，Android 用同一棵树内的 `when` 更省事。
 */
enum class HomeState {
    /** 启动时判定中（读沙箱目录是 IO，先显示转圈，避免闪一下「未安装」） */
    CHECKING,

    /** 未安装资源：开始下载 / 继续下载 / 导入 zip */
    NOT_DOWNLOADED,

    /** 下载或解压中：精灵球摇晃 + 进度 + 暂停/取消 */
    FLOW,

    /** 已就绪：开始运行 */
    READY
}

/**
 * 四态判定。
 *
 * 判定顺序是刻意的：**先看是否在流程中**，再看是否已就绪——
 * 更新资源包时 `gameReady` 可能还是 true（旧包还在），此时必须显示下载流程而不是就绪页。
 */
fun homeStateOf(initializing: Boolean, inFlow: Boolean, gameReady: Boolean): HomeState = when {
    initializing -> HomeState.CHECKING
    inFlow -> HomeState.FLOW
    gameReady -> HomeState.READY
    else -> HomeState.NOT_DOWNLOADED
}

/**
 * 是否该继续显示下载/解压流程。
 *
 * 注意 **PAUSED 也算流程中**：`isActive` 只覆盖「服务正在跑」，暂停后服务还挂着通知等用户回来，
 * 若此时翻回「未安装」页，用户就再也点不到「继续」了（鸿蒙版靠 stage 留在 Downloading 解决）。
 */
fun inFlowOf(status: DownloadStatus, busyInstalling: Boolean, holdReady: Boolean): Boolean =
    busyInstalling || holdReady || status.isActive || status.phase == DownloadPhase.PAUSED

// ─────────────────────────── 精灵球阶段 ───────────────────────────

/** 精灵球动画阶段（与鸿蒙版 `PrepPhase` 同名同序，少一个 Android 不存在的 DOWNLOADED） */
enum class PrepPhase {
    /** ⓪ 静止：未安装资源（或下载失败/取消后回到静止） */
    IDLE,

    /** ① 测速选源 */
    PROBING,

    /** ② 下载中（含暂停：暂停时由 [paused] 冻结在正中） */
    DOWNLOADING,

    /** ③ 解压安装中 */
    EXTRACTING,

    /** ④ 完成：准备切就绪页 */
    READY
}

/** 摇晃半周期（ms），与鸿蒙版 `SHAKE_INTERVAL_MS` 同值 */
const val SHAKE_INTERVAL_MS = 450L

/** 摇晃幅度（度），与鸿蒙版 `SHAKE_ANGLE` 同值 */
const val SHAKE_ANGLE = 14f

/** 单次摇晃的过渡时长（ms），与鸿蒙版 `animation({ duration: 420 })` 同值 */
const val SHAKE_ANIM_MS = 420

/**
 * 解压完成 → 就绪页的停留（ms），与鸿蒙版 `BURST_MS` 同值。
 * 鸿蒙版还有一个 `SHRINK_MS`（下载完成 → 解压中的停留）：Android 的下载与解压都在前台服务里
 * 连续完成，没有「下载完成」这个中间态，所以这里**刻意不搬**那个常量。
 */
const val BURST_MS = 900L

/**
 * 下载状态 → 精灵球阶段。
 *
 * @param busyInstalling 本地 zip 导入/安装进行中（与下载共用同一套动画，阶段算「解压」）
 * @param gameReady 沙箱里已有可用资源
 */
fun prepPhaseOf(phase: DownloadPhase, busyInstalling: Boolean, gameReady: Boolean): PrepPhase = when {
    busyInstalling -> PrepPhase.EXTRACTING
    phase == DownloadPhase.PROBING -> PrepPhase.PROBING
    phase == DownloadPhase.DOWNLOADING || phase == DownloadPhase.PAUSED -> PrepPhase.DOWNLOADING
    phase == DownloadPhase.EXTRACTING -> PrepPhase.EXTRACTING
    // DONE 之后 UI 会先停 900ms 再说「准备就绪」，本地导入完成同理
    phase == DownloadPhase.DONE || gameReady -> PrepPhase.READY
    else -> PrepPhase.IDLE
}

/** 摇晃是否进行中：静止/暂停时停在正中（与鸿蒙版 `PokeballLoader.sync()` 同判定） */
fun isShaking(phase: PrepPhase, paused: Boolean): Boolean = !paused && phase != PrepPhase.IDLE

/**
 * 流程标题（与鸿蒙版 `LauncherSession.flowTitle()` 逐字一致）。
 *
 * @param installingText 本地导入正在进行的进度文案（非空时优先，例如「本地 game.zip：34 个文件，120MB」）
 */
fun flowTitleOf(phase: PrepPhase, paused: Boolean, installingText: String = ""): String = when {
    installingText.isNotEmpty() -> installingText
    phase == PrepPhase.EXTRACTING -> "正在解压 PokeRogue 资源…"
    phase == PrepPhase.READY -> "PokeRogue 资源准备就绪"
    paused -> "已暂停"
    else -> "正在下载 PokeRogue 资源"
}

/** 流程中是否隐藏进度条（这些阶段没有百分比，只有「请勿关闭应用」） */
fun hidesProgress(phase: PrepPhase): Boolean = phase == PrepPhase.EXTRACTING || phase == PrepPhase.READY
