package com.lichtcui.pokelauncher.server

import com.lichtcui.pokelauncher.core.Const

/**
 * 注入脚本。
 *
 * ⚠️ 这里的 JS 是**字符串常量**：raw string 里的 `$` 会被 Kotlin 当模板插值，写 JS 时一律避开 `$`。
 * release 混淆规则已 `-keep` 本类（见 `app/proguard-rules.pro`）。
 *
 * 执行时机：`index.html` 解析到 `<head>` 之后、游戏自身脚本之前。
 */
object InjectScripts {

    /**
     * 插到 `index.html` 的 `<head>` 之后的注入链。
     *
     * ⚠️ **顺序不能变**（四者都是同步的 classic script，按出现顺序执行）：
     * 1. 存档导出拦截（`DownloadBridge.buildScript()`）：要最早，游戏的下载是
     *    `Blob` + `<a download>`，必须在游戏代码之前就把 `URL.createObjectURL` /
     *    `HTMLAnchorElement.prototype.click` 换掉；
     * 2. 存档守卫：localStorage 为空时要先把快照回填，游戏才能读到存档；
     * 3. 参数调整（对应鸿蒙版 `/__cheats__.js`）：要在游戏自身脚本之前拿到场景捕获钩子；
     * 4. 引导脚本：**必须最后**——它是 `window.Phaser` 属性描述符的唯一所有者（见 [bootScript]）。
     *
     * @param patchCanvas canvas `willReadFrequently` 补丁开关（P0 的 A/B 开关）
     * @param saveGuardScript 存档守卫脚本（`SaveGuard.buildScript()`；空串则跳过）
     * @param downloadScript 存档导出拦截脚本（`DownloadBridge.buildScript()`；空串则跳过）
     * @param cheatsEnabled 是否有启用中的参数调整条目（对应鸿蒙版 `hasAnyEnabled()`）
     */
    fun indexInjections(
        patchCanvas: Boolean,
        saveGuardScript: String,
        cheatsEnabled: Boolean,
        downloadScript: String = ""
    ): String {
        val canvasFlag = if (patchCanvas) "1" else "0"
        val cheatsTag = if (cheatsEnabled) {
            "<script src=\"${Const.CHEAT_SCRIPT_PATH}\"></script>"
        } else {
            ""
        }
        return downloadScript + saveGuardScript + cheatsTag +
            "<script src=\"${Const.BOOT_SCRIPT_PATH}?canvas=$canvasFlag\"></script>"
    }

