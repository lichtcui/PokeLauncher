import { common } from '@kit.AbilityKit';
import { router, promptAction } from '@kit.ArkUI';
import { webview } from '@kit.ArkWeb';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { GameRepository, ReleaseInfo, normalizeVersion } from '../model/GameRepository';
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
  @State version: string = '';
  @State updateAvailable: boolean = false;
  @State checking: boolean = false;
  @State deleting: boolean = false;
  @State orientation: string = ORI_PORTRAIT;
  @State cheatMaster: boolean = false;

  private repo: GameRepository | null = null;

  aboutToAppear(): void {
    const ctx: common.UIAbilityContext = this.getUIContext().getHostContext() as common.UIAbilityContext;
    this.repo = new GameRepository(ctx);
    this.version = this.repo.readVersion();
    readOrientation(ctx).then((v: string) => {
      this.orientation = v;
    });
    this.cheatMaster = isMasterEnabled();
    this.checkUpdate();
  }

  onPageShow(): void {
    // 底部安全区留白用白色
    setWindowBackground(HOME_BG_COLOR);
  }

  /** 查询远端最新版本，与本地比较 */
  private checkUpdate(): void {
    const repo: GameRepository | null = this.repo;
    if (repo === null || this.checking) {
      return;
    }
    this.checking = true;
    repo.fetchRelease()
      .then((info: ReleaseInfo) => {
        this.updateAvailable = normalizeVersion(info.tag) !== normalizeVersion(this.version);
        this.checking = false;
      })
      .catch((err: BusinessError) => {
        this.checking = false;
        hilog.warn(DOMAIN, TAG, 'check update failed: %{public}s', JSON.stringify(err));
      });
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
      message: '将删除已下载的游戏核心包，游戏存档不受影响。确定删除吗？',
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

  /**
   * 刷新 Web 缓存（HTTP 缓存 + JS 代码缓存）。
   * 手动改过游戏资源包后，缓存不刷新会继续用旧文件，这里强制清掉。
   */
  private refreshCache(): void {
    try {
      webview.WebviewController.removeAllCache(true);
      promptAction.showToast({ message: '缓存已刷新，下次进入游戏将重新加载资源' });
    } catch (err) {
      hilog.error(DOMAIN, TAG, 'removeAllCache failed: %{public}s', JSON.stringify(err));
      promptAction.showToast({ message: '刷新缓存失败' });
    }
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
          // 检查更新：状态文字随 @State 变化，需直接读取状态（@Builder 值参数不触发刷新）
          Row() {
            Text('检查更新').fontSize(16).fontColor('#333333')
            Blank()
            Text(this.checking ? '检查中…' : (this.updateAvailable ? '发现新版本' : '已是最新'))
              .fontSize(14)
              .fontColor(this.updateAvailable ? '#DA3838' : '#999999')
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 15, bottom: 15 })
          .onClick(() => this.checkUpdate())
          if (this.updateAvailable) {
            this.divider()
            this.navRow('更新到新版本', '', '#999999', true, () => this.triggerUpdate())
          }
          this.divider()
          this.navRow('刷新缓存', '', '#999999', true, () => this.refreshCache())
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
          this.navRow('版本',
            this.version.length > 0 ? normalizeVersion(this.version) : '未知',
            '#999999', false, () => {})
          this.divider()
          this.navRow('资源来源', '原版 PokeRogue', '#999999', false, () => {})

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
