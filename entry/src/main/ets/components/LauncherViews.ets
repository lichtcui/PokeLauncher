import { PrepPhase, PulseProgress } from './PulseProgress';

/**
 * 首页各状态的视图组件。
 *
 * 从 `pages/Index.ets` 拆出：这些视图只负责展示，状态与业务回调由首页注入。
 * 用 @Prop 接收会变化的数据（阶段/进度/开关），保证父组件状态变化时能重渲染
 * （@Builder 的值参数不会触发重渲染）。
 */

/** 未下载：开始下载 / 上传资源（+ 资源包直链） */
@Component
export struct NotDownloadedView {
  @Prop totalText: string = '';
  @Prop requiredStorageText: string = '';
  @Prop storageInsufficient: boolean = false;
  @Prop freeSizeText: string = '';
  /** 运行时解析出的资源包直链（随游戏版本变化，非固定常量） */
  @Prop assetUrl: string = '';
  /** 直链套加速镜像后的地址（国内可达；为空时只显示原始直链） */
  @Prop mirrorUrl: string = '';
  onStartDownload: () => void = () => {};
  onImport: () => void = () => {};
  onCopyUrl: (url: string) => void = () => {};

  build() {
    Column({ space: 16 }) {
      // 静止小圆环 = 动画的起点（呼吸峰值半径），点「开始下载」后原地开始呼吸，无跳变
      PulseProgress({ phase: PrepPhase.IDLE })
      Text('尚未安装离线资源').fontSize(18).fontWeight(FontWeight.Medium)

      // 资源包信息卡片
      Column({ space: 10 }) {
        Row() {
          Text('来源').fontSize(14).fontColor('#888888')
          Text('公开源').fontSize(14).fontColor('#333333')
        }
        .width('100%')
        .justifyContent(FlexAlign.SpaceBetween)

        Row() {
          Text('体积').fontSize(14).fontColor('#888888')
          Text(this.totalText.length > 0 ? this.totalText : '约 531 MB')
            .fontSize(14).fontColor('#333333')
        }
        .width('100%')
        .justifyContent(FlexAlign.SpaceBetween)

        Row() {
          Text('需要存储').fontSize(14).fontColor('#888888')
          Text(this.requiredStorageText).fontSize(14).fontColor('#333333')
        }
        .width('100%')
        .justifyContent(FlexAlign.SpaceBetween)
      }
      .width('84%')
      .padding(16)
      .borderRadius(12)
      .backgroundColor('#F7F7F7')

      // 下载须知
      Column({ space: 6 }) {
        Text('· 下载中可切后台，完成后通知').fontSize(13).fontColor('#999999')
        Text('· 支持断点续传，中断可继续').fontSize(13).fontColor('#999999')
        Text('· 需要开启通知权限').fontSize(13).fontColor('#999999')
      }
      .width('84%')
      .alignItems(HorizontalAlign.Start)

      Button('开始下载')
        .width('84%')
        .height(52)
        .fontSize(17)
        .backgroundColor('#DA3838')
        .fontColor('#FFFFFF')
        .enabled(!this.storageInsufficient)
        .onClick(() => this.onStartDownload())

      if (this.storageInsufficient) {
        Text('存储空间不足：需要约 ' + this.requiredStorageText + '，可用 ' + this.freeSizeText)
          .fontSize(13)
          .fontColor('#DA3838')
          .textAlign(TextAlign.Center)
          .padding({ left: 24, right: 24 })
      }

      // 下载太慢时的备选：复制直链自行下载 zip 后用「上传资源」导入（跳过下载与测速）
      Column({ space: 10 }) {
        if (this.assetUrl.length > 0) {
          Text('下载太慢？复制下方直链自行下载资源包，再用「上传资源」导入').fontSize(12).fontColor('#888888')
          if (this.mirrorUrl.length > 0) {
            Row({ space: 6 }) {
              Text('镜像').fontSize(12).fontColor('#888888')
              // 优先给国内可达的镜像地址，点击即复制（会 toast 提示）
              Text(this.mirrorUrl)
                .fontSize(12)
                .fontColor('#3A6FD8')
                .maxLines(3)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .layoutWeight(1)
                .onClick(() => this.onCopyUrl(this.mirrorUrl))
            }
            .width('100%')
            .alignItems(VerticalAlign.Top)
          }
          Row({ space: 6 }) {
            Text('GitHub').fontSize(12).fontColor('#888888')
            Text(this.assetUrl)
              .fontSize(12)
              .fontColor('#3A6FD8')
              .maxLines(3)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .layoutWeight(1)
              .onClick(() => this.onCopyUrl(this.assetUrl))
          }
          .width('100%')
          .alignItems(VerticalAlign.Top)
        }

        Button('上传资源')
          .width('100%')
          .height(46)
          .fontSize(16)
          .backgroundColor('#F2F2F2')
          .fontColor('#DA3838')
          .onClick(() => this.onImport())
      }
      .width('84%')
      .margin({ top: 4 })
    }
    .width('100%')
  }
}

