import { router } from '@kit.ArkUI';
import { LEGAL_BANNER, LEGAL_SECTIONS, LEGAL_UPDATED_AT, LegalSection } from '../model/LegalText';

/**
 * 法律与免责声明页：从「设置 → 关于 → 法律与免责声明」进入。
 * 本项目为第三方非官方启动器，仅运行时下载游戏资源，不打包/再分发任何游戏本体，
 * 此页用于说明项目定位、版权归属、用户责任与免责边界，规避法律风险。
 */

// 正文数据（LEGAL_BANNER / LEGAL_UPDATED_AT / LEGAL_SECTIONS）由 shared/legal.json 生成，
// **不要在本页内联**：鸿蒙与安卓共用同一份正文，改文案改 shared/legal.json 后跑
// `node scripts/gen-shared.mjs`（generator 的防内联检查会拦住重新内联）。

@Entry
@Component
struct Legal {
  @Builder
  sectionBlock(s: LegalSection) {
    Column() {
      Row() {
        Row()
          .width(4)
          .height(18)
          .borderRadius(2)
          .backgroundColor('#DA3838')
        Text(s.title)
          .fontSize(17)
          .fontWeight(FontWeight.Bold)
          .fontColor('#1A1A1A')
          .margin({ left: 8 })
      }
      .alignItems(VerticalAlign.Center)
      .width('100%')

      ForEach(s.body, (p: string, i: number) => {
        Text(p)
          .fontSize(14)
          .fontColor('#555555')
          .lineHeight(22)
          .width('100%')
          .margin({ top: i === 0 ? 10 : 8 })
      }, (p: string, i: number) => s.id + '_' + i)
    }
    .alignItems(HorizontalAlign.Start)
    .width('100%')
    .padding({ left: 16, right: 16, top: 18, bottom: 18 })
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
        Text('法律与免责声明').fontSize(20).fontWeight(FontWeight.Medium)
        Blank()
      }
      .width('100%')
      .padding({ left: 8, right: 16, top: 8, bottom: 4 })

      // 常驻提示条
      Column() {
        Text(LEGAL_BANNER)
          .fontSize(12)
          .fontColor('#C77700')
          .width('100%')
      }
      .alignItems(HorizontalAlign.Start)
      .width('100%')
      .padding(12)
      .backgroundColor('#FFF8E8')

      Scroll() {
        Column() {
          ForEach(LEGAL_SECTIONS, (s: LegalSection, index: number) => {
            Column() {
              if (index > 0) {
                Divider().color('#EDEDED').strokeWidth(1).margin({ left: 16, right: 16 })
              }
              this.sectionBlock(s)
            }
            .width('100%')
          }, (s: LegalSection) => s.id)

          Text(LEGAL_UPDATED_AT)
            .fontSize(12)
            .fontColor('#BBBBBB')
            .margin({ top: 24, bottom: 40 })
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
