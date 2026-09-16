import { request, zlib, BusinessError } from '@kit.BasicServicesKit';
import { http } from '@kit.NetworkKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { common, wantAgent, WantAgent } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { RELEASE_API, USER_AGENT, DEFAULT_ASSET, GAME_DIR_NAME, DOWNLOAD_MIRRORS, API_MIRROR } from '../common/Const';
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
    const urls: string[] = [];
    for (let i = 0; i < DOWNLOAD_MIRRORS.length; i++) {
      urls.push(DOWNLOAD_MIRRORS[i] + assetUrl);
    }
    urls.push(assetUrl);
    return urls;
  }

  /** 并发探测各源速度，按“快 → 慢 → 失败”排序 */
  private async rankSources(urls: string[]): Promise<string[]> {
    const probes: Promise<number>[] = [];
    for (let i = 0; i < urls.length; i++) {
      probes.push(this.probe(urls[i]));
    }
    const speeds: number[] = await Promise.all(probes);
    const idx: number[] = [];
    for (let i = 0; i < urls.length; i++) {
      idx.push(i);
    }
    idx.sort((a: number, b: number) => speeds[b] - speeds[a]);
    const sorted: string[] = [];
    for (let i = 0; i < idx.length; i++) {
      sorted.push(urls[idx[i]]);
      hilog.info(DOMAIN, TAG, 'source[%{public}d] speed=%{public}f', idx[i], speeds[idx[i]]);
    }
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
        if (this.cancelled) {
          return;
        }
        onExtractStart();
        await this.extract();
        await this.removeTask();
        return;
      } catch (err) {
        if (this.cancelled) {
          return;
        }
        lastErr = err as Error;
        hilog.warn(DOMAIN, TAG, 'download attempt %{public}d failed: %{public}s',
          i, JSON.stringify(err));
      }
    }
    throw lastErr !== null ? lastErr : new Error('all download sources failed');
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

      // 找到第一个仍然有效的任务（search 可能返回已失效的 id）
      let task: request.agent.Task | null = null;
      let taskId: string = '';
      for (let i = 0; i < all.length; i++) {
        try {
          task = await request.agent.getTask(this.ctx, all[i]);
          taskId = all[i];
          break;
        } catch (err) {
          hilog.warn(DOMAIN, TAG, 'skip invalid task %{public}s', all[i]);
        }
      }

      // 没有有效任务：若 zip 已完整，直接解压（兜底）
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
      try {
        if (fs.accessSync(this.zipPath)) {
          const size: number = fs.statSync(this.zipPath).size;
          onProgress(size, -1);
          if (expectedSize > 0 && size >= expectedSize) {
            hilog.info(DOMAIN, TAG, 'poll detected complete: %{public}d >= %{public}d', size, expectedSize);
            onComplete();
          }
        }
      } catch (err) {
        // 文件暂不可读时忽略
      }
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
    const agentObj: WantAgent = await wantAgent.getWantAgent(wantAgentInfo);
    const config: request.agent.Config = {
      action: request.agent.Action.DOWNLOAD,
      url: url,
      title: '正在下载游戏资源',
      description: 'PokeRogue',
      mode: request.agent.Mode.BACKGROUND,
      overwrite: true,
      method: 'GET',
      saveas: 'internal://cache/game.zip',
      network: request.agent.Network.ANY,
      metered: true,
      roaming: true,
      retry: false,
      gauge: true,
      notification: {
        visibility: request.agent.VISIBILITY_PROGRESS,
        wantAgent: agentObj
      }
    };
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

  /** 解压 zip 到 gameDir（zip 内已含 currentVersion.txt） */
  private async extract(): Promise<void> {
    try {
      // 重新解压前清空旧目录：同步删除 3 万+ 文件会阻塞主线程导致闪退，必须异步
      if (fs.accessSync(this.gameDir)) {
        await fs.rmdir(this.gameDir);
      }
      fs.mkdirSync(this.gameDir, true);
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'prepare dir error: %{public}s', JSON.stringify(err));
      throw err as Error;
    }

    try {
      await zlib.decompressFile(this.zipPath, this.gameDir, {
        parallel: zlib.ParallelStrategy.PARALLEL_STRATEGY_PARALLEL_DECOMPRESSION
      });
      try {
        fs.unlinkSync(this.zipPath);
      } catch (err) {
        hilog.warn(DOMAIN, TAG, 'clean zip warn: %{public}s', JSON.stringify(err));
      }
      hilog.info(DOMAIN, TAG, '%{public}s', 'extract complete');
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'decompressFile error: %{public}s', JSON.stringify(err));
      throw err as Error;
    }
  }

  /** 删除本地核心包（异步，避免同步删除 3 万+ 文件阻塞主线程导致闪退） */
  async clear(): Promise<void> {
    this.stopPolling();
    try {
      if (fs.accessSync(this.gameDir)) {
        await fs.rmdir(this.gameDir);
      }
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'clear gameDir error: %{public}s', JSON.stringify(err));
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
