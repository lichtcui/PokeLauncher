package com.lichtcui.pokelauncher.install

import android.util.Log
import com.lichtcui.pokelauncher.core.Const
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * 把共享的 [ZipInputStream] 当成「单个条目的内容流」交给 `extract` 时用。
 *
 * `extract` 对每个条目做 `openEntry(entry).use { … }`；关闭独立条目流是对的，
 * 但流式解压时 `openEntry` 返回的是同一个 `ZipInputStream`，被 `use{}` 关闭后
 * 后续 `nextEntry` 会抛 `Stream closed`。这层包装只屏蔽 `close()`，读写全部透传。
 *
 * 可见性为 `internal` 是为了让单测能锁死这个行为（见 `GameInstallerStreamTest`）。
 */
internal class SharedZipEntryStream(src: InputStream) : FilterInputStream(src) {
    override fun close() {
        // 故意不关闭：底层 ZipInputStream 由 installFromStream 持有，条目流只是共享视图
    }
}

/**
 * 游戏资源的安装与复原。对应鸿蒙版 `GameRepository` 的「原子安装」部分。
 *
 * **安装是原子的**：解压到 `game.new` → 校验 `index.html` → 写完成标记 `.extract-complete`
 * → `game`→`game.old`、`game.new`→`game`（两次瞬时 rename）→ 异步删 `game.old`。
 * 中途失败/被杀只会残留 `.new`/`.old`，不会破坏现有安装；[recover] 按标记区分
 * 「完整 staging / 半成品 / 旧包备份」并复原或清理。
 */
class GameInstaller(root: File) {

    val gameDir: File = File(root, Const.GAME_DIR_NAME)
    val stagingDir: File = File(root, Const.GAME_NEW_DIR_NAME)
    val backupDir: File = File(root, Const.GAME_OLD_DIR_NAME)

    private val markerFile: File = File(stagingDir, Const.EXTRACT_MARKER)

    /** 开发捷径：把 `game.zip` 直接用 `adb push` 到 files 目录，免去走 SAF 选择器 */
    val localZipFile: File = File(root, Const.DEFAULT_ASSET)

    /** 资源版本标记（zip 内 `currentVersion.txt`，内容是 GitHub Release 的 commit） */
    val versionFile: File = File(gameDir, Const.VERSION_FILE_NAME)

    /** 游戏是否就绪（首页判定「已就绪」的唯一依据） */
    fun isReady(): Boolean = File(gameDir, Const.INDEX_FILE).isFile

    /** 读取本地已安装的资源版本；未安装 / 读失败返回空串（对应鸿蒙版 `readVersion`） */
    fun readVersion(): String = try {
        if (versionFile.isFile) versionFile.readText().trim() else ""
    } catch (e: Exception) {
        Log.w(Const.TAG, "read version failed: ${e.message}")
        ""
    }

    /** 可用空间（首页下载前置检查用） */
    fun availableBytes(): Long = rootDir.usableSpace

    val rootDir: File = root

    /**
     * 启动时调用：把上次被中断的安装复原到一致状态。
     * 返回本次实际动作（空串表示无需处理），仅用于日志。
     */
    fun recover(): String {
        var action = ""

        // 1) game 完整 → 清掉残留的 staging / 旧包备份
        if (isReady()) {
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
                action = "cleaned staging"
            }
            if (backupDir.exists()) {
                backupDir.deleteRecursively()
                action = if (action.isEmpty()) "cleaned old backup" else "$action, cleaned old backup"
            }
            if (action.isNotEmpty()) Log.i(Const.TAG, "recover: $action")
            return action
        }

        // 2) game 缺失 + staging 已完成（有标记）→ 上次倒在切换前，补上切换
        if (markerFile.isFile) {
            switchIn()
            Log.w(Const.TAG, "recover: interrupted install completed")
            return "completed interrupted install"
        }

