import { common } from '@kit.AbilityKit';
import { router, promptAction } from '@kit.ArkUI';
import { webview } from '@kit.ArkWeb';
import { BusinessError } from '@kit.BasicServicesKit';
import { preferences } from '@kit.ArkData';
import { statfs } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { GameRepository, ReleaseInfo, normalizeVersion } from '../model/GameRepository';
import { Notifier } from '../model/Notifier';
import { setWindowBackground, HOME_BG_COLOR } from '../model/WindowHolder';
import { PokeballLoader } from '../components/PokeballLoader';

const DOMAIN: number = 0x0000;
const TAG: string = 'Home';

enum Stage {
  Checking,
  NotDownloaded,
  Downloading,
  Extracting,
  Ready
}

function formatBytes(n: number): string {
  if (n <= 0) {
    return '0 B';
  }
  const gb: number = n / 1024 / 1024 / 1024;
  if (gb >= 1) {
    return gb.toFixed(1) + ' GB';
  }
  const mb: number = n / 1024 / 1024;
  if (mb >= 1) {
    return mb.toFixed(1) + ' MB';
  }
  return (n / 1024).toFixed(0) + ' KB';
}

/** 速度：8.2 MB/s / 356 KB/s */
function formatSpeed(bytesPerSec: number): string {
  if (bytesPerSec <= 0) {
    return '';
  }
  return formatBytes(bytesPerSec) + '/s';
}

/** 剩余时间（中文概数）：约 3 分钟 / 约 25 秒 */
function formatDuration(seconds: number): string {
  if (seconds <= 0 || !isFinite(seconds)) {
    return '';
  }
  if (seconds < 60) {
    return '约 ' + Math.max(1, Math.round(seconds)) + ' 秒';
  }
  const minutes: number = Math.round(seconds / 60);
  if (minutes < 60) {
    return '约 ' + minutes + ' 分钟';
  }
  const hours: number = Math.floor(minutes / 60);
  const rest: number = minutes % 60;
  return '约 ' + hours + ' 小时' + (rest > 0 ? ' ' + rest + ' 分钟' : '');
}

/**
 * 首页启动器：未下载 / 下载中 / 解压中 / 已就绪。
 * 核心路径：进入 → 下载核心包（已下载可跳过）→ 游玩。
 */
@Entry
@Component
struct Index {
  @State stage: Stage = Stage.Checking;
  @State progress: number = 0;
  @State receivedText: string = '';
  @State totalText: string = '';
  @State speedText: string = '';
  @State etaText: string = '';
  @State version: string = '';
  @State error: string = '';
  @State paused: boolean = false;
  @State probing: boolean = false;
  @State updateAvailable: boolean = false;
  @State checkingUpdate: boolean = false;
  @State deleting: boolean = false;
  @State storageInsufficient: boolean = false;
  @State freeSizeText: string = '';

  private repo: GameRepository | null = null;
  private ctx: common.UIAbilityContext | null = null;
  private adoptDone: boolean = false;
  private expectedSize: number = 0;
  private speedBps: number = 0;
  private lastReceived: number = 0;
  private lastTime: number = 0;

  aboutToAppear(): void {
    const ctx = this.getUIContext().getHostContext() as common.UIAbilityContext;
    this.ctx = ctx;
    this.repo = new GameRepository(ctx);
    // 清理上次遗留的通知（下载进度/完成等）
    Notifier.cancelAll();
    this.init();
  }

  onPageShow(): void {
    // 首页底部安全区留白用白色
    setWindowBackground(HOME_BG_COLOR);
  }

