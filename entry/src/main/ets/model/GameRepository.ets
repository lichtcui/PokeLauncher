import { request, zlib, BusinessError, deviceInfo } from '@kit.BasicServicesKit';
import { http } from '@kit.NetworkKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { common, wantAgent } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import {
  RELEASE_API, USER_AGENT, DEFAULT_ASSET, GAME_DIR_NAME, DOWNLOAD_MIRRORS, API_MIRROR,
  DEBUG_ASSET_URL
} from '../common/Const';
import { requestText } from '../common/Http';

const DOMAIN: number = 0x0000;
const TAG: string = 'GameRepository';

/** 速度探测读取的字节数 */
const PROBE_BYTES: number = 131072;

/**
 * 单个源的测速上限。rankSources 用 Promise.all 等全部结果，
 * 若某镜像卡住，没有上限时要等满 readTimeout（15s）才开始下载。
 */
const PROBE_TIMEOUT_MS: number = 4000;

/** GitHub Release 中的一个资产 */
class ReleaseAsset {
  name: string = '';
  browser_download_url: string = '';
  size: number = 0;
}

/** GitHub Release 响应（仅取需要的字段） */
class ReleaseData {
  tag_name: string = '';
  assets: ReleaseAsset[] = [];
}

/** 对外暴露的资源包信息 */
export class ReleaseInfo {
  tag: string = '';
  assetName: string = '';
  assetUrl: string = '';
  assetSize: number = 0;
}

/** 去掉 release- 前缀，便于比较（本地 zip 内 currentVersion.txt 只存 commit） */
export function normalizeVersion(v: string): string {
  if (v.startsWith('release-')) {
    return v.substring(8);
  }
  return v;
}

/**
 * Release 结果缓存（模块级）：首页与设置页各自 new 一个 GameRepository，
 * 用实例缓存会各请求一次 GitHub API；共享缓存后同一 asset 只查一次。
 */
let cachedRelease: ReleaseInfo | null = null;

/** 测速结果短 TTL：同日多次下载（重试/更新）不必重复探测同一组镜像 */
const RANK_TTL_MS: number = 5 * 60 * 1000;
let rankCacheKey: string = '';
let rankCacheAt: number = 0;
let rankCacheUrls: string[] = [];

/** 下载失败说明该排序已不可信，丢弃缓存让下次重新测速 */
function invalidateRankCache(): void {
  rankCacheKey = '';
  rankCacheUrls = [];
}

/**
 * 离线资源包仓库：负责下载、解压、删除、版本记录。
 * 数据目录：<sandbox>/files/game
 * 临时 zip：<sandbox>/cache/game.zip
 */
export class GameRepository {
  private ctx: common.UIAbilityContext;
  private task: request.agent.Task | null = null;
  private tid: string = '';
  private cancelled: boolean = false;
  private paused: boolean = false;
  private pollTimer: number = -1;

  constructor(ctx: common.UIAbilityContext) {
    this.ctx = ctx;
  }

  get gameDir(): string {
    return this.ctx.filesDir + '/' + GAME_DIR_NAME;
  }

  get zipPath(): string {
    return this.ctx.cacheDir + '/game.zip';
  }

  get versionFile(): string {
    return this.gameDir + '/currentVersion.txt';
  }

  /** 解压暂存目录：解压完成后再原子切换到 gameDir */
  get stagingDir(): string {
    return this.ctx.filesDir + '/' + GAME_DIR_NAME + '.new';
  }

  /** 切换时的旧包备份目录，切换成功后异步删除 */
  get backupDir(): string {
    return this.ctx.filesDir + '/' + GAME_DIR_NAME + '.old';
  }

  /** 解压完成标记：只有带此标记的 staging 才是完整包，可用于恢复 */
  get stagingMarker(): string {
    return this.stagingDir + '/.extract-complete';
  }

  /** 是否已安装核心包（以 index.html 是否存在为准） */
  hasGameFiles(): boolean {
    try {
      return fs.accessSync(this.gameDir + '/index.html');
    } catch (err) {
      return false;
    }
  }

  /** 读取本地已安装版本号；不存在返回空串 */
  readVersion(): string {
    try {
      return fs.readTextSync(this.versionFile).trim();
    } catch (err) {
      return '';
    }
  }

