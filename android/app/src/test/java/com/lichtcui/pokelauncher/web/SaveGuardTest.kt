package com.lichtcui.pokelauncher.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 存档镜像（`filesDir/saves/localstorage.json`）。
 *
 * ⚠️ Android 侧镜像的目的是 Auto Backup / 存档导出兜底，**不是**从 cacheDir 里抢救
 * （localStorage 本来就在应用数据目录）。所以这里只做「兜底但绝不覆盖」的策略。
 */
class SaveGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = SaveGuardStore(tmp.root)

    @Test
    fun emptyWhenNothingOnDisk() {
        assertEquals("", store().read())
    }

    @Test
    fun submitWritesSnapshot() {
        val s = store()
        assertTrue(s.submit("""{"a":"1"}"""))
        assertTrue(s.awaitIdle())
        assertEquals("""{"a":"1"}""", s.read())
        assertEquals("""{"a":"1"}""", s.mainFile.readText())
        assertTrue(s.mainFile.parentFile?.isDirectory == true)
    }

    @Test
    fun mainFileLivesUnderSavesDir() {
        val s = store()
        assertEquals("saves", s.mainFile.parentFile?.name)
        assertEquals("localstorage.json", s.mainFile.name)
        assertEquals("localstorage.bak.json", s.backupFile.name)
    }

    @Test
    fun duplicateContentIsSkipped() {
        // 游戏内每 60s 会无条件 flush 一次：内容没变时不应该产生 IO
        val s = store()
        assertTrue(s.submit("""{"a":"1"}"""))
        assertTrue(s.awaitIdle())
        assertFalse(s.submit("""{"a":"1"}"""))
        assertTrue(s.awaitIdle())
        assertEquals("""{"a":"1"}""", s.read())
    }

    @Test
    fun tinyContentIsRejected() {
        val s = store()
        assertFalse(s.submit(""))
        assertFalse(s.submit("{}"))
        assertFalse(s.mainFile.exists())
    }

    @Test
    fun previousSnapshotRotatesToBackup() {
        val s = store()
        s.submit("""{"a":"1"}""")
        s.awaitIdle()
        s.submit("""{"a":"2"}""")
        s.awaitIdle()
        assertEquals("""{"a":"2"}""", s.mainFile.readText())
        assertEquals("""{"a":"1"}""", s.backupFile.readText())
    }

    @Test
    fun readFallsBackToBackupWhenMainLost() {
        val s = store()
        s.submit("""{"a":"1"}""")
        s.awaitIdle()
        s.submit("""{"a":"2"}""")
        s.awaitIdle()
        assertTrue(s.mainFile.delete())
        assertEquals("""{"a":"1"}""", s.read())
    }

    @Test
    fun corruptMainFileFallsBackToBackup() {
        val s = store()
        s.submit("""{"a":"1"}""")
        s.awaitIdle()
        s.submit("""{"a":"2"}""")
        s.awaitIdle()
        s.mainFile.writeText("{}")
        assertEquals("""{"a":"1"}""", s.read())
    }

    @Test
    fun existingSnapshotIsLoadedAtConstruction() {
        val first = store()
        first.submit("""{"keep":"me"}""")
        first.awaitIdle()
        // 新进程重启：同一目录重建 store，读到的应是磁盘内容
        assertEquals("""{"keep":"me"}""", store().read())
    }

    @Test
    fun submitIsDeduplicatedAgainstDiskContent() {
        val first = store()
        first.submit("""{"a":"1"}""")
        first.awaitIdle()
        // 重新构造后再提交同样内容也应被跳过（否则每次冷启动都会重写一遍存档）
        assertFalse(store().submit("""{"a":"1"}"""))
    }

    @Test
    fun concurrentSubmitsEndWithLastContent() {
        val s = store()
        for (i in 1..20) s.submit("""{"n":"$i"}""")
        assertTrue(s.awaitIdle())
        assertEquals("""{"n":"20"}""", s.mainFile.readText())
    }

    // ---------------------------------------------------------------- 注入脚本契约

    @Test
    fun scriptStartsWithInjectedTag() {
        val js = SaveGuard.buildScript()
        assertTrue(js.startsWith("<script>(function(){try{"))
        assertTrue(js.endsWith("})();</script>"))
    }

    @Test
    fun scriptUsesTheBridgeObject() {
        val js = SaveGuard.buildScript()
        assertEquals("PokeSaveGuard", SaveGuard.BRIDGE_NAME)
        assertTrue(js.contains("window.${SaveGuard.BRIDGE_NAME}"))
    }

    @Test
    fun scriptCallsEveryBridgeMethod() {
        val js = SaveGuard.buildScript()
        for (m in SaveGuard.BRIDGE_METHODS) {
            assertTrue("bridge method $m 未被脚本调用", js.contains("G.$m("))
        }
    }

    @Test
    fun bridgeMethodsMatchNativeSide() {
        // 与 PokeWebView 的 SaveGuardBridge 方法名一一对应（改这里就要改那边）
        assertEquals(listOf("load", "save", "notifyRestored"), SaveGuard.BRIDGE_METHODS)
    }

    @Test
    fun scriptHooksStorageWrites() {
        val js = SaveGuard.buildScript()
        assertTrue(js.contains("PR.setItem="))
        assertTrue(js.contains("PR.removeItem="))
    }

    @Test
    fun scriptFlushesOnPageHideAndInterval() {
        val js = SaveGuard.buildScript()
        assertTrue(js.contains("pagehide"))
        assertTrue(js.contains("visibilitychange"))
        assertTrue(js.contains("60000"))
    }

    @Test
    fun scriptOnlyRestoresWhenStorageEmpty() {
        // 兜底的前提：只在 localStorage 整体为空时回填，绝不覆盖玩家现有存档
        val js = SaveGuard.buildScript()
        assertTrue(js.contains("if(LS.length===0)"))
    }

    @Test
    fun scriptContainsNoDollarSign() {
        assertFalse(SaveGuard.buildScript().contains('$'))
    }

    @Test
    fun firstWriteDoesNotCreateBackup() {
        val s = store()
        s.submit("""{"a":"1"}""")
        s.awaitIdle()
        assertFalse(s.backupFile.exists())
    }

    @Test
    fun storeDirIsNotCacheDir() {
        // 红线：快照绝不落到 cacheDir（会被「清除缓存」/系统清理干掉）
        val s = SaveGuardStore(File(tmp.root, "files"))
        assertTrue(s.mainFile.absolutePath.contains("${File.separator}files${File.separator}saves"))
        assertFalse(s.mainFile.absolutePath.contains("cache"))
    }

    @Test
    fun submitReturnsTrueForNewContent() {
        assertTrue(store().submit("""{"a":"1"}"""))
    }
}
