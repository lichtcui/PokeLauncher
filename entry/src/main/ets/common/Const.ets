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

/** 默认资源包资产名（原版） */
export const DEFAULT_ASSET: string = 'game.zip';

/** 沙箱内游戏目录名（filesDir/game） */
export const GAME_DIR_NAME: string = 'game';
