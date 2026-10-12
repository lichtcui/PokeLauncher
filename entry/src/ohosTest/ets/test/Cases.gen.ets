/**
 * 跨端行为向量（纯数据）。
 *
 * ⚠️ **本文件由 `scripts/gen-shared.mjs` 自动生成，不要手改**：唯一真相在仓库根 `shared/cases/` 下的 JSON。
 * 期望值只写一份，两端测试遍历同一套向量——这样才能抓住「一端改了、另一端没改」的分叉。
 */

export interface PathCase {
  input: string;
  /** null = 应当拒绝（鸿蒙返回空串、安卓返回 null） */
  expect: string | null;
}

export interface NumberCase {
  input: number;
  expect: string;
}

export interface ErrorDetailCase {
  message: string | null;
  code: number | null;
  expect: string;
}

export interface NormalizeCase {
  input: string;
  expect: string;
}

export interface SameCase {
  local: string;
  remote: string;
  expect: boolean;
}

export interface MimeCase {
  path: string;
  expect: string;
}

export interface CacheCase {
  path: string;
  /** 完整的 `Cache-Control` 头值 */
  expect: string;
}

export interface DownloadCandidateCase {
  assetUrl: string;
  mirrors: string[];
  debugAssetUrl: string;
  expect: string[];
}

export interface DownloadSplitCase {
  candidates: string[];
  expectProbed: string[];
  /** null = 没有直连兜底 */
  expectFallback: string | null;
}

export interface DownloadProbeCase {
  url: string;
  /** bytes/ms；<= 0 表示测速失败或超时 */
  speed: number;
}

export interface DownloadRankCase {
  probed: DownloadProbeCase[];
  fallback: string | null;
  expect: string[];
}

export interface DownloadHostCase {
  url: string;
  expect: string;
}

export interface DownloadKindCase {
  index: number;
  count: number;
  expect: string;
}

export interface DownloadMirrorCase {
  url: string;
  mirrors: string[];
  expect: string;
}

/** 本地 HTTP 服务的路径判定（安全边界） */
export const PATH_CASES: PathCase[] = [
  { input: '/index.html', expect: '/index.html' },
  { input: '/', expect: '/index.html' },
  { input: '', expect: '/index.html' },
  { input: '/js/main.js', expect: '/js/main.js' },
  { input: '/assets/index-CVsVm5Lx.js', expect: '/assets/index-CVsVm5Lx.js' },
  { input: '/data.folder/file.js', expect: '/data.folder/file.js' },
  { input: '/a+b', expect: '/a+b' },
  { input: '/a%20b', expect: '/a b' },
  { input: '/%E4%B8%AD.txt', expect: '/中.txt' },
  { input: '/index.html?v=1', expect: '/index.html' },
  { input: '/main.js#x', expect: '/main.js' },
  { input: '/index.html?x=1#y', expect: '/index.html' },
  { input: '/../etc/passwd', expect: null },
  { input: '/js/../main.js', expect: null },
  { input: '/%2e%2e/x', expect: null },
  { input: '/%2E%2E/index.html', expect: null },
  { input: '/%2e./x', expect: null },
  { input: '/.%2e/x', expect: null },
  { input: '/..%2fx', expect: null },
  { input: '/js/%2e%2e/main.js', expect: null },
  { input: '/%252e%252e/', expect: '/%2e%2e/' },
  { input: '/%zz', expect: '/%zz' },
  { input: '/%4', expect: '/%4' },
  { input: '/%E4%2e%2e/', expect: '/%E4%2e%2e/' }
];

/** `formatBytes` */
export const BYTES_CASES: NumberCase[] = [
  { input: 0, expect: '0 B' },
  { input: -1, expect: '0 B' },
  { input: 512, expect: '1 KB' },
  { input: 1023, expect: '1 KB' },
  { input: 1024, expect: '1 KB' },
  { input: 1536, expect: '2 KB' },
  { input: 1048575, expect: '1024 KB' },
  { input: 1048576, expect: '1.0 MB' },
  { input: 1572864, expect: '1.5 MB' },
  { input: 1073741824, expect: '1.0 GB' },
  { input: 1717986918, expect: '1.6 GB' },
  { input: 531759104, expect: '507.1 MB' }
];

