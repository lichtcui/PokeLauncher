import { webview } from '@kit.ArkWeb';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { ONLINE_URL } from '../common/Const';

const DOMAIN: number = 0x0000;
const TAG: string = 'PokeRogue';

/**
 * M1：内核兼容性验证页。
 * 直接用 Web 组件加载 https://pokerogue.net/，验证 ArkWeb 能否运行 PokeRogue
 * （Phaser / WebGL / 音频 / localStorage）。验证通过后再进入离线方案。
 */
@Entry
@Component
struct Index {
  controller: webview.WebviewController = new webview.WebviewController();
  @State status: string = '正在加载…';

  build() {
    Column() {
      Row() {
        Text(this.status)
          .fontSize(13)
          .fontColor('#333333')
          .layoutWeight(1)
          .maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        Button('重载')
          .fontSize(13)
          .height(32)
          .onClick(() => {
            try {
              this.controller.loadUrl(ONLINE_URL);
            } catch (err) {
              hilog.error(DOMAIN, TAG, 'loadUrl failed: %{public}s', JSON.stringify(err));
            }
          })
      }
      .width('100%')
      .padding({ left: 12, right: 12, top: 6, bottom: 6 })
      .backgroundColor('#F1F3F5')

      Web({ src: ONLINE_URL, controller: this.controller })
        .domStorageAccess(true)
        .javaScriptAccess(true)
        .imageAccess(true)
        .onlineImageAccess(true)
        .mixedMode(MixedMode.All)
        .onPageBegin((event) => {
          this.status = '开始加载: ' + event.url;
          hilog.info(DOMAIN, TAG, 'onPageBegin: %{public}s', event.url);
        })
        .onPageEnd((event) => {
          this.status = '加载完成: ' + event.url;
          hilog.info(DOMAIN, TAG, 'onPageEnd: %{public}s', event.url);
        })
        .onErrorReceive((event) => {
          const desc: string = event.error.getErrorInfo();
          this.status = '加载出错: ' + desc;
          hilog.error(DOMAIN, TAG, 'onErrorReceive: %{public}s', desc);
        })
        .width('100%')
        .layoutWeight(1)
    }
    .width('100%')
    .height('100%')
  }
}
