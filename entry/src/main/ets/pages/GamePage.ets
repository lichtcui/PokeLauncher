import { webview } from '@kit.ArkWeb';
import { router } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { sharedServerBaseUrl } from '../model/LocalHttpServer';
import { setWindowBackground, GAME_BG_COLOR } from '../model/WindowHolder';
import { DEBUG_UI } from '../common/Const';

const DOMAIN: number = 0x0000;
const TAG: string = 'GamePage';

/** 兜底地址（端口与 LocalHttpServer.CANDIDATE_PORTS[0] 一致） */
const FALLBACK_BASE: string = 'http://127.0.0.1:18787';

/** 切后台时暂停游戏音频（best-effort，PokeRogue 用 Phaser/WebAudio） */
const JS_PAUSE_AUDIO: string =
  '(function(){try{' +
  'document.querySelectorAll("audio,video").forEach(function(e){e.pause();});' +
  'var g=window.game||window.PhaserGame;' +
  'if(g&&g.sound&&g.sound.pauseAll){g.sound.pauseAll();}' +
  '}catch(e){}})();';

/** 回前台恢复音频 */
const JS_RESUME_AUDIO: string =
  '(function(){try{' +
  'var g=window.game||window.PhaserGame;' +
  'if(g&&g.sound&&g.sound.resumeAll){g.sound.resumeAll();}' +
  '}catch(e){}})();';

/** 开发用：放大 resource timing 缓冲区 */
const JS_PERF_BUFFER: string =
  'try{performance.setResourceTimingBufferSize(20000);}catch(e){}';

/** 开发用：记录 fetch/XHR 耗时与长任务（定位卡顿） */
const JS_NET_PROBE: string =
  '(function(){try{' +
  'var of=window.fetch;window.fetch=function(u,o){var t=performance.now();var uu=String(u);' +
  'return of.apply(this,arguments).then(function(r){console.log("NET fetch "+(performance.now()-t).toFixed(0)+"ms "+r.status+" "+uu);return r;})' +
  '.catch(function(e){console.log("NET fetchERR "+(performance.now()-t).toFixed(0)+"ms "+uu+" "+e);throw e;});};' +
  'var oo=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){var t=performance.now();var uu=String(u);' +
  'this.addEventListener("loadend",function(){console.log("NET xhr "+(performance.now()-t).toFixed(0)+"ms "+this.status+" "+uu);});return oo.apply(this,arguments);};' +
  'try{new PerformanceObserver(function(l){l.getEntries().forEach(function(e){if(e.duration>300){console.log("NET longtask start="+e.startTime.toFixed(0)+" dur="+e.duration.toFixed(0));}});}).observe({entryTypes:["longtask"]});}catch(e){}' +
  'try{new PerformanceObserver(function(l){l.getEntries().forEach(function(e){' +
  'if(e.duration>300){console.log("LOAF dur="+e.duration.toFixed(0)+" renderStart="+(e.renderStart||0).toFixed(0));' +
  'var ss=e.scripts||[];for(var i=0;i<ss.length;i++){var s=ss[i];' +
  'console.log("LOAF script "+(s.duration||0).toFixed(0)+"ms src="+(s.sourceURL||"").split("/").pop()+" fn="+(s.sourceFunctionName||"")+" inv="+(s.invoker||""));}' +
  '}});}).observe({type:"long-animation-frame",buffered:true});}catch(e){console.log("LOAF observe err "+e);}' +
  '}catch(e){console.log("NET probe err "+e);}})();';

/** 开发用：25s 后 dump 资源加载统计（通过 ARKWEB-CONSOLE 输出） */
const JS_PERF_PROBE: string =
  'console.log("PERF injected now="+Math.round(performance.now())+"ms");' +
  'setTimeout(function(){try{' +
  'var res=performance.getEntriesByType("resource");' +
  'var total=0;for(var i=0;i<res.length;i++){total+=res[i].duration;}' +
  'console.log("PERF count="+res.length+" sumDuration="+Math.round(total)+"ms");' +
  'res.sort(function(a,b){return b.duration-a.duration;});' +
  'for(var j=0;j<Math.min(8,res.length);j++){' +
  'var n=res[j].name.split("/").pop();' +
  'console.log("PERF "+res[j].duration.toFixed(0)+"ms size="+res[j].transferSize+" "+n);}' +
  'var t=performance.timing;' +
  'console.log("PERF dcl="+(t.domContentLoadedEventEnd-t.navigationStart)+"ms load="+(t.loadEventEnd-t.navigationStart)+"ms now="+Math.round(performance.now())+"ms");' +
  '}catch(e){console.log("PERF err "+e);}},25000);';

/**
 * 离线游戏页：本地 HTTP 服务器（App 启动时已在 EntryAbility 拉起）把
 * 沙箱 game 目录映射到 http://127.0.0.1:<port>，Web 组件加载该地址。
 */
@Entry
@Component
struct GamePage {
  controller: webview.WebviewController = new webview.WebviewController();
  private gameUrl: string = FALLBACK_BASE + '/index.html';
  @State loadError: string = '';

  aboutToAppear(): void {
    const base: string = sharedServerBaseUrl();
    this.gameUrl = (base.length > 0 ? base : FALLBACK_BASE) + '/index.html';
    hilog.info(DOMAIN, TAG, 'game url = %{public}s', this.gameUrl);
  }

  build() {
    Stack({ alignContent: Alignment.TopEnd }) {
      Web({ src: this.gameUrl, controller: this.controller })
        .domStorageAccess(true)
        .fileAccess(true)
        .javaScriptAccess(true)
        .imageAccess(true)
        .onlineImageAccess(true)
        .onErrorReceive((event) => {
          const desc: string = event.error.getErrorInfo();
          this.loadError = desc;
          hilog.error(DOMAIN, TAG, 'onErrorReceive: %{public}s', desc);
        })
        .onPageBegin(() => {
          if (DEBUG_UI) {
            this.controller.runJavaScript(JS_PERF_BUFFER);
            this.controller.runJavaScript(JS_NET_PROBE);
          }
        })
        .onPageEnd(() => {
          if (DEBUG_UI) {
            this.controller.runJavaScript(JS_PERF_PROBE);
          }
        })
        .width('100%')
        .height('100%')

      if (this.loadError.length > 0) {
        Text('加载出错: ' + this.loadError)
          .fontSize(12)
          .fontColor('#FFFFFF')
          .backgroundColor('#99000000')
          .padding(8)
          .margin(8)
          .maxLines(3)
      }

      Button('返回')
        .fontSize(12)
        .height(30)
        .backgroundColor('#66000000')
        .margin(8)
        .onClick(() => {
          router.back();
        })
    }
    .width('100%')
    .height('100%')
    .backgroundColor(GAME_BG_COLOR)
  }

  onBackPress(): boolean {
    router.back();
    return true;
  }

  onPageHide(): void {
    try {
      this.controller.runJavaScript(JS_PAUSE_AUDIO);
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'pause audio failed: %{public}s', JSON.stringify(err));
    }
  }

  onPageShow(): void {
    // 底部安全区留白染成游戏背景色
    setWindowBackground(GAME_BG_COLOR);
    try {
      this.controller.runJavaScript(JS_RESUME_AUDIO);
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'resume audio failed: %{public}s', JSON.stringify(err));
    }
  }
}
