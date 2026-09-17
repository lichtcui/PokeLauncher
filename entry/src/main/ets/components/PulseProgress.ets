/**
 * 资源准备进度动画。
 *
 * 六个阶段（对应 App 的真实流程）：
 *
 *   ⓪ 初始静止   PrepPhase.IDLE        小圆环静止（= 呼吸峰值半径）
 *   ① 测速选源   PrepPhase.PROBING     小圆环呼吸，等选源结果
 *   ② 开始下载   PrepPhase.DOWNLOADING 呼吸圆展开成波浪（固定 700ms）
 *   ③ 下载中     PrepPhase.DOWNLOADING 波浪持续流动
 *   ④ 下载完成   PrepPhase.DOWNLOADED  波浪收缩成小圆（固定 700ms）
 *   ⑤ 解压中     PrepPhase.EXTRACTING  小圆环呼吸，时长 = 解压实际耗时
 *   ⑥ 解压完成   PrepPhase.READY       脉冲爆发到 R×1.22，回落到 R
 *
 * ②展开 / ④收缩 / ⑥爆发 都是组件内部按时间戳推进的固定时长过渡，
 * 外部只在「选源完成」「下载完成」「解压完成」这三个瞬间切一次状态。
 */

// ─────────────────────────── 对外状态枚举 ───────────────────────────

export enum PrepPhase {
  /** ⓪ 初始静止：小圆环不动 */
  IDLE = 0,
  /** ① 测速选源：小圆环呼吸 */
  PROBING = 1,
  /** ② 开始下载 + ③ 下载中：展开后波浪流动 */
  DOWNLOADING = 2,
  /** ④ 下载完成：波浪收缩成小圆 */
  DOWNLOADED = 3,
  /** ⑤ 解压中：小圆环呼吸 */
  EXTRACTING = 4,
  /** ⑥ 解压完成：脉冲爆发 */
  READY = 5,
}

/** 各阶段默认文案 */
export function phaseLabel(p: PrepPhase): string {
  switch (p) {
    case PrepPhase.IDLE:        return '准备就绪';
    case PrepPhase.PROBING:     return '正在选择下载源';
    case PrepPhase.DOWNLOADING: return '下载中';
    case PrepPhase.DOWNLOADED:  return '下载完成';
    case PrepPhase.EXTRACTING:  return '解压中';
    case PrepPhase.READY:       return '资源准备就绪';
    default:                    return '';
  }
}

// ─────────────────────────── 几何常量 ───────────────────────────

const N: number = 80;
const X0: number = 20;
const X1: number = 220;
const YC: number = 80;
const AMP: number = 34;
const CX: number = 120;
const CY: number = 80;
const R: number = 55;
const VB_W: number = 240;
const VB_H: number = 160;
const SEAM: number = Math.PI / 2;      // 圆环接缝在底部

// ─────────────────────────── 动画参数 ───────────────────────────

const SMALL: number = 0.14;             // 呼吸最小半径比例
const BREATH_AMP: number = 0.75;        // 呼吸幅度
const BREATH_PERIOD: number = 1200;     // 呼吸周期（ms）
const OVER: number = 0.22;              // 爆发过冲量

/** 静态/呼吸峰值半径（≈13.475） */
const R_STATIC: number = R * SMALL * (1 + BREATH_AMP);

// ─────────────────────────── 时长 ───────────────────────────

const EXPAND_MS: number = 700;          // ② 呼吸圆 → 波浪
export const SHRINK_MS: number = 700;   // ④ 波浪 → 小圆
export const BURST_MS: number = 900;    // ⑥ 爆发
const FRAME_MS: number = 16;
const WAVE_STEP: number = 0.05;

// ─────────────────────────── 纯函数 ───────────────────────────

function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t;
}

function clamp(v: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, v));
}

function ease(t: number): number {
  return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
}

// ─────────────────────────── 组件 ──────────────────────────

@Component
export struct PulseProgress {
  /** 由外部驱动：资源准备阶段 */
  @Prop @Watch('onPhaseChange') phase: PrepPhase = PrepPhase.IDLE;
  /** 暂停时冻结波浪推进（进度事件不再前进，动画也不该继续流动） */
  @Prop paused: boolean = false;
  @Prop canvasW: number = VB_W;
  @Prop canvasH: number = VB_H;
  @Prop strokeColor: string = '#DA3838';
  @Prop strokeWidth: number = 4;

