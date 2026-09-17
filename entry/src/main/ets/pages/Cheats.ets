import { router } from '@kit.ArkUI';
import { CHEATS, Cheat, CheatGroup, CheatGroupInfo, CHEAT_GROUPS, CheatParam } from '../model/Cheats';
import { isMasterEnabled, isCheatEnabled, toggleCheat, getParamNumber, setParam, setParamLive } from '../model/CheatState';

/**
 * 参数调整条目页（独立页面）：从首页就绪页的「参数调整」入口进入。
 * 条目按分组展示（倍率修改 / 其他），均为「实时生效」（开启后无需重启）。
 * 声明了 param 的条目额外显示一个数值滑块（倍率等）。
 */
@Entry
@Component
struct Cheats {
  @State flags: boolean[] = [];
  @State paramValues: number[] = [];
  @State masterOn: boolean = false;

  /** id → 全局下标，避免每次渲染线性查找 */
  private indexById: Map<string, number> = new Map();

  aboutToAppear(): void {
    this.masterOn = isMasterEnabled();
    const f: boolean[] = [];
    const pv: number[] = [];
    for (let i = 0; i < CHEATS.length; i++) {
      const c: Cheat = CHEATS[i];
      this.indexById.set(c.id, i);
      f.push(isCheatEnabled(c.id));
      const p: CheatParam | undefined = c.param;
      pv.push(p !== undefined ? getParamNumber(p.id, p.default) : 0);
    }
    this.flags = f;
    this.paramValues = pv;
  }

  /** 取某分组下的条目（保持 CHEATS 原顺序） */
  private cheatsIn(group: CheatGroup): Cheat[] {
    const out: Cheat[] = [];
    for (let i = 0; i < CHEATS.length; i++) {
      if (CHEATS[i].group === group) {
        out.push(CHEATS[i]);
      }
    }
    return out;
  }

  /** 条目在 CHEATS 中的全局下标（flags / paramValues 按全局下标存储） */
  private cheatIndex(id: string): number {
    const i: number | undefined = this.indexById.get(id);
    return i !== undefined ? i : -1;
  }

  private onToggle(index: number, id: string, on: boolean): void {
    const next: boolean[] = this.flags.slice();
    next[index] = on;
    this.flags = next;
    toggleCheat(id);
  }

  /** 拖动中只更新内存（高频），松手（End/Click）才落盘，避免连续写偏好库 */
  private onParamChange(index: number, v: number, mode: SliderChangeMode): void {
    const p: CheatParam | undefined = CHEATS[index].param;
    if (p === undefined) {
      return;
    }
    const next: number[] = this.paramValues.slice();
    next[index] = v;
    this.paramValues = next;
    if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
      setParam(p.id, v);
    } else {
      setParamLive(p.id, v);
    }
  }

  /** 参数当前值的显示文本：优先用 labels 映射，否则 unit + 数值 */
  private paramText(index: number): string {
    const p: CheatParam | undefined = CHEATS[index].param;
    if (p === undefined) {
      return '';
    }
    const v: number = this.paramValues[index];
    const labels: string[] | undefined = p.labels;
    if (labels !== undefined && v >= 0 && v < labels.length) {
      return labels[v];
    }
    return (p.unit !== undefined ? p.unit : '') + v;
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
        Text('参数调整').fontSize(20).fontWeight(FontWeight.Medium)
        Blank()
      }
      .width('100%')
      .padding({ left: 8, right: 16, top: 8, bottom: 4 })

      // 风险提示（常驻）
      Column() {
        Text('账号风险：开启后存档可能被官方标记/封禁，且已污染的存档不建议导入在线版。')
          .fontSize(12)
          .fontColor('#DA3838')
          .width('100%')
        Text('兼容性风险：参数调整依赖应用内部实现，资源更新后可能失效或表现异常。')
          .fontSize(12)
          .fontColor('#C77700')
          .width('100%')
          .margin({ top: 6 })
      }
      .alignItems(HorizontalAlign.Start)
      .width('100%')
      .padding(12)
      .backgroundColor('#FFF3F3')

      if (!this.masterOn) {
        Text('参数调整未开启，请先到「设置 → 运行设置 → 参数调整」开启。')
          .fontSize(13)
          .fontColor('#999999')
          .width('100%')
          .padding(16)
      }

      Scroll() {
        Column() {
          ForEach(CHEAT_GROUPS, (g: CheatGroupInfo, index: number) => {
            Column() {
              if (index > 0) {
                Divider()
                  .color('#EDEDED')
                  .strokeWidth(1)
                  .margin({ left: 16, right: 16, top: 6 })
              }

              Column() {
                Row() {
                  Row()
                    .width(4)
                    .height(18)
                    .borderRadius(2)
                    .backgroundColor('#DA3838')
                  Text(g.title)
                    .fontSize(17)
                    .fontWeight(FontWeight.Bold)
                    .fontColor('#1A1A1A')
                    .margin({ left: 8 })
                }
                .alignItems(VerticalAlign.Center)
                .width('100%')

                Text(g.subtitle)
                  .fontSize(12)
                  .fontColor('#999999')
                  .width('100%')
                  .margin({ top: 4, left: 12 })
              }
              .alignItems(HorizontalAlign.Start)
              .width('100%')
              .padding({ left: 16, right: 16, top: 18, bottom: 6 })

              ForEach(this.cheatsIn(g.id), (c: Cheat) => {
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

                    Toggle({ type: ToggleType.Switch, isOn: this.flags[this.cheatIndex(c.id)] })
                      .selectedColor('#DA3838')
                      .enabled(this.masterOn)
                      .onChange((on: boolean) => this.onToggle(this.cheatIndex(c.id), c.id, on))
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
                        value: this.paramValues[this.cheatIndex(c.id)],
                        min: c.param.min,
                        max: c.param.max,
                        step: c.param.step !== undefined ? c.param.step : 1,
                        style: SliderStyle.OutSet
                      })
                        .layoutWeight(1)
                        .selectedColor('#DA3838')
                        .enabled(this.masterOn && this.flags[this.cheatIndex(c.id)])
                        .onChange((v: number, mode: SliderChangeMode) => this.onParamChange(this.cheatIndex(c.id), v, mode))
                      Text(this.paramText(this.cheatIndex(c.id)))
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
          }, (g: CheatGroupInfo) => g.id)
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
