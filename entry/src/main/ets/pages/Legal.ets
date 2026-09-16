import { router } from '@kit.ArkUI';

/**
 * 法律与免责声明页：从「设置 → 关于 → 法律与免责声明」进入。
 * 本项目为第三方非官方启动器，仅运行时下载游戏资源，不打包/再分发任何游戏本体，
 * 此页用于说明项目定位、版权归属、用户责任与免责边界，规避法律风险。
 */

interface LegalSection {
  id: string;
  title: string;
  body: string[];
}

const LEGAL_SECTIONS: LegalSection[] = [
  {
    id: 'positioning',
    title: '项目定位',
    body: [
      '本项目是第三方、非官方的 HarmonyOS NEXT 启动器（Launcher），仅用于在鸿蒙设备上离线加载并运行 PokeRogue 游戏资源，改善其在 HarmonyOS 上的运行与操作体验。',
      '本项目与 PokeRogue 的官方开发团队、发行方及任何关联方无隶属、合作、赞助或授权关系，不代表官方立场。',
      '本仓库仅包含启动器代码，不包含、不打包、不再分发任何游戏本体资源（美术、音频、数据、代码等）；游戏资源在运行时由用户设备从公开来源下载。'
    ]
  },
  {
    id: 'copyright',
    title: '版权声明',
    body: [
      'PokeRogue 及其游戏资源（含美术、音乐、音效、数据、源代码等）的著作权归其原作者及贡献者所有，并以其官方仓库声明的协议（如 AGPL-3.0）为准。本项目不对其主张任何权利。',
      '本项目启动器源代码以 MIT 协议开源（详见仓库根目录 LICENSE 文件），版权归本项目作者所有。',
      '本页及仓库中出现的第三方名称、商标、图标与资源，权利均归各自权利人所有，此处仅为说明性引用。'
    ]
  },
  {
    id: 'responsibility',
    title: '用户责任',
    body: [
      '用户应自行确认其所在国家或地区对下载、运行、修改游戏资源以及使用作弊功能的合法性，并自行承担由此产生的一切后果。',
      '用户应妥善保管游戏存档与账号。因使用本启动器（含作弊功能）导致的账号被标记、封禁，或存档损坏、丢失，均由用户自行承担。',
      '用户应同时遵守游戏官方及资源来源方的服务条款与社区规范。'
    ]
  },
  {
    id: 'disclaimer',
    title: '免责条款',
    body: [
      '本项目按「现状（AS IS）」提供，不对可用性、稳定性、兼容性、无错误或特定用途适用性作任何明示或默示担保。',
      '对于因使用或无法使用本项目而造成的任何直接或间接损失（包括但不限于账号封禁、存档丢失、设备或数据损坏、法律纠纷），作者及贡献者不承担任何责任。',
      '本项目可能随上游变化而失效，作者无义务提供维护、更新或技术支持。'
    ]
  },
  {
    id: 'compliance',
    title: '合规使用建议',
    body: [
      '请仅在你拥有合法获取途径的前提下使用，并优先选择官方版本或官方认可的方式游玩。',
      '使用作弊功能前，请确认仅在纯离线、单人、不涉及在线排行榜或联机的环境中使用，且不要将修改后的存档导入或上传至任何在线服务。',
      '若你喜爱本游戏，请通过官方渠道支持原作者，以帮助项目持续发展。'
    ]
  },
  {
    id: 'study',
    title: '学习交流的合法使用',
    body: [
      '本项目仅供个人学习、技术研究与交流使用，可用于了解 HarmonyOS 原生开发、Web 组件、本地 HTTP 服务与离线资源加载等技术。',
      '在符合当地法律及游戏官方条款的前提下，用于个人非商业目的的学习交流属于允许范围。'
    ]
  },
  {
    id: 'prohibited',
    title: '禁止用途',
    body: [
      '禁止将本项目用于任何违反当地法律法规的用途。',
      '禁止将本项目用于商业售卖、付费分发，或将其包装为官方产品进行推广、诱导消费。',
      '禁止利用本项目传播、下载或分发任何侵权内容，或用于规避、破坏游戏官方的正当技术与账号机制以牟利。'
    ]
  },
  {
    id: 'license',
    title: '开源许可',
    body: [
      '本项目启动器代码以 MIT License 发布，可自由使用、修改与分发，但须保留原始版权与许可声明。',
      'PokeRogue 游戏资源不适用本许可，其版权与许可归原作者及贡献者所有。'
    ]
  },
  {
    id: 'feedback',
    title: '权利反馈',
    body: [
      '本项目尊重各方知识产权。若你是相关权利人或授权代表，认为本项目的任何内容（包括但不限于名称、图标、截图、说明文字）侵犯了你的合法权益，请通过项目仓库的 Issue 与我们联系。',
      '项目仓库：gitee.com/licht3345/harmony-pokerogue',
      '我们将在核实后及时删除相关内容或采取其他必要措施。'
    ]
  }
];

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
        Text('本项目为第三方非官方启动器，仅供个人学习交流与离线游玩使用，与 PokeRogue 官方无关。')
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

          Text('最近更新：2026-09-16')
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
