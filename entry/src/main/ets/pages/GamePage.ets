import { webview } from '@kit.ArkWeb';
import { common } from '@kit.AbilityKit';
import { router } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { LocalContentProvider } from '../model/LocalContentProvider';
import { GAME_START_URL, GAME_DIR_NAME } from '../common/Const';

const DOMAIN: number = 0x0000;
const TAG: string = 'GamePage';

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
}
