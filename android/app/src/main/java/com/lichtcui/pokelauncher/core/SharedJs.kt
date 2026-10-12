package com.lichtcui.pokelauncher.core

/**
 * 跨端共享的注入 JS 常量。
 *
 * ⚠️ **本文件由 `scripts/gen-shared.mjs` 自动生成，不要手改**：唯一真相在仓库根 `shared/js/`。
 * 改完跑 `node scripts/gen-shared.mjs`（鸿蒙侧 `common/SharedJs.ets` 会同步更新）。
 *
 * 这里只放**两端必须逐字一致**的 JS；平台独有的行为（谁持有 window.Phaser 描述符、
 * <script> 怎么包装）留在各端自己那几行里。
 * raw string 里的 `$` 会被当模板插值，所以生成器保证这些 JS 不含 `$`。
 */
object SharedJs {

    /** 场景捕获运行时公共部分（`/__cheats__.js` 的底座）。尾部（Phaser 包装）由各端自己追加。 */
    val SCENE_RUNTIME_CORE: String = """
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
    """.trimIndent()

    /** 存档守卫注入脚本（纯 JS，不含 `<script>` 包装）。 */
    val SAVE_GUARD_JS: String = """
        var tries=0;
        function boot(){
        var G=window.PokeSaveGuard;
        if(!G){if(++tries<40){setTimeout(boot,50);}else{console.log("SAVEGUARD bridge missing");}return;}
        if(window.__sgInstalled){return;}window.__sgInstalled=true;
        var LS=window.localStorage,PR=Object.getPrototypeOf(LS),t=null,busy=false,dirty=false;
        function collect(){var o={},i,k;for(i=0;i<LS.length;i++){k=LS.key(i);if(k){o[k]=LS.getItem(k);}}return o;}
        function flush(force){if(busy){return;}if(!force&&!dirty){return;}busy=true;dirty=false;t=null;
        try{var s=JSON.stringify(collect());if(s.length>2){G.save(s);}}catch(e){dirty=true;}busy=false;}
        function schedule(){dirty=true;if(busy){return;}if(t){clearTimeout(t);}t=setTimeout(function(){flush(false);},2000);}
        var _s=PR.setItem,_r=PR.removeItem,_c=PR.clear;
        PR.setItem=function(k,v){var x=_s.call(this,k,v);schedule();return x;};
        PR.removeItem=function(k){var x=_r.call(this,k);schedule();return x;};
        PR.clear=function(){var x=_c.call(this);schedule();return x;};
        if(LS.length===0){try{var raw=G.load();if(raw&&raw.length>2){
        var o=JSON.parse(raw),n=0,k;
        for(k in o){if(Object.prototype.hasOwnProperty.call(o,k)){_s.call(LS,k,o[k]);n++;}}
        if(n>0){G.notifyRestored(n);console.log("SAVEGUARD restored "+n+" keys");}}}
        catch(e){console.log("SAVEGUARD restore err "+e);}}
        window.addEventListener("pagehide",function(){flush(true);});
        window.addEventListener("beforeunload",function(){flush(true);});
        document.addEventListener("visibilitychange",function(){if(document.hidden){flush(true);}});
        setInterval(function(){if(!document.hidden){flush(false);}},60000);
        }
        boot();
    """.trimIndent()

    /** canvas willReadFrequently 补丁（纯 JS 片段，内联进引导脚本）。 */
    val CANVAS_PATCH_JS: String = """
        try{
          var _gc=HTMLCanvasElement.prototype.getContext;
          HTMLCanvasElement.prototype.getContext=function(t,a){
            if(t==="2d"){var n={};if(a){for(var k in a){n[k]=a[k];}}n.willReadFrequently=true;return _gc.call(this,t,n);}
            return _gc.apply(this,arguments);
          };
          console.log("PK canvas willReadFrequently patch applied");
        }catch(e){console.log("PK canvas patch err "+e)}
    """.trimIndent()
}
