import { common } from '@kit.AbilityKit';
import { promptAction } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { preferences } from '@kit.ArkData';
import { statfs } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { GameRepository, ReleaseInfo } from './GameRepository';
import { Notifier } from './Notifier';
import { getStore } from '../common/Prefs';
import { clearWebCache } from '../common/WebCache';
import { formatBytes, formatSpeed, formatDuration, formatErrorDetail } from '../common/Format';
import { PrepPhase, SHRINK_MS, BURST_MS } from '../components/PulseProgress';

const DOMAIN: number = 0x0000;
const TAG: string = 'Launcher';

/** 首页阶段（与原 pages/Index 的 Stage 一致） */
export enum LauncherStage {
  Checking,
  NotDownloaded,
  Downloading,
  Extracting,
  Ready
}

/** 一次进度快照：页面据此刷新下载视图 */
export class ProgressView {
  progress: number = 0;
  receivedText: string = '';
  totalText: string = '';
  speedText: string = '';
  etaText: string = '';
}

/** 控制器 → 页面的状态回写 */
export interface LauncherCallbacks {
  setStage: (s: LauncherStage) => void;
  setPrepPhase: (p: PrepPhase) => void;
  setProgress: (v: ProgressView) => void;
  setPaused: (p: boolean) => void;
  setVersion: (v: string) => void;
  setError: (msg: string) => void;
  setTotalText: (t: string) => void;
  setStorage: (insufficient: boolean, freeText: string) => void;
  setAssetUrl: (url: string) => void;
}

/**
 * 首页下载器：把「下载/解压/接管/暂停/取消/导入/存储预检」等业务逻辑从页面抽离，
 * 页面只负责声明式渲染并把状态变化经回调写回 @State。
 */
export class LauncherSession {
  private ctx: common.UIAbilityContext;
  private repo: GameRepository;
  private cb: LauncherCallbacks;

  private stage: LauncherStage = LauncherStage.Checking;
  private prepPhase: PrepPhase = PrepPhase.IDLE;
  private paused: boolean = false;
  private probing: boolean = false;

  private expectedSize: number = 0;
  private speedBps: number = 0;
  private lastReceived: number = 0;
  private lastTime: number = 0;
  private lastUiAt: number = 0;
  private adoptDone: boolean = false;
  private phaseTimer: number = -1;

  /** 最近一次发出的进度，暂停时据此保留进度条数值 */
  private lastProgress: ProgressView = new ProgressView();

  constructor(ctx: common.UIAbilityContext, cb: LauncherCallbacks) {
    this.ctx = ctx;
    this.cb = cb;
    this.repo = new GameRepository(ctx);
  }

  dispose(): void {
    this.clearPhaseTimer();
  }

  /** 启动：有本地资源直接就绪，否则取包信息并尝试接管上次任务 */
  start(): void {
    if (this.repo.hasGameFiles()) {
      this.cb.setVersion(this.repo.readVersion());
      this.setStage(LauncherStage.Ready);
      return;
    }
    this.repo.resolveRelease().then((info: ReleaseInfo) => {
      this.applyReleaseInfo(info);
      this.tryAdopt();
    }).catch((err: BusinessError) => {
      hilog.warn(DOMAIN, TAG, 'init fetchRelease failed: %{public}s', JSON.stringify(err));
      this.tryAdopt();
    });
  }

  /** 从设置页返回后刷新就绪态（核心包可能已被删除） */
  refreshReadyState(): void {
    if (this.stage !== LauncherStage.Ready) {
      return;
    }
    if (!this.repo.hasGameFiles()) {
      this.cb.setVersion('');
      this.enterNotDownloaded();
      return;
    }
    this.cb.setVersion(this.repo.readVersion());
  }

  /** 校验端口就绪后由页面负责跳转；这里只暴露判定 */
  hasGameFiles(): boolean {
    return this.repo.hasGameFiles();
  }

  requiredStorageText(): string {
    return formatBytes(this.requiredStorage());
  }

  /** 下载流程的标题（随动画阶段变化） */
  flowTitle(): string {
    switch (this.prepPhase) {
      case PrepPhase.DOWNLOADED:  return '下载完成';
      case PrepPhase.EXTRACTING:  return '正在解压离线资源…';
      case PrepPhase.READY:       return '资源准备就绪';
      default:                    return this.paused ? '已暂停' : '正在下载离线资源';
    }
  }

  // ─────────────────────────── 下载 ───────────────────────────