/**
 * 下载 + 解压共用一个动画组件：只有阶段不变时组件才不被重建，
 * 波浪 → 收缩 → 呼吸 → 爆发 才能连贯播放。
 */
@Component
export struct DownloadFlowView {
  @Prop phase: PrepPhase = PrepPhase.IDLE;
  @Prop paused: boolean = false;
  @Prop progress: number = 0;
  @Prop speedText: string = '';
  @Prop etaText: string = '';
  @Prop flowTitle: string = '';
  onTogglePause: () => void = () => {};
  onCancel: () => void = () => {};

  build() {
    Column({ space: 16 }) {
      PulseProgress({ phase: this.phase, paused: this.paused })
      Text(this.flowTitle).fontSize(18).fontWeight(FontWeight.Medium)

      if (this.phase === PrepPhase.DOWNLOADED
        || this.phase === PrepPhase.EXTRACTING
        || this.phase === PrepPhase.READY) {
        Text('请勿关闭应用').fontSize(13).fontColor('#888888')
      } else {
        // 进度条：从左往右填充；百分比居中，白色 + 深色描边（红/灰底都可见）
        Stack() {
          Stack({ alignContent: Alignment.Start }) {
            Row()
              .width('100%')
              .height(22)
              .borderRadius(11)
              .backgroundColor('#EEEEEE')
            Row()
              .width(Math.max(0, Math.min(100, this.progress)) + '%')
              .height(22)
              .borderRadius(11)
              .backgroundColor('#DA3838')
          }
          .width('100%')
          .height(22)

          Text(this.progress.toString() + '%')
            .fontSize(13)
            .fontWeight(FontWeight.Bold)
            .fontColor('#FFFFFF')
            .textShadow({ radius: 2, color: '#99000000', offsetX: 0, offsetY: 1 })
        }
        .width('80%')

        // 速度 · 剩余时间
        if (this.speedText.length > 0 || this.etaText.length > 0) {
          Text(this.speedText + (this.speedText.length > 0 && this.etaText.length > 0 ? ' · ' : '') + this.etaText)
            .fontSize(13)
            .fontColor('#DA3838')
        }

        // 一排两键：暂停/继续为主（实心，适中宽度），取消下载仅文字
        Row({ space: 16 }) {
          Button(this.paused ? '继续' : '暂停')
            .width(140)
            .height(44)
            .fontSize(16)
            .backgroundColor('#DA3838')
            .fontColor('#FFFFFF')
            .onClick(() => this.onTogglePause())
          Button('取消下载')
            .fontSize(14)
            .fontColor('#999999')
            .backgroundColor('#00000000')
            .onClick(() => this.onCancel())
        }
        .width('80%')
        .justifyContent(FlexAlign.Center)
        .margin({ top: 6 })
      }
    }
    .width('100%')
  }
}

/** 就绪：开始运行 + 参数调整入口（仅在设置里开启后显示） */
@Component
export struct ReadyView {
  @Prop versionText: string = '';
  @Prop cheatMasterOn: boolean = false;
  onStartGame: () => void = () => {};
  onOpenCheats: () => void = () => {};

  build() {
    Column({ space: 12 }) {
      Image($r('app.media.resource_icon'))
        .width(96)
        .height(96)
        .objectFit(ImageFit.Contain)
      Text('离线资源已就绪').fontSize(20).fontWeight(FontWeight.Medium)
      Text('版本 ' + this.versionText)
        .fontSize(13).fontColor('#888888')
      Button('开始运行')
        .width('70%')
        .height(54)
        .fontSize(18)
        .backgroundColor('#DA3838')
        .fontColor('#FFFFFF')
        .margin({ top: 16 })
        .onClick(() => this.onStartGame())
      // 参数调整入口：仅在设置里开启后显示；更改在开始游戏时生效
      if (this.cheatMasterOn) {
        Text('参数调整')
          .fontSize(14)
          .fontColor('#999999')
          .margin({ top: 4 })
          .onClick(() => this.onOpenCheats())
      }
    }
    .width('100%')
  }
}