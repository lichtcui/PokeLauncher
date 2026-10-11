package com.lichtcui.pokelauncher.server

/**
 * 本地服务启动失败的诊断（纯函数 → JVM 可单测）。
 *
 * 端口占用是本项目最常见的启动失败原因，而且现象很容易被误判成「服务器坏了」：
 * 鸿蒙版靠 `err.code === 98`（EADDRINUSE）识别，Android 侧 NanoHTTPD 抛的是
 * `java.net.BindException: Address already in use`，不同 ROM 的文案还不一样，
 * 所以这里统一按「异常类型 + 文案关键词」判定，并给出可操作的中文提示。
 */
object ServerDiag {

    /** 端口被占用的判定关键词（全部小写比较） */
    private val PORT_IN_USE_HINTS = listOf(
        "eaddrinuse",
        "address already in use",
        "bind failed",
        "failed to bind",
        "could not bind",
        "cannot assign requested address"
    )

    fun isPortInUseMessage(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return PORT_IN_USE_HINTS.any { m.contains(it) }
    }

    /** 沿 cause 链找 `BindException` / 端口占用文案（NanoHTTPD 可能包了一层） */
    fun isPortInUse(error: Throwable?): Boolean {
        var e = error
        var depth = 0
        while (e != null && depth < 8) {
            if (e is java.net.BindException) return true
            if (isPortInUseMessage(e.message)) return true
            e = e.cause
            depth++
        }
        return false
    }

    /** 失败原因（取异常链里第一条有 message 的） */
    fun reason(error: Throwable?): String {
        var e = error
        var depth = 0
        while (e != null && depth < 8) {
            val m = e.message
            if (!m.isNullOrBlank()) return m
            e = e.cause
            depth++
        }
        return error?.javaClass?.simpleName ?: "未知错误"
    }

    /** 给用户看的一句话（不入日志前缀，调用方自己加） */
    fun describe(port: Int, error: Throwable?): String {
        return if (isPortInUse(error)) {
            "本地服务端口 $port 被占用，无法启动。请先关掉其它正在运行的 PokeRogue 窗口/" +
                "实例后重试；若仍失败，重启手机再试（该端口不能改，改了会让游戏存档失效）。"
        } else {
            "本地服务启动失败（端口 $port）：${reason(error)}。请重试，或重启 App。"
        }
    }
}
