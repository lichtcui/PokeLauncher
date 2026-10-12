import { CheatParam } from '../model/Cheats';

/**
 * 参数调整的数值取值与展示（**纯函数**，无 UI / 偏好库 / 游戏依赖）。
 *
 * 与安卓 `cheat/CheatFormat.kt` 是同一套语义，期望值只写一份：`shared/cases/cheat.json`
 * （由 `scripts/gen-shared.mjs --check` 对账，鸿蒙侧还会被 `scripts/check-harmony-vectors.mjs`
 * 在 Node 里复跑一遍）。
 *
 * 为什么单拎出来：这段逻辑以前散在两处（`model/CheatState.ets` 给注入用、`pages/Cheats.ets`
 * 给界面用），而安卓那边是第三个版本 —— 单位拼接方向（`x10` vs `10x`）、labels 档位判定的宽严、
 * 滑块浮点漂移的处理都真分叉过，且没有任何机械护栏。
 *
 * ⚠️ 数值量级按注册表实际用到的范围（±1000）：两端都没处理 1e18 以上科学计数法写法的差异。
 */

/** 浮点吸附容差：只吃滑块换算带来的误差，真实的中间值（7.5）不动（与安卓同值） */
const SNAP_TOLERANCE: number = 1e-6;

/** 数值 → 数字字面量：整数值不带 `.0`（注入给共享脚本的 `__CHEAT_PARAMS__` 也用它） */
export function numberLiteral(v: number): string {
  if (!Number.isFinite(v)) {
    return '0';
  }
  if (Number.isInteger(v)) {
    return v.toFixed(0);
  }
  return v.toString();
}

/**
 * 消除滑块带来的浮点漂移：只在**非常接近**某个档位时吸附回该档。
 *
 * ArkUI `Slider({ step })` 给出的档位值是 `min + i * step`，浮点运算会得到 `10.999999999`
 * 这类值 —— 不处理就会把脏数字写进偏好库、注入进游戏，还显示成「10.999999x」。
 * 省略 `step` 时按 1 处理（与安卓 `CheatParam.step` 的默认值一致）。
 */
export function snapToStep(param: CheatParam, value: number): number {
  const step: number = param.step === undefined ? 1 : param.step;
  if (step <= 0) {
    return value;
  }
  const steps: number = (value - param.min) / step;
  const nearest: number = Math.round(steps);
  if (Math.abs(steps - nearest) > SNAP_TOLERANCE) {
    return value;
  }
  return param.min + nearest * step;
}

/** 偏好库里的原始串 → 可用数值：空 / 无法解析回退 `default`，再夹紧到 [min, max] 并吸附档位 */
export function resolveNumber(param: CheatParam, raw: string | undefined): number {
  const trimmed: string = raw === undefined ? '' : raw.trim();
  let n: number = param.default;
  if (trimmed.length > 0) {
    const parsed: number = Number(trimmed);
    n = isNaN(parsed) ? param.default : parsed;
  }
  if (isNaN(n)) {
    return param.default;
  }
  const clamped: number = n < param.min ? param.min : (n > param.max ? param.max : n);
  return snapToStep(param, clamped);
}

/** 数值 → 展示文字：整数且落在 `labels` 范围内用档位名，否则 `unit + 数值` */
export function displayValue(param: CheatParam, value: number): string {
  const idx: number = Math.trunc(value);
  const labels: string[] | undefined = param.labels;
  if (labels !== undefined && labels.length > 0 && idx >= 0 && idx < labels.length && value === idx) {
    return labels[idx];
  }
  const unit: string = param.unit !== undefined ? param.unit : '';
  return unit + numberLiteral(value);
}
