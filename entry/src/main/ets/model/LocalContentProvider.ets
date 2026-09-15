import { fileIo as fs } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { GAME_ORIGIN } from '../common/Const';
import { mimeOf } from '../common/Mime';

const DOMAIN: number = 0x0000;
const TAG: string = 'LocalContent';

/**
 * 把 https://pokerogue.local/<path> 的请求映射到沙箱内的离线游戏文件。
 *
 * 用 WebResourceResponse.setResponseData(fd) 直接传文件描述符：
 * - 大文件不进内存；
 * - fd 由 Web 组件负责关闭。
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
      const file: fs.File = fs.openSync(filePath, fs.OpenMode.READ_ONLY);
      response.setResponseData(file.fd);
      response.setResponseMimeType(mimeOf(filePath));
      response.setResponseEncoding('utf-8');
      response.setResponseCode(200);
      response.setReasonMessage('OK');
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
}
