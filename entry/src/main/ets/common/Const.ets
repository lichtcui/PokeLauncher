/**
 * 全局常量
 */

/** 离线伪域名：Web 组件加载该 origin，由 onInterceptRequest 从沙箱提供文件 */
export const GAME_ORIGIN: string = 'https://pokerogue.local';

/** 离线游戏入口 */
export const GAME_START_URL: string = GAME_ORIGIN + '/index.html';

/** GitHub Release API（离线资源包来源） */
export const RELEASE_API: string =
  'https://api.github.com/repos/Admiral-Billy/pokerogue/releases/latest';

/** GitHub API 需要的 User-Agent */
export const USER_AGENT: string = 'Pokerogue-Harmony';

/**
 * 下载镜像前缀（按顺序尝试），拼接在原始 GitHub URL 之前：
 * <mirror><https://github.com/.../game.zip>
 * 全部失败后自动回退到 GitHub 直连。
 */
export const DOWNLOAD_MIRRORS: string[] = [
  'https://ghproxy.net/',
  'https://gh-proxy.com/',
  'https://ghfast.top/',
  'https://gh.llkk.cc/'
];

/** API 镜像（部分镜像不支持 api.github.com，实测 gh-proxy.com 可用） */
export const API_MIRROR: string = 'https://gh-proxy.com/';

/** 默认资源包资产名（原版） */
export const DEFAULT_ASSET: string = 'game.zip';

/**
 * 开发模式 UI：
 * - true：下载页显示资产名与实际使用的下载源（镜像 host / 序号），便于调试；
 * - false（正式发布）：隐藏这些技术细节，只显示进度。
 */
export const DEBUG_UI: boolean = true;

/** 沙箱内游戏目录名（filesDir/game） */
export const GAME_DIR_NAME: string = 'game';