  /**
   * 取资源包信息。DEBUG_ASSET_URL 非空时直接用它（本地小测试包），否则查 GitHub Release。
   */
  async resolveRelease(force: boolean = false): Promise<ReleaseInfo> {
    if (DEBUG_ASSET_URL.length > 0) {
      hilog.warn(DOMAIN, TAG, 'DEBUG_ASSET_URL in use: %{public}s', DEBUG_ASSET_URL);
      const info: ReleaseInfo = new ReleaseInfo();
      info.tag = 'debug';
      info.assetName = 'debug-pack.zip';
      info.assetUrl = DEBUG_ASSET_URL;
      info.assetSize = 0;
      return info;
    }
    if (!force && cachedRelease !== null) {
      return cachedRelease;
    }
    const info: ReleaseInfo = await this.fetchRelease(DEFAULT_ASSET);
    cachedRelease = info;
    return info;
  }

  /** 查询 Release 中指定资产的信息（先直连 GitHub API，失败回退 API 镜像） */
  async fetchRelease(assetName: string = DEFAULT_ASSET): Promise<ReleaseInfo> {
    const candidates: string[] = [RELEASE_API, API_MIRROR + RELEASE_API];
    let lastErr: Error | null = null;
    for (let i = 0; i < candidates.length; i++) {
      const apiUrl: string = candidates[i];
      try {
        const text: string = await requestText(apiUrl, 'application/vnd.github+json');
        const data: ReleaseData = JSON.parse(text) as ReleaseData;
        const asset: ReleaseAsset | undefined =
          data.assets.find((a: ReleaseAsset) => a.name === assetName);
        if (asset === undefined) {
          throw new Error('asset not found: ' + assetName);
        }
        const info: ReleaseInfo = new ReleaseInfo();
        info.tag = data.tag_name;
        info.assetName = asset.name;
        info.assetUrl = asset.browser_download_url;
        info.assetSize = asset.size;
        return info;
      } catch (err) {
        lastErr = err as Error;
        hilog.warn(DOMAIN, TAG, 'fetchRelease via %{public}s failed: %{public}s',
          apiUrl, JSON.stringify(err));
      }
    }
    throw lastErr !== null ? lastErr : new Error('fetchRelease failed');
  }

  /** 构造下载候选地址：镜像优先，最后回退 GitHub 直连 */
  private buildDownloadUrls(assetUrl: string): string[] {
    // 本地调试包不走镜像（地址本身就是可达的）
    if (DEBUG_ASSET_URL.length > 0 && assetUrl === DEBUG_ASSET_URL) {
      return [assetUrl];
    }
    const urls: string[] = [];
    for (let i = 0; i < DOWNLOAD_MIRRORS.length; i++) {
      urls.push(DOWNLOAD_MIRRORS[i] + assetUrl);
    }
    urls.push(assetUrl);
    return urls;
  }

  /**
   * 并发探测各源速度，按“快 → 慢 → 失败”排序。
   * 最后一个候选是 GitHub 直连兜底：不参与测速（白读 128KB 且通常最慢），
   * 镜像都不可用时才会用到它。
   */
  private async rankSources(urls: string[]): Promise<string[]> {
    const key: string = urls.join('|');
    if (rankCacheKey === key && Date.now() - rankCacheAt < RANK_TTL_MS) {
      hilog.info(DOMAIN, TAG, '%{public}s', 'rank cache hit');
      return rankCacheUrls.slice();
    }
    const hasFallback: boolean = urls.length > 1;
    const targets: string[] = hasFallback ? urls.slice(0, urls.length - 1) : urls;
    const probes: Promise<number>[] = [];
    for (let i = 0; i < targets.length; i++) {
      probes.push(this.probe(targets[i]));
    }
    const speeds: number[] = await Promise.all(probes);
    const idx: number[] = [];
    for (let i = 0; i < targets.length; i++) {
      idx.push(i);
    }
    idx.sort((a: number, b: number) => speeds[b] - speeds[a]);
    const sorted: string[] = [];
    for (let i = 0; i < idx.length; i++) {
      sorted.push(targets[idx[i]]);
      // 注意：hilog 不支持 %f，必须用 %s + 格式化后的字符串
      hilog.info(DOMAIN, TAG, 'source[%{public}d] speed=%{public}s KB/ms',
        idx[i], (speeds[idx[i]] / 1024).toFixed(2));
    }
    if (hasFallback) {
      sorted.push(urls[urls.length - 1]);
    }
    rankCacheKey = key;
    rankCacheAt = Date.now();
    rankCacheUrls = sorted.slice();
    return sorted;
  }

