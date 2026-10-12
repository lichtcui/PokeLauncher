package com.lichtcui.pokelauncher.appupdate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.lichtcui.pokelauncher.core.Const
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * 应用内自更新的 IO 部分（下载 APK → 校验 → 交给系统安装器）。
 *
 * 纯逻辑在 [apkFileName] / [apkFileAccepted] / [ApkUpdateState]，这里只管磁盘与 Intent。
 *
 * **存放位置**：`getExternalFilesDir(null)/apk/`（**不是** `cacheDir`）——「清除缓存」
 * 会清掉 `cacheDir`，用户下载到一半的 20MB 安装包不该被系统顺手删掉；同时它属于外部存储，
 * 而 Auto Backup 规则只白名单了 `sharedpref` + `file/saves`，所以它天然不进备份（见 `res/xml/`）。
 *
 * **零存储权限**：应用专属目录不需要任何权限；安装走 `FileProvider` + `ACTION_VIEW`，
 * 唯一需要用户开的是系统级的「安装未知应用」（[canInstall] / [unknownSourcesIntent]）。
 *
 * 注意只有**跑在同一应用内**的下载才能这样做；换到别的 App 去装就断了（所以不做「交给浏览器」以外的分支）。
 */
object ApkUpdater {

    /** 应用专属子目录名（外部存储优先，卸载应用会一并删除） */
    const val DIR_NAME = "apk"

    private const val PART_SUFFIX = ".part"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 更新包目录：`getExternalFilesDir(apk)`，外部存储不可用时退到内部 `filesDir/apk` */
    fun apkDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, DIR_NAME)

    /** 目标文件（不保证存在） */
    fun targetFile(context: Context, name: String): File = File(apkDir(context), name)

    /**
     * 系统是否允许本应用安装未知来源的包。
     *
     * `canRequestPackageInstalls()` 是 API 26 起（minSdk 26，无需版本分支）。
     * 任何异常都按「不允许」处理：宁可多引导一次系统设置，也不要在用户点了安装后崩掉。
     */
    fun canInstall(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /**
     * 交给系统安装器的 Intent（`FileProvider` 内容 URI + 一次性读权限）。
     * @throws IllegalArgumentException 文件不在 [apkDir] 里（FileProvider 会拒绝）
     */
    fun installIntent(context: Context, file: File): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                FileProvider.getUriForFile(context, authority(context), file),
                "application/vnd.android.package-archive"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** 「允许安装未知应用」的系统设置页（带包名，直接落在本应用的开关上） */
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 下载更新包。
     *
     * 先写 `<name>.part`，校验通过后再 `renameTo` 覆盖正式文件：这样**不会**出现
     * 「看起来像安装包但其实是半截」的 `xxx.apk`（用户点它会看到系统的"解析包时出现问题"，
     * 比没有更糟）。取消/失败时会**立刻删掉** `.part`（不留给用户 20MB 垃圾）；只有进程
     * 被杀才可能留下它 —— 那种情况下它也不会被误认成安装包，且下次下载开始时会先清掉。
     *
     * @param onProgress 每写入一块回调一次（`doneBytes`, `totalBytes`；total 未知时为 0）
     * @param isCancelled 每个数据块检查一次；返回 true 时抛 [ApkUpdateCancelled]
     * @throws IOException 网络失败 / 落盘失败
     * @throws ApkUpdateCancelled 用户取消
     */
    fun downloadApk(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): Long {
        val dir = dest.parentFile
        if (dir != null && !dir.exists() && !dir.mkdirs()) throw IOException("无法创建目录 ${dir.name}")
        val part = File(dest.absolutePath + PART_SUFFIX)
        if (part.exists()) part.delete()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Const.USER_AGENT)
            .header("Accept", "application/vnd.android.package-archive,application/octet-stream,*/*")
            .build()

        var done = 0L
        try {
            done = fetchToPart(request, part, onProgress, isCancelled)
        } catch (e: Throwable) {
            // 主动失败/取消也要立刻清掉半截包：否则用户白占 20MB，要到下次点更新才被删。
            // 进程被杀时 catch 不会执行，那才是 .part 唯一的合法残留场景。
            part.delete()
            throw e
        }
        if (dest.exists() && !dest.delete()) throw IOException("无法覆盖旧更新包")
        if (!part.renameTo(dest)) throw IOException("无法保存更新包")
        Log.i(Const.TAG, "apk download: done url=$url bytes=$done dest=${dest.name}")
        return done
    }

    /** 把响应体流写入 `part`，返回写入字节数（rename / 清理语义由调用方负责） */
    private fun fetchToPart(
        request: Request,
        part: File,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean
    ): Long {
        var done = 0L
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("响应为空")
            val total = body.contentLength()
            val source = body.source()
            FileOutputStream(part).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (isCancelled()) throw ApkUpdateCancelled()
                    val read = source.read(buffer, 0, buffer.size)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    done += read
                    onProgress(done, total)
                }
                out.flush()
                out.fd.sync()
            }
            // 长度已知时对一下账：截断的下载（连接被掐）必须当失败，不能喂给安装器
            if (total > 0 && done != total) throw IOException("下载不完整（$done/$total）")
        }
        return done
    }

    /** 读文件头做整体校验（大小 + zip 魔数），配合 [apkFileAccepted] 判断是不是真安装包 */
    fun verify(file: File): Boolean {
        if (!file.isFile || file.length() < MIN_APK_BYTES) return false
        val head = ByteArray(4)
        try {
            RandomAccessFile(file, "r").use { it.readFully(head) }
        } catch (e: IOException) {
            Log.w(Const.TAG, "apk verify read failed: ${e.message}")
            return false
        }
        return apkFileAccepted(file.length(), head)
    }

    /**
     * 清理：删掉目录里多余的旧安装包与残留 `.part`，只留下 [keepName]。
     * 不清的话每发一次版本就多留一份 20MB 的 APK（用户看不到，但会一直吃空间）。
     */
    fun pruneOldApks(dir: File, keepName: String): Int {
        val files = dir.listFiles() ?: return 0
        var removed = 0
        for (f in files) {
            if (!f.isFile) continue
            if (f.name == keepName) continue
            if (f.name.endsWith(".apk", ignoreCase = true) || f.name.endsWith(PART_SUFFIX)) {
                if (f.delete()) removed++
            }
        }
        return removed
    }

    private fun authority(context: Context): String = "${context.packageName}.fileprovider"
}

/** 用户取消下载（继承 [CancellationException]，协程取消语义一致，不会误报成失败） */
class ApkUpdateCancelled : CancellationException(APK_CANCELLED_TEXT)
