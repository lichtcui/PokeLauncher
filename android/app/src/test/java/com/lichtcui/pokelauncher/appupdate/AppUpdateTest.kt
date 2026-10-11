package com.lichtcui.pokelauncher.appupdate

import com.lichtcui.pokelauncher.core.Const
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用自更新纯逻辑：清单解析 → 版本比较 → 检查节流。
 *
 * 重点在**安卓与鸿蒙的比较规则不同**：清单里没有安卓产物字段时必须判定「无更新」，
 * 否则开发期（安卓包号远小于鸿蒙的 1001000）会永远显示「发现新版本」，
 * 而那个链接指向的是 HAP 下载页。
 */
class AppUpdateTest {

    /** 仓库根 `version.json` 的当前内容（与鸿蒙版同一份清单：那时还没有安卓产物字段） */
    private val currentManifest = """
        {
          "versionCode": 1001000,
          "versionName": "1.1.0",
          "minVersionCode": 1000000,
          "forceUpdate": false,
          "changelog": "1. 更名为 PokeRogue（离线鸿蒙版）\n2. 新增宝可梦球下载 / 解压动画",
          "downloadUrl": "https://gitee.com/licht3345/PokeLauncher/releases",
          "pageUrl": "https://gitee.com/licht3345/PokeLauncher/releases"
        }
    """.trimIndent()

    /** 将来发了安卓版之后的清单形态（本轮设计的 5 个安卓字段） */
    private val androidManifest = """
        {
          "versionCode": 1001000,
          "versionName": "1.1.0",
          "forceUpdate": false,
          "changelog": "鸿蒙版的更新说明",
          "pageUrl": "https://gitee.com/licht3345/PokeLauncher/releases",
          "androidVersionCode": 1001000,
          "androidVersionName": "1.1.0",
          "androidMinVersionCode": 1000000,
          "androidChangelog": "1. 首个安卓版\n2. 支持断点续传",
          "apkUrl": "https://gitee.com/licht3345/PokeLauncher/releases/download/v1.1.0/app-release.apk"
        }
    """.trimIndent()

    // ---------------------------------------------------------------- 解析

    @Test
    fun parsesSharedFields() {
        val m = parseAppUpdateManifest(currentManifest)
        assertEquals(1001000, m.versionCode)
        assertEquals("1.1.0", m.versionName)
        assertEquals(1000000, m.minVersionCode)
        assertFalse(m.forceUpdate)
        assertTrue(m.changelog.startsWith("1. 更名为 PokeRogue"))
        assertEquals("https://gitee.com/licht3345/PokeLauncher/releases", m.pageUrl)
    }

    @Test
    fun currentManifestHasNoAndroidArtifact() {
        // 关键回归：现在的清单里没有安卓字段 → 不得判定为「有更新」
        val m = parseAppUpdateManifest(currentManifest)
        assertFalse(m.androidPublished)
        assertEquals(0, m.androidVersionCode)
        assertTrue(m.androidChangelog.isEmpty())

        val r = compareAppUpdate(1, "0.1.0-p0", m)
        assertFalse(r.hasUpdate)
        assertFalse(r.forceUpdate)
        assertEquals("安卓版暂未发布", r.statusText(checking = false, failed = false))
    }

    @Test
    fun parsesAndroidFields() {
        val m = parseAppUpdateManifest(androidManifest)
        assertEquals(1001000, m.androidVersionCode)
        assertEquals("1.1.0", m.androidVersionName)
        assertEquals(1000000, m.androidMinVersionCode)
        assertTrue(m.androidChangelog.startsWith("1. 首个安卓版"))
        assertTrue(m.apkUrl.endsWith("app-release.apk"))
        assertTrue(m.androidPublished)
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        val m = parseAppUpdateManifest("{}")
        assertEquals(0, m.versionCode)
        assertEquals("", m.versionName)
        assertFalse(m.forceUpdate)
        assertEquals("", m.apkUrl)
        assertFalse(m.androidPublished)
    }

    @Test
    fun nullFieldsFallBackToDefaults() {
        // 清单是手改的：显式 null 不能抛异常，按默认值处理
        val m = parseAppUpdateManifest(
            """{"versionCode":null,"androidVersionCode":null,"changelog":null,"apkUrl":null}"""
        )
        assertEquals(0, m.versionCode)
        assertEquals(0, m.androidVersionCode)
        assertEquals("", m.changelog)
        assertEquals("", m.apkUrl)
        assertFalse(m.androidPublished)
        assertFalse(m.androidVersionCode > 0)
    }

    @Test(expected = org.json.JSONException::class)
    fun brokenJsonThrowsForCallerToReportFailure() {
        // 非 JSON（比如 Gitee 返回了一个 404 页面）→ 抛异常，由 UI 归到「检查失败」
        parseAppUpdateManifest("<html><body>404</body></html>")
    }

    // ---------------------------------------------------------------- 比较

    @Test
    fun newerAndroidCodeMeansUpdate() {
        val m = parseAppUpdateManifest(androidManifest).copy(androidVersionCode = 1001001)
        val r = compareAppUpdate(1001000, "1.1.0", m)
        assertTrue(r.hasUpdate)
        assertFalse(r.forceUpdate)
        assertEquals("发现新版本", r.statusText(checking = false, failed = false))
    }