    /**
     * `/__pk_boot.js` 的内容。
     *
     * 职责有三：
     * 1. canvas `willReadFrequently` 补丁（可 A/B 关闭，验证收益是否真实存在）；
     * 2. **计时埋点**：`head → phaser → game → ready` 各阶段耗时回传原生，
     *    这是 P0 判据 2 / 3 的唯一数据来源；
     * 3. **`window.Phaser` 包装的唯一所有者**：参数调整脚本（`/__cheats__.js`）不再自己
     *    `defineProperty`，而是把回调压进 `window.__pkGameHooks`，由这里在 Game 实例构造完成后
     *    统一派发。两个脚本各定义一次同名属性时后者会覆盖前者（setter 永远不触发），
     *    这是必须在 JS 侧消除的隐性 bug（详见 `cheat/CheatScripts.kt` 的类注释）。
     */
    fun bootScript(patchCanvas: Boolean): String {
        val canvasJs = if (patchCanvas) {
            """
            try{
              var _gc=HTMLCanvasElement.prototype.getContext;
              HTMLCanvasElement.prototype.getContext=function(t,a){
                if(t==='2d'){var n={};if(a){for(var k in a){n[k]=a[k];}}n.willReadFrequently=true;return _gc.call(this,t,n);}
                return _gc.apply(this,arguments);
              };
              log('canvas willReadFrequently patch applied');
            }catch(e){log('canvas patch err '+e)}
            """.trimIndent()
        } else {
            "log('canvas patch disabled (A/B baseline)');"
        }

        return """
            (function(){
            try{
              var T0=performance.now();
              function log(m){try{console.log('PK '+m);}catch(e){}}
              function report(k,ms){try{
                if(window.PokeNative&&window.PokeNative.reportTiming){
                  window.PokeNative.reportTiming(JSON.stringify({k:k,ms:Math.round((ms===undefined?performance.now():ms)-T0)}));
                }
              }catch(e){}}
              function reportState(){
                try{report('state',performance.now());}catch(e){}
                try{
                  if(window.PokeNative&&window.PokeNative.reportState){
                    window.PokeNative.reportState(JSON.stringify({
                      getImageData:window.__pkGid||0,
                      ready:!!window.__pkReady,
                      canvasCount:document.getElementsByTagName('canvas').length
                    }));
                  }
                }catch(e){}
              }
              window.__pkReportState=reportState;

              log('boot script patchCanvas=$patchCanvas');
              report('head');

              // 1) getImageData 调用计数：canvas 补丁 A/B 的辅助观测值（鸿蒙版实测累计约 2 万次）
              try{
                if(!window.__pkGidWrap){
                  window.__pkGidWrap=1;window.__pkGid=0;
                  var _gi=HTMLCanvasElement.prototype.getImageData;
                  HTMLCanvasElement.prototype.getImageData=function(){window.__pkGid++;return _gi.apply(this,arguments);};
                }
              }catch(e){log('gid wrap err '+e)}

              // 2) canvas 补丁（鸿蒙版同款：强制 2D canvas 走 CPU 后备，消除 GPU 读回开销）
              $canvasJs

              // 3) Phaser 计时 + Game 实例派发：
              //    - 计时：head → phaser → game → ready（P0 判据 2 / 3 的数据来源）
              //    - 派发：把实例交给 window.__pkGameHooks（参数调整脚本注册的场景捕获回调）
              //    复用而非重定数组：/__cheats__.js 先于本脚本执行并 push 了自己的回调
              window.__pkGameHooks=window.__pkGameHooks||[];
              function patchPhaser(v){
                try{
                  if(v&&v.Game&&!v.Game.__pkWrapped){
                    var OG=v.Game;
                    var WG=function(){
                      var inst=Reflect.construct(OG,arguments,new.target||OG);
                      try{
                        window.__pkGame=inst;
                        report('game');
                        if(inst&&inst.events&&inst.events.once){
                          inst.events.once('ready',function(){window.__pkReady=true;report('ready');});
                        }
                        var raf=window.requestAnimationFrame?window.requestAnimationFrame.bind(window):function(f){setTimeout(f,16);};
                        var n=0;
                        (function poll(){
                          if(!window.__pkReady&&inst&&inst.isBooted){window.__pkReady=true;report('ready');return;}
                          if(++n<1800){raf(poll);}
                        })();
                      }catch(e){log('game wrap err '+e)}
                      try{
                        var hks=window.__pkGameHooks||[];
                        for(var i=0;i<hks.length;i++){try{hks[i](inst);}catch(e){log('game hook err '+e);}}
                      }catch(e){log('game hook err '+e)}
                      return inst;
                    };
                    WG.prototype=OG.prototype;WG.__pkWrapped=true;v.Game=WG;
                    report('phaser');
                    log('phaser Game wrapped');
                  }
                }catch(e){log('phaser patch err '+e)}
              }
              try{
                var _P;
                Object.defineProperty(window,'Phaser',{configurable:true,
                  get:function(){return _P;},
                  set:function(v){_P=v;patchPhaser(v);}});
                // 兜底：万一 Phaser 在本脚本之前就被赋值（正常注入顺序下不会发生）
                if(window.Phaser){patchPhaser(window.Phaser);}
              }catch(e){log('defineProperty err '+e)}

              // 4) JS 错误一律打到 console，原生 onConsoleMessage 会落 logcat，避免静默白屏
              window.addEventListener('error',function(e){
                log('JSERROR '+(e&&e.message)+' @'+(e&&e.filename)+':'+(e&&e.lineno));
              });
              window.addEventListener('unhandledrejection',function(e){log('JSREJECT '+e.reason);});

              // 5) 10s 后补一次状态快照（P0 观测用，正式版移除）
              setTimeout(reportState,10000);
            }catch(e){try{console.log('PK boot fatal '+e);}catch(_){}}
            })();
        """.trimIndent()
    }
}
