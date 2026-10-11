package com.lichtcui.pokelauncher.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 游戏内存档导出（对应鸿蒙 `WebDownloadDelegate`）。
 *
 * 没有真机也能单测的部分全部在这里：文件名清洗、`Content-Disposition` 解析、文案、
 * JS 字符串转义、注入脚本的关键片段（并且**注入脚本里不能出现 `$`**，会被 Kotlin 当模板）。
 * 真正落盘（MediaStore / 应用目录）只能真机验证，见 `docs/android-port.md` §4.4。
 */
class DownloadBridgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------ 文件名清洗

    @Test
    fun safeFileNameStripsPathSeparators() {
        assertEquals("etcpasswd", DownloadBridge.safeFileName("../../etc/passwd"))
        assertEquals("abdata.prsv", DownloadBridge.safeFileName("a\\b/data.prsv"))
        assertEquals("xprsv", DownloadBridge.safeFileName("x:prsv"))
    }

    @Test
    fun safeFileNameFallsBackForEmptyAndDots() {
        assertEquals(DownloadBridge.DEFAULT_FILE_NAME, DownloadBridge.safeFileName(""))
        assertEquals(DownloadBridge.DEFAULT_FILE_NAME, DownloadBridge.safeFileName("   "))
        assertEquals(DownloadBridge.DEFAULT_FILE_NAME, DownloadBridge.safeFileName(".."))
        assertEquals(DownloadBridge.DEFAULT_FILE_NAME, DownloadBridge.safeFileName("..."))
        assertEquals(DownloadBridge.DEFAULT_FILE_NAME, DownloadBridge.safeFileName("< >"))
    }

    @Test
    fun safeFileNameDropsLeadingDotAndQuotes() {
        assertEquals("prsv", DownloadBridge.safeFileName(".prsv"))
        assertEquals("data.prsv", DownloadBridge.safeFileName("\"data.prsv\""))
    }

    @Test
    fun safeFileNameKeepsCjkAndTruncatesLongNames() {
        assertEquals("存档.prsv", DownloadBridge.safeFileName("存档.prsv"))
        val long = "a".repeat(300) + ".prsv"
        val clean = DownloadBridge.safeFileName(long)
        assertTrue(clean.length <= 120)
        assertTrue(clean.endsWith(".prsv"))
    }

    @Test
    fun safeFileNameRemovesControlChars() {
        assertEquals("ab.prsv", DownloadBridge.safeFileName("a\u0000\u0007b.prsv"))
    }

    // ------------------------------------------------------------ Content-Disposition

    @Test
    fun fileNameFromDispositionHandlesCommonForms() {
        assertEquals("data.prsv", DownloadBridge.fileNameFromDisposition("attachment; filename=\"data.prsv\""))
        assertEquals("data.prsv", DownloadBridge.fileNameFromDisposition("attachment; filename=data.prsv"))
        assertEquals("save.prsv", DownloadBridge.fileNameFromDisposition("attachment; filename*=UTF-8''save.prsv"))
        assertEquals("save.prsv", DownloadBridge.fileNameFromDisposition("attachment; filename=" + "\"save.prsv\"; size=1"))
        assertEquals("", DownloadBridge.fileNameFromDisposition(null))
        assertEquals("", DownloadBridge.fileNameFromDisposition("attachment"))
    }

    // ------------------------------------------------------------ 文案

    @Test
    fun exportTexts() {
        assertEquals("已导出 data.prsv 到「下载」", DownloadBridge.exportOkText("data.prsv", "下载"))
        assertEquals("导出失败：目录不可写", DownloadBridge.exportFailText("目录不可写"))
        assertEquals("导出失败", DownloadBridge.exportFailText("   "))
        assertEquals("下载", DownloadBridge.locationText(true, File("/x")))
        assertEquals("/x", DownloadBridge.locationText(false, File("/x")))
    }

    // ------------------------------------------------------------ JS 转义与脚本

    @Test
    fun jsStringLiteralEscapesDangerousChars() {
        assertEquals("\"a\\\"b\"", DownloadBridge.jsStringLiteral("a\"b"))
        assertEquals("\"a\\\\b\"", DownloadBridge.jsStringLiteral("a\\b"))
        assertEquals("\"a\\nb\"", DownloadBridge.jsStringLiteral("a\nb"))
        // </script> 必须被打断，否则注入的脚本会提前闭合
        assertFalse(DownloadBridge.jsStringLiteral("</script>").contains("</script>"))
    }

    @Test
    fun buildScriptWrapsAndTargetsBridge() {
        val js = DownloadBridge.buildScript()
        assertTrue(js.startsWith("<script>"))
        assertTrue(js.endsWith("</script>"))
        assertTrue(js.contains("__pkDlInstalled"))
        assertTrue(js.contains("URL.createObjectURL"))
        assertTrue(js.contains("revokeObjectURL"))
        assertTrue(js.contains("HTMLAnchorElement.prototype.click"))
        assertTrue(js.contains("readAsDataURL"))
        assertTrue(js.contains("preventDefault"))
        // 桥对象就是已有的 PokeNative，新增方法名 saveDownload
        assertTrue(js.contains("PokeNative"))
        assertTrue(js.contains("saveDownload"))
        assertTrue(js.contains("data.prsv"))
    }

    @Test
    fun scriptsContainNoDollarSign() {
        // Kotlin raw string 会把 $ 当模板插值，注入脚本里出现 $ 就是编译期/运行期事故
        assertFalse(DownloadBridge.buildScript().contains('$'))
        assertFalse(DownloadBridge.blobFallbackScript("blob:http://x/1", "a.prsv", "text/plain").contains('$'))
    }

    @Test
    fun blobFallbackScriptEmbedsEscapedValues() {
        val js = DownloadBridge.blobFallbackScript(
            "blob:http://127.0.0.1:18787/abc",
            "../../evil.prsv",
            "application/octet-stream"
        )
        assertTrue(js.startsWith("<script>"))
        assertTrue(js.endsWith("</script>"))
        assertTrue(js.contains("XMLHttpRequest"))
        assertTrue(js.contains("blob:http://127.0.0.1:18787/abc"))
        assertTrue(js.contains("saveDownload"))
        // 文件名被清洗（路径分隔符与前面的点没了）后以 JS 字符串字面量形式嵌入
        assertTrue(js.contains("\"evil.prsv\""))
        assertFalse(js.contains("../"))
    }

    @Test
    fun bridgeMethodsMatchScript() {
        val js = DownloadBridge.buildScript()
        for (method in DownloadBridge.BRIDGE_METHODS) {
            assertTrue("script should call $method", js.contains(method))
        }
    }

    // ------------------------------------------------------------ 同名文件去重

    @Test
    fun uniqueFileAppendsSuffixWhenOccupied() {
        val dir = tmp.root
        assertEquals("data.prsv", DownloadBridge.uniqueFile(dir, "data.prsv").name)
        File(dir, "data.prsv").writeText("x")
        assertEquals("data (1).prsv", DownloadBridge.uniqueFile(dir, "data.prsv").name)
        File(dir, "data (1).prsv").writeText("x")
        assertEquals("data (2).prsv", DownloadBridge.uniqueFile(dir, "data.prsv").name)
    }

    @Test
    fun uniqueFileHandlesExtensionlessNames() {
        val dir = tmp.root
        File(dir, "save").writeText("x")
        assertEquals("save (1)", DownloadBridge.uniqueFile(dir, "save").name)
        assertNotEquals("save", DownloadBridge.uniqueFile(dir, "save").name)
    }
}