/** `formatSpeed` */
export const SPEED_CASES: NumberCase[] = [
  { input: 0, expect: '' },
  { input: -5, expect: '' },
  { input: 1024, expect: '1 KB/s' },
  { input: 1048576, expect: '1.0 MB/s' },
  { input: 531759104, expect: '507.1 MB/s' }
];

/** `formatDuration`（NaN / Infinity 在 JSON 里表达不了，由测试自己补） */
export const DURATION_CASES: NumberCase[] = [
  { input: 0, expect: '' },
  { input: -1, expect: '' },
  { input: 1, expect: '约 1 秒' },
  { input: 30, expect: '约 30 秒' },
  { input: 59.4, expect: '约 59 秒' },
  { input: 59.5, expect: '约 60 秒' },
  { input: 60, expect: '约 1 分钟' },
  { input: 89, expect: '约 1 分钟' },
  { input: 90, expect: '约 2 分钟' },
  { input: 119, expect: '约 2 分钟' },
  { input: 120, expect: '约 2 分钟' },
  { input: 3599, expect: '约 1 小时' },
  { input: 3600, expect: '约 1 小时' },
  { input: 3660, expect: '约 1 小时 1 分钟' },
  { input: 7200, expect: '约 2 小时' },
  { input: 1000000000, expect: '约 277777 小时 47 分钟' }
];

/** `formatErrorDetail` */
export const ERROR_DETAIL_CASES: ErrorDetailCase[] = [
  { message: null, code: null, expect: '未知错误' },
  { message: '', code: null, expect: '未知错误' },
  { message: null, code: 12, expect: '错误码 12' },
  { message: 'boom', code: 12, expect: 'boom' },
  { message: '', code: 7, expect: '错误码 7' }
];

/** `normalizeVersion` */
export const NORMALIZE_CASES: NormalizeCase[] = [
  { input: 'release-abc1234', expect: 'abc1234' },
  { input: 'abc1234', expect: 'abc1234' },
  { input: '', expect: '' },
  { input: 'v1.2.3', expect: 'v1.2.3' },
  { input: 'my-release-1', expect: 'my-release-1' },
  { input: 'release-', expect: '' },
  { input: 'release-release-x', expect: 'release-x' }
];

/** `sameVersion` */
export const SAME_CASES: SameCase[] = [
  { local: 'abc1234', remote: 'release-abc1234', expect: true },
  { local: 'release-abc1234', remote: 'release-abc1234', expect: true },
  { local: 'abc1234', remote: 'release-def5678', expect: false },
  { local: '', remote: 'release-abc1234', expect: false },
  { local: 'abc1234', remote: '', expect: false },
  { local: '', remote: '', expect: false },
  { local: 'release-', remote: '', expect: false }
];

