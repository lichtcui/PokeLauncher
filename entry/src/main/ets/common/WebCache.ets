import { webview } from '@kit.ArkWeb';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0000;
const TAG: string = 'WebCache';

/**
 * 清空 Web 的 HTTP 缓存与 JS 代码缓存。
 *
 * 本地服务的静态资源带 `Cache-Control: max-age=31536000`，URL 又固定为
 * http://127.0.0.1:18787/...，所以游戏资源包更新后必须清缓存，否则 Web 会继续
 * 用旧文件。游戏资源下载/解压完成后自动调用。
 * @returns 是否成功
 */
export function clearWebCache(): boolean {
  try {
    webview.WebviewController.removeAllCache(true);
    hilog.info(DOMAIN, TAG, '%{public}s', 'web cache cleared');
    return true;
  } catch (err) {
    hilog.warn(DOMAIN, TAG, 'removeAllCache failed: %{public}s', JSON.stringify(err));
    return false;
  }
}