  private init(): void {
    const repo: GameRepository | null = this.repo;
    if (repo === null) {
      return;
    }
    if (repo.hasGameFiles()) {
      this.version = repo.readVersion();
      this.stage = Stage.Ready;
      this.autoCheckUpdate();
      return;
    }
    // 先取资源包大小（用于进度与完成判定），再尝试接管上次未完成的任务
    repo.fetchRelease().then((info: ReleaseInfo) => {
      this.expectedSize = info.assetSize;
      this.totalText = formatBytes(info.assetSize);
      this.tryAdopt();
    }).catch((err: BusinessError) => {
      hilog.warn(DOMAIN, TAG, 'init fetchRelease failed: %{public}s', JSON.stringify(err));
      this.tryAdopt();
    });
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
        this.stage = Stage.Extracting;
      },
      () => {
        this.adoptDone = true;
        this.version = repo.readVersion();
        this.stage = Stage.Ready;
        Notifier.publishComplete();
        this.autoCheckUpdate();
      },
      (msg: string) => {
        this.adoptDone = true;
        this.error = msg;
        if (repo.hasGameFiles()) {
          this.stage = Stage.Ready;
        } else {
          this.enterNotDownloaded();
        }
      },
      this.expectedSize
    ).then((adopted: boolean) => {
      if (adopted && !this.adoptDone) {
        this.stage = Stage.Downloading;
        this.resetSpeed();
      } else if (!adopted) {
        this.enterNotDownloaded();
      }
    });
  }

  private applyProgress(received: number, total: number, fallbackTotal: number): void {
    const t: number = total > 0 ? total : fallbackTotal;
    this.progress = t > 0 ? Math.min(100, Math.floor(received * 100 / t)) : 0;
    this.receivedText = formatBytes(received);
    this.totalText = t > 0 ? formatBytes(t) : '未知大小';

    // 速度：指数平滑，避免抖动
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
    this.speedText = '';
    this.etaText = '';
  }

  /** 下载核心包所需的存储空间（zip + 解压后余量） */
  private requiredStorage(): number {
    const size: number = this.expectedSize > 0 ? this.expectedSize : 531 * 1024 * 1024;
    return size * 2.4;
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
      const store: preferences.Preferences = await preferences.getPreferences(ctx, 'pokerogue_settings');
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

  /** 每次打开 App 自动检查更新（无弹窗、无提示文字） */
  private autoCheckUpdate(): void {
    const repo: GameRepository | null = this.repo;
    if (repo === null || this.checkingUpdate) {
      return;
    }
    this.checkingUpdate = true;
    repo.fetchRelease()
      .then((info: ReleaseInfo) => {
        this.updateAvailable = normalizeVersion(info.tag) !== normalizeVersion(this.version);
        this.checkingUpdate = false;
      })
      .catch((err: BusinessError) => {
        this.checkingUpdate = false;
        hilog.warn(DOMAIN, TAG, 'auto check update failed: %{public}s', JSON.stringify(err));
      });
  }

  private startDownload(): void {
    const repo: GameRepository | null = this.repo;
    if (repo === null) {
      return;
    }
    this.stage = Stage.Downloading;
    this.progress = 0;
    this.error = '';
    this.paused = false;
    this.resetSpeed();
    // 测速选源阶段会停在 0% 几秒，给出明确提示
    this.probing = true;
    this.etaText = '正在测速，选择最快的下载源…';
    this.maybeShowBgHint();
    repo.fetchRelease()
      .then((info: ReleaseInfo) => {
        this.receivedText = '0 MB';
        this.totalText = formatBytes(info.assetSize);
        return repo.startDownload(
          info,
          (received: number, total: number) => {
            this.applyProgress(received, total, info.assetSize);
          },
          () => {
            this.stage = Stage.Extracting;
          },
          (url: string, sourceIndex: number, sourceCount: number) => {
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
        this.version = repo.readVersion();
        this.updateAvailable = false;
        this.stage = Stage.Ready;
        Notifier.publishComplete();
        promptAction.showToast({ message: '核心包下载完成' });
      })
      .catch((err: BusinessError) => {
        if (err.message === 'cancelled') {
          if (!repo.hasGameFiles()) {
            this.enterNotDownloaded();
          }
          return;
        }
        hilog.error(DOMAIN, TAG, 'download flow failed: %{public}s', JSON.stringify(err));
        this.error = '下载失败：' + (err.message !== undefined ? err.message : '未知错误');
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
    this.progress = 0;
    this.receivedText = '';
    this.paused = false;
    if (repo.hasGameFiles()) {
      this.stage = Stage.Ready;
    } else {
      this.enterNotDownloaded();
    }
  }

  /** 删除是危险操作：先确认，再异步删除并显示进度 */
  private deleteGame(): void {
    if (this.deleting || this.repo === null) {
      return;
    }
    promptAction.showDialog({
      title: '删除本地数据',
      message: '将删除已下载的游戏核心包（约 531MB），游戏存档不受影响。确定删除吗？',
      buttons: [
        { text: '取消', color: '#666666' },
        { text: '删除', color: '#DA3838' }
      ]
    }).then((result: promptAction.ShowDialogSuccessResponse) => {
      if (result.index === 1) {
        this.doDelete();
      }
    }).catch((err: BusinessError) => {
      hilog.warn(DOMAIN, TAG, 'showDialog failed: %{public}s', JSON.stringify(err));
    });
  }

  private async doDelete(): Promise<void> {
    const repo: GameRepository | null = this.repo;
    if (repo === null || this.deleting) {
      return;
    }
    this.deleting = true;
    this.error = '';
    try {
      await repo.clear();
      this.version = '';
      this.updateAvailable = false;
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'clear failed: %{public}s', JSON.stringify(err));
      this.error = '删除失败';
    }
    this.deleting = false;
    this.init();
  }

  /**
   * 刷新 Web 缓存（HTTP 缓存 + JS 代码缓存）。
   * 手动改过游戏资源包后，缓存不刷新会继续用旧文件，这里强制清掉。
   */
  private refreshCache(): void {
    try {
      webview.WebviewController.removeAllCache(true);
      promptAction.showToast({ message: '缓存已刷新，下次进入游戏将重新加载资源' });
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'removeAllCache failed: %{public}s', JSON.stringify(err));
      promptAction.showToast({ message: '刷新缓存失败' });
    }
  }

  private startGame(): void {
    router.pushUrl({ url: 'pages/GamePage' }).catch((err: BusinessError) => {
      hilog.error(DOMAIN, TAG, 'pushUrl GamePage failed: %{public}s', JSON.stringify(err));
    });
  }

  @Builder
  notDownloadedView() {
    Column({ space: 16 }) {
      Image($r('app.media.pokeball'))
        .width(72)
        .height(72)
        .objectFit(ImageFit.Contain)
      Text('尚未安装游戏核心包').fontSize(18).fontWeight(FontWeight.Medium)

      // 资源包信息卡片
      Column({ space: 10 }) {
        Row() {
          Text('来源').fontSize(14).fontColor('#888888')
          Text('原版 PokeRogue').fontSize(14).fontColor('#333333')
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

      Button('下载核心包')
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

  @Builder
  downloadingView() {
    Column({ space: 16 }) {
      PokeballLoader({ mode: 'shake', active: !this.paused })
      Text(this.paused ? '已暂停' : '正在下载核心包').fontSize(18).fontWeight(FontWeight.Medium)

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
    .width('100%')
  }

  @Builder
  extractingView() {
    Column({ space: 14 }) {
      PokeballLoader({ mode: 'open', active: true })
      Text('正在解压核心包…').fontSize(18).fontWeight(FontWeight.Medium)
      Text('请勿关闭应用').fontSize(13).fontColor('#888888')
    }
    .width('100%')
  }

  @Builder
  readyView() {
    Column({ space: 12 }) {
      Text('🎮').fontSize(64)
      Text('核心包已就绪').fontSize(18).fontWeight(FontWeight.Medium)
      Text('版本 ' + (this.version.length > 0 ? normalizeVersion(this.version) : '未知'))
        .fontSize(13).fontColor('#888888')
      Button('开始游戏')
        .width('70%')
        .height(52)
        .fontSize(18)
        .onClick(() => this.startGame())
      Row({ space: 16 }) {
        Button(this.updateAvailable ? '可更新' : '已是最新')
          .fontSize(14)
          .height(38)
          .enabled(this.updateAvailable)
          .backgroundColor(this.updateAvailable ? '#DA3838' : '#EEEEEE')
          .fontColor(this.updateAvailable ? '#FFFFFF' : '#999999')
          .onClick(() => {
            if (this.updateAvailable) {
              this.startDownload();
            }
          })
        Button(this.deleting ? '删除中…' : '删除数据')
          .fontSize(14)
          .height(38)
          .enabled(!this.deleting)
          .backgroundColor('#EEEEEE')
          .fontColor(this.deleting ? '#999999' : '#333333')
          .onClick(() => this.deleteGame())
      }
      Button('刷新缓存')
        .fontSize(13)
        .height(34)
        .backgroundColor('#F5F5F5')
        .fontColor('#888888')
        .onClick(() => this.refreshCache())
    }
    .width('100%')
  }

  build() {
    Stack() {
      Column() {
        Column() {
          if (this.stage === Stage.Checking) {
            LoadingProgress().width(40).height(40)
          } else if (this.stage === Stage.NotDownloaded) {
            this.notDownloadedView()
          } else if (this.stage === Stage.Downloading) {
            this.downloadingView()
          } else if (this.stage === Stage.Extracting) {
            this.extractingView()
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

      // 删除中的全屏遮罩，避免误操作和看起来没反应
      if (this.deleting) {
        Column({ space: 14 }) {
          Text('正在删除本地数据…').fontSize(18).fontWeight(FontWeight.Medium)
          LoadingProgress().width(48).height(48).color('#DA3838')
          Text('请勿关闭应用').fontSize(13).fontColor('#888888')
        }
        .width('100%')
        .height('100%')
        .justifyContent(FlexAlign.Center)
        .backgroundColor('#FFFFFF')
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#FFFFFF')
  }
}
