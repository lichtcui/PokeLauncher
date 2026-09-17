import { bundleManager, common, Want } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import {
  APP_UPDATE_MANIFEST_URL,
  APP_UPDATE_PAGE_URL,
  APP_UPDATE_CHECK_INTERVAL_MS
} from '../common/Const';
import { getStore, SETTINGS_STORE } from '../common/Prefs';
import { requestText } from '../common/Http';

const DOMAIN: number = 0x0000;
const TAG: string = 'AppUpdate';

const KEY_LAST_CHECK: string = 'appUpdateLastCheck';

/** 本地应用版本（来自 AppScope/app.json5） */
export class AppVersion {
  versionCode: number = 0;
  versionName: string = '';
}

/** 远程 version.json 的宽松解析结果（字段可能缺失） */
class ManifestJson {
  versionCode?: number;
  versionName?: string;
  minVersionCode?: number;
  forceUpdate?: boolean;
  changelog?: string;
  downloadUrl?: string;
  pageUrl?: string;
}

/** 远程最新版本信息 */
export class AppUpdateInfo {
  versionCode: number = 0;
  versionName: string = '';
  minVersionCode: number = 0;
  forceUpdate: boolean = false;
  changelog: string = '';
  downloadUrl: string = '';
  pageUrl: string = '';
}

/** 检查结果 */
export class AppUpdateResult {
  hasUpdate: boolean = false;
  forceUpdate: boolean = false;
  local: AppVersion = new AppVersion();
  remote: AppUpdateInfo | null = null;
}

/** 读取本地应用版本；失败返回 versionCode=0 */
export function readLocalVersion(): AppVersion {
  const v: AppVersion = new AppVersion();
  try {
    const info: bundleManager.BundleInfo = bundleManager.getBundleInfoForSelfSync(
      bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
    v.versionCode = info.versionCode;
    v.versionName = info.versionName;
  } catch (err) {
    hilog.warn(DOMAIN, TAG, 'getBundleInfoForSelfSync failed: %{public}s', JSON.stringify(err));
  }
  return v;
}

/** 拉取并解析远程 version.json */
async function fetchManifest(): Promise<AppUpdateInfo> {
  const text: string = await requestText(APP_UPDATE_MANIFEST_URL);
  const raw: ManifestJson = JSON.parse(text) as ManifestJson;
  const info: AppUpdateInfo = new AppUpdateInfo();
  info.versionCode = raw.versionCode !== undefined ? Number(raw.versionCode) : 0;
  info.versionName = raw.versionName !== undefined ? String(raw.versionName) : '';
  info.minVersionCode = raw.minVersionCode !== undefined ? Number(raw.minVersionCode) : 0;
  info.forceUpdate = raw.forceUpdate === true;
  info.changelog = raw.changelog !== undefined ? String(raw.changelog) : '';
  info.downloadUrl = raw.downloadUrl !== undefined ? String(raw.downloadUrl) : '';
  info.pageUrl = raw.pageUrl !== undefined ? String(raw.pageUrl) : '';
  return info;
}

/** 检查应用更新（本地 versionCode vs 远程） */
export async function checkAppUpdate(): Promise<AppUpdateResult> {
  const result: AppUpdateResult = new AppUpdateResult();
  result.local = readLocalVersion();
  const remote: AppUpdateInfo = await fetchManifest();
  result.remote = remote;
  result.hasUpdate = remote.versionCode > result.local.versionCode;
  result.forceUpdate = remote.forceUpdate ||
    (remote.minVersionCode > 0 && result.local.versionCode < remote.minVersionCode);
  hilog.info(DOMAIN, TAG, 'app update check local=%{public}d remote=%{public}d hasUpdate=%{public}s force=%{public}s',
    result.local.versionCode, remote.versionCode,
    result.hasUpdate.toString(), result.forceUpdate.toString());
  return result;
}

/** 是否到了自动检查的时间（默认 24h 一次） */
export async function shouldAutoCheck(ctx: common.UIAbilityContext): Promise<boolean> {
  try {
    const store: preferences.Preferences = await getStore(ctx, SETTINGS_STORE);
    const last: number = await store.get(KEY_LAST_CHECK, 0) as number;
    return Date.now() - last >= APP_UPDATE_CHECK_INTERVAL_MS;
  } catch (err) {
    hilog.warn(DOMAIN, TAG, 'shouldAutoCheck failed: %{public}s', JSON.stringify(err));
    return true;
  }
}

/** 记录本次检查时间（仅在成功拿到清单后调用） */
export async function markChecked(ctx: common.UIAbilityContext): Promise<void> {
  try {
    const store: preferences.Preferences = await getStore(ctx, SETTINGS_STORE);
    await store.put(KEY_LAST_CHECK, Date.now());
    await store.flush();
  } catch (err) {
    hilog.warn(DOMAIN, TAG, 'markChecked failed: %{public}s', JSON.stringify(err));
  }
}

/** 用系统浏览器打开下载页（应用内无法静默安装 HAP，只能引导） */
export function openDownloadPage(ctx: common.UIAbilityContext, url: string): void {
  const target: string = url.length > 0 ? url : APP_UPDATE_PAGE_URL;
  const want: Want = {
    action: 'ohos.want.action.viewData',
    entities: ['entity.system.browsable'],
    uri: target
  };
  ctx.startAbility(want).catch((err: BusinessError) => {
    hilog.warn(DOMAIN, TAG, 'startAbility failed: %{public}s url=%{public}s', JSON.stringify(err), target);
  });
}