    @Test
    fun sameOrOlderCodeIsUpToDate() {
        val same = parseAppUpdateManifest(androidManifest)
        assertFalse(compareAppUpdate(1001000, "1.1.0", same).hasUpdate)
        assertEquals("已是最新", compareAppUpdate(1001000, "1.1.0", same).statusText(false, false))

        val older = same.copy(androidVersionCode = 999999)
        assertFalse(compareAppUpdate(1001000, "1.1.0", older).hasUpdate)
    }

    @Test
    fun forceFlagOnlyMattersWhenThereIsAnUpdate() {
        val m = parseAppUpdateManifest(androidManifest).copy(forceUpdate = true)
        // 同一个包（无更新）时不该显示「必须更新」
        assertFalse(compareAppUpdate(1001000, "1.1.0", m).forceUpdate)

        val newer = m.copy(androidVersionCode = 1001001)
        assertTrue(compareAppUpdate(1001000, "1.1.0", newer).forceUpdate)
    }

    @Test
    fun androidMinVersionCodeForcesUpdate() {
        val m = parseAppUpdateManifest(androidManifest).copy(androidVersionCode = 1001001)
        // 声明了 androidMinVersionCode（1000000）且本地低于它 → 强制
        assertTrue(compareAppUpdate(999999, "1.0.0", m).forceUpdate)
        // 本地已达标（恰好等于门槛）→ 不强制
        assertFalse(compareAppUpdate(1000000, "1.0.0", m).forceUpdate)
        assertFalse(compareAppUpdate(1001000, "1.1.0", m).forceUpdate)
    }

    @Test
    fun changelogNeverFallsBackToHarmonyText() {
        // 安卓更新说明必须来自 androidChangelog：拿鸿蒙的说明会给用户看错平台的更新内容
        val m = parseAppUpdateManifest(androidManifest)
            .copy(androidVersionCode = 1001001, androidChangelog = "")
        val r = compareAppUpdate(1001000, "1.1.0", m)
        assertTrue(r.hasUpdate)
        assertEquals("", r.remote.androidChangelog)
        // 版本名可以回退（两个平台通常同号），更新说明不行
        assertEquals("1.1.0", r.remote.displayVersionName)
        assertEquals("鸿蒙版的更新说明", r.remote.changelog)
    }

    @Test
    fun updateUrlPrefersApkAndFallsBackToPage() {
        val withApk = parseAppUpdateManifest(androidManifest)
        assertTrue(withApk.updateUrl.endsWith(".apk"))

        val withoutApk = withApk.copy(apkUrl = "")
        assertEquals(withApk.pageUrl, withoutApk.updateUrl)
    }

    @Test
    fun statusTextCoversAllFourStates() {
        val m = parseAppUpdateManifest(androidManifest).copy(androidVersionCode = 1001001)
        val r = compareAppUpdate(1, "0.1.0", m)
        assertEquals("检查中…", r.statusText(checking = true, failed = false))
        assertEquals("检查失败", r.statusText(checking = false, failed = true))
        assertEquals("发现新版本", r.statusText(checking = false, failed = false))

        val upToDate = compareAppUpdate(1001000, "1.1.0", parseAppUpdateManifest(androidManifest))
        assertEquals("已是最新", upToDate.statusText(checking = false, failed = false))
    }

    // ---------------------------------------------------------------- 节流

    @Test
    fun refetchPolicy() {
        val ttl = APP_UPDATE_CHECK_TTL_MS
        // force 无条件穿透
        assertTrue(shouldRefetchAppUpdate(force = true, hasCached = true, lastCheckAt = 1L, now = 2L))
        // 没缓存必须请求
        assertTrue(shouldRefetchAppUpdate(force = false, hasCached = false, lastCheckAt = 0L, now = 0L))
        // TTL 内复用缓存
        assertFalse(shouldRefetchAppUpdate(false, true, lastCheckAt = 1000L, now = 1000L + ttl - 1))
        // 恰好到 TTL 视为过期
        assertTrue(shouldRefetchAppUpdate(false, true, lastCheckAt = 1000L, now = 1000L + ttl))
    }

    // ---------------------------------------------------------------- 启动自动检查（24h）

    @Test
    fun autoCheckDueWhenNeverChecked() {
        // 从没查过（偏好里读到默认 0）
        assertTrue(isAutoCheckDue(lastCheckMs = 0L, now = 1_700_000_000_000L))
    }

    @Test
    fun autoCheckIntervalIsTwentyFourHours() {
        val day = 24L * 60 * 60 * 1000
        assertEquals("启动自动检查的节流窗口应为 24 小时", day, Const.APP_UPDATE_CHECK_INTERVAL_MS)
    }

    @Test
    fun autoCheckBoundary() {
        val now = 1_700_000_000_000L
        val interval = Const.APP_UPDATE_CHECK_INTERVAL_MS
        // 刚查完：不到期
        assertFalse(isAutoCheckDue(lastCheckMs = now, now = now))
        // 差 1ms 到期：仍不到期
        assertFalse(isAutoCheckDue(lastCheckMs = now - interval + 1, now = now))
        // 恰好过期：到期
        assertTrue(isAutoCheckDue(lastCheckMs = now - interval, now = now))
        // 早就过期：到期
        assertTrue(isAutoCheckDue(lastCheckMs = now - 30 * interval, now = now))
    }

    @Test
    fun autoCheckIgnoresFutureTimestamp() {
        // 用户改过系统时间（上次检查时间在「未来」）：宁可少查一次，也不要每次启动都发请求
        val now = 1_700_000_000_000L
        assertFalse(isAutoCheckDue(lastCheckMs = now + 60_000L, now = now))
    }
}
