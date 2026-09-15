import { router } from '@kit.ArkUI';
import { CHEATS, Cheat, CheatParam } from '../model/Cheats';
import { isMasterEnabled, isCheatEnabled, toggleCheat, getParamNumber, setParam } from '../model/CheatState';

/**
 * 作弊条目页（独立页面）：从首页就绪页的「作弊设置」入口进入。
 * 条目统一为「加载时注入」，更改在开始游戏时随全新加载生效。
 * 声明了 param 的条目额外显示一个数值滑块（倍率等）。
 */
@Entry
@Component
struct Cheats {
  @State flags: boolean[] = [];
  @State paramValues: number[] = [];
  @State masterOn: boolean = false;

  aboutToAppear(): void {
    this.masterOn = isMasterEnabled();
    const f: boolean[] = [];
    const pv: number[] = [];
    for (let i = 0; i < CHEATS.length; i++) {
      const c: Cheat = CHEATS[i];
      f.push(isCheatEnabled(c.id));
      const p: CheatParam | undefined = c.param;
      pv.push(p !== undefined ? getParamNumber(p.id, p.default) : 0);
    }
    this.flags = f;
    this.paramValues = pv;
  }

  private onToggle(index: number, id: string, on: boolean): void {
    const next: boolean[] = this.flags.slice();
    next[index] = on;
    this.flags = next;
    toggleCheat(id);
  }

  private onParamChange(index: number, v: number): void {
    const p: CheatParam | undefined = CHEATS[index].param;
    if (p === undefined) {
      return;
    }
    const next: number[] = this.paramValues.slice();
    next[index] = v;
    this.paramValues = next;
    setParam(p.id, v);
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
        Text('作弊').fontSize(20).fontWeight(FontWeight.Medium)
        Blank()
      }
      .width('100%')
      .padding({ left: 8, right: 16, top: 8, bottom: 4 })

      // 风险提示（常驻）
      Row() {
        Text('账号风险：开启作弊后存档可能被官方标记/封禁，且已污染的存档不建议导入在线版。')
          .fontSize(12)
          .fontColor('#DA3838')
          .layoutWeight(1)
      }
      .width('100%')
      .padding(12)
      .backgroundColor('#FFF3F3')

      if (!this.masterOn) {
        Text('作弊模式未开启，请先到「设置 → 游戏 → 作弊模式」开启。')
          .fontSize(13)
          .fontColor('#999999')
          .width('100%')
          .padding(16)
      }

      Scroll() {
        Column() {
          Text('更改将在开始游戏时生效')
            .fontSize(12)
            .fontColor('#999999')
            .width('100%')
            .padding({ left: 16, right: 16, top: 14, bottom: 6 })

          ForEach(CHEATS, (c: Cheat, index: number) => {
            Column() {
              Row() {
                Column() {
                  Text(c.name).fontSize(16).fontColor('#333333')
                  if (c.desc.length > 0) {
                    Text(c.desc).fontSize(12).fontColor('#999999').margin({ top: 3 })
                  }
                }
                .alignItems(HorizontalAlign.Start)
                .layoutWeight(1)

                Toggle({ type: ToggleType.Switch, isOn: this.flags[index] })
                  .selectedColor('#DA3838')
                  .enabled(this.masterOn)
                  .onChange((on: boolean) => this.onToggle(index, c.id, on))
              }
              .width('100%')
              .padding({ left: 16, right: 16, top: 14, bottom: 14 })

              if (c.param !== undefined) {
                Row() {
                  Text(c.param.label)
                    .fontSize(13)
                    .fontColor('#666666')
                    .width(52)
                  Slider({
                    value: this.paramValues[index],
                    min: c.param.min,
                    max: c.param.max,
                    step: c.param.step !== undefined ? c.param.step : 1,
                    style: SliderStyle.OutSet
                  })
                    .layoutWeight(1)
                    .selectedColor('#DA3838')
                    .enabled(this.masterOn && this.flags[index])
                    .onChange((v: number, mode: SliderChangeMode) => this.onParamChange(index, v))
                  Text((c.param.unit !== undefined ? c.param.unit : '') + this.paramValues[index])
                    .fontSize(14)
                    .fontColor('#333333')
                    .width(56)
                    .textAlign(TextAlign.End)
                }
                .width('100%')
                .padding({ left: 16, right: 16, bottom: 14 })
              }
            }
            .width('100%')
          }, (c: Cheat) => c.id)
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
