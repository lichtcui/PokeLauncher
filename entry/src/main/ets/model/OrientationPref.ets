import { window } from '@kit.ArkUI';
import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { getStore, SETTINGS_STORE } from '../common/Prefs';

const DOMAIN: number = 0x0000;
const TAG: string = 'OrientationPref';

const KEY: string = 'orientation';

/** 屏幕方向偏好值 */
export const ORI_PORTRAIT: string = 'portrait';
export const ORI_LANDSCAPE: string = 'landscape';
export const ORI_SYSTEM: string = 'system';

/** 默认竖屏（保持现状） */
export const DEFAULT_ORIENTATION: string = ORI_PORTRAIT;

/** 偏好值 → 窗口方向枚举 */
export function toOrientation(v: string): window.Orientation {
  if (v === ORI_LANDSCAPE) {
    // 横屏锁定，允许两个横屏方向随传感器翻转
    return window.Orientation.AUTO_ROTATION_LANDSCAPE;
  }
  if (v === ORI_SYSTEM) {
    // 跟随系统自动旋转
    return window.Orientation.UNSPECIFIED;
  }
  return window.Orientation.PORTRAIT;
}

/** 读取屏幕方向偏好（失败回退默认值） */
export async function readOrientation(ctx: common.UIAbilityContext): Promise<string> {
  try {
    const store: preferences.Preferences = await getStore(ctx, SETTINGS_STORE);
    const value: preferences.ValueType = await store.get(KEY, DEFAULT_ORIENTATION);
    return value as string;
  } catch (err) {
    hilog.warn(DOMAIN, TAG, 'readOrientation failed: %{public}s', JSON.stringify(err));
    return DEFAULT_ORIENTATION;
  }
}

/** 保存屏幕方向偏好 */
export async function saveOrientation(ctx: common.UIAbilityContext, v: string): Promise<void> {
  const store: preferences.Preferences = await getStore(ctx, SETTINGS_STORE);
  await store.put(KEY, v);
  await store.flush();
}
