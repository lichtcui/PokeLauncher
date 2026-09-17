import { common } from '@kit.AbilityKit';
import { router, promptAction, window } from '@kit.ArkUI';
import { BusinessError, pasteboard } from '@kit.BasicServicesKit';
import { picker } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { normalizeVersion } from '../model/GameRepository';
import {
  AppUpdateInfo,
  AppUpdateResult,
  checkAppUpdate,
  markChecked,
  openDownloadPage,
  shouldAutoCheck
} from '../model/AppUpdate';
import { Notifier } from '../model/Notifier';
import { LauncherSession, LauncherStage, ProgressView } from '../model/LauncherSession';
import { setWindowBackground, setOrientation, HOME_BG_COLOR } from '../model/WindowHolder';
import { isMasterEnabled } from '../model/CheatState';
import { sharedServerReady } from '../model/LocalHttpServer';
import { PrepPhase } from '../components/PulseProgress';
import { NotDownloadedView, DownloadFlowView, ReadyView } from '../components/LauncherViews';

const DOMAIN: number = 0x0000;
const TAG: string = 'Home';

/**
 * 首页启动器：未下载 / 下载中 / 解压中 / 已就绪。
 * 下载与解压业务在 `model/LauncherSession.ets`，本页只做声明式渲染与状态回写。
 */
@Entry
@Component
struct Index {
  @State stage: LauncherStage = LauncherStage.Checking;
  @State prepPhase: PrepPhase = PrepPhase.IDLE;
  @State progress: number = 0;
  @State receivedText: string = '';
  @State totalText: string = '';
  @State speedText: string = '';
  @State etaText: string = '';
  @State version: string = '';
  @State error: string = '';
  @State paused: boolean = false;
  @State storageInsufficient: boolean = false;
  @State freeSizeText: string = '';
  @State cheatMasterOn: boolean = false;
  /** 运行时解析出的资源包直链（GitHub Release 的 game.zip），随版本自动变化 */
  @State assetUrl: string = '';

  private ctx: common.UIAbilityContext | null = null;
  private session: LauncherSession | null = null;

  aboutToAppear(): void {
    const ctx: common.UIAbilityContext =
      this.getUIContext().getHostContext() as common.UIAbilityContext;
    this.ctx = ctx;
    this.cheatMasterOn = isMasterEnabled();
    // 清理上次遗留的完成/失败通知（不动下载任务的进度通知）
    Notifier.cancelOwn();
    this.session = new LauncherSession(ctx, {
      setStage: (s: LauncherStage) => { this.stage = s; },
      setPrepPhase: (p: PrepPhase) => { this.prepPhase = p; },
      setProgress: (v: ProgressView) => {
        this.progress = v.progress;
        this.receivedText = v.receivedText;
        this.totalText = v.totalText;
        this.speedText = v.speedText;
        this.etaText = v.etaText;
      },
      setPaused: (p: boolean) => { this.paused = p; },
      setVersion: (v: string) => { this.version = v; },
      setError: (msg: string) => { this.error = msg; },
      setTotalText: (t: string) => { this.totalText = t; },
      setStorage: (insufficient: boolean, freeText: string) => {
        this.storageInsufficient = insufficient;
        this.freeSizeText = freeText;
      },
      setAssetUrl: (url: string) => { this.assetUrl = url; }
    });
    this.session.start();
    this.maybeAutoCheckAppUpdate();
  }

  aboutToDisappear(): void {
    const session: LauncherSession | null = this.session;
    if (session !== null) {
      session.dispose();
    }
  }

  onPageShow(): void {
    // 首页底部安全区留白用白色
    setWindowBackground(HOME_BG_COLOR);
    // 首页始终保持竖屏（游戏页可能已切换为横屏/跟随系统）
    setOrientation(window.Orientation.PORTRAIT);
    // 刷新参数调整入口可见性（可能在设置页开启了总开关）
    this.cheatMasterOn = isMasterEnabled();
    const session: LauncherSession | null = this.session;
    if (session === null) {
      return;
    }
    // 从设置页返回：更新标记优先，其次刷新就绪态（核心包可能已被删除）
    const pending: boolean | undefined = AppStorage.get<boolean>('pendingUpdate');
    if (pending === true) {
      AppStorage.setOrCreate('pendingUpdate', false);
      session.startDownload();
      return;
    }
    session.refreshReadyState();
  }

