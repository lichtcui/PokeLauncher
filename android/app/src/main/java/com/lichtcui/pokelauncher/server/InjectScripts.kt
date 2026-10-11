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
     * 插到 `index.html` 的 `<head>` 之后的标签。
     * 作弊脚本（对应鸿蒙版 `/__cheats__.js`）在 P2 接入，此处先只注入引导脚本。
     */
    fun indexInjections(patchCanvas: Boolean): String {
        val canvasFlag = if (patchCanvas) "1" else "0"
        return "<script src=\"${Const.BOOT_SCRIPT_PATH}?canvas=$canvasFlag\"></script>"
    }

    /**
     * `/__pk_boot.js` 的内容。
     *
     * P0 阶段职责有二：
     * 1. canvas `willReadFrequently` 补丁（可 A/B 关闭，验证收益是否真实存在）；
     * 2. **计时埋点**：`head → phaser → game → ready` 各阶段耗时回传原生，
     *    这是 P0 判据 2 / 3 的唯一数据来源。
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

              // 3) Phaser 计时：包装 Game 构造函数，拿实例并等 'ready'
              //    与鸿蒙版 cheats 的场景捕获同源技术，P2 会在此基础上扩展
              try{
                var _P;
                Object.defineProperty(window,'Phaser',{configurable:true,
                  get:function(){return _P;},
                  set:function(v){
                    _P=v;
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
                          return inst;
                        };
                        WG.prototype=OG.prototype;WG.__pkWrapped=true;v.Game=WG;
                        report('phaser');
                        log('phaser Game wrapped');
                      }
                    }catch(e){log('phaser patch err '+e)}
                  }});
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
