/**
 * 本地 HTTP 服务的缓存策略（响应头 `Cache-Control`）。
 *
 * 为什么单独一个文件：安卓侧有一份必须与这里逐字对齐的实现
 * （`android/.../server/HttpCache.kt`），而「HTML / service-worker 被缓存住」的症状是
 * **改了注入内容却看不到变化**——只有真机才看得出来，代码评审看不见。
 * 期望值只写一份：`shared/cases/http.json`（`node scripts/gen-shared.mjs --check` 会复跑）。
 *
 * 口径（入参是已经过 `resolveRequestPath` 的游戏内路径，query 已去掉）：
 * - HTML / htm / webmanifest / service-worker.js → `no-cache`（每次进游戏重新校验）；
 * - 其余静态资源长缓存一年：资源包换版本时会清一次 WebView 缓存，文件名也带版本。
 *
 * 大小写不敏感是有意的：Windows 上打包出来的资源可能带大写扩展名，
 * 一致成 `max-age=31536000` 会把一个 HTML 缓存一年。
 */
const NO_CACHE: string = 'no-cache';
const LONG_CACHE: string = 'max-age=31536000';

/** `Cache-Control` 头值 */
export function cacheControl(filePath: string): string {
  const lower: string = filePath.toLowerCase();
  if (lower.endsWith('.html') || lower.endsWith('.htm') ||
    lower.endsWith('.webmanifest') || lower.endsWith('service-worker.js')) {
    return NO_CACHE;
  }
  return LONG_CACHE;
}
