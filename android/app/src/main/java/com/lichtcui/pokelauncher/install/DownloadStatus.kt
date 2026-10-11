package com.lichtcui.pokelauncher.install

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.lichtcui.pokelauncher.core.Format

/** 下载链路的阶段。UI 文案集中在 `SpikeScreen`，这里只表达状态 */
enum class DownloadPhase { IDLE, PROBING, DOWNLOADING, PAUSED, EXTRACTING, DONE, FAILED, CANCELLED }

/**
 * 下载状态快照：打靶页与通知都**只读**它。
 * 对应鸿蒙版 `LauncherSession` 里的 `ProgressView`（同一份数据分别驱动卡片与通知）。
 */
data class DownloadStatus(
    val phase: DownloadPhase = DownloadPhase.IDLE,
    val received: Long = 0,
    val total: Long = 0,
    val speedBps: Double = 0.0,
    /** 剩余秒数（由 [ProgressMeter] 估算；未知为 0） */
    val etaSeconds: Double = 0.0,
    val sourceUrl: String = "",
    val sourceIndex: Int = 0,
    val sourceCount: Int = 0,
    val tag: String = "",
    /** 阶段说明（测速中 / 正在解压… / 已暂停） */
    val message: String = "",
    val error: String = ""
) {
    /** 0~100；总大小未知时为 0（界面改显示「已下载 x」） */
    val percent: Int
        get() = if (total > 0) ((received * 100 / total).coerceIn(0, 100)).toInt() else 0

    val host: String get() = DownloadUrls.hostOf(sourceUrl)

    val sourceKind: String get() = DownloadUrls.kindOf(sourceIndex, sourceCount)

    /** 下载进行中（含测速/解压），此时禁止重复发起 */
    val isActive: Boolean
        get() = phase == DownloadPhase.PROBING ||
            phase == DownloadPhase.DOWNLOADING ||
            phase == DownloadPhase.EXTRACTING

    val canPause: Boolean get() = phase == DownloadPhase.DOWNLOADING
    val canResume: Boolean get() = phase == DownloadPhase.PAUSED
    val canCancel: Boolean get() = isActive || phase == DownloadPhase.PAUSED
}

/**
 * 下载文案（通知与打靶页共用；纯函数，可 JVM 单测），单位格式化全部复用 [Format]。
 * 对应鸿蒙版 `LauncherSession.emitProgress` 里那串「百分比 · 已下/总量 · 速度 · 剩余」。
 */
object DownloadText {

    fun describe(status: DownloadStatus): String {
        val percentText = if (status.total > 0) "${status.percent}%" else ""
        val sizeText = if (status.total > 0) {
            "${Format.bytes(status.received)} / ${Format.bytes(status.total)}"
        } else {
            "已下载 ${Format.bytes(status.received)}"
        }
        val sourceText = if (status.sourceUrl.isNotEmpty()) {
            " · ${status.host}（${status.sourceKind} ${status.sourceIndex + 1}/${status.sourceCount}）"
        } else {
            ""
        }
        return when (status.phase) {
            DownloadPhase.IDLE -> "未开始"
            DownloadPhase.PROBING -> status.message.ifEmpty { "正在测速，选择最快的下载源…" }
            DownloadPhase.DOWNLOADING -> {
                val parts = ArrayList<String>(4)
                if (percentText.isNotEmpty()) parts.add(percentText)
                parts.add(sizeText)
                Format.speed(status.speedBps.toLong()).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
                Format.duration(status.etaSeconds).takeIf { it.isNotEmpty() }
                    ?.let { parts.add("剩余 $it") }
                parts.joinToString(" · ") + sourceText
            }

            DownloadPhase.PAUSED ->
                listOf("已暂停", percentText.takeIf { it.isNotEmpty() }, sizeText)
                    .filterNotNull().joinToString(" · ")

            DownloadPhase.EXTRACTING -> status.message.ifEmpty { "正在解压 PokeRogue 资源…" }
            DownloadPhase.DONE -> status.message.ifEmpty { "下载并安装完成" }
            DownloadPhase.FAILED -> status.error.ifEmpty { status.message.ifEmpty { "下载失败" } }
            DownloadPhase.CANCELLED -> "已取消"
        }
    }
}

/**
 * 进程内下载状态总线：`DownloadService` 是唯一写入方，
 * UI（以及未来的首页状态机）订阅它。用进程级单例而不是 bind/回调，
 * 是因为前台服务可能活得比 Activity 久（旋转、回首页都不该丢状态）。
 */
object DownloadBus {

    private val _status = MutableStateFlow(DownloadStatus())
    val status: StateFlow<DownloadStatus> = _status.asStateFlow()

    fun publish(next: DownloadStatus) {
        _status.value = next
    }

    /** 局部更新（只改阶段/文案时用，避免各处重复构造整份快照） */
    fun update(block: (DownloadStatus) -> DownloadStatus) {
        _status.value = block(_status.value)
    }
}

/**
 * 速度平滑 + 剩余时间估算（纯类，可 JVM 单测）。
 * 逐字对齐鸿蒙版 `LauncherSession.applyProgress` 的算法：
 * 指数平滑 `speed = speed * 0.6 + inst * 0.4`，采样间隔 >= 300ms，避免数字抖动。
 */
class ProgressMeter(
    private val smoothing: Double = 0.6,
    private val minSampleMs: Long = 300
) {
    var speedBps: Double = 0.0
        private set

    private var lastAt: Long = 0
    private var lastBytes: Long = 0

    fun reset() {
        speedBps = 0.0
        lastAt = 0
        lastBytes = 0
    }

    /** 返回平滑后的速度（bytes/s） */
    fun update(received: Long, nowMs: Long): Double {
        if (lastAt > 0 && received >= lastBytes) {
            val dtMs = nowMs - lastAt
            if (dtMs >= minSampleMs) {
                val inst = (received - lastBytes) * 1000.0 / dtMs
                speedBps = if (speedBps > 0) speedBps * smoothing + inst * (1 - smoothing) else inst
                lastBytes = received
                lastAt = nowMs
            }
        } else {
            // 换源/回退导致字节数变小时重新起算，否则会出现负速度
            lastBytes = received
            lastAt = nowMs
        }
        return speedBps
    }

    /** 剩余秒数；速度未知或已下完返回 0（界面据此不显示） */
    fun etaSeconds(total: Long, received: Long): Double {
        if (speedBps <= 0) return 0.0
        if (total <= received) return 0.0
        return (total - received) / speedBps
    }

    /**
     * 进度事件非常密集（每读一块触发一次）：节流 UI / 通知刷新。
     * 暂停与 100% 立即刷新，否则用户会觉得按钮没响应。
     */
    fun shouldEmit(
        lastEmitAt: Long,
        nowMs: Long,
        percent: Int,
        paused: Boolean = false,
        throttleMs: Long = 150
    ): Boolean {
        if (paused || percent >= 100) return true
        return nowMs - lastEmitAt >= throttleMs
    }
}
