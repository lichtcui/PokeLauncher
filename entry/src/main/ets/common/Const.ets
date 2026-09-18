/**
 * 全局常量
 */

import { deviceInfo } from '@kit.BasicServicesKit';

/**
 * 调试开关：true 时强制走「低版本 API」分支（`apiAtLeast()` 恒返回 false），
 * 用于在现有高版本设备上验证降级路径。正式发布必须为 false。
 */
export const DEBUG_FORCE_OLD_API: boolean = false;

/** 系统 API 等级是否 >= n（`DEBUG_FORCE_OLD_API` 为 true 时恒 false，便于本地验证兜底） */
export function apiAtLeast(n: number): boolean {
  return !DEBUG_FORCE_OLD_API && deviceInfo.sdkApiVersion >= n;
}

/** GitHub Release API（离线资源包来源） */
export const RELEASE_API: string =
  'https://api.github.com/repos/Admiral-Billy/pokerogue/releases/latest';

/** GitHub API 需要的 User-Agent */
export const USER_AGENT: string = 'PokeLauncher-Harmony';

/**
 * 下载镜像前缀（按顺序尝试），拼接在原始 GitHub URL 之前：
 * <mirror><https://github.com/.../game.zip>
 * 全部失败后自动回退到 GitHub 直连。
 *
 * 顺序按实测速度排（2026-09 本机 256KB Range 探测）：
 * gh-proxy.com 125~146 KB/s > ghfast.top 54~76 KB/s > ghproxy.net 7~9 KB/s。
 * gh.llkk.cc 已删：域名被 DNS 劫持（解析到 69.63.x.x），是死源。
 */
export const DOWNLOAD_MIRRORS: string[] = [
  'https://gh-proxy.com/',
  'https://ghfast.top/',
  'https://ghproxy.net/'
];

/** API 镜像（部分镜像不支持 api.github.com，实测 gh-proxy.com 可用） */
export const API_MIRROR: string = 'https://gh-proxy.com/';

/** 默认资源包资产名（原版） */
export const DEFAULT_ASSET: string = 'game.zip';

/**
 * 开发模式：正式发布必须为 false。
 * - true：开启 ArkWeb 远程调试（`setWebDebuggingAccess`，见 `docs/debugging.md`）；
 * - false：关闭，避免调试端口暴露。
 */
export const DEBUG_UI: boolean = false;

/**
 * 调试开关：非空时，离线资源包改用这个 URL 下载（跳过 GitHub Release 查询与镜像），
 * 用于本地放一个小测试包、几秒内跑完「测速 → 下载 → 解压 → 爆发 → 就绪」全链路。
 * 只用于本地联调，正式发布必须为空串。
 */
export const DEBUG_ASSET_URL: string = '';

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
  'https://gitee.com/licht3345/PokeLauncher/raw/master/version.json';

/** 应用下载页（检测到新版本后用浏览器打开） */
export const APP_UPDATE_PAGE_URL: string =
  'https://gitee.com/licht3345/PokeLauncher/releases';

/** 项目 GitHub 仓库（设置页「关于」入口用浏览器打开） */
export const PROJECT_GITHUB_URL: string =
  'https://github.com/lichtcui/PokeLauncher';

/** 启动时自动检查应用更新的最小间隔（24 小时节流） */
export const APP_UPDATE_CHECK_INTERVAL_MS: number = 24 * 60 * 60 * 1000;