  startDownload(): void {
    this.clearPhaseTimer();
    this.setStage(LauncherStage.Downloading);
    this.setPrepPhase(PrepPhase.PROBING);
    this.cb.setError('');
    this.paused = false;
    this.cb.setPaused(false);
    this.resetSpeed();
    // 测速选源阶段会停在 0% 几秒，给出明确提示
    this.probing = true;
    this.emitProgress(0, '',
      this.expectedSize > 0 ? formatBytes(this.expectedSize) : '',
      '', '正在测速，选择最快的下载源…');
    this.maybeShowBgHint();
    this.repo.resolveRelease()
      .then((info: ReleaseInfo) => {
        this.applyReleaseInfo(info);
        this.emitProgress(0, '0 MB', formatBytes(info.assetSize),
          '', '正在测速，选择最快的下载源…');
        return this.repo.startDownload(
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
              this.setPrepPhase(PrepPhase.DOWNLOADING);
            }
            // 下载源只写日志，不在界面上展示
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
        this.enterReady();
        promptAction.showToast({ message: '离线资源下载完成' });
      })
      .catch((err: BusinessError) => {
        if (err.message === 'cancelled') {
          if (!this.repo.hasGameFiles()) {
            this.enterNotDownloaded();
          }
          return;
        }
        this.clearPhaseTimer();
        this.setPrepPhase(PrepPhase.IDLE);
        hilog.error(DOMAIN, TAG, 'download flow failed: %{public}s', JSON.stringify(err));
        const msg: string = '下载失败：' + formatErrorDetail(err.message, err.code);
        this.cb.setError(msg);
        Notifier.publishFailed(msg);
        if (this.repo.hasGameFiles()) {
          this.setStage(LauncherStage.Ready);
        } else {
          this.enterNotDownloaded();
        }
      });
  }

  private applyReleaseInfo(info: ReleaseInfo): void {
    this.expectedSize = info.assetSize;
    this.cb.setAssetUrl(info.assetUrl);
    // 大小未知（调试包 / API 缺字段）时留空，界面回退到「约 531 MB」
    if (info.assetSize > 0) {
      this.cb.setTotalText(formatBytes(info.assetSize));
    }
  }

