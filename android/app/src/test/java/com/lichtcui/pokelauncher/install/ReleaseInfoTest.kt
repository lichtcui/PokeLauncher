package com.lichtcui.pokelauncher.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** GitHub Release JSON 解析：字段名是字符串字面量（R8 安全），缺失资产必须报错好让调用方换镜像 */
class ReleaseInfoTest {

    private val json = """
        {
          "tag_name": "release-abc1234",
          "assets": [
            { "name": "notes.txt", "browser_download_url": "https://example.com/notes.txt", "size": 10 },
            { "name": "game.zip", "browser_download_url": "https://github.com/a/b/releases/download/x/game.zip", "size": 507000000 }
          ]
        }
    """.trimIndent()

    @Test
    fun parsePicksRequestedAsset() {
        val info = ReleaseParser.parse(json, "game.zip")
        assertEquals("release-abc1234", info.tag)
        assertEquals("game.zip", info.assetName)
        assertEquals("https://github.com/a/b/releases/download/x/game.zip", info.assetUrl)
        assertEquals(507_000_000L, info.assetSize)
        assertFalse(info.debug)
    }

    @Test
    fun parseThrowsWhenAssetMissing() {
        assertThrows(IllegalArgumentException::class.java) { ReleaseParser.parse(json, "missing.zip") }
    }

    @Test
    fun parseThrowsOnBrokenJson() {
        assertThrows(Exception::class.java) { ReleaseParser.parse("{", "game.zip") }
    }

    @Test
    fun parseSkipsAssetsWithoutDownloadUrl() {
        val broken = """{"tag_name":"t","assets":[{"name":"game.zip","size":1}]}"""
        assertThrows(IllegalArgumentException::class.java) { ReleaseParser.parse(broken, "game.zip") }
    }

    @Test
    fun parseToleratesMissingAssetsArray() {
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseParser.parse("""{"tag_name":"t"}""", "game.zip")
        }
    }

    @Test
    fun debugFactoryHasNoSizeAndIsFlagged() {
        val info = ReleaseInfo.debug("http://127.0.0.1:8000/game.zip")
        assertTrue(info.debug)
        assertEquals(0L, info.assetSize)
        assertEquals("http://127.0.0.1:8000/game.zip", info.assetUrl)
    }
}
