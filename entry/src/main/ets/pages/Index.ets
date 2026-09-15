import { common } from '@kit.AbilityKit';
import { router, promptAction } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { GameRepository, ReleaseInfo, normalizeVersion } from '../model/GameRepository';
import { Notifier } from '../model/Notifier';
import { DEBUG_UI } from '../common/Const';

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
  const mb: number = n / 1024 / 1024;
  if (mb >= 1) {
    return mb.toFixed(1) + ' MB';
  }
  return (n / 1024).toFixed(0) + ' KB';
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
  @State version: string = '';
  @State error: string = '';
  @State sourceText: string = '';
  @State paused: boolean = false;
  @State updateAvailable: boolean = false;
  @State checkingUpdate: boolean = false;

  private repo: GameRepository | null = null;
  private ctx: common.UIAbilityContext | null = null;
  private adoptDone: boolean = false;

  aboutToAppear(): void {
    const ctx = this.getUIContext().getHostContext() as common.UIAbilityContext;
    this.ctx = ctx;
    this.repo = new GameRepository(ctx);
    this.init();
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
    // 尝试接管上次未完成的后台下载任务
    repo.adoptDownload(
      (received: number, total: number) => {
        this.applyProgress(received, total, 0);
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
        this.stage = repo.hasGameFiles() ? Stage.Ready : Stage.NotDownloaded;
      }
    ).then((adopted: boolean) => {
      if (adopted && !this.adoptDone) {
        this.stage = Stage.Downloading;
        this.sourceText = '';
      } else if (!adopted) {
        this.stage = Stage.NotDownloaded;
      }
    });
  }

  private applyProgress(received: number, total: number, fallbackTotal: number): void {
    const t: number = total > 0 ? total : fallbackTotal;
    this.progress = t > 0 ? Math.floor(received * 100 / t) : 0;
    this.receivedText = formatBytes(received);
    this.totalText = t > 0 ? formatBytes(t) : '未知大小';
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
    this.sourceText = '正在测速选择下载源…';
    if (this.ctx !== null) {
      Notifier.requestPermission(this.ctx);
    }
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
            if (!DEBUG_UI) {
              this.sourceText = '';
              return;
            }
            let host: string = url;
            const schemeAt: number = url.indexOf('://');
            if (schemeAt >= 0) {
              const rest: string = url.substring(schemeAt + 3);
              const slash: number = rest.indexOf('/');
              host = slash >= 0 ? rest.substring(0, slash) : rest;
            }
            const kind: string = sourceIndex < sourceCount - 1 ? '镜像' : '直连';
            this.sourceText = '[dev] ' + (sourceIndex + 1) + '/' + sourceCount + ' ' + kind + ' · ' + host;
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
          this.stage = repo.hasGameFiles() ? Stage.Ready : Stage.NotDownloaded;
          return;
        }
        hilog.error(DOMAIN, TAG, 'download flow failed: %{public}s', JSON.stringify(err));
        this.error = '下载失败：' + (err.message !== undefined ? err.message : '未知错误');
        Notifier.publishFailed(this.error);
        this.stage = repo.hasGameFiles() ? Stage.Ready : Stage.NotDownloaded;
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
    } else {
      repo.pauseDownload();
      this.paused = true;
    }
  }

  private cancelDownload(): void {
    const repo: GameRepository | null = this.repo;
    if (repo === null) {
      return;
    }
    repo.cancelDownload();
    this.progress = 0;
    this.receivedText = '';
    this.totalText = '';
    this.paused = false;
    this.stage = repo.hasGameFiles() ? Stage.Ready : Stage.NotDownloaded;
  }

  private deleteGame(): void {
    const repo: GameRepository | null = this.repo;
    if (repo === null) {
      return;
    }
    repo.clear();
    this.version = '';
    this.updateAvailable = false;
    this.init();
  }

  private startGame(): void {
    router.pushUrl({ url: 'pages/GamePage' }).catch((err: BusinessError) => {
      hilog.error(DOMAIN, TAG, 'pushUrl GamePage failed: %{public}s', JSON.stringify(err));
    });
  }

  @Builder
  notDownloadedView() {
    Column({ space: 12 }) {
      Text('🎮').fontSize(64)
      Text('尚未安装游戏核心包').fontSize(18).fontWeight(FontWeight.Medium)
      Text('首次游玩需先下载核心包').fontSize(14).fontColor('#888888')
      Text('原版 · 约 531 MB').fontSize(14).fontColor('#DA3838')
      Button('下载核心包')
        .width('70%')
        .height(48)
        .fontSize(17)
        .onClick(() => this.startDownload())
    }
    .width('100%')
  }

  @Builder
  downloadingView() {
    Column({ space: 14 }) {
      Text(this.paused ? '已暂停' : '正在下载核心包').fontSize(18).fontWeight(FontWeight.Medium)
      if (DEBUG_UI && this.sourceText.length > 0) {
        Text(this.sourceText).fontSize(12).fontColor('#AAAAAA')
      }
      Progress({ value: this.progress, total: 100, type: ProgressType.Linear })
        .width('80%')
        .height(8)
      Text(this.progress.toString() + '%').fontSize(22).fontWeight(FontWeight.Bold)
      Text(this.receivedText + ' / ' + this.totalText).fontSize(14).fontColor('#666666')
      Text('下载中可切后台，完成后会通知你').fontSize(12).fontColor('#AAAAAA')
      Row({ space: 16 }) {
        Button(this.paused ? '继续' : '暂停')
          .width(120)
          .height(40)
          .fontSize(15)
          .backgroundColor('#EEEEEE')
          .fontColor('#333333')
          .onClick(() => this.togglePause())
        Button('取消下载')
          .width(120)
          .height(40)
          .fontSize(15)
          .backgroundColor('#EEEEEE')
          .fontColor('#333333')
          .onClick(() => this.cancelDownload())
      }
      .margin({ top: 6 })
    }
    .width('100%')
  }

  @Builder
  extractingView() {
    Column({ space: 14 }) {
      Text('正在解压核心包…').fontSize(18).fontWeight(FontWeight.Medium)
      LoadingProgress().width(48).height(48).color('#DA3838')
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
        Button('删除数据')
          .fontSize(14)
          .height(38)
          .backgroundColor('#EEEEEE')
          .fontColor('#333333')
          .onClick(() => this.deleteGame())
      }
    }
    .width('100%')
  }

  build() {
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
  }
}
