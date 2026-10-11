package com.lichtcui.pokelauncher.web

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import com.lichtcui.pokelauncher.core.Const
import java.io.File

/**
 * 游戏内存档导出（对应鸿蒙版 `GamePage.ets` 的 `WebDownloadDelegate`）。
 *
 * ## 为什么不能只靠 `WebView.setDownloadListener`
 *
 * 游戏「导出数据」是 `Blob` + `<a download>` 触发下载，而 `blob:` 下载在部分
 * WebView 版本上**不会**走 `DownloadListener`（Chromium 内部处理），所以主路径是
 * **JS 拦截**：在 `<head>` 里替换 `URL.createObjectURL`、`HTMLAnchorElement.prototype.click`
 * 和 document 捕获阶段的 click，直接把 Blob 读成 base64 交给原生
 * （[buildScript] 注入，桥对象就是已有的 `PokeNative`，新增 `saveDownload` 方法）。
 *
 * 残留路径（`window.open(blobUrl)` / `location = blobUrl` 这类不经过 `<a>` 的写法）交给
 * `DownloadListener`，用 [blobFallbackScript] 的 XHR 再兜一次；两条路径互不重叠
 * （能走到 `DownloadListener` 说明 JS 拦截没命中，不会重复落盘）。
 *
 * ## 落盘位置
 *
 * - **API 29+**：`MediaStore.Downloads`（零权限，用户在「下载」里能看到，与鸿蒙
 *   `DocumentPickerMode.DOWNLOAD` 的用户预期一致）；
 * - **API 26–28**：`getExternalFilesDir(DIRECTORY_DOWNLOADS)`（应用专属，同样零权限）。
 *   **刻意不申请 `WRITE_EXTERNAL_STORAGE`**：为了一个存档导出把「存储」权限弹框加回来
 *   不划算，`.prsv` 存档也就几十 KB。
 *
 * 纯部分（文件名清洗、文案、JS 字符串转义）都是可 JVM 单测的函数，见 `DownloadBridgeTest`。
 */
object DownloadBridge {

    /** 拿不到文件名时的兜底名（与参考实现 Admiral-Billy/PokeRogue-App-Android 一致） */
    const val DEFAULT_FILE_NAME = "data.prsv"

    /** 存档导出用的 MIME（游戏没给 `download` 属性时的兜底） */
    const val DEFAULT_MIME = "application/octet-stream"

    /** 文件名/路径里不允许出现的字符（Windows 与 Android 都容不下 `/`） */
    private const val ILLEGAL = "/\\:*?\"<>|\u0000"

    /**
     * 清洗文件名：去掉路径分隔符与控制字符、压掉空白、限长、拒绝 `..` 之类。
     * 名字来自页面（`a.download` / `Content-Disposition`），**不可信**，必须过这一层。
     */
    fun safeFileName(raw: String, fallback: String = DEFAULT_FILE_NAME): String {
        val trimmed = raw.trim().trim('"', '\'')
        val sb = StringBuilder(trimmed.length)
        for (ch in trimmed) {
            if (ch.code < 0x20 || ILLEGAL.indexOf(ch) >= 0) continue
            sb.append(ch)
        }
        // Windows 上的保留名与「只有点」的名字（"." / ".."）一并挡掉
        var name = sb.toString().trim().trimEnd('.')
        if (name.isEmpty() || name == "." || name == "..") return fallback
        // 前面的点会让文件在选择器里「隐身」，去掉（".prsv" → "prsv"）
        while (name.startsWith(".")) name = name.substring(1)
        if (name.isEmpty()) return fallback
        if (name.length > 120) {
            val dot = name.lastIndexOf('.')
            name = if (dot in 1 until name.length - 1) {
                name.substring(0, 96) + name.substring(dot)
            } else {
                name.substring(0, 120)
            }
        }
        return name.ifEmpty { fallback }
    }

    /**
     * 从 `Content-Disposition` 里抠文件名（`attachment; filename="data.prsv"`）。
     * 只认最常见的 `filename=` / `filename*=` 两种写法，抠不到返回空串。
     */
    fun fileNameFromDisposition(disposition: String?): String {
        val text = disposition ?: return ""
        val idx = text.indexOf("filename", ignoreCase = true)
        if (idx < 0) return ""
        var rest = text.substring(idx + "filename".length)
        // filename*=UTF-8''data.prsv（RFC 5987）
        if (rest.startsWith("*")) rest = rest.substring(1)
        if (!rest.startsWith("=")) return ""
        rest = rest.substring(1).trim()
        val star = rest.indexOf("''")
        if (star >= 0) rest = rest.substring(star + 2)
        if (rest.startsWith("\"")) {
            val end = rest.indexOf('"', 1)
            return if (end > 1) rest.substring(1, end) else rest.substring(1)
        }
        val end = rest.indexOfFirst { it == ';' || it == ',' }
        return if (end >= 0) rest.substring(0, end).trim() else rest.trim()
    }

