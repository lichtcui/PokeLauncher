import { common } from '@kit.AbilityKit';
import { router, promptAction, window } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { preferences } from '@kit.ArkData';
import { statfs } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { GameRepository, ReleaseInfo, normalizeVersion } from '../model/GameRepository';
import {
  AppUpdateInfo,
  AppUpdateResult,
  checkAppUpdate,
  markChecked,
  openDownloadPage,
  shouldAutoCheck
} from '../model/AppUpdate';
import { Notifier } from '../model/Notifier';
import { getStore } from '../common/Prefs';
import { clearWebCache } from '../common/WebCache';
import { formatBytes, formatSpeed, formatDuration } from '../common/Format';
import { setWindowBackground, setOrientation, HOME_BG_COLOR } from '../model/WindowHolder';
import { isMasterEnabled } from '../model/CheatState';
import { sharedServerReady } from '../model/LocalHttpServer';
import { PulseProgress, PrepPhase, SHRINK_MS, BURST_MS } from '../components/PulseProgress';

const DOMAIN: number = 0x0000;
const TAG: string = 'Home';

enum Stage {
  Checking,
  NotDownloaded,
  Downloading,
  Extracting,
  Ready
}

/**
 * 首页启动器：未下载 / 下载中 / 解压中 / 已就绪。
 * 核心路径：进入 → 下载核心包（已下载可跳过）→ 游玩。
 */
@Entry
@Component
struct Index {
  @State stage: Stage = Stage.Checking;
  @State prepPhase: PrepPhase = PrepPhase.IDLE;
  @State progress: number = 0;
  @State receivedText: string = '';
  @State totalText: string = '';
  @State speedText: string = '';
  @State etaText: string = '';
  @State version: string = '';
  @State error: string = '';
  @State paused: boolean = false;
  @State probing: boolean = false;
  @State storageInsufficient: boolean = false;
  @State freeSizeText: string = '';
  @State cheatMasterOn: boolean = false;

  private repo: GameRepository | null = null;
  private ctx: common.UIAbilityContext | null = null;
  private adoptDone: boolean = false;
  private expectedSize: number = 0;
  private speedBps: number = 0;
  private lastReceived: number = 0;
  private lastTime: number = 0;
  private lastUiAt: number = 0;
  private phaseTimer: number = -1;

  aboutToAppear(): void {
    const ctx = this.getUIContext().getHostContext() as common.UIAbilityContext;
    this.ctx = ctx;
    this.repo = new GameRepository(ctx);
    this.cheatMasterOn = isMasterEnabled();
    // 清理上次遗留的完成/失败通知（不动下载任务的进度通知）
    Notifier.cancelOwn();
    this.init();
    this.maybeAutoCheckAppUpdate();
  }

  aboutToDisappear(): void {
    this.clearPhaseTimer();
  }

  /** 取消/退出流程时清掉过渡计时器，避免延迟切到错误阶段 */
  private clearPhaseTimer(): void {
    if (this.phaseTimer >= 0) {
      clearTimeout(this.phaseTimer);
      this.phaseTimer = -1;
    }
  }

  /** 下载完成：先播「波浪收缩」，过渡播完再切「解压中」 */
  private enterExtracting(): void {
    this.prepPhase = PrepPhase.DOWNLOADED;
    this.stage = Stage.Extracting;
    this.clearPhaseTimer();
    this.phaseTimer = setTimeout(() => {
      this.phaseTimer = -1;
      this.prepPhase = PrepPhase.EXTRACTING;
    }, SHRINK_MS);
  }

  /** 解压完成：先播「脉冲爆发」，播完再进就绪页 */
  private enterReady(repo: GameRepository): void {
    this.prepPhase = PrepPhase.READY;
    // 资源刚解压完成：清掉 Web 缓存，避免继续加载旧文件
    clearWebCache();
    this.version = repo.readVersion();
    Notifier.publishComplete();
    this.clearPhaseTimer();
    this.phaseTimer = setTimeout(() => {
      this.phaseTimer = -1;
      this.stage = Stage.Ready;
    }, BURST_MS);
  }

  /** 下载流程的标题（随动画阶段变化） */
  private flowTitle(): string {
    switch (this.prepPhase) {
      case PrepPhase.DOWNLOADED:  return '下载完成';
      case PrepPhase.EXTRACTING:  return '正在解压离线资源…';
      case PrepPhase.READY:       return '资源准备就绪';
      default:                    return this.paused ? '已暂停' : '正在下载离线资源';
    }
  }