  /** 返回 bytes/ms；失败或超时返回 -1 */
  private probe(url: string): Promise<number> {
    const req: http.HttpRequest = http.createHttp();
    const start: number = Date.now();
    return new Promise<number>((resolve: (v: number) => void) => {
      let settled: boolean = false;
      let timer: number = -1;
      const finish = (v: number): void => {
        if (settled) {
          return;
        }
        settled = true;
        if (timer >= 0) {
          clearTimeout(timer);
        }
        try {
          req.destroy();
        } catch (err) {
          hilog.warn(DOMAIN, TAG, 'probe destroy failed: %{public}s', JSON.stringify(err));
        }
        resolve(v);
      };
      // 全局上限：某个源卡住时不再拖慢整体测速
      timer = setTimeout(() => finish(-1), PROBE_TIMEOUT_MS);
      req.request(url, {
        method: http.RequestMethod.GET,
        header: {
          'User-Agent': USER_AGENT,
          'Range': 'bytes=0-' + (PROBE_BYTES - 1)
        },
        connectTimeout: 6000,
        readTimeout: 15000,
        // 兜底：若镜像忽略 Range 返回 200 全量，maxLimit 会在 PROBE_BYTES 处截断，
        // 避免测速时把整个 531MB 资源包拉下来，同时耗时仍只反映 PROBE_BYTES 的下载速度。
        maxLimit: PROBE_BYTES,
        expectDataType: http.HttpDataType.ARRAY_BUFFER
      }).then((resp: http.HttpResponse) => {
        const elapsed: number = Date.now() - start;
        if (resp.responseCode >= 200 && resp.responseCode < 400 && elapsed > 0) {
          const buf: ArrayBuffer = resp.result as ArrayBuffer;
          const len: number = buf !== undefined ? buf.byteLength : 0;
          finish(len > 0 ? len / elapsed : -1);
          return;
        }
        finish(-1);
      }).catch((err: Error) => {
        finish(-1);
      });
    });
  }

