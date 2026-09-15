import { common } from '@kit.AbilityKit';
import { router, promptAction } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { GameRepository, ReleaseInfo } from '../model/GameRepository';
import { Notifier } from '../model/Notifier';

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
 * 首页启动器：未下载 / 下载中 / 解压中 / 已就绪 四态。
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
  @State message: string = '';
  @State error: string = '';

  private repo: GameRepository | null = null;
  private ctx: common.UIAbilityContext | null = null;

  aboutToAppear(): void {
    const ctx = this.getUIContext().getHostContext() as common.UIAbilityContext;
    this.ctx = ctx;
    this.repo = new GameRepository(ctx);
    this.refresh();
  }

  private refresh(): void {
    if (this.repo === null) {
      return;
    }
    if (this.repo.hasGameFiles()) {
      this.version = this.repo.readVersion();
      this.stage = Stage.Ready;
    } else {
      this.stage = Stage.NotDownloaded;
    }
  }

  private startDownload(): void {
    if (this.repo === null) {
      return;
    }
    this.stage = Stage.Downloading;
    this.progress = 0;
    this.error = '';
    this.message = '';
    if (this.ctx !== null) {
      Notifier.requestPermission(this.ctx);
    }
    const repo: GameRepository = this.repo;
    repo.fetchRelease()
      .then((info: ReleaseInfo) => {
        this.receivedText = '0 MB';
        this.totalText = formatBytes(info.assetSize);
        return repo.downloadAndExtract(
          info,
          (received: number, total: number) => {
            const t: number = total > 0 ? total : info.assetSize;
            this.progress = t > 0 ? Math.floor(received * 100 / t) : 0;
            this.receivedText = formatBytes(received);
            this.totalText = formatBytes(t);
          },
          () => {
            this.stage = Stage.Extracting;
          }
        );
      })
      .then(() => {
        this.version = repo.readVersion();
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

  private cancelDownload(): void {
    if (this.repo === null) {
      return;
    }
    this.repo.cancelDownload();
    this.progress = 0;
    this.receivedText = '';
    this.totalText = '';
    this.stage = this.repo.hasGameFiles() ? Stage.Ready : Stage.NotDownloaded;
  }

  private checkUpdate(): void {
    if (this.repo === null) {
      return;
    }
    const repo: GameRepository = this.repo;
    repo.fetchRelease()
      .then((info: ReleaseInfo) => {
        if (info.tag !== this.version) {
          this.message = '发现新版本：' + info.tag;
          promptAction.showToast({ message: '发现新版本，点击下载核心包即可更新' });
        } else {
          this.message = '已是最新版本';
          promptAction.showToast({ message: '已是最新版本' });
        }
      })
      .catch((err: BusinessError) => {
        this.message = '检查更新失败';
        hilog.error(DOMAIN, TAG, 'check update failed: %{public}s', JSON.stringify(err));
      });
  }

  private deleteGame(): void {
    if (this.repo === null) {
      return;
    }
    this.repo.clear();
    this.version = '';
    this.message = '';
    this.refresh();
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
      Text('正在下载核心包').fontSize(18).fontWeight(FontWeight.Medium)
      Text('game.zip').fontSize(13).fontColor('#888888')
      Progress({ value: this.progress, total: 100, type: ProgressType.Linear })
        .width('80%')
        .height(8)
      Text(this.progress.toString() + '%').fontSize(22).fontWeight(FontWeight.Bold)
      Text(this.receivedText + ' / ' + this.totalText).fontSize(14).fontColor('#666666')
      Text('下载中可切后台，完成后会通知你').fontSize(12).fontColor('#AAAAAA')
      Button('取消下载')
        .width('50%')
        .height(40)
        .fontSize(15)
        .backgroundColor('#EEEEEE')
        .fontColor('#333333')
        .margin({ top: 6 })
        .onClick(() => this.cancelDownload())
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
      Text('版本 ' + (this.version.length > 0 ? this.version : '未知')).fontSize(13).fontColor('#888888')
      Button('开始游戏')
        .width('70%')
        .height(52)
        .fontSize(18)
        .onClick(() => this.startGame())
      Row({ space: 16 }) {
        Button('检查更新')
          .fontSize(14)
          .height(38)
          .backgroundColor('#EEEEEE')
          .fontColor('#333333')
          .onClick(() => this.checkUpdate())
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
      Row() {
        Text('PokeRogue').fontSize(20).fontWeight(FontWeight.Bold).layoutWeight(1)
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 10, bottom: 10 })

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
          Text(this.error).fontSize(13).fontColor('#DA3838').margin({ top: 16 }).padding({ left: 24, right: 24 })
        }
        if (this.message.length > 0) {
          Text(this.message).fontSize(13).fontColor('#666666').margin({ top: 16 })
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
