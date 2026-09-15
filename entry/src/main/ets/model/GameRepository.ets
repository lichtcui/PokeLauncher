import { request, zlib, BusinessError } from '@kit.BasicServicesKit';
import { http } from '@kit.NetworkKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { common, wantAgent, WantAgent } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { RELEASE_API, USER_AGENT, DEFAULT_ASSET, GAME_DIR_NAME, DOWNLOAD_MIRRORS, API_MIRROR } from '../common/Const';

const DOMAIN: number = 0x0000;
const TAG: string = 'GameRepository';

/** 速度探测读取的字节数 */
const PROBE_BYTES: number = 131072;

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

  isPaused(): boolean {
    return this.paused;
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
      return fs.readTextSync(this.versionFile);
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
        const text: string = await this.requestText(apiUrl);
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

  private async requestText(url: string): Promise<string> {
    const httpRequest: http.HttpRequest = http.createHttp();
    try {
      const resp: http.HttpResponse = await httpRequest.request(url, {
        method: http.RequestMethod.GET,
        header: {
          'User-Agent': USER_AGENT,
          'Accept': 'application/vnd.github+json'
        },
        connectTimeout: 15000,
        readTimeout: 30000
      });
      if (resp.responseCode !== 200) {
        throw new Error('HTTP ' + resp.responseCode);
      }
      return resp.result as string;
    } finally {
      httpRequest.destroy();
    }
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

  /** 返回 bytes/ms；失败返回 -1 */
  private async probe(url: string): Promise<number> {
    const req: http.HttpRequest = http.createHttp();
    const start: number = Date.now();
    try {
      const resp: http.HttpResponse = await req.request(url, {
        method: http.RequestMethod.GET,
        header: {
          'User-Agent': USER_AGENT,
          'Range': 'bytes=0-' + (PROBE_BYTES - 1)
        },
        connectTimeout: 6000,
        readTimeout: 15000,
        expectDataType: http.HttpDataType.ARRAY_BUFFER
      });
      const elapsed: number = Date.now() - start;
      if (resp.responseCode >= 200 && resp.responseCode < 400 && elapsed > 0) {
        const buf: ArrayBuffer = resp.result as ArrayBuffer;
        const len: number = buf !== undefined ? buf.byteLength : 0;
        if (len > 0) {
          return len / elapsed;
        }
      }
      return -1;
    } catch (err) {
      return -1;
    } finally {
      req.destroy();
    }
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
    onError: (msg: string) => void
  ): Promise<boolean> {
    try {
      const completed: string[] = await request.agent.search({
        action: request.agent.Action.DOWNLOAD,
        mode: request.agent.Mode.BACKGROUND,
        state: request.agent.State.COMPLETED
      });
      if (completed.length > 0) {
        if (!this.hasGameFiles()) {
          onExtractStart();
          await this.extract();
          onDone();
        }
        await request.agent.remove(completed[0]);
        return true;
      }

      const active: string[] = await request.agent.search({
        action: request.agent.Action.DOWNLOAD,
        mode: request.agent.Mode.BACKGROUND
      });
      if (active.length === 0 || this.hasGameFiles()) {
        for (let i = 0; i < active.length; i++) {
          await request.agent.remove(active[i]);
        }
        return false;
      }

      const task: request.agent.Task = await request.agent.getTask(this.ctx, active[0]);
      this.task = task;
      this.tid = task.tid;
      this.cancelled = false;
      this.paused = false;
      task.on('progress', (p: request.agent.Progress) => {
        const total: number = (p.sizes !== undefined && p.sizes.length > 0) ? p.sizes[0] : -1;
        onProgress(p.processed, total);
      });
      task.on('completed', () => {
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
      });
      task.on('failed', (p: request.agent.Progress) => {
        this.task = null;
        if (this.cancelled) {
          return;
        }
        onError('下载失败');
      });
      return true;
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'adoptDownload failed: %{public}s', JSON.stringify(err));
      return false;
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
      title: 'PokeRogue 核心包',
      description: '正在下载核心包',
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
        title: '正在下载核心包',
        text: 'PokeRogue',
        visibility: request.agent.VISIBILITY_COMPLETION | request.agent.VISIBILITY_PROGRESS,
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
  private extract(): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      try {
        if (fs.accessSync(this.gameDir)) {
          fs.rmdirSync(this.gameDir);
        }
        fs.mkdirSync(this.gameDir, true);
      } catch (err) {
        hilog.error(DOMAIN, TAG, 'prepare dir error: %{public}s', JSON.stringify(err));
        reject(err as Error);
        return;
      }

      zlib.decompressFile(this.zipPath, this.gameDir, {
        parallel: zlib.ParallelStrategy.PARALLEL_STRATEGY_PARALLEL_DECOMPRESSION
      }).then(() => {
        try {
          fs.unlinkSync(this.zipPath);
        } catch (err) {
          hilog.warn(DOMAIN, TAG, 'clean zip warn: %{public}s', JSON.stringify(err));
        }
        hilog.info(DOMAIN, TAG, '%{public}s', 'extract complete');
        resolve();
      }).catch((err: BusinessError) => {
        hilog.error(DOMAIN, TAG, 'decompressFile error: %{public}s', JSON.stringify(err));
        reject(err);
      });
    });
  }

  /** 删除本地核心包 */
  clear(): void {
    try {
      if (fs.accessSync(this.gameDir)) {
        fs.rmdirSync(this.gameDir);
      }
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'clear error: %{public}s', JSON.stringify(err));
    }
    this.removePartialZip();
  }
}