/** `mimeOf`（由 mime.json 生成） */
export const MIME_CASES: MimeCase[] = [
  { path: '/x.html', expect: 'text/html' },
  { path: '/x.htm', expect: 'text/html' },
  { path: '/x.js', expect: 'text/javascript' },
  { path: '/x.mjs', expect: 'text/javascript' },
  { path: '/x.css', expect: 'text/css' },
  { path: '/x.json', expect: 'application/json' },
  { path: '/x.webmanifest', expect: 'application/manifest+json' },
  { path: '/x.wasm', expect: 'application/wasm' },
  { path: '/x.png', expect: 'image/png' },
  { path: '/x.jpg', expect: 'image/jpeg' },
  { path: '/x.jpeg', expect: 'image/jpeg' },
  { path: '/x.gif', expect: 'image/gif' },
  { path: '/x.webp', expect: 'image/webp' },
  { path: '/x.svg', expect: 'image/svg+xml' },
  { path: '/x.ico', expect: 'image/x-icon' },
  { path: '/x.bmp', expect: 'image/bmp' },
  { path: '/x.mp3', expect: 'audio/mpeg' },
  { path: '/x.ogg', expect: 'audio/ogg' },
  { path: '/x.m4a', expect: 'audio/mp4' },
  { path: '/x.wav', expect: 'audio/wav' },
  { path: '/x.mp4', expect: 'video/mp4' },
  { path: '/x.webm', expect: 'video/webm' },
  { path: '/x.ttf', expect: 'font/ttf' },
  { path: '/x.otf', expect: 'font/otf' },
  { path: '/x.woff', expect: 'font/woff' },
  { path: '/x.woff2', expect: 'font/woff2' },
  { path: '/x.txt', expect: 'text/plain' },
  { path: '/x.xml', expect: 'text/xml' },
  { path: '/no-extension', expect: 'application/octet-stream' },
  { path: '/x.unknownext', expect: 'application/octet-stream' },
  { path: '/UPPER.PNG', expect: 'image/png' }
];

/** 本地 HTTP 服务的 `Cache-Control`（HTML / SW 不能被缓存） */
export const CACHE_CASES: CacheCase[] = [
  { path: '/index.html', expect: 'no-cache' },
  { path: '/app/index.html', expect: 'no-cache' },
  { path: '/index.htm', expect: 'no-cache' },
  { path: '/site.webmanifest', expect: 'no-cache' },
  { path: '/manifest.webmanifest', expect: 'no-cache' },
  { path: '/service-worker.js', expect: 'no-cache' },
  { path: '/app/service-worker.js', expect: 'no-cache' },
  { path: '/INDEX.HTML', expect: 'no-cache' },
  { path: '/Service-Worker.JS', expect: 'no-cache' },
  { path: '/main.js', expect: 'max-age=31536000' },
  { path: '/worker.js', expect: 'max-age=31536000' },
  { path: '/html.txt', expect: 'max-age=31536000' },
  { path: '/assets/bg.png', expect: 'max-age=31536000' },
  { path: '/data/en.json', expect: 'max-age=31536000' },
  { path: '/', expect: 'max-age=31536000' }
];

/** 下载候选地址（镜像前缀 + 直连兜底） */
export const DOWNLOAD_CANDIDATE_CASES: DownloadCandidateCase[] = [
  { assetUrl: 'https://github.com/a/b/releases/download/v1/game.zip', mirrors: ['https://m1/', 'https://m2/'], debugAssetUrl: '', expect: ['https://m1/https://github.com/a/b/releases/download/v1/game.zip', 'https://m2/https://github.com/a/b/releases/download/v1/game.zip', 'https://github.com/a/b/releases/download/v1/game.zip'] },
  { assetUrl: 'https://github.com/a/b/releases/download/v1/game.zip', mirrors: [], debugAssetUrl: '', expect: ['https://github.com/a/b/releases/download/v1/game.zip'] },
  { assetUrl: 'http://127.0.0.1:8000/game.zip', mirrors: ['https://m1/', 'https://m2/'], debugAssetUrl: 'http://127.0.0.1:8000/game.zip', expect: ['http://127.0.0.1:8000/game.zip'] },
  { assetUrl: '', mirrors: ['https://m1/', 'https://m2/'], debugAssetUrl: '', expect: [] },
  { assetUrl: 'https://github.com/a/b/releases/download/v1/game.zip', mirrors: ['https://m1/'], debugAssetUrl: '', expect: ['https://m1/https://github.com/a/b/releases/download/v1/game.zip', 'https://github.com/a/b/releases/download/v1/game.zip'] },
  { assetUrl: 'http://127.0.0.1:8000/other.zip', mirrors: ['https://m1/'], debugAssetUrl: 'http://127.0.0.1:8000/game.zip', expect: ['https://m1/http://127.0.0.1:8000/other.zip', 'http://127.0.0.1:8000/other.zip'] }
];

