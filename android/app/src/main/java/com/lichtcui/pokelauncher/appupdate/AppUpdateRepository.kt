package com.lichtcui.pokelauncher.appupdate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.lichtcui.pokelauncher.BuildConfig
import com.lichtcui.pokelauncher.core.Const
import com.lichtcui.pokelauncher.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 应用自更新检查（IO 部分）：拉 Gitee 上的 `version.json` → [parseAppUpdateManifest] →
 * [compareAppUpdate]。纯逻辑在 `AppUpdate.kt`，这里只管「取字节 + 缓存 + 打开链接」。
 *
 * 对应鸿蒙版 `model/AppUpdate.ets`。**能力差异（平台决定，不是取舍）**：
 * 鸿蒙版只能「检测 + 引导浏览器」（应用内无法静默安装 HAP）；
 * 安卓可以做应用内下载 APK，但那属于 P4（FileProvider + `REQUEST_INSTALL_PACKAGES`），
 * 本阶段与鸿蒙版行为一致：检测到新版本后用浏览器打开下载页。
 *
 * 缓存是**模块级**的（与鸿蒙版设置页的模块级 `appCheckAt` / `appCheckedResult` 一致）：
 * 设置页每次进出都会重建 Compose 状态，靠这个缓存才不会每次都发请求。
 */
object AppUpdateRepository {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Volatile
    private var cachedResult: AppUpdateResult? = null

    @Volatile
    private var lastCheckAt: Long = 0L

    /** 本地应用版本（来自 `build.gradle.kts` 的 versionCode / versionName） */
    fun localVersionCode(): Int = BuildConfig.VERSION_CODE

    fun localVersionName(): String = BuildConfig.VERSION_NAME

    /**
     * 启动时是否该自动检查（24h 节流，与鸿蒙版 `shouldAutoCheck` 同规则同 key 名）。
     * 偏好未初始化（JVM 单测 / 极早期调用）时读到 0 → 到期，行为安全。
     */
    fun shouldAutoCheck(now: Long = System.currentTimeMillis()): Boolean =
        isAutoCheckDue(Prefs.getLong(KEY_LAST_CHECK, 0L), now)

    /** 记录本次检查时间（**仅在成功拿到清单后**调用，与鸿蒙版一致） */
    fun markChecked(now: Long = System.currentTimeMillis()) {
        Prefs.putLong(KEY_LAST_CHECK, now)
    }

    /**
     * 检查应用更新。
     * @param force 用户手动点击时为 true：绕过内存节流，一定重新拉清单
     * @throws IOException 网络失败 / 非 2xx；解析失败抛 JSONException。调用方负责显示「检查失败」
     */
    suspend fun check(force: Boolean = false): AppUpdateResult {
        val cached = cachedResult
        if (!shouldRefetchAppUpdate(force, cached != null, lastCheckAt, System.currentTimeMillis())) {
            // 只有非空缓存才会走到这里（shouldRefetch 的 hasCached 就是它）
            return cached ?: throw IOException("缓存状态异常")
        }
        val manifest = withContext(Dispatchers.IO) { fetchManifest() }
        val result = compareAppUpdate(localVersionCode(), localVersionName(), manifest)
        cachedResult = result
        lastCheckAt = System.currentTimeMillis()
        Log.i(
            Const.TAG,
            "app update: local=${result.localVersionCode}(${result.localVersionName}) " +
                "android=${manifest.androidVersionCode} published=${result.androidPublished} " +
                "hasUpdate=${result.hasUpdate} force=${result.forceUpdate}"
        )
        return result
    }

    /** 测试 / 调试用：丢掉内存缓存 */
    fun resetCache() {
        cachedResult = null
        lastCheckAt = 0L
    }

    /** 用系统浏览器打开链接（应用商店/浏览器缺失时只记日志，不让界面崩） */
    fun openUrl(context: Context, url: String) {
        val target = url.ifEmpty { Const.APP_UPDATE_PAGE_URL }
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(target))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Throwable) {
            Log.w(Const.TAG, "打开链接失败：$target（${e.message}）")
        }
    }

    private fun fetchManifest(): AppUpdateManifest {
        val request = Request.Builder()
            .url(Const.APP_UPDATE_MANIFEST_URL)
            .header("User-Agent", Const.USER_AGENT)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isEmpty()) throw IOException("清单为空")
            return parseAppUpdateManifest(body)
        }
    }

    /** 自动检查的节流时间戳（与鸿蒙版 `AppUpdate.KEY_LAST_CHECK` 同名） */
    private const val KEY_LAST_CHECK = "appUpdateLastCheck"
}
