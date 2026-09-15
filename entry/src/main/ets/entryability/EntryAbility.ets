import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';
import { webview } from '@kit.ArkWeb';
import { startSharedServer } from '../model/LocalHttpServer';
import { GAME_DIR_NAME, DEBUG_UI } from '../common/Const';

const DOMAIN: number = 0x0000;
const TAG: string = 'PokeRogue';

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(DOMAIN, TAG, '%{public}s', 'Ability onCreate');
    if (DEBUG_UI) {
      // 开发用：开启 Web 远程调试（hdc fport tcp:9222 tcp:9222 后可用 DevTools）
      try {
        webview.WebviewController.setWebDebuggingAccess(true);
      } catch (err) {
        hilog.warn(DOMAIN, TAG, 'setWebDebuggingAccess failed: %{public}s', JSON.stringify(err));
      }
    }
  }

  onDestroy(): void {
    hilog.info(DOMAIN, TAG, '%{public}s', 'Ability onDestroy');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    hilog.info(DOMAIN, TAG, '%{public}s', 'Ability onWindowStageCreate');

    // 提前启动本地 HTTP 服务（游戏页直接加载固定地址，避免端口未就绪）
    startSharedServer(this.context.filesDir + '/' + GAME_DIR_NAME);

    // 默认竖屏（后续可在设置中扩展「跟随系统 / 横屏锁定」）
    try {
      const win: window.Window = windowStage.getMainWindowSync();
      win.setPreferredOrientation(window.Orientation.PORTRAIT);
      // 保留顶部状态栏（时间/电量，同时避开挖孔摄像头），隐藏底部导航栏（避免底部白条）。
      // 不使用 layoutFullScreen，让内容区域自动避开状态栏。
      win.setWindowLayoutFullScreen(false).catch((err: Error) => {
        hilog.warn(DOMAIN, TAG, 'setWindowLayoutFullScreen failed: %{public}s', JSON.stringify(err));
      });
      win.setWindowSystemBarEnable(['status']).catch((err: Error) => {
        hilog.warn(DOMAIN, TAG, 'setWindowSystemBarEnable failed: %{public}s', JSON.stringify(err));
      });
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'window setup failed: %{public}s', JSON.stringify(err));
    }

    windowStage.loadContent('pages/Index', (err) => {
      if (err.code) {
        hilog.error(DOMAIN, TAG, 'Failed to load the content. Cause: %{public}s', JSON.stringify(err) ?? '');
        return;
      }
      hilog.info(DOMAIN, TAG, '%{public}s', 'Succeeded in loading the content.');
    });
  }

  onWindowStageDestroy(): void {
    hilog.info(DOMAIN, TAG, '%{public}s', 'Ability onWindowStageDestroy');
  }

  onForeground(): void {
    hilog.info(DOMAIN, TAG, '%{public}s', 'Ability onForeground');
  }

  onBackground(): void {
    hilog.info(DOMAIN, TAG, '%{public}s', 'Ability onBackground');
  }
}
