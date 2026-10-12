/**
 * 下载候选地址的构造与排序（纯函数，无副作用）。
 * 与安卓版 `install/DownloadUrls.kt` 一一对应：那边的文件头过去写着
 * 「对应鸿蒙版 buildDownloadUrls / rankSources」「与鸿蒙版 Index.mirrorOf 同规则」——
 * 靠注释维系的一致性，改一端漏另一端时没有任何东西会红（候选少一个镜像、
 * 失败源没被跳过、单源 debug 包被当成“没测速结果”跳过，都要真机下载才看得出来）。
 * 现在两端都从这里取规则，期望值放 `shared/cases/download.json`，两端测试遍历同一套向量。
 */

/** 一次测速的结果。[speed] 单位 bytes/ms（与原实现口径一致），<= 0 表示失败或超时 */
export interface ProbeResult {
  url: string;
  speed: number;
}

/** 候选地址的拆分结果：参与测速的源 + 直连兜底（fallback 为空串 = 没有兜底） */
export interface SourceSplit {
  probed: string[];
  fallback: string;
}

/** GitHub 直链前缀：只有这种地址才套加速镜像 */
export const GITHUB_PREFIX: string = 'https://github.com/';

/**
 * 候选地址：镜像前缀逐个拼接，**最后**回退 GitHub 直连。
 * 本地调试包不走镜像（地址本身可达，且镜像基本只代理 github.com）；
 * 空地址返回空列表（否则会去下载「镜像前缀 + 空串」这种网址）。
 */
export function downloadCandidates(assetUrl: string, mirrors: string[], debugAssetUrl: string): string[] {
  if (assetUrl.length === 0) {
    return [];
  }
  if (debugAssetUrl.length > 0 && assetUrl === debugAssetUrl) {
    return [assetUrl];
  }
  const urls: string[] = [];
  for (let i = 0; i < mirrors.length; i++) {
    urls.push(mirrors[i] + assetUrl);
  }
  urls.push(assetUrl);
  return urls;
}

/** 拆分参与测速的镜像 与 直连兜底：单源时兜底为空串（它失败了就没有别的源） */
export function splitCandidates(candidates: string[]): SourceSplit {
  if (candidates.length > 1) {
    return {
      probed: candidates.slice(0, candidates.length - 1),
      fallback: candidates[candidates.length - 1]
    };
  }
  return { probed: candidates.slice(), fallback: '' };
}

/**
 * 按测速结果排序：快 → 慢 → 失败。
 *
 * - 有直连兜底（[fallback] 非空）时**跳过测速失败的镜像**，避免下载阶段再撞一次死源；
 * - 单源（调试包）不跳过：它是唯一候选，仍要尝试下载；
 * - 直连兜底**不参与测速**（白读 128KB 且通常最慢），永远排在最后；
 * - 同速保持传入顺序（排序必须稳定）。
 */
export function rankSourcesByProbe(probed: ProbeResult[], fallback: string): string[] {
  const hasFallback: boolean = fallback.length > 0;
  const idx: number[] = [];
  for (let i = 0; i < probed.length; i++) {
    idx.push(i);
  }
  idx.sort((a: number, b: number) => probed[b].speed - probed[a].speed);
  const sorted: string[] = [];
  for (let i = 0; i < idx.length; i++) {
    const sp: number = probed[idx[i]].speed;
    if (hasFallback && sp <= 0) {
      continue;
    }
    sorted.push(probed[idx[i]].url);
  }
  if (hasFallback) {
    sorted.push(fallback);
  }
  return sorted;
}

/** `https://gh-proxy.com/https://github.com/a/b.zip` → `gh-proxy.com`（日志只显示 host） */
export function hostOf(url: string): string {
  const schemeAt: number = url.indexOf('://');
  const rest: string = schemeAt >= 0 ? url.substring(schemeAt + 3) : url;
  const slash: number = rest.indexOf('/');
  return slash >= 0 ? rest.substring(0, slash) : rest;
}

/** 候选列表里的角色：最后一个候选是 GitHub 直连，其余是镜像 */
export function kindOf(index: number, count: number): string {
  return index < count - 1 ? '镜像' : '直连';
}

/**
 * 给 GitHub 原始直链套上首个加速镜像（首页「下载太慢？」里展示给用户复制）。
 * 非 GitHub 地址或没有配置镜像时返回空串（调用方据此只显示原始直链）。
 */
export function mirrorOf(url: string, mirrors: string[]): string {
  if (mirrors.length === 0 || !url.startsWith(GITHUB_PREFIX)) {
    return '';
  }
  return mirrors[0] + url;
}