  onPageShow(): void {
    // 首页底部安全区留白用白色
    setWindowBackground(HOME_BG_COLOR);
    // 首页始终保持竖屏（游戏页可能已切换为横屏/跟随系统）
    setOrientation(window.Orientation.PORTRAIT);
    // 刷新参数调整入口可见性（可能在设置页开启了总开关）
    this.cheatMasterOn = isMasterEnabled();
    // 从设置页返回：更新标记优先，其次刷新就绪态（核心包可能已被删除）
    const pending: boolean | undefined = AppStorage.get<boolean>('pendingUpdate');
    if (pending === true) {
      AppStorage.setOrCreate('pendingUpdate', false);
      this.startDownload();
      return;
    }
    this.refreshReadyState();
  }

  private init(): void {
    const repo: GameRepository | null = this.repo;
    if (repo === null) {
      return;
    }
    if (repo.hasGameFiles()) {
      this.version = repo.readVersion();
      this.stage = Stage.Ready;
      return;
    }
    // 先取资源包大小（用于进度与完成判定），再尝试接管上次未完成的任务
    repo.resolveRelease().then((info: ReleaseInfo) => {
      this.expectedSize = info.assetSize;
      // 大小未知（调试包 / API 缺字段）时留空，界面回退到「约 531 MB」
      if (info.assetSize > 0) {
        this.totalText = formatBytes(info.assetSize);
      }
      this.tryAdopt();
    }).catch((err: BusinessError) => {
      hilog.warn(DOMAIN, TAG, 'init fetchRelease failed: %{public}s', JSON.stringify(err));
      this.tryAdopt();
    });
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

  private tryAdopt(): void {
    const repo: GameRepository | null = this.repo;
    if (repo === null) {
      return;
    }
    repo.adoptDownload(
      (received: number, total: number) => {
        this.applyProgress(received, total, this.expectedSize);
      },
      () => {
        this.enterExtracting();
      },
      () => {
        this.adoptDone = true;
        this.enterReady(repo);
      },
      (msg: string) => {
        this.adoptDone = true;
        this.clearPhaseTimer();
        this.prepPhase = PrepPhase.IDLE;
        this.error = msg;
        if (repo.hasGameFiles()) {
          this.stage = Stage.Ready;
        } else {
          this.enterNotDownloaded();
        }
      },
      this.expectedSize
    ).then((adopted: boolean) => {
      // 只在还没被事件推进过阶段时接管，避免与 onExtractStart/onDone 抢状态
      if (adopted && !this.adoptDone && this.stage === Stage.Checking) {
        this.stage = Stage.Downloading;
        this.prepPhase = PrepPhase.DOWNLOADING;
        this.resetSpeed();
      } else if (!adopted) {
        this.enterNotDownloaded();
      }
    });
  }

  private applyProgress(received: number, total: number, fallbackTotal: number): void {
    const t: number = total > 0 ? total : fallbackTotal;
    const pct: number = t > 0 ? Math.min(100, Math.floor(received * 100 / t)) : 0;

    // 速度：指数平滑，避免抖动（每次都更新内部计数，保证速度准确）
    const now: number = Date.now();
    if (this.lastTime > 0 && received >= this.lastReceived) {
      const dt: number = (now - this.lastTime) / 1000;
      if (dt >= 0.3) {
        const inst: number = (received - this.lastReceived) / dt;
        this.speedBps = this.speedBps > 0 ? this.speedBps * 0.6 + inst * 0.4 : inst;
        this.lastReceived = received;
        this.lastTime = now;
      }
    } else {
      this.lastReceived = received;
      this.lastTime = now;
    }

    // 进度事件可能非常密集：节流 UI 刷新（暂停态与 100% 立即刷新）
    if (!this.paused && pct < 100 && now - this.lastUiAt < 150) {
      return;
    }
    this.lastUiAt = now;
    this.progress = pct;
    this.receivedText = formatBytes(received);
    this.totalText = t > 0 ? formatBytes(t) : '未知大小';

    if (this.paused) {
      this.speedText = '';
      this.etaText = '已暂停';
    } else if (this.speedBps > 1024) {
      this.probing = false;
      this.speedText = formatSpeed(this.speedBps);
      this.etaText = t > received ? '剩余' + formatDuration((t - received) / this.speedBps) : '';
    } else {
      // 还没测出速度：保持测速提示，避免中间出现空档
      this.speedText = '';
      this.etaText = this.probing ? '正在测速，选择最快的下载源…' : '连接中…';
    }
  }

  private resetSpeed(): void {
    this.speedBps = 0;
    this.lastReceived = 0;
    this.lastTime = 0;
    this.lastUiAt = 0;
    this.speedText = '';
    this.etaText = '';
  }

  /**
   * 下载核心包所需的存储空间。
   * 首次安装：zip + 解压后包 ≈ 2×；
   * 更新：解压到 staging 期间需同时保留旧包，zip + 旧包 + 新包 ≈ 3.2×。
   */
  private requiredStorage(): number {
    const size: number = this.expectedSize > 0 ? this.expectedSize : 531 * 1024 * 1024;
    const updating: boolean = this.repo !== null && this.repo.hasGameFiles();
    return size * (updating ? 3.2 : 2.4);
  }

  private requiredStorageText(): string {
    return formatBytes(this.requiredStorage());
  }

  /** 进入未下载态的准备：前置申请通知权限 + 存储空间预检 */
  private prepareNotDownloaded(): void {
    const ctx: common.UIAbilityContext | null = this.ctx;
    if (ctx !== null) {
      Notifier.requestPermission(ctx);
    }
    if (this.expectedSize > 0) {
      this.totalText = formatBytes(this.expectedSize);
    }
    try {
      const free: number = ctx !== null ? statfs.getFreeSizeSync(ctx.filesDir) : -1;
      if (free >= 0) {
        this.freeSizeText = formatBytes(free);
        this.storageInsufficient = free < this.requiredStorage();
      } else {
        this.storageInsufficient = false;
      }
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'getFreeSizeSync failed: %{public}s', JSON.stringify(err));
      this.storageInsufficient = false;
    }
  }

