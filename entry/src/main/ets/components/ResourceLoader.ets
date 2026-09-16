/**
 * 资源加载动画组件：左右摇晃（下载/解压中）。
 * active=false 时停止动画并复位。
 */
@Component
export struct ResourceLoader {
  @Prop @Watch('onStateChanged') active: boolean = true;
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
    if (!this.active) {
      this.angle = 0;
      return;
    }
    let dir: number = 1;
    this.angle = 14;
    this.timerId = setInterval(() => {
      this.angle = dir > 0 ? 14 : -14;
      dir = -dir;
    }, 450);
  }

  build() {
    Image($r('app.media.resource_icon'))
      .width(72)
      .height(72)
      .objectFit(ImageFit.Contain)
      .rotate({ angle: this.angle })
      .animation({ duration: 420, curve: Curve.EaseInOut })
  }
}
