import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0000;
const TAG: string = 'Prefs';

/** 本应用统一使用的偏好库名（作弊 / 更新 / 方向等共用） */
export const SETTINGS_STORE: string = 'pokelauncher_settings';

/** 按库名缓存 Preferences 实例，避免各处反复 getPreferences（每次都有 IPC 开销） */
const cache: Map<string, Promise<preferences.Preferences>> = new Map();

/** 获取（并缓存）偏好库实例；首次失败时移除缓存，允许下次重试 */
export function getStore(ctx: common.UIAbilityContext,
  name: string = SETTINGS_STORE): Promise<preferences.Preferences> {
  let pending: Promise<preferences.Preferences> | undefined = cache.get(name);
  if (pending === undefined) {
    pending = preferences.getPreferences(ctx, name);
    cache.set(name, pending);
    pending.catch((err: Error) => {
      cache.delete(name);
      hilog.warn(DOMAIN, TAG, 'getPreferences %{public}s failed: %{public}s', name, JSON.stringify(err));
    });
  }
  return pending;
}