  /** 统一入口：切到未下载态并完成准备 */
  private enterNotDownloaded(): void {
    this.clearPhaseTimer();
    this.prepPhase = PrepPhase.IDLE;
    this.stage = Stage.NotDownloaded;
    this.prepareNotDownloaded();
  }

  /** 首次下载时提示一次「可切后台」（之后不再打扰） */
  private async maybeShowBgHint(): Promise<void> {
    const hint: string = '下载中可切后台，完成后会通知你';
    const ctx: common.UIAbilityContext | null = this.ctx;
    if (ctx === null) {
      promptAction.showToast({ message: hint });
      return;
    }
    try {
      const store: preferences.Preferences = await getStore(ctx);
      const shown: boolean = await store.get('bgHintShown', false) as boolean;
      if (!shown) {
        promptAction.showToast({ message: hint });
        await store.put('bgHintShown', true);
        await store.flush();
      }
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'show bg hint failed: %{public}s', JSON.stringify(err));
    }
  }

  /** 从设置页返回后刷新就绪态（核心包可能已被删除） */
  private refreshReadyState(): void {
    const repo: GameRepository | null = this.repo;
    if (repo === null || this.stage !== Stage.Ready) {
      return;
    }
    if (!repo.hasGameFiles()) {
      this.version = '';
      this.enterNotDownloaded();
      return;
    }
    this.version = repo.readVersion();
  }

  private startDownload(): void {
    const repo: GameRepository | null = this.repo;
    if (repo === null) {
      return;
    }
    this.clearPhaseTimer();
    this.stage = Stage.Downloading;
    this.prepPhase = PrepPhase.PROBING;
    this.progress = 0;
    this.error = '';
    this.paused = false;
    this.resetSpeed();
    // 测速选源阶段会停在 0% 几秒，给出明确提示
    this.probing = true;
    this.etaText = '正在测速，选择最快的下载源…';
    this.maybeShowBgHint();
    repo.resolveRelease()
      .then((info: ReleaseInfo) => {
        this.receivedText = '0 MB';
        this.totalText = formatBytes(info.assetSize);
        return repo.startDownload(
          info,
          (received: number, total: number) => {
            this.applyProgress(received, total, info.assetSize);
          },
          () => {
            this.enterExtracting();
          },
          (url: string, sourceIndex: number, sourceCount: number) => {
            // 选源完成、真正开始下载：测速呼吸圆展开成波浪
            if (this.prepPhase === PrepPhase.PROBING) {
              this.prepPhase = PrepPhase.DOWNLOADING;
            }
            // 下载源只写日志，不在界面上展示（测速提示保留到速度出现为止）
            let host: string = url;
            const schemeAt: number = url.indexOf('://');
            if (schemeAt >= 0) {
              const rest: string = url.substring(schemeAt + 3);
              const slash: number = rest.indexOf('/');
              host = slash >= 0 ? rest.substring(0, slash) : rest;
            }
            const kind: string = sourceIndex < sourceCount - 1 ? '镜像' : '直连';
            hilog.info(DOMAIN, TAG, 'download source %{public}d/%{public}d %{public}s %{public}s',
              sourceIndex + 1, sourceCount, kind, host);
          }
        );
      })
      .then(() => {
        this.enterReady(repo);
        promptAction.showToast({ message: '离线资源下载完成' });
      })
      .catch((err: BusinessError) => {
        if (err.message === 'cancelled') {
          if (!repo.hasGameFiles()) {
            this.enterNotDownloaded();
          }
          return;
        }
        this.clearPhaseTimer();
        this.prepPhase = PrepPhase.IDLE;
        hilog.error(DOMAIN, TAG, 'download flow failed: %{public}s', JSON.stringify(err));
        // BusinessError 的 message 可能为空，此时回退到错误码，避免出现「下载失败：」
        const detail: string = (err.message !== undefined && err.message.length > 0)
          ? err.message
          : (err.code !== undefined ? '错误码 ' + err.code : '未知错误');
        this.error = '下载失败：' + detail;
        Notifier.publishFailed(this.error);
        if (repo.hasGameFiles()) {
          this.stage = Stage.Ready;
        } else {
          this.enterNotDownloaded();
        }
      });
  }

  private togglePause(): void {
    const repo: GameRepository | null = this.repo;
    if (repo === null) {
      return;
    }
    if (this.paused) {
      repo.resumeDownload();
      this.paused = false;
      this.resetSpeed();
    } else {
      repo.pauseDownload();
      this.paused = true;
      this.speedText = '';
      this.etaText = '已暂停';
    }
  }

  /** 取消是重操作（会丢失已下载进度）：先二次确认 */
  private confirmCancel(): void {
    if (this.repo === null) {
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
        this.cancelDownload();
      }
    }).catch((err: BusinessError) => {
      hilog.warn(DOMAIN, TAG, 'showDialog failed: %{public}s', JSON.stringify(err));
    });
  }

  private cancelDownload(): void {
    const repo: GameRepository | null = this.repo;
    if (repo === null) {
      return;
    }
    repo.cancelDownload();
    this.clearPhaseTimer();
    this.prepPhase = PrepPhase.IDLE;
    this.progress = 0;
    this.receivedText = '';
    this.paused = false;
    if (repo.hasGameFiles()) {
      this.stage = Stage.Ready;
    } else {
      this.enterNotDownloaded();
    }
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

  @Builder
  notDownloadedView() {
    Column({ space: 16 }) {
      // 静止小圆环 = 动画的起点（呼吸峰值半径），点「开始下载」后原地开始呼吸，无跳变
      PulseProgress({ phase: PrepPhase.IDLE })
      Text('尚未安装离线资源').fontSize(18).fontWeight(FontWeight.Medium)

      // 资源包信息卡片
      Column({ space: 10 }) {
        Row() {
          Text('来源').fontSize(14).fontColor('#888888')
          Text('公开源').fontSize(14).fontColor('#333333')
        }
        .width('100%')
        .justifyContent(FlexAlign.SpaceBetween)

        Row() {
          Text('体积').fontSize(14).fontColor('#888888')
          Text(this.totalText.length > 0 ? this.totalText : '约 531 MB')
            .fontSize(14).fontColor('#333333')
        }
        .width('100%')
        .justifyContent(FlexAlign.SpaceBetween)

        Row() {
          Text('需要存储').fontSize(14).fontColor('#888888')
          Text(this.requiredStorageText()).fontSize(14).fontColor('#333333')
        }
        .width('100%')
        .justifyContent(FlexAlign.SpaceBetween)
      }
      .width('84%')
      .padding(16)
      .borderRadius(12)
      .backgroundColor('#F7F7F7')

      // 下载须知
      Column({ space: 6 }) {
        Text('· 下载中可切后台，完成后通知').fontSize(13).fontColor('#999999')
        Text('· 支持断点续传，中断可继续').fontSize(13).fontColor('#999999')
        Text('· 需要开启通知权限').fontSize(13).fontColor('#999999')
      }
      .width('84%')
      .alignItems(HorizontalAlign.Start)

      Button('开始下载')
        .width('84%')
        .height(52)
        .fontSize(17)
        .backgroundColor('#DA3838')
        .fontColor('#FFFFFF')
        .enabled(!this.storageInsufficient)
        .onClick(() => this.startDownload())

      if (this.storageInsufficient) {
        Text('存储空间不足：需要约 ' + this.requiredStorageText() + '，可用 ' + this.freeSizeText)
          .fontSize(13)
          .fontColor('#DA3838')
          .textAlign(TextAlign.Center)
          .padding({ left: 24, right: 24 })
      }
    }
    .width('100%')
  }

  /**
   * 下载 + 解压共用一个动画组件：只有阶段不变时组件才不被重建，
   * 波浪 → 收缩 → 呼吸 → 爆发 才能连贯播放。
   */
  @Builder
  downloadFlowView() {
    Column({ space: 16 }) {
      PulseProgress({ phase: this.prepPhase, paused: this.paused })
      Text(this.flowTitle()).fontSize(18).fontWeight(FontWeight.Medium)

      if (this.prepPhase === PrepPhase.DOWNLOADED
        || this.prepPhase === PrepPhase.EXTRACTING
        || this.prepPhase === PrepPhase.READY) {
        Text('请勿关闭应用').fontSize(13).fontColor('#888888')
      } else {
        // 进度条：从左往右填充；百分比居中，白色 + 深色描边（红/灰底都可见）
        Stack() {
          Stack({ alignContent: Alignment.Start }) {
            Row()
              .width('100%')
              .height(22)
              .borderRadius(11)
              .backgroundColor('#EEEEEE')
            Row()
              .width(Math.max(0, Math.min(100, this.progress)) + '%')
              .height(22)
              .borderRadius(11)
              .backgroundColor('#DA3838')
          }
          .width('100%')
          .height(22)

          Text(this.progress.toString() + '%')
            .fontSize(13)
            .fontWeight(FontWeight.Bold)
            .fontColor('#FFFFFF')
            .textShadow({ radius: 2, color: '#99000000', offsetX: 0, offsetY: 1 })
        }
        .width('80%')

        // 速度 · 剩余时间
        if (this.speedText.length > 0 || this.etaText.length > 0) {
          Text(this.speedText + (this.speedText.length > 0 && this.etaText.length > 0 ? ' · ' : '') + this.etaText)
            .fontSize(13)
            .fontColor('#DA3838')
        }

        // 一排两键：暂停/继续为主（实心，适中宽度），取消下载仅文字
        Row({ space: 16 }) {
          Button(this.paused ? '继续' : '暂停')
            .width(140)
            .height(44)
            .fontSize(16)
            .backgroundColor('#DA3838')
            .fontColor('#FFFFFF')
            .onClick(() => this.togglePause())
          Button('取消下载')
            .fontSize(14)
            .fontColor('#999999')
            .backgroundColor('#00000000')
            .onClick(() => this.confirmCancel())
        }
        .width('80%')
        .justifyContent(FlexAlign.Center)
        .margin({ top: 6 })
      }
    }
    .width('100%')
  }

  @Builder
  readyView() {
    Column({ space: 12 }) {
      Image($r('app.media.resource_icon'))
        .width(96)
        .height(96)
        .objectFit(ImageFit.Contain)
      Text('离线资源已就绪').fontSize(20).fontWeight(FontWeight.Medium)
      Text('版本 ' + (this.version.length > 0 ? normalizeVersion(this.version) : '未知'))
        .fontSize(13).fontColor('#888888')
      Button('开始运行')
        .width('70%')
        .height(54)
        .fontSize(18)
        .backgroundColor('#DA3838')
        .fontColor('#FFFFFF')
        .margin({ top: 16 })
        .onClick(() => this.startGame())
      // 参数调整入口：仅在设置里开启后显示；更改在开始游戏时生效
      if (this.cheatMasterOn) {
        Text('参数调整')
          .fontSize(14)
          .fontColor('#999999')
          .margin({ top: 4 })
          .onClick(() => this.startCheats())
      }
    }
    .width('100%')
  }

  build() {
    Stack() {
      Column() {
        // 右上角设置入口（仅就绪态显示）
        if (this.stage === Stage.Ready) {
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
          if (this.stage === Stage.Checking) {
            LoadingProgress().width(40).height(40)
          } else if (this.stage === Stage.NotDownloaded) {
            this.notDownloadedView()
          } else if (this.stage === Stage.Downloading || this.stage === Stage.Extracting) {
            this.downloadFlowView()
          } else {
            this.readyView()
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
