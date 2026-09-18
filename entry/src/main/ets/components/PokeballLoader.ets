/**
 * 精灵球资源准备动画（下载/解压阶段）。
 *
 * - 精灵球左右摇晃：测速 / 下载 / 下载完成 / 解压 / 解压完成，`paused` 时停在正中
 * - 未开始（IDLE）或应用切到后台：静止，且不跑定时器（省电）
 *
 * 阶段枚举与时长常量也放在这里：`LauncherSession` 按阶段驱动下载/解压的
 * 文案与切页时机，`LauncherViews` 只把阶段透传给本组件。
 */

// ─────────────────────────── 对外状态枚举 ───────────────────────────

export enum PrepPhase {
  /** ⓪ 初始静止：未安装资源 */
  IDLE = 0,
  /** ① 测速选源：精灵球开始摇晃 */
  PROBING = 1,
  /** ② 开始下载 + ③ 下载中：持续摇晃 */
  DOWNLOADING = 2,
  /** ④ 下载完成：保持摇晃，准备切「解压中」 */
  DOWNLOADED = 3,
  /** ⑤ 解压中：精灵球逐帧打开 */
  EXTRACTING = 4,
  /** ⑥ 解压完成：打开动画收尾后进就绪页 */
  READY = 5,
}

// ─────────────────────────── 时长 ───────────────────────────

/** 下载完成 → 解压中的过渡停留（ms） */
export const SHRINK_MS: number = 700;
/** 解压完成 → 就绪页的过渡停留（ms） */
export const BURST_MS: number = 900;

const SHAKE_INTERVAL_MS: number = 450;
const SHAKE_ANGLE: number = 14;

// ─────────────────────────── 组件 ───────────────────────────

@Component
export struct PokeballLoader {
  /** 由外部驱动：资源准备阶段 */
  @Prop @Watch('onStateChanged') phase: PrepPhase = PrepPhase.IDLE;
  /** 暂停：冻结摇晃并复位到正中 */
  @Prop @Watch('onStateChanged') paused: boolean = false;
  /** 应用切到后台：停表省电，回前台再续播（页面 onPageHide/onPageShow 驱动） */
  @Prop @Watch('onStateChanged') appHidden: boolean = false;
  @Prop iconSize: number = 72;

  @State angle: number = 0;

  private timerId: number = -1;

  aboutToAppear(): void {
    this.sync();
  }

  aboutToDisappear(): void {
    this.clearTimer();
  }

  onStateChanged(): void {
    this.sync();
  }

  private clearTimer(): void {
    if (this.timerId >= 0) {
      clearInterval(this.timerId);
      this.timerId = -1;
    }
  }

  private sync(): void {
    this.clearTimer();
    if (this.appHidden || this.phase === PrepPhase.IDLE) {
      this.angle = 0;
      return;
    }
    if (this.paused) {
      this.angle = 0;
      return;
    }
    let dir: number = 1;
    this.angle = SHAKE_ANGLE;
    this.timerId = setInterval(() => {
      this.angle = dir > 0 ? SHAKE_ANGLE : -SHAKE_ANGLE;
      dir = -dir;
    }, SHAKE_INTERVAL_MS);
  }

  build() {
    Image($r('app.media.pokeball'))
      .width(this.iconSize)
      .height(this.iconSize)
      .objectFit(ImageFit.Contain)
      .rotate({ angle: this.angle })
      .animation({ duration: 420, curve: Curve.EaseInOut })
  }
}
