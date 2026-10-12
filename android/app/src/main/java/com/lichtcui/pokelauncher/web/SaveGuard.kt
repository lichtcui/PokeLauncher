package com.lichtcui.pokelauncher.web

import com.lichtcui.pokelauncher.core.Const
import com.lichtcui.pokelauncher.core.SharedJs
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 存档快照仓库：把游戏 Web 的 `localStorage` 镜像一份到 `filesDir/saves/`。
 * 对应鸿蒙版 `model/SaveGuard.ets` 的落盘部分。
 *
 * ## 为什么 Android 也要镜像
 * 鸿蒙版需要它是因为 localStorage 物理上落在 `<沙箱>/cache/web/`（会被「清除缓存」清掉）。
 * Android 的 localStorage 本来就在应用数据目录，不会被清缓存干掉；这里镜像的目的是
 * **Auto Backup / 存档导出（P4）** 与「卸载重装前先落盘一份」的兜底。
 *
 * ## 只做兜底，绝不覆盖
 * 只在 localStorage **被整体清空**（`LS.length===0`）时用快照回填，避免把用户
 * 主动删掉的存档又复活。
 *
 * 纯 `java.io` 实现（无 `android.*` 依赖）→ JVM 可单测。
 */
class SaveGuardStore(private val rootDir: File) {

    private val dir: File = File(rootDir, Const.SAVE_DIR_NAME)

    val mainFile: File = File(dir, Const.SAVE_FILE_NAME)
    val backupFile: File = File(dir, Const.SAVE_BAK_NAME)

    private val lock = Any()

    /** 已成功落盘的内容（去重用） */
    @Volatile
    private var diskContent: String = read()

    /** 已排队/正在写的内容（写入期间再次提交时去重与合并） */
    @Volatile
    private var queuedContent: String = ""

    @Volatile
    var lastError: String = ""
        private set

    /** 单线程串行化：避免并发写同一文件互相覆盖 */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pk-saveguard").apply { isDaemon = true }
    }

    /** 读取快照；主文件不可用时退回 `.bak` */
    fun read(): String {
        val main = readText(mainFile)
        if (main.length > 2) return main
        return readText(backupFile)
    }

    /**
     * 提交快照（异步、去重、串行）。
     * 内容与已落盘/在写内容相同则直接跳过——游戏内每 60s 会无条件触发一次 flush，
     * 没有变化时不应产生任何 IO。
     * @return 是否真的排队了一次写入
     */
    fun submit(data: String): Boolean {
        if (data.length <= 2) return false
        synchronized(lock) {
            if (data == diskContent || data == queuedContent) return false
            queuedContent = data
        }
        worker.execute { writeNow(data) }
        return true
    }

    /** 等待队列里的写入完成（单测用；生产代码不依赖它） */
    fun awaitIdle(timeoutMs: Long = 5000L): Boolean {
        val latch = CountDownLatch(1)
        worker.execute { latch.countDown() }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** 已落盘内容（单测/诊断用） */
    fun diskContent(): String = diskContent

    private fun writeNow(payload: String) {
        synchronized(lock) { if (queuedContent == payload) queuedContent = "" }
        try {
            if (!dir.exists()) dir.mkdirs()
            // 先把上一份滚到 .bak（rename，不复制数据）：写主文件途中被杀也能从 .bak 恢复
            rotateBackup()
            mainFile.writeText(payload, Charsets.UTF_8)
            diskContent = payload
            lastError = ""
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            // 写失败：允许后续 flush 重试（queuedContent 已清空）
        }
    }

    private fun rotateBackup() {
        runCatching {
            if (!mainFile.exists()) return
            if (backupFile.exists()) backupFile.delete()
            mainFile.renameTo(backupFile)
        }
    }

    private fun readText(file: File): String {
        return try {
            if (!file.isFile) "" else file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        /** 快照小于等于这个长度视为「没有内容」（`{}` 是 2 字节） */
        const val MIN_CONTENT: Int = 2
    }
}

/**
 * 进程级单例：注入脚本与 JS 桥共用同一个快照仓库。
 *
 * JS 侧桥对象名固定为 `PokeSaveGuard`（与鸿蒙版 `javaScriptProxy` 同名），
 * 方法名与 [buildScript] 中的调用一一对应，改动要同步 `proguard-rules.pro`。
 */
object SaveGuard {

    @Volatile
    private var storeRef: SaveGuardStore? = null

    @Volatile
    private var restoredKeys: Int = 0

    /** App 启动时调用（`rootDir` = `filesDir`，与鸿蒙版一致用内部存储） */
    fun init(filesDir: File) {
        if (storeRef == null) {
            storeRef = SaveGuardStore(filesDir)
        }
    }

    fun isReady(): Boolean = storeRef != null

    /** JS 侧读取快照（localStorage 为空时用于回填） */
    fun loadSnapshot(): String = storeRef?.read() ?: ""

    /** JS 侧提交快照（异步、去重，无变化时零 IO） */
    fun submit(data: String): Boolean = storeRef?.submit(data) ?: false

    /** JS 侧回填成功后回报，供页面提示用户 */
    fun notifyRestored(count: Int) {
        restoredKeys = count
    }

    /** 本次进入游戏页是否发生过回填（0 表示没有） */
    fun restoredCount(): Int = restoredKeys

    /** 单测/诊断用 */
    fun store(): SaveGuardStore? = storeRef

    /**
     * 注入到游戏页的存档守卫脚本。
     *
     * 执行时机在 `<head>` 内、游戏脚本之前，因此能保证「先回填再被游戏读取」。
     */
    fun buildScript(): String {
        // 注入的 JS 来自 `shared/js/save-guard.js`（与鸿蒙逐字共享，见 shared/README.md）。
        // 轮询等桥、只在 localStorage 被整体清空时回填等设计要点见文件顶部的类注释。
        val js = SharedJs.SAVE_GUARD_JS

        return "<script>(function(){try{" + js +
            "}catch(e){console.log(\"SAVEGUARD err \"+e);}})();</script>"
    }

    /** JS 桥的方法名（单测断言脚本里调用的名字都在这张表里） */
    val BRIDGE_METHODS: List<String> = listOf("load", "save", "notifyRestored")

    /** JS 桥的对象名（`addJavascriptInterface` 的第二个参数） */
    const val BRIDGE_NAME = "PokeSaveGuard"
}
