import { http } from '@kit.NetworkKit';
import { USER_AGENT } from './Const';

/**
 * 发起 GET 请求并返回文本（200 之外抛错）。
 * 供 GitHub Release API / 应用更新清单等复用，避免各处重复实现。
 */
export async function requestText(url: string,
  accept: string = 'application/json'): Promise<string> {
  const httpRequest: http.HttpRequest = http.createHttp();
  try {
    const resp: http.HttpResponse = await httpRequest.request(url, {
      method: http.RequestMethod.GET,
      header: {
        'User-Agent': USER_AGENT,
        'Accept': accept
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
