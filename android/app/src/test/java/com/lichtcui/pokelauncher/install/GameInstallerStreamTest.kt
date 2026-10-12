package com.lichtcui.pokelauncher.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 锁死「流式解压时条目流不能被关闭」这条约束。
 *
 * 真机验收（P3-14「上传资源」）暴露的缺陷：`extract` 对每个条目做
 * `openEntry(entry).use { … }`，而 [GameInstaller.installFromStream] 传进去的是**同一个**
 * 共享 [ZipInputStream]，第一个条目读完就把整个 zip 流关掉了，第二条 `nextEntry`
 * 直接抛 `IOException: Stream closed`（真机日志 `install failed: 解压失败：Stream closed`）。
 *
 * 这里用内存 zip 精确复现 `extract` 的消费模式，不依赖文件系统与 `android.util.Log`。
 */
class GameInstallerStreamTest {

    private fun zipOf(files: LinkedHashMap<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, body) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sample(): ByteArray = zipOf(
        linkedMapOf(
            "index.html" to "<html>index</html>",
            "js/main.js" to "console.log('main')",
            "assets/a.txt" to "a",
            "assets/b.txt" to "b",
        )
    )

    /** 复现 `extract` 的循环：每个条目都 `use {}` 关一次，仍必须能读到最后一个条目。 */
    @Test
    fun sharedEntryStream_survivesUseBlock_forEveryEntry() {
        val zipIn = ZipInputStream(ByteArrayInputStream(sample()))
        val names = mutableListOf<String>()
        while (true) {
            val entry = zipIn.nextEntry ?: break
            if (entry.isDirectory) continue
            SharedZipEntryStream(zipIn).use { it.readBytes() } // ← extract 里的写法
            names += entry.name
        }
        assertEquals(listOf("index.html", "js/main.js", "assets/a.txt", "assets/b.txt"), names)
    }

    /** 对照组：按修复前的写法直接用 zipIn，第二个条目必然炸——就是真机上的报错。 */
    @Test
    fun rawZipInputStream_breaksOnSecondEntry_provingTheBug() {
        val zipIn = ZipInputStream(ByteArrayInputStream(sample()))
        assertNotNull(zipIn.nextEntry)
        zipIn.use { it.readBytes() } // 模拟 extract 的 use{}：把共享流关掉
        val error = runCatching { zipIn.nextEntry }.exceptionOrNull()
        assertTrue("应复现 Stream closed，实际=$error", error is IOException)
    }

    @Test
    fun sharedEntryStream_readTransfersExactBytes() {
        val zipIn = ZipInputStream(ByteArrayInputStream(sample()))
        zipIn.nextEntry
        val bytes = SharedZipEntryStream(zipIn).use { it.readBytes() }
        assertEquals("<html>index</html>", String(bytes))
    }

    /** 关闭包装流不能影响底层：后续条目仍可推进。 */
    @Test
    fun sharedEntryStream_closeDoesNotCloseSource() {
        val zipIn = ZipInputStream(ByteArrayInputStream(sample()))
        zipIn.nextEntry
        SharedZipEntryStream(zipIn).close()
        assertNotNull(zipIn.nextEntry)
    }
}
