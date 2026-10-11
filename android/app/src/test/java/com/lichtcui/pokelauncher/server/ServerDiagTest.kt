package com.lichtcui.pokelauncher.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.BindException

/**
 * 本地服务启动失败的诊断（端口占用的判定与中文提示）。
 * 鸿蒙版靠 `err.code === 98`，Android 侧只能靠异常类型 + 文案关键词。
 */
class ServerDiagTest {

    @Test
    fun bindExceptionIsPortInUse() {
        assertTrue(ServerDiag.isPortInUse(BindException("Address already in use")))
    }

    @Test
    fun androidMessageVariantIsPortInUse() {
        assertTrue(ServerDiag.isPortInUse(BindException("bind failed: EADDRINUSE (Address already in use)")))
    }

    @Test
    fun nanoHttpdWrappedMessageIsPortInUse() {
        assertTrue(ServerDiag.isPortInUse(IOException("Could not bind to 127.0.0.1:18787")))
    }

    @Test
    fun portInUseIsFoundThroughCauseChain() {
        val e = IOException("server start failed", RuntimeException("wrap", BindException("Address already in use")))
        assertTrue(ServerDiag.isPortInUse(e))
    }

    @Test
    fun genericFailureIsNotPortInUse() {
        assertFalse(ServerDiag.isPortInUse(RuntimeException("boom")))
        assertFalse(ServerDiag.isPortInUse(null))
        assertFalse(ServerDiag.isPortInUse(SecurityException("permission denied")))
    }

    @Test
    fun messageMatchingIsCaseInsensitive() {
        assertTrue(ServerDiag.isPortInUseMessage("ADDRESS ALREADY IN USE"))
        assertTrue(ServerDiag.isPortInUseMessage("eaddrinuse"))
        assertFalse(ServerDiag.isPortInUseMessage(null))
    }

    @Test
    fun describePortInUseMentionsPortAndPortCannotChange() {
        val text = ServerDiag.describe(18787, BindException("Address already in use"))
        assertTrue(text.contains("18787"))
        assertTrue(text.contains("被占用"))
        // 用户最常问「能不能换个端口」：提示里必须给出「不能改」的原因
        assertTrue(text.contains("存档"))
    }

    @Test
    fun describeOtherErrorKeepsOriginalMessage() {
        val text = ServerDiag.describe(18787, RuntimeException("permission denied"))
        assertTrue(text.contains("18787"))
        assertTrue(text.contains("permission denied"))
        assertFalse(text.contains("被占用"))
    }

    @Test
    fun describeFallsBackToClassNameWhenNoMessage() {
        val text = ServerDiag.describe(18787, RuntimeException())
        assertTrue(text.contains("RuntimeException"))
    }

    @Test
    fun reasonUsesDeepestAvailableMessage() {
        val e = IOException("outer", RuntimeException("inner"))
        assertTrue(ServerDiag.reason(e) == "outer")
    }
}
