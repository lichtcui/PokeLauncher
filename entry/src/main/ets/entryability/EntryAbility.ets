import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';
import { webview } from '@kit.ArkWeb';
import { startSharedServer } from '../model/LocalHttpServer';
import { GameRepository } from '../model/GameRepository';
import { loadCheatState } from '../model/CheatState';
import { initSaveGuard } from '../model/SaveGuard';
import { setMainWindow } from '../model/WindowHolder';
import { GAME_DIR_NAME, DEBUG_UI } from '../common/Const';

const DOMAIN: number = 0x0000;
const TAG: string = 'PokeLauncher';

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

    // 恢复上次被中断的资源切换（必须在服务/首页读取游戏目录之前完成）
    new GameRepository(this.context).recover();

    // 提前启动本地 HTTP 服务（游戏页直接加载固定地址，避免端口未就绪）
    startSharedServer(this.context.filesDir + '/' + GAME_DIR_NAME);

    // 载入作弊状态（供 /__cheats__.js 动态下发）
    loadCheatState(this.context).catch((err: Error) => {
      hilog.warn(DOMAIN, TAG, 'loadCheatState failed: %{public}s', JSON.stringify(err));
    });

    // 存档快照目录（filesDir 下，不受「清除缓存」影响）
    initSaveGuard(this.context);

    // 启动默认竖屏（首页/设置页恒竖屏；游戏页按用户选择在 onPageShow 应用方向）
    try {
      const win: window.Window = windowStage.getMainWindowSync();
      win.setPreferredOrientation(window.Orientation.PORTRAIT);
      // 不使用全屏布局：内容避让系统安全区（游戏底部按键不会被手势栏压住）。
      // 底部留白用 setWindowBackgroundColor 染成游戏背景色来盖住。
      win.setWindowLayoutFullScreen(false).catch((err: Error) => {
        hilog.warn(DOMAIN, TAG, 'setWindowLayoutFullScreen failed: %{public}s', JSON.stringify(err));
      });
      win.setWindowSystemBarEnable(['status']).catch((err: Error) => {
        hilog.warn(DOMAIN, TAG, 'setWindowSystemBarEnable failed: %{public}s', JSON.stringify(err));
      });
      setMainWindow(win);
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
