package com.lichtcui.pokelauncher.cheat

import com.lichtcui.pokelauncher.core.SharedJs

/**
 * `/__cheats__.js` 的内容生成（对应鸿蒙版 `model/CheatState.ets` 的 `SCENE_RUNTIME` + `buildBootScript()`）。
 *
 * ⚠️ `SCENE_RUNTIME` 的公共部分（场景捕获运行时）来自 `shared/js/scene-runtime.core.js`，由
 *    `scripts/gen-shared.mjs` 生成到 `core/SharedJs.kt`（两端逐字共享，见 shared/README.md）。
 *    本文件只负责**安卓独有**的两件事：脚本怎么装配、Game 实例怎么接。
 *    **不要在这里重新内联那段 JS**（`scripts/gen-shared.mjs --check` 会报错）。
 *    它依赖游戏内部方法名（`__isBattle` 的鸭子类型判定等），改它要同时想着鸿蒙。
 *
 * ## 与鸿蒙版的一处必要差异（Phaser 包装的归属）
 * 鸿蒙版把 `Object.defineProperty(window,'Phaser',…)` 写在参数调整脚本里（那边没有别的脚本碰它）；
 * Android 的 `index.html` 还会注入 `/__pk_boot.js`（canvas 补丁 + 启动计时），那里也定义同一个属性。
 * 同一属性被 `defineProperty` 两次时**后者覆盖前者**，于是必然有一方的 setter 永远不触发
 * （哪一方取决于注入顺序，属于隐性 bug）。
 *
 * 因此 Android 侧规定：**Phaser 包装由 `/__pk_boot.js` 独占**，它把构造出的 Game 实例交给
 * `window.__pkGameHooks` 里的回调（见 [GAME_HOOK_TAIL]）；本脚本只把 `__watch` 注册进去。
 * `Array.prototype.push` 场景捕获路径（本脚本的主要路径）两端保持原样，所以即使
 * `/__pk_boot.js` 将来被移除，最坏情况也只是退化成单一路径。
 */
object CheatScripts {

    /**
     * 尾部（安卓独有）：把 Game 实例交给 `window.__pkGameHooks`。
     *
     * 与鸿蒙不同：安卓的注入链里 `/__pk_boot.js` 与参数调整脚本并存，`window.Phaser` 的描述符
     * 只能由 boot 脚本独占（两次 `defineProperty` 会互相覆盖，先注册的一方 setter 永不触发），
     * 所以这里只压回调、不自己包装；鸿蒙那边没有 boot 脚本，描述符由参数调整脚本自己持有。
     */
    private const val GAME_HOOK_TAIL: String = """(function(){try{window.__pkGameHooks=window.__pkGameHooks||[];window.__pkGameHooks.push(function(g){try{console.log("CHEAT game constructed");}catch(e){}__watch(g);});}catch(e){console.log("CHEAT game hook reg err "+e);}})();"""

    /** 场景捕获运行时 = 共享的公共部分 + 上面的安卓尾部 */
    val SCENE_RUNTIME: String = SharedJs.SCENE_RUNTIME_CORE + "\n" + GAME_HOOK_TAIL

    /**
     * 生成加载时注入的「参数调整」脚本（总开关关闭或无条目时返回空串）。
     *
     * 与鸿蒙版 `buildBootScript()` 逐句对应：只拼接**已启用**条目的参数与脚本，
     * 每条脚本各自 `try/catch`，单条失效不影响其余条目。
     */
    fun buildBootScript(
        masterEnabled: Boolean,
        enabledIds: List<String>,
        paramValues: Map<String, String>
    ): String {
        if (!masterEnabled || enabledIds.isEmpty()) return ""

        val paramParts = ArrayList<String>()
        val js = StringBuilder()
        for (cheat in CHEATS) {
            if (!enabledIds.contains(cheat.id)) continue
            cheat.param?.let { p ->
                val n = CheatFormat.resolveNumber(p, paramValues[p.id])
                paramParts += "\"" + p.id + "\":" + CheatFormat.numberLiteral(n)
            }
            if (cheat.script.isNotEmpty()) {
                js.append("try{").append(cheat.script)
                    .append("}catch(e){console.log(\"CHEAT err ").append(cheat.id).append(" \"+e);}")
            }
        }
        if (js.isEmpty()) return ""

        val paramsJs = "{" + paramParts.joinToString(",") + "}"
        return "(function(){var __CHEAT_PARAMS__=" + paramsJs + ";" + SCENE_RUNTIME + js + "})();"
    }
}
