import { webview } from '@kit.ArkWeb';
import { router } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { sharedServerBaseUrl } from '../model/LocalHttpServer';
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
    try {
      this.controller.runJavaScript(JS_RESUME_AUDIO);
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'resume audio failed: %{public}s', JSON.stringify(err));
    }
  }
}
