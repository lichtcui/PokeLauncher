/**
 * 精灵球加载动画组件。
 * - mode 'shake'：左右摇晃（下载中）
 * - mode 'open' ：逐帧打开、露出白光（解压中）
 * active=false 时停止动画并复位。
 */
@Component
export struct PokeballLoader {
  @Prop @Watch('onStateChanged') mode: string = 'shake';
  @Prop @Watch('onStateChanged') active: boolean = true;
  @State angle: number = 0;
  @State frame: number = 0;

  private timerId: number = -1;
  private openFrames: Resource[] = [
    $r('app.media.pokeball_open_0'),
    $r('app.media.pokeball_open_1'),
    $r('app.media.pokeball_open_2')
  ];

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
    if (!this.active) {
      this.angle = 0;
      this.frame = 0;
      return;
    }
    if (this.mode === 'open') {
      this.frame = 0;
      this.timerId = setInterval(() => {
        this.frame = (this.frame + 1) % this.openFrames.length;
      }, 260);
    } else {
      let dir: number = 1;
      this.angle = 14;
      this.timerId = setInterval(() => {
        this.angle = dir > 0 ? 14 : -14;
        dir = -dir;
      }, 450);
    }
  }

  build() {
    if (this.mode === 'open') {
      Image(this.openFrames[this.frame])
        .width(72)
        .height(72)
        .objectFit(ImageFit.Contain)
    } else {
      Image($r('app.media.pokeball'))
        .width(72)
        .height(72)
        .objectFit(ImageFit.Contain)
        .rotate({ angle: this.angle })
        .animation({ duration: 420, curve: Curve.EaseInOut })
    }
  }
}