  /** 启动时自动检查应用更新（24h 节流；失败静默） */
  private maybeAutoCheckAppUpdate(): void {
    const ctx: common.UIAbilityContext | null = this.ctx;
    if (ctx === null) {
      return;
    }
    shouldAutoCheck(ctx).then((due: boolean) => {
      if (!due) {
        return;
      }
      checkAppUpdate().then((res: AppUpdateResult) => {
        // 强制更新不写节流时间戳：下次启动重新检查并提示
        if (!res.forceUpdate) {
          markChecked(ctx);
        }
        if (!res.hasUpdate || res.remote === null) {
          return;
        }
        this.showAppUpdateDialog(res);
      }).catch((err: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'auto app update check failed: %{public}s', JSON.stringify(err));
      });
    }).catch((err: BusinessError) => {
      hilog.warn(DOMAIN, TAG, 'shouldAutoCheck failed: %{public}s', JSON.stringify(err));
    });
  }

  /** 发现新版本：普通更新可稍后，强制更新只有「去更新」且不可关闭 */
  private showAppUpdateDialog(res: AppUpdateResult): void {
    const remote: AppUpdateInfo | null = res.remote;
    if (remote === null) {
      return;
    }
    const name: string = remote.versionName.length > 0 ? remote.versionName : String(remote.versionCode);
    const message: string = remote.changelog.length > 0
      ? '发现新版本 ' + name + '\n\n' + remote.changelog
      : '发现新版本 ' + name;
    const go = (): void => {
      const ctx: common.UIAbilityContext | null = this.ctx;
      if (ctx !== null) {
        openDownloadPage(ctx, remote.pageUrl.length > 0 ? remote.pageUrl : remote.downloadUrl);
      }
    };
    if (res.forceUpdate) {
      AlertDialog.show({
        title: '需要更新',
        message: message,
        autoCancel: false,
        confirm: {
          value: '去更新',
          action: go
        }
      });
    } else {
      AlertDialog.show({
        title: '发现新版本',
        message: message,
        primaryButton: {
          value: '稍后',
          action: () => {}
        },
        secondaryButton: {
          value: '去更新',
          action: go
        }
      });
    }
  }

  private requiredStorageText(): string {
    const session: LauncherSession | null = this.session;
    return session !== null ? session.requiredStorageText() : '';
  }

  private flowTitle(): string {
    const session: LauncherSession | null = this.session;
    return session !== null ? session.flowTitle() : '';
  }

  /** 选中自行下载好的资源包 zip（文件选择器只能读，需拷进沙箱再解压） */
  private importResource(): void {
    const ctx: common.UIAbilityContext | null = this.ctx;
    const session: LauncherSession | null = this.session;
    if (ctx === null || session === null) {
      return;
    }
    const opts: picker.DocumentSelectOptions = new picker.DocumentSelectOptions();
    opts.maxSelectNumber = 1;
    opts.fileSuffixFilters = ['.zip'];
    const docPicker: picker.DocumentViewPicker = new picker.DocumentViewPicker(ctx);
    docPicker.select(opts).then((uris: string[]) => {
      if (uris === undefined || uris.length === 0) {
        return;
      }
      session.installFromZip(uris[0]);
    }).catch((err: BusinessError) => {
      hilog.warn(DOMAIN, TAG, 'select resource zip failed: %{public}s', JSON.stringify(err));
    });
  }

  /** 复制资源包直链（GitHub 直链一般交给浏览器/下载工具使用，复制比跳转更实用） */
  private copyAssetUrl(): void {
    const url: string = this.assetUrl;
    if (url.length === 0) {
      promptAction.showToast({ message: '暂未获取到直链，请先联网重试' });
      return;
    }
    try {
      const data: pasteboard.PasteData =
        pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, url);
      pasteboard.getSystemPasteboard().setData(data).then(() => {
        promptAction.showToast({ message: '直链已复制' });
      }).catch((err: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'copy asset url failed: %{public}s', JSON.stringify(err));
      });
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'create paste data failed: %{public}s', JSON.stringify(err));
    }
  }

  private togglePause(): void {
    const session: LauncherSession | null = this.session;
    if (session !== null) {
      session.pauseToggle();
    }
  }

  /** 取消是重操作（会丢失已下载进度）：先二次确认 */
  private confirmCancel(): void {
    if (this.session === null) {
      return;
    }
    const downloaded: string = this.receivedText.length > 0 ? this.receivedText : '0 MB';
    promptAction.showDialog({
      title: '取消下载',
      message: '确定取消下载吗？已下载的 ' + downloaded + ' 将全部丢失。',
      buttons: [
        { text: '继续下载', color: '#666666' },
        { text: '确定取消', color: '#DA3838' }
      ]
    }).then((result: promptAction.ShowDialogSuccessResponse) => {
      if (result.index === 1) {
        const session: LauncherSession | null = this.session;
        if (session !== null) {
          session.cancelDownload();
        }
      }
    }).catch((err: BusinessError) => {
      hilog.warn(DOMAIN, TAG, 'showDialog failed: %{public}s', JSON.stringify(err));
    });
  }

  private startGame(): void {
    // 本地服务未就绪（如端口被占用）时进入游戏只会白屏，提前拦截并提示
    if (!sharedServerReady()) {
      hilog.error(DOMAIN, TAG, '%{public}s', 'startGame blocked: local server not ready');
      promptAction.showToast({ message: '本地服务未就绪，请重启应用后重试' });
      return;
    }
    router.pushUrl({ url: 'pages/GamePage' }).catch((err: BusinessError) => {
      hilog.error(DOMAIN, TAG, 'pushUrl GamePage failed: %{public}s', JSON.stringify(err));
    });
  }

  private startSettings(): void {
    router.pushUrl({ url: 'pages/Settings' }).catch((err: BusinessError) => {
      hilog.error(DOMAIN, TAG, 'pushUrl Settings failed: %{public}s', JSON.stringify(err));
    });
  }

  private startCheats(): void {
    router.pushUrl({ url: 'pages/Cheats' }).catch((err: BusinessError) => {
      hilog.error(DOMAIN, TAG, 'pushUrl Cheats failed: %{public}s', JSON.stringify(err));
    });
  }

  build() {
    Stack() {
      Column() {
        // 右上角设置入口（仅就绪态显示）
        if (this.stage === LauncherStage.Ready) {
          Row() {
            Button({ type: ButtonType.Circle }) {
              Image($r('app.media.settings'))
                .width(22)
                .height(22)
                .objectFit(ImageFit.Contain)
            }
            .width(40)
            .height(40)
            .backgroundColor('#F2F2F2')
            .onClick(() => this.startSettings())
          }
          .width('100%')
          .justifyContent(FlexAlign.End)
          // top 留够：右上角是系统手势区（下拉控制中心），贴太近会点不到
          .padding({ right: 16, top: 24 })
        }

        Column() {
          if (this.stage === LauncherStage.Checking) {
            LoadingProgress().width(40).height(40)
          } else if (this.stage === LauncherStage.NotDownloaded) {
            NotDownloadedView({
              totalText: this.totalText,
              requiredStorageText: this.requiredStorageText(),
              storageInsufficient: this.storageInsufficient,
              freeSizeText: this.freeSizeText,
              assetUrl: this.assetUrl,
              onStartDownload: () => {
                const session: LauncherSession | null = this.session;
                if (session !== null) {
                  session.startDownload();
                }
              },
              onImport: () => this.importResource(),
              onCopyUrl: () => this.copyAssetUrl()
            })
          } else if (this.stage === LauncherStage.Downloading
            || this.stage === LauncherStage.Extracting) {
            DownloadFlowView({
              phase: this.prepPhase,
              paused: this.paused,
              progress: this.progress,
              speedText: this.speedText,
              etaText: this.etaText,
              flowTitle: this.flowTitle(),
              onTogglePause: () => this.togglePause(),
              onCancel: () => this.confirmCancel()
            })
          } else {
            ReadyView({
              versionText: this.version.length > 0 ? normalizeVersion(this.version) : '未知',
              cheatMasterOn: this.cheatMasterOn,
              onStartGame: () => this.startGame(),
              onOpenCheats: () => this.startCheats()
            })
          }

          if (this.error.length > 0) {
            Text(this.error).fontSize(13).fontColor('#DA3838').margin({ top: 16 })
              .padding({ left: 24, right: 24 })
          }
        }
        .layoutWeight(1)
        .width('100%')
        .justifyContent(FlexAlign.Center)
      }
      .width('100%')
      .height('100%')
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#FFFFFF')
  }
}