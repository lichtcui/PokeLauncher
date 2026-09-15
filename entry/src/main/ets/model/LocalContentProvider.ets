import { fileIo as fs } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { GAME_ORIGIN } from '../common/Const';
import { mimeOf } from '../common/Mime';

const DOMAIN: number = 0x0000;
const TAG: string = 'LocalContent';

let reqCount: number = 0;
let totalBytes: number = 0;
let lastLogAt: number = 0;

/** 探针：统计一次加载期间的请求数与数据量 */
function noteRequest(size: number): void {
  reqCount++;
  totalBytes += size;
  const now: number = Date.now();
  if (now - lastLogAt > 3000) {
    lastLogAt = now;
    hilog.info(DOMAIN, TAG, 'served %{public}d files, %{public}d KB',
      reqCount, Math.floor(totalBytes / 1024));
  }
}

/**
 * 把 https://pokerogue.local/<path> 的请求映射到沙箱内的离线游戏文件。
 *
 * 用 WebResourceResponse.setResponseData(fd) 直接传文件描述符：
 * - 大文件不进内存；
 * - fd 由 Web 组件负责关闭。
 *
 * 缓存策略（加速二次进入）：
 * - .js/.mjs：带 ResponseDataID，让内核生成 V8 CodeCache（字节码缓存）；
 * - 静态资源：长缓存（文件名带 hash，更新后自然失效）；
 * - index.html / service-worker.js / webmanifest：no-cache。
 */
export class LocalContentProvider {
  private gameDir: string;

  constructor(gameDir: string) {
    this.gameDir = gameDir;
  }

  handle(event: OnInterceptRequestEvent): WebResourceResponse | null {
    const url: string = event.request.getRequestUrl();
    if (!url.startsWith(GAME_ORIGIN)) {
      return null; // 非本地域名，交给网络
    }

    let rel: string = url.substring(GAME_ORIGIN.length);
    const hashIndex: number = rel.indexOf('#');
    if (hashIndex >= 0) {
      rel = rel.substring(0, hashIndex);
    }
    const queryIndex: number = rel.indexOf('?');
    if (queryIndex >= 0) {
      rel = rel.substring(0, queryIndex);
    }
    try {
      rel = decodeURIComponent(rel);
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'decodeURIComponent failed for %{public}s', rel);
    }
    if (rel.length === 0 || rel === '/') {
      rel = '/index.html';
    }

    const response: WebResourceResponse = new WebResourceResponse();
    if (rel.indexOf('..') >= 0) {
      response.setResponseCode(403);
      response.setReasonMessage('Forbidden');
      response.setResponseMimeType('text/plain');
      response.setResponseData('403');
      return response;
    }

    const filePath: string = this.gameDir + rel;
    try {
      const stat: fs.Stat = fs.statSync(filePath);
      const file: fs.File = fs.openSync(filePath, fs.OpenMode.READ_ONLY);
      noteRequest(stat.size);
      response.setResponseData(file.fd);
      response.setResponseMimeType(mimeOf(filePath));
      response.setResponseEncoding('utf-8');
      response.setResponseCode(200);
      response.setReasonMessage('OK');
      response.setResponseHeader(this.buildHeaders(filePath, stat.size));
      return response;
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'open file failed: %{public}s', filePath);
      response.setResponseCode(404);
      response.setReasonMessage('Not Found');
      response.setResponseMimeType('text/plain');
      response.setResponseData('404 Not Found');
      return response;
    }
  }

  private buildHeaders(filePath: string, size: number): Array<Header> {
    const headers: Array<Header> = [];
    const isJs: boolean = filePath.endsWith('.js') || filePath.endsWith('.mjs');
    const isHtml: boolean = filePath.endsWith('.html') || filePath.endsWith('.htm');
    const isDynamic: boolean = filePath.endsWith('service-worker.js') ||
      filePath.endsWith('.webmanifest');

    if (isJs && !filePath.endsWith('service-worker.js')) {
      // 13 位以内的数字标识；内容变化（size 变化）时自动失效
      headers.push({ headerKey: 'ResponseDataID', headerValue: size.toString() });
    }

    if (isHtml || isDynamic) {
      headers.push({ headerKey: 'Cache-Control', headerValue: 'no-cache' });
    } else {
      headers.push({ headerKey: 'Cache-Control', headerValue: 'max-age=31536000' });
    }
    return headers;
  }
}
