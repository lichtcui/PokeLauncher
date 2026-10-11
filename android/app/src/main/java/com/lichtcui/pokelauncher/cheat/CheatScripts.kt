package com.lichtcui.pokelauncher.cheat

/**
 * `/__cheats__.js` 的内容生成（对应鸿蒙版 `model/CheatState.ets` 的 `SCENE_RUNTIME` + `buildBootScript()`）。
 *
 * ⚠️ [SCENE_RUNTIME] 是**逐字保真**的 JS（只有末尾的 Phaser 捕获方式有改动，见下），
 * 这里只是搬运，不要「顺手优化」——它依赖游戏内部方法名（`__isBattle` 的鸭子类型判定等）。
 *
 * ## 与鸿蒙版的一处必要差异（Phaser 包装的归属）
 * 鸿蒙版把 `Object.defineProperty(window,'Phaser',…)` 写在本脚本里；Android 的
 * `index.html` 还会注入 `/__pk_boot.js`（canvas 补丁 + 启动计时），那里也定义了同一个
 * 属性。同一属性被 `defineProperty` 两次时**后者覆盖前者**，于是必然有一方的
 * setter 永远不触发（哪一方取决于注入顺序，属于隐性 bug）。
 *
 * 因此 Android 侧规定：**Phaser 包装由 `/__pk_boot.js` 独占**，它把构造出的 Game 实例
 * 交给 `window.__pkGameHooks` 里的回调；本脚本只把 `__watch` 注册进去。
 * `Array.prototype.push` 场景捕获路径（本脚本的主要路径）保持原样，所以即使
 * `/__pk_boot.js` 将来被移除，最坏情况只是退化成单一路径。
 */
object CheatScripts {

    /**
     * 共享「场景捕获」运行时：在游戏启动前 hook `Array.prototype.push` 捕获 Phaser 场景，
     * 用鸭子类型找到 BattleScene（有 addMoney/getPlayerParty/ui/phaseManager），
     * 之后回调所有 `__onScene` 注册的函数，并卸载 push 钩子。
     * 依赖游戏内部方法名，游戏更新后可能失效。
     */
    val SCENE_RUNTIME: String = """
        var __cs=null,__ccb=[];
        function __fire(s){__cs=s;try{window.__cheatScene=s;}catch(e){}var a=__ccb;__ccb=[];for(var i=0;i<a.length;i++){try{a[i](s);}catch(e){console.log("CHEAT scene cb "+e);}}}
        function __onScene(cb){if(__cs){try{cb(__cs);}catch(e){console.log("CHEAT scene cb "+e);}}else{__ccb.push(cb);}}
        function __isBattle(s){try{return !!(s&&typeof s.addMoney==="function"&&typeof s.getPlayerParty==="function"&&s.ui&&s.phaseManager);}catch(e){return false;}}
        function __scenes(g){try{if(g&&g.scene&&typeof g.scene.getScenes==="function"){return g.scene.getScenes(false);}if(g&&g.scene&&Array.isArray(g.scene.scenes)){return g.scene.scenes;}}catch(e){}return [];}
        function __watch(g){if(!g||!g.scene||g.__cheatWatch){return;}g.__cheatWatch=true;var n=0;var iv=setInterval(function(){if(__cs){clearInterval(iv);return;}var a=__scenes(g);for(var i=0;i<a.length;i++){if(__isBattle(a[i])){clearInterval(iv);console.log("CHEAT scene captured");__fire(a[i]);return;}}if(n===0){console.log("CHEAT watch scenes="+a.length);}if(++n>1200){clearInterval(iv);}},250);}
        var __phaseCbs={};
        function __onPhase(name,cb){(__phaseCbs[name]=__phaseCbs[name]||[]).push(cb);}
        function __installPhaseHook(scene){try{var pm=scene.phaseManager;if(!pm){return;}var proto=Object.getPrototypeOf(pm);if(typeof proto.create!=="function"||proto.__cheatPhaseHook){return;}var orig=proto.create;proto.create=function(name){var inst=orig.apply(this,arguments);var cbs=__phaseCbs[name];if(cbs){for(var i=0;i<cbs.length;i++){try{cbs[i](inst);}catch(e){console.log("CHEAT phase cb "+e);}}}return inst;};proto.__cheatPhaseHook=true;console.log("CHEAT phase hook installed");}catch(e){console.log("CHEAT phase hook err "+e);}}
        __onScene(__installPhaseHook);
        function __looksScene(x){try{return !!(x&&typeof x==="object"&&x.sys&&x.sys.scene===x&&x.sys.settings&&typeof x.sys.settings.key==="string");}catch(e){return false;}}
        var __np=Array.prototype.push;
        var __hp=function(){var r=__np.apply(this,arguments);if(!__cs){for(var i=0;i<arguments.length;i++){if(__looksScene(arguments[i])){var s=arguments[i];var n=0;var iv=setInterval(function(){var g=s.sys&&s.sys.game;if(g&&g.scene){clearInterval(iv);if(Array.prototype.push===__hp){Array.prototype.push=__np;}__watch(g);}else if(++n>300){clearInterval(iv);}},60);break;}}}return r;};
        Array.prototype.push=__hp;
        (function(){try{window.__pkGameHooks=window.__pkGameHooks||[];window.__pkGameHooks.push(function(g){try{console.log("CHEAT game constructed");}catch(e){}__watch(g);});}catch(e){console.log("CHEAT game hook reg err "+e);}})();
    """.trimIndent()

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
