import { webview } from '@kit.ArkWeb';
import { router, promptAction } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { picker, fileUri, fileIo as fs } from '@kit.CoreFileKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { common } from '@kit.AbilityKit';
import { uri } from '@kit.ArkTS';
import { sharedServerBaseUrl, sharedServerReady, sharedServerErrorText } from '../model/LocalHttpServer';
import { setWindowBackground, setOrientation, GAME_BG_COLOR } from '../model/WindowHolder';
import { readOrientation, toOrientation } from '../model/OrientationPref';
import { SaveGuardBridge } from '../model/SaveGuard';
import { apiAtLeast } from '../common/Const';

const DOMAIN: number = 0x0000;
const TAG: string = 'GamePage';

/** 兜底地址（端口与 LocalHttpServer.PORT 一致） */
const FALLBACK_BASE: string = 'http://127.0.0.1:18787';

/**
 * 取文件 URI 的路径部分。`fileUri.FileUri` 是 @since 15，低版本回退到
 * 通用 `@ohos.uri` 的 `URI`（@since 8），二者对 file:// URI 的 path 语义一致。
 */
function pathOfUri(u: string): string {
  if (apiAtLeast(15)) {
    return new fileUri.FileUri(u).path;
  }
  return new uri.URI(u).path;
}

/**
 * 离线游戏页：本地 HTTP 服务器（App 启动时已在 EntryAbility 拉起）把
 * 沙箱 game 目录映射到 http://127.0.0.1:<port>，Web 组件加载该地址。
 */
@Entry
@Component
struct GamePage {
  controller: webview.WebviewController = new webview.WebviewController();
  private downloadDelegate: webview.WebDownloadDelegate = new webview.WebDownloadDelegate();
  /** 存档快照桥：注入脚本通过 window.PokeSaveGuard 读写 filesDir 下的快照 */
  private saveBridge: SaveGuardBridge = new SaveGuardBridge();
  private gameUrl: string = FALLBACK_BASE + '/index.html';
  /**
   * Web 音频策略：Web 音频默认映射为 STREAM_USAGE_MUSIC，与后台音乐/视频属同类流，
   * 会互相抢占焦点导致后台音量被降音（duck）甚至忽大忽小。
   * AMBIENT 改映射为 STREAM_USAGE_GAME，官方语义是「Web 游戏声音与系统音乐可同时播放」，
   * 即与后台音频并发混音、互不降音。`audioSessionType` 是 @since 20：低版本不设置，
   * 回退为音频被系统降音（duck）。注意：必须在首次播放前设置才生效。
   */
  private mediaOptions: WebMediaOptions = {
    audioExclusive: false,
    resumeInterval: 0
  };
  private attached: boolean = false;
  private lastSavePath: string = '';
  private backTimer: number = -1;
  /** 页面是否仍在栈中：异步回调返回时据此避免操作已销毁的页面 */
  private alive: boolean = true;
  @State loadError: string = '';
  /** 返回按钮是否全显（false 时淡出，仅留可点痕迹，避免遮挡游戏信息） */
  @State backVisible: boolean = true;

  aboutToAppear(): void {
    this.alive = true;
    // audioSessionType（@since 20）：与后台音乐并发混音；低版本不支持则回退（会降音）
    if (apiAtLeast(20)) {
      this.mediaOptions.audioSessionType = AudioSessionType.AMBIENT;
    }
    const base: string = sharedServerBaseUrl();
    this.gameUrl = (base.length > 0 ? base : FALLBACK_BASE) + '/index.html';
    hilog.info(DOMAIN, TAG, 'game url = %{public}s', this.gameUrl);
    if (!sharedServerReady()) {
      const detail: string = sharedServerErrorText();
      this.loadError = '本地服务未就绪' + (detail.length > 0 ? '：' + detail : '');
      hilog.error(DOMAIN, TAG, 'local server not ready: %{public}s', detail);
    }
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
    this.alive = false;
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
      promptAction.showToast({ message: '已导出 ' + name + ' 到「下载/com.lichtcui.pokelauncher」' });
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
          // DOWNLOAD 模式返回的是目录 URI，需要拼上文件名
          const target: string = pathOfUri(result[0]) + '/' + fileName;
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
        // 资源全部走本地 HTTP，无需 Web 直接访问沙箱文件系统
        .fileAccess(false)
        .javaScriptAccess(true)
        .imageAccess(true)
        .onlineImageAccess(true)
        // 与后台音乐并发混音，不对其降音（必须在首次播放前设置才生效）
        .mediaOptions(this.mediaOptions)
        // 存档快照桥：必须在页面加载前注册，注入脚本才能拿到 PokeSaveGuard
        .javaScriptProxy({
          object: this.saveBridge,
          name: 'PokeSaveGuard',
          methodList: ['load', 'save', 'notifyRestored'],
          controller: this.controller
        })
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
          // 存档是从快照回填的 → 明确告知用户，避免误以为进度被回滚
          const restored: number = this.saveBridge.getRestoredCount();
          if (restored > 0) {
            promptAction.showToast({
              message: '检测到本地存档缺失，已从快照恢复 ' + restored + ' 项数据'
            });
            hilog.info(DOMAIN, TAG, 'save restored from snapshot: %{public}d keys', restored);
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
        if (this.alive) {
          setOrientation(toOrientation(v));
        }
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