    /** 导出成功文案（与鸿蒙版「已导出 <name> 到「下载/…」」同句式） */
    fun exportOkText(name: String, location: String): String =
        "已导出 " + name + " 到「" + location + "」"

    /** 导出失败文案（`detail` 为空时退化成短句，不显示空冒号） */
    fun exportFailText(detail: String): String {
        val d = detail.trim()
        return if (d.isEmpty()) "导出失败" else "导出失败：" + d
    }

    /** 成功时上报给玩家的位置文案：公共「下载」目录 vs 应用专属目录（带绝对路径） */
    fun locationText(publicDownloads: Boolean, appDownloadsDir: File): String =
        if (publicDownloads) "下载" else appDownloadsDir.absolutePath

    /**
     * 把 base64 内容写成文件。返回给玩家看的文案（成功或失败都非空）。
     *
     * 调用方负责放到 IO 线程：MediaStore 插入与写盘都是阻塞操作。
     */
    fun saveToDownloads(context: Context, rawName: String, base64: String, mime: String): String {
        val name = safeFileName(rawName)
        val bytes = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (t: Throwable) {
            Log.w(Const.TAG, "export decode failed: ${t.message}")
            return exportFailText("数据损坏")
        }
        val type = mime.trim().ifEmpty { DEFAULT_MIME }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, name, bytes, type)
        } else {
            saveViaAppDir(context, name, bytes)
        }
    }

    /** API 29+：MediaStore.Downloads（`IS_PENDING` 保证「要么完整、要么没有」） */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(
        context: Context,
        name: String,
        bytes: ByteArray,
        mime: String
    ): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        var uri: Uri? = null
        return try {
            uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return exportFailText("系统下载目录不可写")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: return exportFailText("系统下载目录不可写")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            Log.i(Const.TAG, "exported save to Downloads: $name (${bytes.size} bytes)")
            exportOkText(name, locationText(true, appDownloadsDir(context)))
        } catch (t: Throwable) {
            Log.w(Const.TAG, "export to MediaStore failed: ${t.message}")
            // 失败的半成品必须删掉，否则「下载」里会留一个 0 字节文件
            runCatching { uri?.let { context.contentResolver.delete(it, null, null) } }
            exportFailText(t.message ?: t.javaClass.simpleName)
        }
    }

    /** API 26–28：应用专属下载目录（不申请存储权限的代价就是文件在应用目录里） */
    private fun saveViaAppDir(context: Context, name: String, bytes: ByteArray): String {
        val dir = appDownloadsDir(context)
        return try {
            if (!dir.exists() && !dir.mkdirs()) return exportFailText("目录不可写")
            val file = uniqueFile(dir, name)
            file.writeBytes(bytes)
            Log.i(Const.TAG, "exported save to ${file.absolutePath} (${bytes.size} bytes)")
            exportOkText(file.name, locationText(false, dir))
        } catch (t: Throwable) {
            Log.w(Const.TAG, "export to app dir failed: ${t.message}")
            exportFailText(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * `getExternalFilesDir(DIRECTORY_DOWNLOADS)`，拿不到就退内部存储（极端情况，
     * 与 `GameRepository.rootDirOf` 同一策略）。
     */
    fun appDownloadsDir(context: Context): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir

    /**
     * 同名文件自动加 `(1)` `(2)` 后缀（MediaStore 自己会做，应用目录里要手动做）。
     * 纯文件名计算，不碰磁盘状态（`exists` 是唯一副作用，测试用临时目录即可）。
     */
    fun uniqueFile(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val stem = if (dot in 1 until name.length - 1) name.substring(0, dot) else name
        val ext = if (dot in 1 until name.length - 1) name.substring(dot) else ""
        for (i in 1..99) {
            val candidate = File(dir, "$stem ($i)$ext")
            if (!candidate.exists()) return candidate
        }
        return File(dir, "$stem (${System.currentTimeMillis()})$ext")
    }

    /** JS 字符串字面量转义（`</script>` 与引号都要处理，值来自页面，不可信） */
    fun jsStringLiteral(value: String): String {
        val sb = StringBuilder(value.length + 16)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003c")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * 注入到 `index.html` 的 `<head>` 之后的拦截脚本（见类注释的主路径）。
     *
     * ⚠️ raw string 里不能出现 `$`（Kotlin 会当成模板插值），JS 侧一律避开。
     */
    fun buildScript(): String {
        val js = """
            if(!window.__pkDlInstalled){window.__pkDlInstalled=true;
            var blobs={},last={},bridgeN=0;
            function bridge(){return window.PokeNative;}
            function defName(a){var n='';try{n=a&&a.getAttribute?String(a.getAttribute('download')||''):'';}catch(e){}
            if(!n||n==='true'){n='$DEFAULT_FILE_NAME';}return n;}
            function mimeOf(b){try{return (b&&b.type)?String(b.type):'$DEFAULT_MIME';}catch(e){return '$DEFAULT_MIME';}}
            function send(name,mime,b64){var B=bridge();
            if(!B||typeof B.saveDownload!=='function'){console.log('PKDL bridge missing');return;}
            var key=name+':'+b64.length;
            if(last[key]){console.log('PKDL duplicate skipped '+name);return;}
            last[key]=1;setTimeout(function(){delete last[key];},5000);
            bridgeN++;console.log('PKDL saved '+name+' #'+bridgeN);
            try{B.saveDownload(name,mime,b64);}catch(e){console.log('PKDL native err '+e);}}
            function deliver(a){
            try{
            var href=a&&a.href?String(a.href):'';
            var b=blobs[href];
            if(!b){if(href.slice(0,5)==='data:'){var s=href.slice(href.indexOf(',')+1);
            send(defName(a),'$DEFAULT_MIME',s);return true;}return false;}
            var r=new FileReader();
            r.onload=function(){try{var s=String(r.result);var i=s.indexOf(',');
            send(defName(a),mimeOf(b),i>=0?s.slice(i+1):'');}catch(e){console.log('PKDL read err '+e);}};
            r.onerror=function(){console.log('PKDL read error');};
            r.readAsDataURL(b);
            return true;
            }catch(e){console.log('PKDL deliver err '+e);return false;}}
            var co=URL.createObjectURL;
            if(typeof co==='function'){URL.createObjectURL=function(b){
            var u=co.apply(URL,arguments);
            try{if(b&&typeof b.size==='number'){blobs[u]=b;}}catch(e){}
            return u;};}
            var rev=URL.revokeObjectURL;
            if(typeof rev==='function'){URL.revokeObjectURL=function(u){
            try{delete blobs[u];}catch(e){}return rev.apply(URL,arguments);};}
            var click=HTMLAnchorElement.prototype.click;
            HTMLAnchorElement.prototype.click=function(){
            if(this&&this.hasAttribute&&this.hasAttribute('download')&&deliver(this)){return;}
            return click.apply(this,arguments);};
            document.addEventListener('click',function(e){
            var t=e.target;
            while(t&&t.tagName!=='A'){t=t.parentElement;}
            if(t&&t.hasAttribute&&t.hasAttribute('download')&&deliver(t)){
            e.preventDefault();e.stopPropagation();}},true);
            console.log('PKDL installed');}
        """.trimIndent()

        return "<script>(function(){try{" + js +
            "}catch(e){console.log(\"PKDL err \"+e);}})();</script>"
    }

    /**
     * 兜底脚本：`DownloadListener` 拿到 `blob:` URL 时用 XHR 把内容读成 base64
     * （对应 `WebView` 里 `<a>` 之外的下载路径；正常路径都由 [buildScript] 拦掉）。
     */
    fun blobFallbackScript(blobUrl: String, rawName: String, mime: String): String {
        val name = jsStringLiteral(safeFileName(rawName))
        val type = jsStringLiteral(mime.trim().ifEmpty { DEFAULT_MIME })
        val url = jsStringLiteral(blobUrl)
        val js = """
            (function(){var x=new XMLHttpRequest();
            x.open('GET',$url,true);x.responseType='blob';
            x.onload=function(){try{var r=new FileReader();
            r.onload=function(){var s=String(r.result);var i=s.indexOf(',');
            var B=window.PokeNative;
            if(!B||typeof B.saveDownload!=='function'){console.log('PKDL bridge missing');return;}
            try{B.saveDownload($name,$type,i>=0?s.slice(i+1):'');}
            catch(e){console.log('PKDL native err '+e);}};
            r.onerror=function(){console.log('PKDL fallback read error');};
            r.readAsDataURL(x.response);}catch(e){console.log('PKDL fallback err '+e);}};
            x.onerror=function(){console.log('PKDL fallback xhr error');};
            try{x.send();}catch(e){console.log('PKDL fallback send err '+e);}})();
        """.trimIndent()
        return "<script>" + js + "</script>"
    }

    /** JS 桥的方法名（单测断言脚本里调用的名字都在这张表里） */
    val BRIDGE_METHODS: List<String> = listOf("saveDownload")
}