/** 候选地址拆分：参与测速的源 + 直连兜底（null = 没有兜底） */
export const DOWNLOAD_SPLIT_CASES: DownloadSplitCase[] = [
  { candidates: ['https://m1/x', 'https://m2/x', 'https://github.com/x'], expectProbed: ['https://m1/x', 'https://m2/x'], expectFallback: 'https://github.com/x' },
  { candidates: ['https://m1/x', 'https://github.com/x'], expectProbed: ['https://m1/x'], expectFallback: 'https://github.com/x' },
  { candidates: ['http://127.0.0.1:8000/game.zip'], expectProbed: ['http://127.0.0.1:8000/game.zip'], expectFallback: null },
  { candidates: [], expectProbed: [], expectFallback: null }
];

/** 测速排序：快 → 慢 → 失败，兜底最后 */
export const DOWNLOAD_RANK_CASES: DownloadRankCase[] = [
  { probed: [{ url: 'slow', speed: 10 }, { url: 'fast', speed: 900 }, { url: 'mid', speed: 100 }], fallback: 'https://github.com/x', expect: ['fast', 'mid', 'slow', 'https://github.com/x'] },
  { probed: [{ url: 'dead', speed: -1 }, { url: 'timeout', speed: 0 }, { url: 'ok', speed: 50 }], fallback: 'https://github.com/x', expect: ['ok', 'https://github.com/x'] },
  { probed: [{ url: 'only', speed: -1 }], fallback: null, expect: ['only'] },
  { probed: [{ url: 'first', speed: 5 }, { url: 'second', speed: 5 }, { url: 'third', speed: 5 }], fallback: null, expect: ['first', 'second', 'third'] },
  { probed: [{ url: 'dead1', speed: -1 }, { url: 'dead2', speed: -1 }], fallback: 'https://github.com/x', expect: ['https://github.com/x'] },
  { probed: [], fallback: 'https://github.com/x', expect: ['https://github.com/x'] },
  { probed: [], fallback: null, expect: [] }
];

/** 域名提取（日志/界面只显示 host） */
export const DOWNLOAD_HOST_CASES: DownloadHostCase[] = [
  { url: 'https://gh-proxy.com/https://github.com/a/b.zip', expect: 'gh-proxy.com' },
  { url: 'http://127.0.0.1:8000/game.zip', expect: '127.0.0.1:8000' },
  { url: 'bare.host/path', expect: 'bare.host' },
  { url: '', expect: '' },
  { url: 'https://', expect: '' }
];

/** 候选角色：最后一个候选是直连，其余是镜像 */
export const DOWNLOAD_KIND_CASES: DownloadKindCase[] = [
  { index: 0, count: 4, expect: '镜像' },
  { index: 2, count: 4, expect: '镜像' },
  { index: 3, count: 4, expect: '直连' },
  { index: 0, count: 1, expect: '直连' },
  { index: 0, count: 0, expect: '直连' }
];

/** 加速镜像前缀套用（首页「下载太慢？」） */
export const DOWNLOAD_MIRROR_CASES: DownloadMirrorCase[] = [
  { url: 'https://github.com/a/b/releases/download/v1/game.zip', mirrors: ['https://m1/', 'https://m2/'], expect: 'https://m1/https://github.com/a/b/releases/download/v1/game.zip' },
  { url: 'https://gitee.com/a/b.zip', mirrors: ['https://m1/'], expect: '' },
  { url: 'https://github.com.evil.com/a/b.zip', mirrors: ['https://m1/'], expect: '' },
  { url: 'https://github.com/a/b.zip', mirrors: [], expect: '' },
  { url: '', mirrors: ['https://m1/'], expect: '' }
];
