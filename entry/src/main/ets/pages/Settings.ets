import { common } from '@kit.AbilityKit';
import { router, promptAction } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { GameRepository, ReleaseInfo, normalizeVersion } from '../model/GameRepository';
import {
  AppUpdateInfo,
  AppUpdateResult,
  AppVersion,
  checkAppUpdate,
  openDownloadPage,
  readLocalVersion
} from '../model/AppUpdate';
import { setWindowBackground, HOME_BG_COLOR } from '../model/WindowHolder';
import { ORI_PORTRAIT, ORI_LANDSCAPE, ORI_SYSTEM, readOrientation, saveOrientation } from '../model/OrientationPref';
import { isMasterEnabled, setMaster } from '../model/CheatState';

const DOMAIN: number = 0x0000;
const TAG: string = 'Settings';

/**
 * 设置页：从首页右上角齿轮进入。
 * 承载游戏选项（横屏/作弊，后续）、数据管理（更新/缓存/删除）与关于信息。
 */
@Entry
@Component
struct Settings {
  @State gameVersion: string = '';
  @State gameUpdateAvailable: boolean = false;
  @State checkingGame: boolean = false;
  @State appVersion: string = '';
  @State appUpdateAvailable: boolean = false;
  @State appUpdateForce: boolean = false;
  @State checkingApp: boolean = false;
  @State appCheckFailed: boolean = false;
  @State appRemoteName: string = '';
  @State appChangelog: string = '';
  @State deleting: boolean = false;
  @State orientation: string = ORI_PORTRAIT;
  @State cheatMaster: boolean = false;

  private repo: GameRepository | null = null;
  private appRemote: AppUpdateInfo | null = null;

  aboutToAppear(): void {
    const ctx: common.UIAbilityContext = this.getUIContext().getHostContext() as common.UIAbilityContext;
    this.repo = new GameRepository(ctx);
    this.gameVersion = this.repo.readVersion();
    const local: AppVersion = readLocalVersion();
    this.appVersion = local.versionName.length > 0
      ? local.versionName + ' (' + local.versionCode + ')'
      : '未知';
    readOrientation(ctx).then((v: string) => {
      this.orientation = v;
    });
    this.cheatMaster = isMasterEnabled();
    this.checkGameUpdate();
    this.checkAppUpdate();
  }

  onPageShow(): void {
    // 底部安全区留白用白色
    setWindowBackground(HOME_BG_COLOR);
  }

  /** 查询远端最新游戏资源包，与本地比较 */
  private checkGameUpdate(): void {
    const repo: GameRepository | null = this.repo;
    if (repo === null || this.checkingGame) {
      return;
    }
    this.checkingGame = true;
    repo.fetchRelease()
      .then((info: ReleaseInfo) => {
        this.gameUpdateAvailable = normalizeVersion(info.tag) !== normalizeVersion(this.gameVersion);
        this.checkingGame = false;
      })
      .catch((err: BusinessError) => {
        this.checkingGame = false;
        hilog.warn(DOMAIN, TAG, 'check game update failed: %{public}s', JSON.stringify(err));
      });
  }

  /** 查询远端应用版本，与本地 app 版本比较 */
  private checkAppUpdate(): void {
    if (this.checkingApp) {
      return;
    }
    this.checkingApp = true;
    this.appCheckFailed = false;
    checkAppUpdate()
      .then((res: AppUpdateResult) => {
        this.checkingApp = false;
        this.appRemote = res.remote;
        this.appUpdateAvailable = res.hasUpdate;
        this.appUpdateForce = res.forceUpdate;
        const remote: AppUpdateInfo | null = res.remote;
        this.appRemoteName = remote !== null && remote.versionName.length > 0
          ? remote.versionName
          : (remote !== null ? String(remote.versionCode) : '');
        this.appChangelog = remote !== null ? remote.changelog : '';
      })
      .catch((err: BusinessError) => {
        this.checkingApp = false;
        this.appCheckFailed = true;
        hilog.warn(DOMAIN, TAG, 'check app update failed: %{public}s', JSON.stringify(err));
      });
  }

  /** 打开应用下载页（应用内无法静默安装 HAP） */
  private openAppDownload(): void {
    const ctx: common.UIAbilityContext = this.getUIContext().getHostContext() as common.UIAbilityContext;
    const remote: AppUpdateInfo | null = this.appRemote;
    const url: string = remote !== null && remote.pageUrl.length > 0 ? remote.pageUrl : '';
    openDownloadPage(ctx, url);
  }

  /** 更新走首页已有的下载/解压流程：置标记后返回首页触发 */
  private triggerUpdate(): void {
    AppStorage.setOrCreate('pendingUpdate', true);
    router.back();
  }

