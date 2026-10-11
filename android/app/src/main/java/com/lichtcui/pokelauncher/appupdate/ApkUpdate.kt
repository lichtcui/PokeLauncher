package com.lichtcui.pokelauncher.appupdate

import com.lichtcui.pokelauncher.core.Format

/**
 * 应用内自更新（下载 APK → 交给系统安装器）的**纯逻辑**：
 * 文件名推导 / 包体校验 / 状态文案。无 Android 依赖，可 JVM 单测。
 *
 * 为什么要自己推导文件名：`apkUrl` 是仓库里手写的地址（可能带 query、可能是
 * `.../download`、也可能没扩展名），直接当文件名用会拿到空串或带 `?` 的名字。
 *
 * 对应鸿蒙版**没有**的能力：鸿蒙只能「检测 + 引导浏览器」（应用内无法静默装 HAP），
 * 安卓这条链是端到端体验差异最大的一处（见 `docs/android-port.md` §4.3.8）。
 */

/** 自更新下载阶段（UI 只读这个枚举决定显示什么） */
enum class ApkUpdatePhase {
    /** 还没开始（或已复位） */
    IDLE,

    /** 正在下载更新包 */
    DOWNLOADING,

    /** 下载完，正在校验（包体大小 + zip 魔数） */
    VERIFYING,

    /** 已就绪：文件在 [ApkUpdateState.fileName] 指向的位置，等系统安装器接手 */
    READY,

    /** 失败：原因在 [ApkUpdateState.message] */
    FAILED
}

/**
 * 自更新状态快照（不可变；控制器整体替换，Compose 才能正确重组）。
 *
 * @param doneBytes 已下载字节
 * @param totalBytes 总字节（`Content-Length` 缺失时为 0，此时百分比按 0 处理）
 * @param fileName 落盘文件名（在应用专属 `apk/` 目录里）
 * @param versionName 目标版本名（只用于展示）
 */
data class ApkUpdateState(
    val phase: ApkUpdatePhase = ApkUpdatePhase.IDLE,
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val fileName: String = "",
    val versionName: String = "",
    val message: String = ""
) {

    /** 是否正在下载（决定「取消」按钮是否出现） */
    val downloading: Boolean get() = phase == ApkUpdatePhase.DOWNLOADING

    /** 百分比：`total` 未知时返回 0（不能返回 -1，进度条会炸） */
    val progressPercent: Int
        get() = if (totalBytes <= 0) 0 else ((doneBytes * 100) / totalBytes).coerceIn(0L, 100L).toInt()

    /** 更新行的标题 */
    fun rowTitleText(): String = when (phase) {
        ApkUpdatePhase.IDLE -> "去更新"
        ApkUpdatePhase.DOWNLOADING -> "正在下载更新包…"
        ApkUpdatePhase.VERIFYING -> "正在校验更新包…"
        ApkUpdatePhase.READY -> "安装更新"
        ApkUpdatePhase.FAILED -> "重试更新"
    }

    /** 更新行的右侧文案（空串表示不显示） */
    fun rowStatusText(): String = when (phase) {
        ApkUpdatePhase.IDLE -> versionName
        ApkUpdatePhase.DOWNLOADING ->
            if (totalBytes > 0) "$progressPercent%" else Format.bytes(doneBytes)
        ApkUpdatePhase.VERIFYING -> ""
        ApkUpdatePhase.READY -> "已下载完成"
        ApkUpdatePhase.FAILED -> message
    }

    /** 详情行（失败原因 / 取消提示 / 安装提示），无内容时返回空串 */
    fun detailText(): String = when (phase) {
        // IDLE + message：本块唯一的来源就是「用户取消了下载」，不提示的话界面像没点过
        ApkUpdatePhase.IDLE -> message
        ApkUpdatePhase.FAILED -> if (message.isEmpty()) "" else "更新失败：$message"
        ApkUpdatePhase.READY -> READY_DETAIL
        else -> ""
    }

    companion object {
        const val READY_DETAIL = "更新包已下载，点「安装更新」交给系统安装器（需要允许安装未知应用）。"
    }
}

/** 「去更新 / 取消 / 安装」三个动作（首页弹窗与设置页共用同一套分发） */
enum class ApkUpdateAction { START, CANCEL, INSTALL }

/**
 * 当前阶段下按同一个按钮应该做什么。
 *
 * 抽出来的原因：两处 UI（首页弹窗、设置页行）必须完全一致——
 * 一块写「下载中可取消」另一块写「下载中重新下载」就会双开下载。
 */
fun apkActionOf(phase: ApkUpdatePhase): ApkUpdateAction = when (phase) {
    ApkUpdatePhase.DOWNLOADING, ApkUpdatePhase.VERIFYING -> ApkUpdateAction.CANCEL
    ApkUpdatePhase.READY -> ApkUpdateAction.INSTALL
    ApkUpdatePhase.IDLE, ApkUpdatePhase.FAILED -> ApkUpdateAction.START
}