  private settings: RenderingContextSettings = new RenderingContextSettings(true);
  private ctx: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.settings);

  // —— 动画内部状态 ——
  private wavePhase: number = 0;
  private lastBreathR: number = R_STATIC;   // 呼吸最新一帧半径，展开/爆发从这里起步
  private probeStartAt: number = 0;         // ① 测速开始
  private downloadStartAt: number = 0;      // ② 展开起点
  private downloadDoneAt: number = 0;       // ④ 收缩起点
  private extractDoneAt: number = 0;        // ⑥ 爆发起点
  private timerId: number = -1;
  private canvasReady: boolean = false;
  private circleCache: number[][] = [];

  aboutToAppear(): void {
    for (let i = 0; i <= N; i++) {
      const th: number = SEAM + (i / N) * Math.PI * 2;
      this.circleCache.push([CX + R * Math.cos(th), CY + R * Math.sin(th)]);
    }
    // @Watch 只在值「变化」时触发：首次挂载（未下载页 → 下载页是不同分支，
    // 组件是新建的）必须自己按当前阶段初始化时间戳，
    // 否则初始就是 PROBING 时不会呼吸，只剩一个静止小圆。
    this.applyPhase(Date.now());
  }

  aboutToDisappear(): void {
    this.stopLoop();
  }

  /**
   * 状态切换入口。外部按真实流程改 `phase`：
   *
   *   进入下载页        → PROBING     （开始测速）
   *   选源完成，开始下载 → DOWNLOADING （触发展开）
   *   下载回调完成       → DOWNLOADED  （触发收缩）
   *   解压开始           → EXTRACTING  （收缩播完自动接呼吸）
   *   解压回调完成       → READY       （触发爆发）
   */
  private onPhaseChange(_propName: string): void {
    this.applyPhase(Date.now());

    if (this.phase === PrepPhase.IDLE) {
      this.stopLoop();
      this.renderStatic();
    } else {
      this.ensureLoop();
    }
  }

  /** 按当前阶段重置时间戳（首次挂载与阶段切换共用） */
  private applyPhase(now: number): void {
    switch (this.phase) {
      case PrepPhase.IDLE: {
        this.probeStartAt = 0;
        this.downloadStartAt = 0;
        this.downloadDoneAt = 0;
        this.extractDoneAt = 0;
        this.lastBreathR = R_STATIC;
        this.wavePhase = 0;
        break;
      }
      case PrepPhase.PROBING: {
        this.probeStartAt = now;
        this.downloadStartAt = 0;
        this.downloadDoneAt = 0;
        this.extractDoneAt = 0;
        this.lastBreathR = R_STATIC;
        break;
      }
      case PrepPhase.DOWNLOADING: {
        if (this.probeStartAt === 0) {
          this.probeStartAt = now;
        }
        this.downloadStartAt = now;
        this.downloadDoneAt = 0;
        this.extractDoneAt = 0;
        break;
      }
      case PrepPhase.DOWNLOADED: {
        if (this.downloadStartAt === 0) {
          this.downloadStartAt = now - EXPAND_MS;
        }
        this.downloadDoneAt = now;
        this.extractDoneAt = 0;
        break;
      }
      case PrepPhase.EXTRACTING: {
        // 若收缩还没播完也没关系：computePoints 会先把收缩播完再进呼吸
        if (this.downloadDoneAt === 0) {
          this.downloadDoneAt = now;
        }
        this.extractDoneAt = 0;
        break;
      }
      case PrepPhase.READY: {
        // 解压比收缩还快时，把爆发起点推到收缩结束后
        const earliest: number = this.downloadDoneAt > 0
          ? this.downloadDoneAt + SHRINK_MS
          : now;
        this.extractDoneAt = Math.max(now, earliest);
        if (this.downloadDoneAt === 0) {
          this.downloadDoneAt = this.extractDoneAt - SHRINK_MS;
        }
        break;
      }
      default: {
        break;
      }
    }
  }

  // ────────── 动画循环 ───────────

  private ensureLoop(): void {
    if (this.timerId >= 0 || !this.canvasReady) {
      return;
    }
    this.timerId = setInterval(() => this.draw(), FRAME_MS);
  }

  private stopLoop(): void {
    if (this.timerId >= 0) {
      clearInterval(this.timerId);
      this.timerId = -1;
    }
  }

  private renderStatic(): void {
    if (!this.canvasReady) {
      return;
    }
    this.strokePath(this.ringAt(R_STATIC / R));
  }

  private draw(): void {
    const now: number = Date.now();
    const pts: number[][] = this.computePoints(now);
    this.strokePath(pts);

    // ⑥ 爆发播完 → 停表省电（终点是静止全圆）
    if (this.extractDoneAt > 0 && now >= this.extractDoneAt + BURST_MS) {
      this.lastBreathR = R;
      this.stopLoop();
    }
  }

  /** 根据时间戳判断当前处在哪一段，返回该帧折线点 */
  private computePoints(now: number): number[][] {
    // ─── ⑥ 解压完成：脉冲爆发 ───
    if (this.extractDoneAt > 0 && now >= this.extractDoneAt) {
      const q: number = clamp((now - this.extractDoneAt) / BURST_MS, 0, 1);
      const s: number = q < 0.5
        ? lerp(this.lastBreathR, R * (1 + OVER), ease(q / 0.5))
        : lerp(R * (1 + OVER), R, ease((q - 0.5) / 0.5));
      return this.ringAt(s / R);
    }

    // ─── ④ 下载完成之后：收缩 → ⑤ 解压呼吸 ───
    if (this.downloadDoneAt > 0) {
      const dt: number = now - this.downloadDoneAt;

      if (dt < SHRINK_MS) {
        const p: number = clamp(dt / SHRINK_MS, 0, 1);
        if (!this.paused) {
          this.wavePhase += WAVE_STEP * (1 - p);
        }
        const target: number[][] = this.ringAt(SMALL);
        const wave: number[][] = this.wavePoints(this.wavePhase, AMP);
        const out: number[][] = [];
        for (let i = 0; i <= N; i++) {
          out.push([
            lerp(wave[i][0], target[i][0], ease(p)),
            lerp(wave[i][1], target[i][1], ease(p)),
          ]);
        }
        return out;
      }

      const tb: number = dt - SHRINK_MS;
      const r: number = this.breathRadius(tb);
      this.lastBreathR = r;
      return this.ringAt(r / R);
    }

    // ─── ② 下载开始之后：展开 → ③ 波浪 ───
    if (this.downloadStartAt > 0) {
      const dt: number = now - this.downloadStartAt;

      if (!this.paused) {
        this.wavePhase += WAVE_STEP;
      }
      const wave: number[][] = this.wavePoints(this.wavePhase, AMP);

      if (dt < EXPAND_MS) {
        const p: number = clamp(dt / EXPAND_MS, 0, 1);
        const start: number[][] = this.ringAt(this.lastBreathR / R);
        const out: number[][] = [];
        for (let i = 0; i <= N; i++) {
          out.push([
            lerp(start[i][0], wave[i][0], ease(p)),
            lerp(start[i][1], wave[i][1], ease(p)),
          ]);
        }
        return out;
      }
      return wave;
    }

    // ─── ① 测速选源：呼吸 ───
    if (this.probeStartAt > 0) {
      const r: number = this.breathRadius(now - this.probeStartAt);
      this.lastBreathR = r;
      return this.ringAt(r / R);
    }

    // ── ⓪ 初始静止 ───
    return this.ringAt(R_STATIC / R);
  }

  // ─────────── 几何辅助 ───────────

  /** 从峰值开始的呼吸半径（与静态半径无缝衔接） */
  private breathRadius(tb: number): number {
    const breath: number = 0.5 + 0.5 * Math.cos(tb / BREATH_PERIOD * Math.PI * 2);
    return R * SMALL * (1 + BREATH_AMP * breath);
  }

  /** 波浪上第 i 个采样点 */
  private wavePoints(phase: number, amp: number): number[][] {
    const out: number[][] = [];
    for (let i = 0; i <= N; i++) {
      const t: number = i / N;
      out.push([
        X0 + (X1 - X0) * t,
        YC + amp * Math.sin(phase - t * Math.PI * 4),
      ]);
    }
    return out;
  }

  /** 半径比例为 s 的圆环点集（s=1 即原始圆） */
  private ringAt(s: number): number[][] {
    const out: number[][] = [];
    for (let i = 0; i <= N; i++) {
      const b: number[] = this.circleCache[i];
      out.push([CX + (b[0] - CX) * s, CY + (b[1] - CY) * s]);
    }
    return out;
  }

  /** 把点集描到画布 */
  private strokePath(pts: number[][]): void {
    const sx: number = this.canvasW / VB_W;
    const sy: number = this.canvasH / VB_H;

    this.ctx.clearRect(0, 0, this.canvasW, this.canvasH);
    this.ctx.beginPath();
    this.ctx.strokeStyle = this.strokeColor;
    this.ctx.lineWidth = this.strokeWidth;
    this.ctx.lineCap = 'round';
    this.ctx.lineJoin = 'round';

    this.ctx.moveTo(pts[0][0] * sx, pts[0][1] * sy);
    for (let i = 1; i < pts.length; i++) {
      this.ctx.lineTo(pts[i][0] * sx, pts[i][1] * sy);
    }
    this.ctx.stroke();
  }

  build() {
    Canvas(this.ctx)
      .width(this.canvasW)
      .height(this.canvasH)
      .onReady(() => {
        this.canvasReady = true;
        if (this.phase === PrepPhase.IDLE) {
          this.renderStatic();
        } else {
          this.ensureLoop();
        }
      })
  }
}