import { webview } from '@kit.ArkWeb';
import { router, promptAction } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { picker, fileUri, fileIo as fs } from '@kit.CoreFileKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { common } from '@kit.AbilityKit';
import { sharedServerBaseUrl } from '../model/LocalHttpServer';
import { setWindowBackground, setOrientation, GAME_BG_COLOR } from '../model/WindowHolder';
import { readOrientation, toOrientation } from '../model/OrientationPref';

const DOMAIN: number = 0x0000;
const TAG: string = 'GamePage';

/** 兜底地址（端口与 LocalHttpServer.PORT 一致） */
const FALLBACK_BASE: string = 'http://127.0.0.1:18787';

/**
 * 离线游戏页：本地 HTTP 服务器（App 启动时已在 EntryAbility 拉起）把
 * 沙箱 game 目录映射到 http://127.0.0.1:<port>，Web 组件加载该地址。
 */
@Entry
@Component
struct GamePage {
  controller: webview.WebviewController = new webview.WebviewController();
  private downloadDelegate: webview.WebDownloadDelegate = new webview.WebDownloadDelegate();
  private gameUrl: string = FALLBACK_BASE + '/index.html';
  private attached: boolean = false;
  private lastSavePath: string = '';
  private backTimer: number = -1;
  @State loadError: string = '';
  /** 返回按钮是否全显（false 时淡出，仅留可点痕迹，避免遮挡游戏信息） */
  @State backVisible: boolean = true;

  aboutToAppear(): void {
    const base: string = sharedServerBaseUrl();
    this.gameUrl = (base.length > 0 ? base : FALLBACK_BASE) + '/index.html';
    hilog.info(DOMAIN, TAG, 'game url = %{public}s', this.gameUrl);
  }

  /** 全显返回按钮并重置淡出计时 */
  private resetBackTimer(): void {
    this.backVisible = true;
    if (this.backTimer >= 0) {
      clearTimeout(this.backTimer);
    }
    this.backTimer = setTimeout(() => {
      this.backVisible = false;
    }, 3000);
  }

  aboutToDisappear(): void {
    if (this.backTimer >= 0) {
      clearTimeout(this.backTimer);
      this.backTimer = -1;
    }
  }

  /** 注册下载代理：游戏「导出数据」是 blob URL + <a download>，需要它才能落盘 */
  private setupDownloadDelegate(): void {
    this.downloadDelegate.onBeforeDownload((item: webview.WebDownloadItem) => {
      this.saveDownload(item);
    });
    this.downloadDelegate.onDownloadFailed((item: webview.WebDownloadItem) => {
      hilog.error(DOMAIN, TAG, 'web download failed: %{public}s code=%{public}d',
        item.getUrl(), item.getLastErrorCode());
    });
    this.downloadDelegate.onDownloadFinish((item: webview.WebDownloadItem) => {
      let exists: boolean = false;
      let size: number = 0;
      try {
        exists = fs.accessSync(this.lastSavePath);
        size = exists ? fs.statSync(this.lastSavePath).size : 0;
      } catch (err) {
        hilog.warn(DOMAIN, TAG, 'stat saved file failed: %{public}s', JSON.stringify(err));
      }
      hilog.info(DOMAIN, TAG, 'web download finish: %{public}s exists=%{public}s size=%{public}d',
        this.lastSavePath, exists.toString(), size);
      // 给用户明确反馈（文件保存位置）
      const name: string = item.getSuggestedFileName();
      promptAction.showToast({ message: '已导出 ' + name + ' 到「下载/com.lichtcui.pokerogue」' });
    });
    try {
      this.controller.setDownloadDelegate(this.downloadDelegate);
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'setDownloadDelegate failed: %{public}s', JSON.stringify(err));
    }
  }

  /** 让用户选择保存位置后落盘（沙箱外的公共目录需要用户授权） */
  private saveDownload(item: webview.WebDownloadItem): void {
    const fileName: string = item.getSuggestedFileName();
    try {
      const opts: picker.DocumentSaveOptions = new picker.DocumentSaveOptions();
      opts.pickerMode = picker.DocumentPickerMode.DOWNLOAD;
      opts.newFileNames = [fileName];
      const hostCtx = this.getUIContext().getHostContext();
      if (hostCtx === undefined) {
        item.cancel();
        return;
      }
      const docPicker: picker.DocumentViewPicker = new picker.DocumentViewPicker(hostCtx);
      docPicker.save(opts).then((result: Array<string>) => {
        if (result !== undefined && result.length > 0) {
          const uri: fileUri.FileUri = new fileUri.FileUri(result[0]);
          // DOWNLOAD 模式返回的是目录 URI，需要拼上文件名
          const target: string = uri.path + '/' + fileName;
          this.lastSavePath = target;
          item.start(target);
          hilog.info(DOMAIN, TAG, 'saving export to %{public}s', target);
        } else {
          item.cancel();
        }
      }).catch((err: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'save picker failed: %{public}s', JSON.stringify(err));
        item.cancel();
      });
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'saveDownload failed: %{public}s', JSON.stringify(err));
      item.cancel();
    }
  }

  build() {
    Stack({ alignContent: Alignment.TopEnd }) {
      Web({ src: this.gameUrl, controller: this.controller })
        .domStorageAccess(true)
        .fileAccess(true)
        .javaScriptAccess(true)
        .imageAccess(true)
        .onlineImageAccess(true)
        .onControllerAttached(() => {
          // 控制器挂载后才能注册下载代理/设置音频
          this.attached = true;
          this.setupDownloadDelegate();
          try {
            this.controller.setAudioMuted(false);
          } catch (err) {
            hilog.warn(DOMAIN, TAG, 'initial unmute failed: %{public}s', JSON.stringify(err));
          }
        })
        .onErrorReceive((event) => {
          const desc: string = event.error.getErrorInfo();
          this.loadError = desc;
          hilog.error(DOMAIN, TAG, 'onErrorReceive: %{public}s', desc);
        })
        .onPageEnd(() => {
          // 页面加载成功：清掉上一次的错误浮层
          if (this.loadError.length > 0) {
            this.loadError = '';
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
        .opacity(this.backVisible ? 1 : 0.2)
        .margin(8)
        .onClick(() => {
          if (this.backVisible) {
            router.back();
          } else {
            this.resetBackTimer();
          }
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
    // 切后台/离开页面：静音 Web 音频（BGM 停止），保留游戏进程
    hilog.info(DOMAIN, TAG, 'onPageHide attached=%{public}s', this.attached.toString());
    if (!this.attached) {
      return;
    }
    try {
      this.controller.setAudioMuted(true);
      hilog.info(DOMAIN, TAG, '%{public}s', 'audio muted');
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'mute audio failed: %{public}s', JSON.stringify(err));
    }
  }

  onPageShow(): void {
    // 回到游戏页：返回按钮先全显，随后自动淡出
    this.resetBackTimer();
    // 底部安全区留白染成游戏背景色
    setWindowBackground(GAME_BG_COLOR);
    // 应用用户在设置页选择的屏幕方向（仅游戏页生效）
    const hostCtx = this.getUIContext().getHostContext();
    if (hostCtx !== undefined) {
      readOrientation(hostCtx as common.UIAbilityContext).then((v: string) => {
        setOrientation(toOrientation(v));
      });
    }
    hilog.info(DOMAIN, TAG, 'onPageShow attached=%{public}s', this.attached.toString());
    if (!this.attached) {
      return;
    }
    try {
      this.controller.setAudioMuted(false);
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'unmute audio failed: %{public}s', JSON.stringify(err));
    }
  }
}
