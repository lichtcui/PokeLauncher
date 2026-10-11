package com.lichtcui.pokelauncher.appupdate

import com.lichtcui.pokelauncher.core.Const
import org.json.JSONObject

/**
 * 应用自更新的**纯逻辑**（无网络、无 Android 依赖，可 JVM 单测）：
 * 清单解析 → 版本比较 → 检查节流策略。
 * 对应鸿蒙版 `model/AppUpdate.ets` 里除 `requestText` / `openDownloadPage` 之外的部分；
 * 真正的网络请求在 [AppUpdateRepository]。
 */

/**
 * 应用自更新清单（仓库根 `version.json`，托管在 Gitee Raw）的宽松解析结果。
 *
 * ⚠️ **安卓专用字段**（`android*` 与 `apkUrl`）是端到端设计的核心：
 * 一份清单同时描述两个平台的产物，安卓只认这几个字段。
 * 不这么做的话，安卓端只能拿鸿蒙的 `versionCode` 比——那会永远显示「有新版本」，
 * 而点进去是 HAP 的下载页（见 [compareAppUpdate]）。
 */
data class AppUpdateManifest(
    // ---- 鸿蒙版（安卓端只用来展示，不参与比较）----
    val versionCode: Int = 0,
    val versionName: String = "",
    val minVersionCode: Int = 0,
    val forceUpdate: Boolean = false,
    val changelog: String = "",
    val downloadUrl: String = "",
    val pageUrl: String = "",
    // ---- 安卓专用 ----
    /** 安卓产物版本号；**为 0 表示清单里没有安卓产物**（不是「版本号为 0」） */
    val androidVersionCode: Int = 0,
    val androidVersionName: String = "",
    /** 低于此版本强制更新；0 表示未声明 */
    val androidMinVersionCode: Int = 0,
    /** 安卓专用更新说明：**不回退到共享的 [changelog]**，那是另一个平台的说明 */
    val androidChangelog: String = "",
    /** 安卓安装包直链（P4 应用内下载用）；留空则退回 [pageUrl] 用浏览器打开 */
    val apkUrl: String = ""
) {

    /** 清单里是否声明了安卓产物 */
    val androidPublished: Boolean get() = androidVersionCode > 0

    /** 展示用版本名：优先安卓专用字段 */
    val displayVersionName: String get() = androidVersionName.ifEmpty { versionName }

    /** 「去更新」要打开的地址：优先 APK 直链 */
    val updateUrl: String get() = apkUrl.ifEmpty { pageUrl }
}

/**
 * 一次更新检查的结果（纯数据，UI 直接读）。
 *
 * [localVersionCode] / [localVersionName] 来自 `BuildConfig`（安卓）或 `bundleManager`（鸿蒙）。
 */
data class AppUpdateResult(
    val localVersionCode: Int,
    val localVersionName: String,
    val remote: AppUpdateManifest,
    /** 远端清单里声明了安卓产物（false = 安卓版还没有已发布的版本） */
    val androidPublished: Boolean,
    val hasUpdate: Boolean,
    val forceUpdate: Boolean
) {

    /** 界面上的「已是最新 / 发现新版本 / 暂未发布」三态文案 */
    fun statusText(checking: Boolean, failed: Boolean): String = when {
        checking -> "检查中…"
        failed -> "检查失败"
        !androidPublished -> "安卓版暂未发布"
        hasUpdate -> "发现新版本"
        else -> "已是最新"
    }
}

/**
 * 解析清单文本。
 *
 * 只做宽松解析：非 JSON 文本会抛 [org.json.JSONException]（由调用方捕获成「检查失败」），
 * 但 JSON 里缺字段不报错。
 */
fun parseAppUpdateManifest(json: String): AppUpdateManifest {
    val raw = JSONObject(json)
    return AppUpdateManifest(
        versionCode = raw.optInt("versionCode", 0),
        versionName = raw.optString("versionName", ""),
        minVersionCode = raw.optInt("minVersionCode", 0),
        forceUpdate = raw.optBoolean("forceUpdate", false),
        changelog = raw.optString("changelog", ""),
        downloadUrl = raw.optString("downloadUrl", ""),
        pageUrl = raw.optString("pageUrl", ""),
        androidVersionCode = raw.optInt("androidVersionCode", 0),
        androidVersionName = raw.optString("androidVersionName", ""),
        androidMinVersionCode = raw.optInt("androidMinVersionCode", 0),
        androidChangelog = raw.optString("androidChangelog", ""),
        apkUrl = raw.optString("apkUrl", "")
    )
}

/**
 * 手动检查之间的内存节流：TTL 内复用上次结果。与鸿蒙版设置页的 `CHECK_TTL_MS` 同值。
 */
const val APP_UPDATE_CHECK_TTL_MS = 5 * 60 * 1000L

/**
 * 这次是否真的要发请求？
 *
 * 抽成纯函数是因为它有三个容易写错的边界：`force` 必须无条件穿透、
 * 没缓存时必须请求、TTL 判定符号（`now - last >= ttl` vs `>`）。
 */
fun shouldRefetchAppUpdate(
    force: Boolean,
    hasCached: Boolean,
    lastCheckAt: Long,
    now: Long
): Boolean = force || !hasCached || now - lastCheckAt >= APP_UPDATE_CHECK_TTL_MS

/**
 * 启动时的自动检查是否到期（24h 节流，与鸿蒙版 `shouldAutoCheck` 同规则）。
 *
 * `lastCheckMs = 0`（从没查过）→ 到期；`now - last >= interval` → 到期。
 * 注意「上次检查时间在末来」（用户改过系统时间）时结果是 false：宁可少查一次，
 * 也不要在时间被回拨后每次启动都发请求。
 */
fun isAutoCheckDue(
    lastCheckMs: Long,
    now: Long,
    intervalMs: Long = Const.APP_UPDATE_CHECK_INTERVAL_MS
): Boolean = now - lastCheckMs >= intervalMs

/**
 * 本地版本 vs 远端清单。
 *
 * 比较规则（安卓，这里是刻意**不等于**鸿蒙版的地方）：
 * 1. 清单没有 `androidVersionCode` → [AppUpdateResult.androidPublished] = false、`hasUpdate` = false。
 *    这样开发期（安卓包号还很小时）不会误报「有新版本」并把用户引到 HAP 下载页。
 * 2. `hasUpdate` = `androidVersionCode > localVersionCode`。
 * 3. `forceUpdate` = 共享的 `forceUpdate`，或本地低于 `androidMinVersionCode`（仅当声明了后者）。
 */
fun compareAppUpdate(
    localVersionCode: Int,
    localVersionName: String,
    manifest: AppUpdateManifest
): AppUpdateResult {
    val published = manifest.androidPublished
    val hasUpdate = published && manifest.androidVersionCode > localVersionCode
    val force = hasUpdate && (
        manifest.forceUpdate ||
            (manifest.androidMinVersionCode > 0 && localVersionCode < manifest.androidMinVersionCode)
        )
    return AppUpdateResult(
        localVersionCode = localVersionCode,
        localVersionName = localVersionName,
        remote = manifest,
        androidPublished = published,
        hasUpdate = hasUpdate,
        forceUpdate = force
    )
}
