package com.lichtcui.pokelauncher.install

import org.json.JSONArray
import org.json.JSONObject

/**
 * Release 查询结果（只取需要的字段）。
 * 对应鸿蒙版 `GameRepository.ets` 的 `ReleaseInfo` / `ReleaseAsset` / `ReleaseData`。
 */
class ReleaseInfo(
    val tag: String,
    val assetName: String,
    val assetUrl: String,
    val assetSize: Long,
    /** `Const.DEBUG_ASSET_URL` 生效时为 true：大小未知、不走镜像 */
    val debug: Boolean = false
) {
    companion object {
        /** 本地调试包（`DEBUG_ASSET_URL` 非空时用它跑全链路，几秒即可完成） */
        fun debug(url: String): ReleaseInfo = ReleaseInfo("debug", "debug-pack.zip", url, 0, true)
    }
}

/**
 * GitHub Release JSON 解析。
 *
 * ⚠️ 字段名全部以**字符串字面量**出现（`optString("tag_name")`），R8 混淆改不了字面量，
 * 因此这里不需要 `-keep-property-name` 式的规则；也**不要**改成反射式/序列化框架式的
 * 「属性名当 key 用」写法，那样 release 包会静默读不到值（见 `proguard-rules.pro`）。
 */
object ReleaseParser {

    /**
     * 从 Release JSON 里取出指定资产。
     * @throws IllegalArgumentException JSON 非法或没有该资产（调用方据此换镜像重试）
     */
    fun parse(json: String, assetName: String): ReleaseInfo {
        val root = JSONObject(json)
        val tag = root.optString("tag_name")
        val assets: JSONArray = root.optJSONArray("assets") ?: JSONArray()
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") != assetName) continue
            val url = asset.optString("browser_download_url")
            if (url.isEmpty()) continue
            return ReleaseInfo(tag, assetName, url, asset.optLong("size"))
        }
        throw IllegalArgumentException("Release 中缺少资源 $assetName")
    }
}