  private tryAdopt(): void {
    this.repo.adoptDownload(
      (received: number, total: number) => {
        this.applyProgress(received, total, this.expectedSize);
      },
      () => {
        this.enterExtracting();
      },
      () => {
        this.adoptDone = true;
        this.enterReady();
      },
      (msg: string) => {
        this.adoptDone = true;
        this.clearPhaseTimer();
        this.setPrepPhase(PrepPhase.IDLE);
        this.cb.setError(msg);
        if (this.repo.hasGameFiles()) {
          this.setStage(LauncherStage.Ready);
        } else {
          this.enterNotDownloaded();
        }
      },
      this.expectedSize
    ).then((adopted: boolean) => {
      // 只在还没被事件推进过阶段时接管，避免与 onExtractStart/onDone 抢状态
      if (adopted && !this.adoptDone && this.stage === LauncherStage.Checking) {
        this.setStage(LauncherStage.Downloading);
        this.setPrepPhase(PrepPhase.DOWNLOADING);
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

    let speedText: string = '';
    let etaText: string = '';
    if (this.paused) {
      etaText = '已暂停';
    } else if (this.speedBps > 1024) {
      this.probing = false;
      speedText = formatSpeed(this.speedBps);
      etaText = t > received ? '剩余' + formatDuration((t - received) / this.speedBps) : '';
    } else {
      // 还没测出速度：保持测速提示，避免中间出现空档
      etaText = this.probing ? '正在测速，选择最快的下载源…' : '连接中…';
    }
    this.emitProgress(pct, formatBytes(received), t > 0 ? formatBytes(t) : '未知大小',
      speedText, etaText);
  }

  private resetSpeed(): void {
    this.speedBps = 0;
    this.lastReceived = 0;
    this.lastTime = 0;
    this.lastUiAt = 0;
  }

  private emitProgress(progress: number, receivedText: string, totalText: string,
    speedText: string, etaText: string): void {
    // 复用同一对象：进度刷新每 150ms 触发一次，避免高频 new 造成 GC 抖动
    const v: ProgressView = this.lastProgress;
    v.progress = progress;
    v.receivedText = receivedText;
    v.totalText = totalText;
    v.speedText = speedText;
    v.etaText = etaText;
    this.cb.setProgress(v);
  }

  pauseToggle(): void {
    if (this.paused) {
      this.repo.resumeDownload();
      this.paused = false;
      this.cb.setPaused(false);
      this.resetSpeed();
    } else {
      this.repo.pauseDownload();
      this.paused = true;
      this.cb.setPaused(true);
      const p: ProgressView = this.lastProgress;
      this.emitProgress(p.progress, p.receivedText, p.totalText, '', '已暂停');
    }
  }

  cancelDownload(): void {
    this.repo.cancelDownload();
    this.clearPhaseTimer();
    this.setPrepPhase(PrepPhase.IDLE);
    this.paused = false;
    this.cb.setPaused(false);
    this.emitProgress(0, '', this.lastProgress.totalText, '', '');
    if (this.repo.hasGameFiles()) {
      this.setStage(LauncherStage.Ready);
    } else {
      this.enterNotDownloaded();
    }
  }

  // ─────────────────────────── 导入 / 解压 ───────────────────────────

  /** 导入本地资源包：复用解压/原子切换流程，进度界面与自动下载一致 */
  installFromZip(uri: string): void {
    this.clearPhaseTimer();
    this.cb.setError('');
    this.setStage(LauncherStage.Extracting);
    this.setPrepPhase(PrepPhase.EXTRACTING);
    promptAction.showToast({ message: '正在导入资源包…' });
    this.repo.installFromZip(uri).then(() => {
      this.enterReady();
      promptAction.showToast({ message: '资源包导入完成' });
    }).catch((err: BusinessError) => {
      hilog.error(DOMAIN, TAG, 'import resource failed: %{public}s', JSON.stringify(err));
      this.cb.setError('导入失败：' + formatErrorDetail(err.message, err.code));
      this.clearPhaseTimer();
      this.setPrepPhase(PrepPhase.IDLE);
      if (this.repo.hasGameFiles()) {
        this.setStage(LauncherStage.Ready);
      } else {
        this.enterNotDownloaded();
      }
    });
  }

  /** 下载完成：先播「波浪收缩」，过渡播完再切「解压中」 */
  private enterExtracting(): void {
    this.setPrepPhase(PrepPhase.DOWNLOADED);
    this.setStage(LauncherStage.Extracting);
    this.clearPhaseTimer();
    this.phaseTimer = setTimeout(() => {
      this.phaseTimer = -1;
      this.setPrepPhase(PrepPhase.EXTRACTING);
    }, SHRINK_MS);
  }

  /** 解压完成：先播「脉冲爆发」，播完再进就绪页 */
  private enterReady(): void {
    this.setPrepPhase(PrepPhase.READY);
    // 资源刚解压完成：清掉 Web 缓存，避免继续加载旧文件
    clearWebCache();
    this.cb.setVersion(this.repo.readVersion());
    Notifier.publishComplete();
    this.clearPhaseTimer();
    this.phaseTimer = setTimeout(() => {
      this.phaseTimer = -1;
      this.setStage(LauncherStage.Ready);
    }, BURST_MS);
  }

  // ─────────────────────────── 存储 / 辅助 ───────────────────────────

  /**
   * 下载核心包所需的存储空间。
   * 首次安装：zip + 解压后包 ≈ 2×；
   * 更新：解压到 staging 期间需同时保留旧包，zip + 旧包 + 新包 ≈ 3.2×。
   */
  private requiredStorage(): number {
    const size: number = this.expectedSize > 0 ? this.expectedSize : 531 * 1024 * 1024;
    const updating: boolean = this.repo.hasGameFiles();
    return size * (updating ? 3.2 : 2.4);
  }

  /** 进入未下载态的准备：前置申请通知权限 + 存储空间预检（异步，不阻塞主线程） */
  private prepareNotDownloaded(): void {
    Notifier.requestPermission(this.ctx);
    if (this.expectedSize > 0) {
      this.cb.setTotalText(formatBytes(this.expectedSize));
    }
    statfs.getFreeSize(this.ctx.filesDir).then((free: number) => {
      this.cb.setStorage(free < this.requiredStorage(), formatBytes(free));
    }).catch((err: BusinessError) => {
      hilog.warn(DOMAIN, TAG, 'getFreeSize failed: %{public}s', JSON.stringify(err));
      this.cb.setStorage(false, '');
    });
  }

  /** 统一入口：切到未下载态并完成准备 */
  private enterNotDownloaded(): void {
    this.clearPhaseTimer();
    this.setPrepPhase(PrepPhase.IDLE);
    this.setStage(LauncherStage.NotDownloaded);
    this.prepareNotDownloaded();
  }

  /** 首次下载时提示一次「可切后台」（之后不再打扰） */
  private async maybeShowBgHint(): Promise<void> {
    const hint: string = '下载中可切后台，完成后会通知你';
    try {
      const store: preferences.Preferences = await getStore(this.ctx);
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

  // ─────────────────────────── 内部状态 ───────────────────────────

  private setStage(s: LauncherStage): void {
    this.stage = s;
    this.cb.setStage(s);
  }

  private setPrepPhase(p: PrepPhase): void {
    this.prepPhase = p;
    this.cb.setPrepPhase(p);
  }

  private clearPhaseTimer(): void {
    if (this.phaseTimer >= 0) {
      clearTimeout(this.phaseTimer);
      this.phaseTimer = -1;
    }
  }
}