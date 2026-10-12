/**
 * 扩展名 → MIME 映射（本地 HTTP 服务的 Content-Type）。
 *
 * ⚠️ **本文件由 `scripts/gen-shared.mjs` 自动生成，不要手改**：唯一真相在仓库根 `shared/mime.json`。
 *
 * 关键：ArkWeb 基于 Chromium，对 <script type="module"> 有严格 MIME 校验，
 * 若 .js 返回错误的 MIME 会导致白屏，必须返回 text/javascript。
 */

const MIME_MAP: Map<string, string> = new Map<string, string>([
  ['html', 'text/html'],
  ['htm', 'text/html'],
  ['js', 'text/javascript'],
  ['mjs', 'text/javascript'],
  ['css', 'text/css'],
  ['json', 'application/json'],
  ['webmanifest', 'application/manifest+json'],
  ['wasm', 'application/wasm'],
  ['png', 'image/png'],
  ['jpg', 'image/jpeg'],
  ['jpeg', 'image/jpeg'],
  ['gif', 'image/gif'],
  ['webp', 'image/webp'],
  ['svg', 'image/svg+xml'],
  ['ico', 'image/x-icon'],
  ['bmp', 'image/bmp'],
  ['mp3', 'audio/mpeg'],
  ['ogg', 'audio/ogg'],
  ['m4a', 'audio/mp4'],
  ['wav', 'audio/wav'],
  ['mp4', 'video/mp4'],
  ['webm', 'video/webm'],
  ['ttf', 'font/ttf'],
  ['otf', 'font/otf'],
  ['woff', 'font/woff'],
  ['woff2', 'font/woff2'],
  ['txt', 'text/plain'],
  ['xml', 'text/xml']
]);

/** 无扩展名 / 未知扩展名的退化值 */
const MIME_FALLBACK: string = 'application/octet-stream';

export function mimeOf(path: string): string {
  const dot: number = path.lastIndexOf('.');
  if (dot < 0) {
    return MIME_FALLBACK;
  }
  const ext: string = path.substring(dot + 1).toLowerCase();
  const mime: string | undefined = MIME_MAP.get(ext);
  return mime !== undefined ? mime : MIME_FALLBACK;
}