  private removePartialZip(): void {
    try {
      if (fs.accessSync(this.zipPath)) {
        fs.unlinkSync(this.zipPath);
      }
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'remove partial zip failed: %{public}s', JSON.stringify(err));
    }
  }

  /**
   * 新下载：探测选源 → 依次尝试 → 下载完成后解压。
   */
  async startDownload(
    info: ReleaseInfo,
    onProgress: (received: number, total: number) => void,
    onExtractStart: () => void,
    onSourceChange: (url: string, sourceIndex: number, sourceCount: number) => void
  ): Promise<void> {
    this.cancelled = false;
    this.paused = false;
    this.stopPolling();
    const urls: string[] = await this.rankSources(this.buildDownloadUrls(info.assetUrl));
    let lastErr: Error | null = null;
    for (let i = 0; i < urls.length; i++) {
      if (this.cancelled) {
        return;
      }
      onSourceChange(urls[i], i, urls.length);
      this.removePartialZip();
      try {
        await this.download(urls[i], onProgress);
      } catch (err) {
        if (this.cancelled) {
          return;
        }
        // 下载失败才换源重试；同时丢弃排序缓存，下次下载重新测速
        lastErr = err as Error;
        invalidateRankCache();
        hilog.warn(DOMAIN, TAG, 'download attempt %{public}d failed: %{public}s',
          i, JSON.stringify(err));
        continue;
      }
      if (this.cancelled) {
        return;
      }
      // 解压失败与下载失败不同：换源重下会白下整个资源包，直接抛出由上层提示
      onExtractStart();
      await this.extract();
      await this.removeTask();
      return;
    }
    throw lastErr !== null ? lastErr : new Error('all download sources failed');
  }

  /**
   * 手动导入资源包：把用户自行下载好的 zip（文件选择器返回的 URI）复制成缓存 zip，
   * 之后走与自动下载完全相同的解压 / 原子切换流程（跳过测速与下载）。
   */
  async installFromZip(srcUri: string): Promise<void> {
    this.cancelled = false;
    this.paused = false;
    this.stopPolling();
    this.removePartialZip();
    const src: fs.File = fs.openSync(srcUri, fs.OpenMode.READ_ONLY);
    const dest: fs.File = fs.openSync(this.zipPath,
      fs.OpenMode.CREATE | fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC);
    try {
      await fs.copyFile(src.fd, dest.fd);
    } finally {
      fs.closeSync(src);
      fs.closeSync(dest);
    }
    hilog.info(DOMAIN, TAG, 'imported zip size=%{public}d', this.zipSize());
    await this.extract();
  }

  /**
   * App 重启后接管未完成的下载任务（request.agent 后台任务在 App 被杀后仍会继续）。
   * @returns 是否接管了任务（接管后通过回调通知进度/完成）
   */
  async adoptDownload(
    onProgress: (received: number, total: number) => void,
    onExtractStart: () => void,
    onDone: () => void,
    onError: (msg: string) => void,
    expectedSize: number
  ): Promise<boolean> {
    try {
      const all: string[] = await request.agent.search({
        action: request.agent.Action.DOWNLOAD,
        mode: request.agent.Mode.BACKGROUND
      });

      // 已安装：清掉所有残留任务（含历史通知）
      if (this.hasGameFiles()) {
        await this.removeTasks(all);
        return false;
      }

      // 只接管仍「活跃」的任务（进行中/重试/等待/已暂停）。
      // 已完成/失败/已移除的任务不可接管：事件不会再触发、轮询也等不到 zip，
      // 界面会永久卡在下载中（例如解压中被杀，任务已 COMPLETED 但 zip 已被消费）。
      const adoptable: string[] = [];
      const states: request.agent.State[] = [
        request.agent.State.RUNNING,
        request.agent.State.RETRYING,
        request.agent.State.WAITING,
        request.agent.State.PAUSED
      ];
      for (let s = 0; s < states.length; s++) {
        try {
          const ids: string[] = await request.agent.search({
            action: request.agent.Action.DOWNLOAD,
            mode: request.agent.Mode.BACKGROUND,
            state: states[s]
          });
          for (let i = 0; i < ids.length; i++) {
            adoptable.push(ids[i]);
          }
        } catch (err) {
          hilog.warn(DOMAIN, TAG, 'search state %{public}d failed: %{public}s',
            states[s], JSON.stringify(err));
        }
      }

      // 找到第一个仍然有效的任务（search 可能返回已失效的 id）
      let task: request.agent.Task | null = null;
      let taskId: string = '';
      for (let i = 0; i < adoptable.length; i++) {
        try {
          task = await request.agent.getTask(this.ctx, adoptable[i]);
          taskId = adoptable[i];
          break;
        } catch (err) {
          hilog.warn(DOMAIN, TAG, 'skip invalid task %{public}s', adoptable[i]);
        }
      }

      // 没有可接管任务：若 zip 已完整，直接解压（兜底）
      if (task === null) {
        if (this.hasZip() && (expectedSize <= 0 || this.zipSize() >= expectedSize)) {
          hilog.info(DOMAIN, TAG, '%{public}s', 'no valid task but zip complete, extracting');
          onExtractStart();
          try {
            await this.extract();
            await this.removeTasks(all);
            onDone();
            return true;
          } catch (err) {
            hilog.error(DOMAIN, TAG, 'fallback extract failed: %{public}s', JSON.stringify(err));
          }
        }
        await this.removeTasks(all);
        return false;
      }

      const activeTask: request.agent.Task = task;

      // 清掉其余任务
      for (let i = 0; i < all.length; i++) {
        if (all[i] === taskId) {
          continue;
        }
        try {
          await request.agent.remove(all[i]);
        } catch (err) {
          // 失效任务忽略
        }
      }

      this.task = activeTask;
      this.tid = taskId;
      this.cancelled = false;
      this.paused = false;
      // 重新显示该任务的通知（App 重启后通知可能已被清理）
      request.agent.show(taskId).catch((err: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'show adopted task failed: %{public}s', JSON.stringify(err));
      });

      let finished: boolean = false;
      const finish = (): void => {
        if (finished) {
          return;
        }
        finished = true;
        this.stopPolling();
        this.task = null;
        if (this.cancelled) {
          return;
        }
        onExtractStart();
        this.extract().then(() => {
          this.removeTask();
          onDone();
        }).catch((err: BusinessError) => {
          hilog.error(DOMAIN, TAG, 'adopt extract failed: %{public}s', JSON.stringify(err));
          onError('解压失败');
        });
      };

      activeTask.on('progress', (p: request.agent.Progress) => {
        const total: number = (p.sizes !== undefined && p.sizes.length > 0) ? p.sizes[0] : -1;
        onProgress(p.processed, total);
      });
      activeTask.on('completed', finish);
      activeTask.on('failed', (p: request.agent.Progress) => {
        if (finished) {
          return;
        }
        finished = true;
        this.stopPolling();
        this.task = null;
        if (!this.cancelled) {
          onError('下载失败');
        }
      });
      // 接管的任务事件不可靠：用文件大小轮询兜底进度，达到预期大小即判定完成
      this.startPolling(onProgress, expectedSize, finish);
      return true;
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'adoptDownload failed: %{public}s', JSON.stringify(err));
      return false;
    }
  }

  private zipSize(): number {
    try {
      return fs.statSync(this.zipPath).size;
    } catch (err) {
      return 0;
    }
  }

  private hasZip(): boolean {
    try {
      return fs.accessSync(this.zipPath);
    } catch (err) {
      return false;
    }
  }

  /** 接管任务时用文件大小轮询兜底进度；达到预期大小即判定完成 */
  private startPolling(onProgress: (received: number, total: number) => void,
    expectedSize: number, onComplete: () => void): void {
    this.stopPolling();
    this.pollTimer = setInterval(() => {
      fs.stat(this.zipPath).then((stat: fs.Stat) => {
        const size: number = stat.size;
        onProgress(size, -1);
        if (expectedSize > 0 && size >= expectedSize) {
          hilog.info(DOMAIN, TAG, 'poll detected complete: %{public}d >= %{public}d', size, expectedSize);
          onComplete();
        }
      }).catch((err: Error) => {
        // 文件尚未落盘/暂不可读时忽略
      });
    }, 1000);
  }

  private stopPolling(): void {
    if (this.pollTimer >= 0) {
      clearInterval(this.pollTimer);
      this.pollTimer = -1;
    }
  }

  private async removeTasks(tids: string[]): Promise<void> {
    for (let i = 0; i < tids.length; i++) {
      try {
        await request.agent.remove(tids[i]);
      } catch (err) {
        hilog.warn(DOMAIN, TAG, 'remove task %{public}s failed: %{public}s',
          tids[i], JSON.stringify(err));
      }
    }
  }

  private async download(
    url: string,
    onProgress: (received: number, total: number) => void
  ): Promise<void> {
    const task: request.agent.Task = await this.createTask(url);
    this.task = task;
    this.tid = task.tid;
    await new Promise<void>((resolve, reject) => {
      task.on('progress', (p: request.agent.Progress) => {
        const total: number = (p.sizes !== undefined && p.sizes.length > 0) ? p.sizes[0] : -1;
        onProgress(p.processed, total);
      });
      task.on('completed', () => {
        this.task = null;
        if (this.cancelled) {
          reject(new Error('cancelled'));
          return;
        }
        resolve();
      });
      task.on('failed', (p: request.agent.Progress) => {
        this.task = null;
        if (this.cancelled) {
          reject(new Error('cancelled'));
          return;
        }
        reject(new Error('download failed, state=' + p.state));
      });
      task.start().catch((err: BusinessError) => {
        this.task = null;
        if (this.cancelled) {
          reject(new Error('cancelled'));
          return;
        }
        reject(err);
      });
    });
  }

  private async createTask(url: string): Promise<request.agent.Task> {
    const config: request.agent.Config = {
      action: request.agent.Action.DOWNLOAD,
      url: url,
      title: '正在下载离线资源',
      description: 'PokeLauncher',
      mode: request.agent.Mode.BACKGROUND,
      overwrite: true,
      method: 'GET',
      saveas: 'internal://cache/game.zip',
      network: request.agent.Network.ANY,
      metered: true,
      roaming: true,
      retry: false,
      gauge: true
    };
    // notification 字段 @since 15、visibility @since 21、wantAgent @since 22：
    // 低版本整体不设置，回退到 gauge 决定通知样式（仍显示进度），只是点击不再拉起应用。
    if (deviceInfo.sdkApiVersion >= 22) {
      const wantAgentInfo: wantAgent.WantAgentInfo = {
        wants: [
          {
            deviceId: '',
            bundleName: this.ctx.abilityInfo.bundleName,
            abilityName: this.ctx.abilityInfo.name,
            action: '',
            entities: [],
            uri: '',
            parameters: {}
          }
        ],
        actionType: wantAgent.OperationType.START_ABILITY,
        requestCode: 0,
        wantAgentFlags: [wantAgent.WantAgentFlags.CONSTANT_FLAG]
      };
      config.notification = {
        visibility: request.agent.VISIBILITY_PROGRESS,
        wantAgent: await wantAgent.getWantAgent(wantAgentInfo)
      };
    }
    return await request.agent.create(this.ctx, config);
  }

  /** 暂停下载 */
  pauseDownload(): void {
    this.paused = true;
    if (this.task !== null) {
      this.task.pause().catch((err: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'pause failed: %{public}s', JSON.stringify(err));
      });
    }
  }

  /** 继续下载 */
  resumeDownload(): void {
    this.paused = false;
    if (this.task !== null) {
      this.task.resume().catch((err: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'resume failed: %{public}s', JSON.stringify(err));
      });
    }
  }

  /** 取消下载 */
  cancelDownload(): void {
    this.cancelled = true;
    this.paused = false;
    this.stopPolling();
    const t: request.agent.Task | null = this.task;
    this.task = null;
    const id: string = t !== null ? t.tid : this.tid;
    this.tid = '';
    if (id.length > 0) {
      request.agent.remove(id).catch((err: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'remove task failed: %{public}s', JSON.stringify(err));
      });
    }
    this.removePartialZip();
  }

  private async removeTask(): Promise<void> {
    this.stopPolling();
    const id: string = this.tid;
    this.tid = '';
    this.task = null;
    if (id.length > 0) {
      try {
        await request.agent.remove(id);
      } catch (err) {
        hilog.warn(DOMAIN, TAG, 'removeTask failed: %{public}s', JSON.stringify(err));
      }
    }
  }

  /**
   * 启动时恢复被中断的资源切换（App 在解压/切换过程中被杀）。
   *
   * 切换顺序：解压到 staging → game 改名为 backup → staging 改名为 game。
   * - game 缺失且 staging 存在：staging 必然是完整包（改名只发生在解压完成后）→ 顶上
   * - game 缺失且 staging 不存在：从 backup 还原上一份可用包
   * - game 存在：清理残留的 staging / backup
   *
   * 改名是同步且瞬时（同一文件系统），删除残留走异步，避免主线程删大量文件。
   */
  recover(): void {
    try {
      const hasGame: boolean = fs.accessSync(this.gameDir);
      const hasStaging: boolean = fs.accessSync(this.stagingDir);
      // 只有解压完整（带标记）的 staging 才能顶上；否则是半成品，直接丢弃
      const stagingComplete: boolean = hasStaging && fs.accessSync(this.stagingMarker);
      const hasBackup: boolean = fs.accessSync(this.backupDir);
      if (!hasGame) {
        if (stagingComplete) {
          fs.renameSync(this.stagingDir, this.gameDir);
          hilog.info(DOMAIN, TAG, '%{public}s', 'recover: promoted staging to game');
        } else {
          if (hasStaging) {
            hilog.warn(DOMAIN, TAG, '%{public}s', 'recover: discard incomplete staging');
            this.removeDirAsync(this.stagingDir);
          }
          if (hasBackup) {
            fs.renameSync(this.backupDir, this.gameDir);
            hilog.warn(DOMAIN, TAG, '%{public}s', 'recover: restored backup');
          }
        }
      }
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'recover rename failed: %{public}s', JSON.stringify(err));
    }
    // game 已就位：清掉残留目录（异步删除）
    this.removeDirAsync(this.stagingDir);
    this.removeDirAsync(this.backupDir);
  }

  /** 写解压完成标记（fileIo 无 writeFileSync，需 open+write+close） */
  private writeMarker(path: string): void {
    const fd: fs.File = fs.openSync(path,
      fs.OpenMode.CREATE | fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC);
    try {
      fs.writeSync(fd.fd, '1');
    } finally {
      fs.closeSync(fd);
    }
  }

  /** 异步删除目录（存在才删）；不阻塞主线程 */
  private removeDirAsync(dir: string): void {
    try {
      if (fs.accessSync(dir)) {
        fs.rmdir(dir).catch((err: Error) => {
          hilog.warn(DOMAIN, TAG, 'remove dir %{public}s failed: %{public}s', dir, JSON.stringify(err));
        });
      }
    } catch (err) {
      // 目录不存在时忽略
    }
  }

  /**
   * 解压 zip 到 staging，成功后再原子切换到 gameDir（zip 内已含 currentVersion.txt）。
   * 这样解压中途失败/被杀也不会破坏现有安装，只会残留 staging/backup，由 recover() 处理。
   */
  private async extract(): Promise<void> {
    try {
      if (fs.accessSync(this.stagingDir)) {
        await fs.rmdir(this.stagingDir);
      }
      fs.mkdirSync(this.stagingDir, true);
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'prepare staging error: %{public}s', JSON.stringify(err));
      throw err as Error;
    }

    try {
      // parallel 选项 @since 18：低版本回退为串行解压（仅慢一些）
      if (deviceInfo.sdkApiVersion >= 18) {
        await zlib.decompressFile(this.zipPath, this.stagingDir, {
          parallel: zlib.ParallelStrategy.PARALLEL_STRATEGY_PARALLEL_DECOMPRESSION
        });
      } else {
        await zlib.decompressFile(this.zipPath, this.stagingDir);
      }
      // 校验解压结果，避免把损坏/结构不符的包切换上去（否则会显示「已就绪」却打不开游戏）
      if (!fs.accessSync(this.stagingDir + '/index.html')) {
        throw new Error('解压结果缺少 index.html');
      }
      // 解压完成才写标记：recover() 据此区分完整包与半成品
      this.writeMarker(this.stagingMarker);
      try {
        fs.unlinkSync(this.zipPath);
      } catch (err) {
        hilog.warn(DOMAIN, TAG, 'clean zip warn: %{public}s', JSON.stringify(err));
      }
      hilog.info(DOMAIN, TAG, '%{public}s', 'extract to staging complete');
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'decompress/validate error: %{public}s', JSON.stringify(err));
      // 清掉半成品，保留现有安装
      this.removeDirAsync(this.stagingDir);
      throw err as Error;
    }

    // 原子切换：game → backup，staging → game
    try {
      if (fs.accessSync(this.gameDir)) {
        if (fs.accessSync(this.backupDir)) {
          await fs.rmdir(this.backupDir);
        }
        fs.renameSync(this.gameDir, this.backupDir);
      }
      fs.renameSync(this.stagingDir, this.gameDir);
    } catch (err) {
      // 切换失败：把 backup 还原回 game，避免游戏不可用
      try {
        if (!fs.accessSync(this.gameDir) && fs.accessSync(this.backupDir)) {
          fs.renameSync(this.backupDir, this.gameDir);
        }
      } catch (rollbackErr) {
        hilog.error(DOMAIN, TAG, 'rollback failed: %{public}s', JSON.stringify(rollbackErr));
      }
      hilog.error(DOMAIN, TAG, 'swap failed: %{public}s', JSON.stringify(err));
      throw err as Error;
    }
    // 旧包异步删除
    this.removeDirAsync(this.backupDir);
    hilog.info(DOMAIN, TAG, '%{public}s', 'extract complete');
  }

  /** 删除本地核心包（异步，避免同步删除 3 万+ 文件阻塞主线程导致闪退） */
  async clear(): Promise<void> {
    this.stopPolling();
    const dirs: string[] = [this.gameDir, this.stagingDir, this.backupDir];
    for (let i = 0; i < dirs.length; i++) {
      try {
        if (fs.accessSync(dirs[i])) {
          await fs.rmdir(dirs[i]);
        }
      } catch (err) {
        hilog.warn(DOMAIN, TAG, 'clear dir %{public}s error: %{public}s', dirs[i], JSON.stringify(err));
      }
    }
    try {
      if (fs.accessSync(this.zipPath)) {
        await fs.unlink(this.zipPath);
      }
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'clear zip error: %{public}s', JSON.stringify(err));
    }
  }
}
