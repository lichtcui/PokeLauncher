import { webview } from '@kit.ArkWeb';
import { common } from '@kit.AbilityKit';
import { router } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { LocalContentProvider } from '../model/LocalContentProvider';
import { GAME_START_URL, GAME_DIR_NAME } from '../common/Const';

const DOMAIN: number = 0x0000;
const TAG: string = 'GamePage';

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

/**
 * 离线游戏页：Web 组件加载 https://pokerogue.local/index.html，
 * 由 LocalContentProvider 从沙箱 filesDir/game 提供资源。
 */
@Entry
@Component
struct GamePage {
  controller: webview.WebviewController = new webview.WebviewController();
  private provider: LocalContentProvider | null = null;
  @State loadError: string = '';

  aboutToAppear(): void {
    const ctx = this.getUIContext().getHostContext() as common.UIAbilityContext;
    this.provider = new LocalContentProvider(ctx.filesDir + '/' + GAME_DIR_NAME);
  }

  build() {
    Stack({ alignContent: Alignment.TopEnd }) {
      Web({ src: GAME_START_URL, controller: this.controller })
        .domStorageAccess(true)
        .fileAccess(true)
        .javaScriptAccess(true)
        .imageAccess(true)
        .onlineImageAccess(true)
        .onInterceptRequest((event) => {
          if (this.provider !== null) {
            return this.provider.handle(event);
          }
          return null;
        })
        .onErrorReceive((event) => {
          const desc: string = event.error.getErrorInfo();
          this.loadError = desc;
          hilog.error(DOMAIN, TAG, 'onErrorReceive: %{public}s', desc);
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
    // 切后台/离开页面：保留游戏但停止背景音乐
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
