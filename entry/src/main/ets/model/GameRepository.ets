import { request, zlib, BusinessError } from '@kit.BasicServicesKit';
import { http } from '@kit.NetworkKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { common, wantAgent, WantAgent } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { RELEASE_API, USER_AGENT, DEFAULT_ASSET, GAME_DIR_NAME } from '../common/Const';

const DOMAIN: number = 0x0000;
const TAG: string = 'GameRepository';

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

/** 下载进度 */
export interface DownloadProgress {
  received: number;
  total: number;
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
      return fs.readTextSync(this.versionFile);
    } catch (err) {
      return '';
    }
  }

  /** 查询 Release 中指定资产的信息 */
  async fetchRelease(assetName: string = DEFAULT_ASSET): Promise<ReleaseInfo> {
    const httpRequest: http.HttpRequest = http.createHttp();
    try {
      const resp: http.HttpResponse = await httpRequest.request(RELEASE_API, {
        method: http.RequestMethod.GET,
        header: {
          'User-Agent': USER_AGENT,
          'Accept': 'application/vnd.github+json'
        },
        connectTimeout: 15000,
        readTimeout: 30000
      });
      if (resp.responseCode !== 200) {
        throw new Error('GitHub API HTTP ' + resp.responseCode);
      }
      const data: ReleaseData = JSON.parse(resp.result as string) as ReleaseData;
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
    } finally {
      httpRequest.destroy();
    }
  }

  /**
   * 下载资源包并解压到 gameDir。
   * @param info Release 信息
   * @param onProgress 下载进度回调（received, total）
   * @param onExtractStart 进入解压阶段的回调
   */
  async downloadAndExtract(
    info: ReleaseInfo,
    onProgress: (received: number, total: number) => void,
    onExtractStart: () => void
  ): Promise<void> {
    this.cancelled = false;
    await this.download(info, onProgress);
    if (this.cancelled) {
      return;
    }
    onExtractStart();
    await this.extract(info);
  }

  /** 取消正在进行的下载（移除系统后台下载任务） */
  cancelDownload(): void {
    this.cancelled = true;
    const t: request.agent.Task | null = this.task;
    this.task = null;
    const id: string = t !== null ? t.tid : this.tid;
    this.tid = '';
    if (id.length > 0) {
      request.agent.remove(id).catch((err: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'remove task failed: %{public}s', JSON.stringify(err));
      });
    }
    // 清理可能残留的部分文件
    try {
      if (fs.accessSync(this.zipPath)) {
        fs.unlinkSync(this.zipPath);
      }
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'clean partial zip failed: %{public}s', JSON.stringify(err));
    }
  }

  /**
   * 清理历史遗留的下载任务，避免 App 被杀后残留「孤儿」通知。
   * 在首页启动时调用。
   */
  async cleanupStaleTasks(): Promise<void> {
    try {
      const tids: string[] = await request.agent.search({
        action: request.agent.Action.DOWNLOAD,
        mode: request.agent.Mode.BACKGROUND
      });
      for (let i = 0; i < tids.length; i++) {
        const id: string = tids[i];
        if (this.task === null || id !== this.task.tid) {
          await request.agent.remove(id);
          hilog.info(DOMAIN, TAG, 'removed stale task %{public}s', id);
        }
      }
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'cleanup stale tasks failed: %{public}s', JSON.stringify(err));
    }
  }

  private async download(
    info: ReleaseInfo,
    onProgress: (received: number, total: number) => void
  ): Promise<void> {
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
      url: info.assetUrl,
      title: 'PokeRogue 核心包',
      description: '正在下载核心包',
      mode: request.agent.Mode.BACKGROUND,
      overwrite: true,
      method: 'GET',
      saveas: 'internal://cache/game.zip',
      network: request.agent.Network.ANY,
      metered: true,
      roaming: true,
      retry: true,
      gauge: true,
      notification: {
        title: '正在下载核心包',
        text: 'PokeRogue',
        visibility: request.agent.VISIBILITY_COMPLETION | request.agent.VISIBILITY_PROGRESS,
        wantAgent: agentObj
      }
    };

    const task: request.agent.Task = await request.agent.create(this.ctx, config);
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
        hilog.info(DOMAIN, TAG, '%{public}s', 'download complete');
        resolve();
      });
      task.on('failed', (p: request.agent.Progress) => {
        this.task = null;
        if (this.cancelled) {
          reject(new Error('cancelled'));
          return;
        }
        hilog.error(DOMAIN, TAG, 'download failed, state=%{public}d', p.state);
        reject(new Error('download failed, state=' + p.state));
      });
      task.start().catch((err: BusinessError) => {
        this.task = null;
        if (this.cancelled) {
          reject(new Error('cancelled'));
          return;
        }
        hilog.error(DOMAIN, TAG, 'task.start error: %{public}s', JSON.stringify(err));
        reject(err);
      });
    });
  }

  private extract(info: ReleaseInfo): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      try {
        // 清空旧目录并重建（decompressFile 要求 outFile 目录已存在）
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
          const vf: fs.File = fs.openSync(
            this.versionFile,
            fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC
          );
          fs.writeSync(vf.fd, info.tag);
          fs.closeSync(vf);
          fs.unlinkSync(this.zipPath);
        } catch (err) {
          hilog.warn(DOMAIN, TAG, 'post-extract cleanup warn: %{public}s', JSON.stringify(err));
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
    try {
      if (fs.accessSync(this.zipPath)) {
        fs.unlinkSync(this.zipPath);
      }
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'clear zip error: %{public}s', JSON.stringify(err));
    }
  }
}