  /** 删除是危险操作：先二次确认 */
  private confirmDelete(): void {
    if (this.deleting || this.repo === null) {
      return;
    }
    promptAction.showDialog({
      title: '删除本地数据',
      message: '将删除已下载的游戏资源，游戏存档不受影响。确定删除吗？',
      buttons: [
        { text: '取消', color: '#666666' },
        { text: '删除', color: '#DA3838' }
      ]
    }).then((result: promptAction.ShowDialogSuccessResponse) => {
      if (result.index === 1) {
        this.doDelete();
      }
    }).catch((err: BusinessError) => {
      hilog.warn(DOMAIN, TAG, 'showDialog failed: %{public}s', JSON.stringify(err));
    });
  }

  private async doDelete(): Promise<void> {
    const repo: GameRepository | null = this.repo;
    if (repo === null || this.deleting) {
      return;
    }
    this.deleting = true;
    try {
      await repo.clear();
      promptAction.showToast({ message: '本地数据已删除' });
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'clear failed: %{public}s', JSON.stringify(err));
      promptAction.showToast({ message: '删除失败' });
    }
    this.deleting = false;
    router.back();
  }

  /** 应用屏幕方向：更新状态并持久化（实际旋转在进入游戏页时生效） */
  private applyOrientation(v: string): void {
    if (this.orientation === v) {
      return;
    }
    this.orientation = v;
    const ctx: common.UIAbilityContext = this.getUIContext().getHostContext() as common.UIAbilityContext;
    saveOrientation(ctx, v).catch((err: BusinessError) => {
      hilog.warn(DOMAIN, TAG, 'save orientation failed: %{public}s', JSON.stringify(err));
    });
  }

  /** 作弊模式总开关：开启前必须确认账号风险，未确认则回滚开关状态 */
  private onCheatToggle(on: boolean): void {
    if (!on) {
      this.cheatMaster = false;
      setMaster(false);
      return;
    }
    promptAction.showDialog({
      title: '开启作弊模式',
      message: '账号风险：PokéRogue 官方对作弊行为有检测机制，使用 Hook 修改游戏数据可能使账号被标记（Flagged）甚至封禁。开启后本机存档已不纯净，不建议再导入或上传到在线版。请仅在离线或确认安全的环境下使用。\n\n兼容性风险：作弊依赖游戏内部实现，游戏更新后可能失效或表现异常。',
      buttons: [
        { text: '取消', color: '#666666' },
        { text: '仍然开启', color: '#DA3838' }
      ]
    }).then((result: promptAction.ShowDialogSuccessResponse) => {
      if (result.index === 1) {
        this.cheatMaster = true;
        setMaster(true);
      } else {
        this.cheatMaster = false;
      }
    }).catch((err: BusinessError) => {
      this.cheatMaster = false;
      hilog.warn(DOMAIN, TAG, 'showDialog failed: %{public}s', JSON.stringify(err));
    });
  }

  @Builder
  groupTitle(text: string) {
    Text(text)
      .fontSize(13)
      .fontColor('#999999')
      .width('100%')
      .padding({ left: 16, right: 16 })
      .margin({ top: 22, bottom: 6 })
  }

  @Builder
  navRow(label: string, value: string, valueColor: string, showArrow: boolean, onTap: () => void) {
    Row() {
      Text(label).fontSize(16).fontColor('#333333')
      Blank()
      if (value.length > 0) {
        Text(value).fontSize(14).fontColor(valueColor).margin({ right: 6 })
      }
      if (showArrow) {
        Text('›').fontSize(20).fontColor('#CCCCCC')
      }
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 15, bottom: 15 })
    .onClick(onTap)
  }

  /** 屏幕方向分段项：选中态直接读 this.orientation，故能随状态刷新 */
  @Builder
  segmentItem(value: string, label: string) {
    Text(label)
      .fontSize(14)
      .fontWeight(this.orientation === value ? FontWeight.Medium : FontWeight.Normal)
      .fontColor(this.orientation === value ? '#FFFFFF' : '#666666')
      .backgroundColor(this.orientation === value ? '#DA3838' : '#F2F2F2')
      .textAlign(TextAlign.Center)
      .layoutWeight(1)
      .height(36)
      .borderRadius(18)
      .onClick(() => this.applyOrientation(value))
  }

  @Builder
  comingSoonRow(label: string) {
    Row() {
      Text(label).fontSize(16).fontColor('#BBBBBB')
      Blank()
      Text('即将推出')
        .fontSize(12)
        .fontColor('#999999')
        .backgroundColor('#EEEEEE')
        .borderRadius(9)
        .padding({ left: 8, right: 8, top: 3, bottom: 3 })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 15, bottom: 15 })
  }

  @Builder
  divider() {
    Divider().color('#EDEDED').strokeWidth(1).margin({ left: 16 })
  }

  build() {
    Column() {
      // 顶部返回栏
      Row() {
        Text('‹')
          .fontSize(30)
          .fontColor('#333333')
          .width(44)
          .height(44)
          .textAlign(TextAlign.Center)
          .onClick(() => router.back())
        Text('设置').fontSize(20).fontWeight(FontWeight.Medium)
        Blank()
      }
      .width('100%')
      .padding({ left: 8, right: 16, top: 8, bottom: 4 })

      Scroll() {
        Column() {
          this.groupTitle('游戏')
          // 屏幕方向：分段控件，选中态随 @State 变化，需直接读取状态
          Column() {
            Text('屏幕方向').fontSize(16).fontColor('#333333').width('100%')
            Row({ space: 8 }) {
              this.segmentItem(ORI_PORTRAIT, '竖屏')
              this.segmentItem(ORI_LANDSCAPE, '横屏')
              this.segmentItem(ORI_SYSTEM, '跟随系统')
            }
            .width('100%')
            .margin({ top: 10 })
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 12, bottom: 12 })
          this.divider()
          // 作弊模式总开关（条目在游戏内「作弊」页维护）
          Row() {
            Text('作弊模式').fontSize(16).fontColor('#333333')
            Blank()
            Toggle({ type: ToggleType.Switch, isOn: this.cheatMaster })
              .selectedColor('#DA3838')
              .onChange((on: boolean) => this.onCheatToggle(on))
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 10, bottom: 10 })

          this.groupTitle('数据管理')
          // 应用更新：状态文字随 @State 变化，需直接读取状态（@Builder 值参数不触发刷新）
          Row() {
            Text('应用更新').fontSize(16).fontColor('#333333')
            Blank()
            Text(this.checkingApp ? '检查中…'
              : (this.appCheckFailed ? '检查失败'
                : (this.appUpdateAvailable ? '发现新版本' : '已是最新')))
              .fontSize(14)
              .fontColor(this.appUpdateAvailable ? '#DA3838' : '#999999')
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 15, bottom: 15 })
          .onClick(() => this.checkAppUpdate())
          if (this.appUpdateAvailable) {
            this.divider()
            this.navRow('去更新' + (this.appRemoteName.length > 0 ? '（' + this.appRemoteName + '）' : ''),
              this.appUpdateForce ? '必须更新' : '', '#DA3838', true, () => this.openAppDownload())
            if (this.appChangelog.length > 0) {
              Text(this.appChangelog)
                .fontSize(13)
                .fontColor('#888888')
                .width('100%')
                .padding({ left: 16, right: 16, bottom: 14 })
            }
          }
          this.divider()
          // 游戏资源更新：状态文字随 @State 变化，需直接读取状态（@Builder 值参数不触发刷新）
          Row() {
            Text('游戏资源更新').fontSize(16).fontColor('#333333')
            Blank()
            Text(this.checkingGame ? '检查中…' : (this.gameUpdateAvailable ? '发现新版本' : '已是最新'))
              .fontSize(14)
              .fontColor(this.gameUpdateAvailable ? '#DA3838' : '#999999')
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 15, bottom: 15 })
          .onClick(() => this.checkGameUpdate())
          if (this.gameUpdateAvailable) {
            this.divider()
            this.navRow('更新到新版本', '', '#999999', true, () => this.triggerUpdate())
          }
          this.divider()
          Row() {
            Text(this.deleting ? '正在删除…' : '删除本地数据').fontSize(16).fontColor('#333333')
            Blank()
            Text('›').fontSize(20).fontColor('#CCCCCC')
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 15, bottom: 15 })
          .onClick(() => this.confirmDelete())

          this.groupTitle('关于')
          this.navRow('应用版本', this.appVersion, '#999999', false, () => {})
          this.divider()
          this.navRow('游戏资源版本',
            this.gameVersion.length > 0 ? normalizeVersion(this.gameVersion) : '未安装',
            '#999999', false, () => {})
          this.divider()
          this.navRow('资源来源', '原版 PokeRogue', '#999999', false, () => {})
          this.divider()
          this.navRow('法律与免责声明', '', '#999999', true,
            () => router.pushUrl({ url: 'pages/Legal' }))

          Text('PokeRogue 鸿蒙离线版 · 仅供学习交流')
            .fontSize(12)
            .fontColor('#BBBBBB')
            .margin({ top: 32, bottom: 40 })
        }
        .width('100%')
      }
      .layoutWeight(1)
      .width('100%')
      .align(Alignment.Top)
      .scrollBar(BarState.Off)
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#FFFFFF')
  }
}