        // 3) game 缺失 + staging 是半成品 → 丢掉半成品；若旧包备份还在就回滚
        if (stagingDir.exists()) {
            stagingDir.deleteRecursively()
            action = "discarded partial staging"
        }
        if (backupDir.exists()) {
            if (backupDir.renameTo(gameDir)) {
                action = if (action.isEmpty()) "rolled back to old game" else "$action, rolled back to old game"
            } else {
                Log.e(Const.TAG, "recover: rollback rename failed")
            }
        }
        if (action.isNotEmpty()) Log.w(Const.TAG, "recover: $action")
        return action
    }

    fun deleteGame() {
        gameDir.deleteRecursively()
        stagingDir.deleteRecursively()
        backupDir.deleteRecursively()
        Log.i(Const.TAG, "game data deleted")
    }

    /**
     * 从本地 zip 文件安装（随机访问，可提前知道条目数）。
     *
     * @param onProgress (已完成条目数, 已写出字节数)
     */
    fun installFromZipFile(zipFile: File, onProgress: (Int, Long) -> Unit) {
        if (!zipFile.isFile) throw IOException("资源包不存在：${zipFile.absolutePath}")
        precheckSpace(zipFile.length())
        ZipFile(zipFile).use { zip ->
            extract(zip.entries().asSequence(), { zip.getInputStream(it) }, onProgress)
        }
    }

    /**
     * 从任意输入流安装（SAF 选中的 zip 走这里，避免为 507MB 的包再复制一份）。
     *
     * 注意：流式解压拿不到压缩包总大小，因此**无法做存储预检**。
     * 调用方应在选包前先用 [availableBytes] 做一次粗判（P1 接入）。
     */
    fun installFromStream(input: InputStream, onProgress: (Int, Long) -> Unit) {
        val zipIn = ZipInputStream(input.buffered(256 * 1024))
        // ⚠️ ZipInputStream 是流式的：当前条目的内容流在下一次 nextEntry 后即失效，
        // 所以必须在推进到下一个条目之前把内容读完——extract 的循环正是这么做的。
        // ⚠️ 另一个坑：extract 对每个条目做 `openEntry(entry).use { … }`。对 ZipFile 来说
        // 每次拿到的是互不相干的独立流，关掉它是对的；但这里 openEntry 返回的是**同一个**
        // ZipInputStream，一旦被 use{} 关掉，第二条 nextEntry 就抛 "Stream closed"——
        // 所以必须包一层屏蔽 close() 的视图（真机验收时「上传资源」正是死在这里）。
        extract(generateSequence { zipIn.nextEntry }, { SharedZipEntryStream(zipIn) }, onProgress)
    }

    private fun precheckSpace(zipLength: Long) {
        // 更新期间要同时容纳「旧包 + 新包 + zip」，与鸿蒙版一致按 3.2× 估算（判定逻辑在 Storage，可单测）
        val free = availableBytes()
        val problem = Storage.check(free, zipLength)
        if (problem != null) throw IOException(problem)
        Log.i(
            Const.TAG,
            "space precheck ok: need ${Storage.requiredBytes(zipLength) / 1024 / 1024}MB, free ${free / 1024 / 1024}MB"
        )
    }

    /**
     * 解压全部条目到 staging 目录。任一条目失败即抛出（**不重试、不回退到旧包**：
     * 换源只针对下载失败，解压失败换源会白下整个资源包）。
     */
    private fun extract(
        entries: Sequence<ZipEntry>,
        openEntry: (ZipEntry) -> InputStream,
        onProgress: (Int, Long) -> Unit
    ) {
        stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs() && !stagingDir.isDirectory) {
            throw IOException("无法创建解压目录：${stagingDir.absolutePath}")
        }

        val base = stagingDir.canonicalPath + File.separator
        var count = 0
        var bytes = 0L

        try {
            for (entry in entries) {
                val name = entry.name
                if (name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")) continue

                val target = File(stagingDir, name)
                // Zip Slip 防护：压缩包内的条目名可能带 `..` 或绝对路径
                if (!target.canonicalPath.startsWith(base)) {
                    throw IOException("资源包条目路径非法：$name")
                }

                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                openEntry(entry).use { input ->
                    target.outputStream().use { output ->
                        bytes += input.copyTo(output, DEFAULT_BUFFER_SIZE * 8)
                    }
                }
                count++
                if (count % 200 == 0) onProgress(count, bytes)
            }
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            throw IOException("解压失败：${e.message}", e)
        }

        onProgress(count, bytes)

        // 校验：没有 index.html 的包直接判定为坏包，绝不切换
        if (!File(stagingDir, Const.INDEX_FILE).isFile) {
            stagingDir.deleteRecursively()
            throw IOException("资源包不完整：缺少 ${Const.INDEX_FILE}")
        }

        markerFile.writeText("$count entries, $bytes bytes, ${System.currentTimeMillis()}")
        switchIn()
        Log.i(Const.TAG, "installed $count entries, ${bytes / 1024 / 1024} MB")
    }

    /** 原子切换：game→game.old、game.new→game（两次瞬时 rename） */
    private fun switchIn() {
        if (gameDir.exists() && !gameDir.renameTo(backupDir)) {
            throw IOException("无法移动旧资源目录（${gameDir.absolutePath}）")
        }
        if (!stagingDir.renameTo(gameDir)) {
            // 切换失败：尽力把旧包放回去，避免变成「什么都没有」
            backupDir.renameTo(gameDir)
            throw IOException("无法启用新资源目录（${stagingDir.absolutePath}）")
        }
        // 旧包异步删：不阻塞安装完成的返回
        if (backupDir.exists()) {
            Thread {
                backupDir.deleteRecursively()
                Log.i(Const.TAG, "old game backup removed")
            }.start()
        }
    }
}
