import { window } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0000;
const TAG: string = 'WindowHolder';

/** 游戏背景色（PokeRogue 外层背景） */
export const GAME_BG_COLOR: string = '#484050';
/** 首页背景色 */
export const HOME_BG_COLOR: string = '#FFFFFF';

let mainWindow: window.Window | null = null;

export function setMainWindow(w: window.Window): void {
  mainWindow = w;
}

/** 设置窗口方向（仅游戏页使用；首页/设置页始终保持竖屏） */
export function setOrientation(o: window.Orientation): void {
  const w: window.Window | null = mainWindow;
  if (w === null) {
    return;
  }
  try {
    w.setPreferredOrientation(o);
  } catch (err) {
    hilog.warn(DOMAIN, TAG, 'setPreferredOrientation failed: %{public}s', JSON.stringify(err));
  }
}

/** 设置窗口背景色（会显示在安全区/系统栏留白处） */
export function setWindowBackground(color: string): void {
  const w: window.Window | null = mainWindow;
  if (w === null) {
    return;
  }
  try {
    w.setWindowBackgroundColor(color);
  } catch (err) {
    hilog.warn(DOMAIN, TAG, 'setWindowBackgroundColor failed: %{public}s', JSON.stringify(err));
  }
}
