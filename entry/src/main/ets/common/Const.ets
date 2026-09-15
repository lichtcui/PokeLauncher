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

/**
 * 实验开关：强制游戏回退到 Canvas 渲染器（绕过 ArkWeb 慢的 WebGL texImage2D）。
 * 结论：无效——PokeRogue 依赖 WebGL，禁用后会报
 * "Uncaught Error: Cannot create WebGL context, aborting." 并卡死。故默认关闭。
 */
export const FORCE_CANVAS: boolean = false;

/**
 * 实验开关：强制所有 2D canvas 使用 willReadFrequently（CPU 后备）。
 * 背景：Phaser 的 CanvasTexture 对每个 Text 都调用 getImageData 拷贝像素，
 * 而 ArkWeb 的 getImageData 有约 0.3ms 的 GPU 读回固定开销；
 * 游戏启动会创建约 2 万个 Text，累计约 8 秒。改为 CPU canvas 可消除该开销。
 */
export const FORCE_WILL_READ_FREQUENTLY: boolean = true;

/** 沙箱内游戏目录名（filesDir/game） */
export const GAME_DIR_NAME: string = 'game';

/**
 * 应用自更新清单（Gitee Raw）。
 * 格式见仓库根目录 version.json；发版时同步更新该文件。
 * 应用内无法静默安装 HAP（需系统级权限），此处只做「检测 + 引导」。
 */
export const APP_UPDATE_MANIFEST_URL: string =
  'https://gitee.com/licht3345/harmony-pokerogue/raw/master/version.json';

/** 应用下载页（检测到新版本后用浏览器打开） */
export const APP_UPDATE_PAGE_URL: string =
  'https://gitee.com/licht3345/harmony-pokerogue/releases';

/** 启动时自动检查应用更新的最小间隔（24 小时节流） */
export const APP_UPDATE_CHECK_INTERVAL_MS: number = 24 * 60 * 60 * 1000;