/**
 * 更新入口的标题：首页弹窗按钮与设置页行共用（两处文案必须一字不差）。
 *
 * @param versionName 目标版本名；空串时 IDLE 下不拼括号
 */
fun apkRowLabel(state: ApkUpdateState, versionName: String): String = when (state.phase) {
    ApkUpdatePhase.IDLE -> if (versionName.isEmpty()) "去更新" else "去更新（$versionName）"
    else -> state.rowTitleText()
}

/**
 * 更新入口右侧的文案（百分比 / 结果）。
 *
 * `forceUpdate` 只在「还没开始下载」时有意义：一旦按下去，右侧就该让位给进度与结果。
 */
fun apkRowValue(state: ApkUpdateState, forceUpdate: Boolean): String = when (state.phase) {
    // IDLE 不重复 [ApkUpdateState.detailText] 的取消提示（两行同文看起来像 bug）
    ApkUpdatePhase.IDLE -> if (forceUpdate) "必须更新" else ""
    ApkUpdatePhase.FAILED -> state.message
    else -> state.rowStatusText()
}

/** 小于这个大小的文件一定不是安装包（用来识破「404 页面被当成 APK 存下来」） */
const val MIN_APK_BYTES = 64L * 1024

/**
 * 从下载地址推导 APK 文件名。
 *
 * 规则：取路径最后一段 → 只留 `[A-Za-z0-9._-]` → 去前导点 → 保证 `.apk` 结尾 →
 * 空/非法时回退 `pokerogue-<versionName>.apk` → 超长截断（保扩展名）。
 */
fun apkFileName(url: String, versionName: String = ""): String {
    val path = url.substringBefore('#').substringBefore('?')
    val last = path.trimEnd('/').substringAfterLast('/')
    val cleaned = sanitizeFileNameSegment(last)
    val withExt = when {
        cleaned.isEmpty() -> ""
        cleaned.endsWith(".apk", ignoreCase = true) -> cleaned
        else -> "$cleaned.apk"
    }
    if (withExt.isNotEmpty()) return withExt
    val version = sanitizeFileNameSegment(versionName).ifEmpty { "update" }
    return "pokerogue-$version.apk"
}

/**
 * zip 魔数校验：`PK` + `03 04`（普通）/ `05 06`（空归档）/ `07 08`（分卷）。
 * 只读文件头 4 字节即可，不解析整个 APK（手机上下载的东西不值得我们信任到去解析）。
 */
fun apkLooksValid(head: ByteArray): Boolean {
    if (head.size < 4) return false
    if (head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) return false
    val b2 = head[2].toInt() and 0xFF
    val b3 = head[3].toInt() and 0xFF
    return (b2 == 0x03 && b3 == 0x04) || (b2 == 0x05 && b3 == 0x06) || (b2 == 0x07 && b3 == 0x08)
}

/** 下载完成后整体校验：大小 + 魔数（两者任一不过都算「下载到的不是安装包」） */
fun apkFileAccepted(size: Long, head: ByteArray): Boolean = size >= MIN_APK_BYTES && apkLooksValid(head)

/** 校验失败的统一文案（网络中间页/被运营商劫持都会长这样） */
const val INVALID_APK_TEXT = "下载到的不是安装包（可能被网络劫持或链接失效）"

/** 没有可用下载地址时的文案 */
const val NO_APK_URL_TEXT = "清单里没有可用的安装包地址"

/** 「允许安装未知应用」的引导文案（用户点安装但系统设置没开时显示） */
const val UNKNOWN_SOURCES_TEXT = "需要先允许本应用安装未知来源的应用，去系统设置里打开后回来再点「安装更新」。"

/** 取消下载的文案 */
const val APK_CANCELLED_TEXT = "已取消更新下载"

/**
 * 只保留安全字符的文件名片段；顺带把 `.`/`..` 这种目录引用打成空串。
 * （与 `web/DownloadBridge.safeFileName` 同一套思路，但这里是给系统安装器看，
 * 更严格：只允许 ASCII 字母数字与 `. _ -`。）
 */
private fun sanitizeFileNameSegment(raw: String): String {
    val trimmed = raw.trim().trim('"', '\'')
    val sb = StringBuilder(trimmed.length)
    for (ch in trimmed) {
        val ok = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' || ch == '_' || ch == '-'
        if (ok) sb.append(ch)
    }
    var name = sb.toString().trimStart('.')
    name = name.trimEnd('.')
    if (name.isEmpty() || name.all { it == '.' }) return ""
    if (name.length > 100) {
        val ext = ".apk"
        name = if (name.endsWith(ext, ignoreCase = true)) {
            name.substring(0, 100 - ext.length) + ext
        } else {
            name.substring(0, 100)
        }
    }
    return name
}
