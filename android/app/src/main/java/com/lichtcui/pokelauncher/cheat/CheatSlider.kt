package com.lichtcui.pokelauncher.cheat

/**
 * 参数调整条目的**UI 换算**（手写文件）。
 *
 * 这些是「注册表 → 滑块」的展示逻辑，与鸿蒙侧的 `pages/Cheats.ets` 同口径，但两边写法完全不同
 * （Compose `Slider` vs ArkUI `Slider`），因此**不放进 `shared/`、也不放进生成的 `Cheats.kt`**。
 * 注册表本身由 `scripts/gen-shared.mjs` 从 `shared/cheats.json` 生成。
 */

/**
 * Compose `Slider` 的档位数。
 *
 * `Slider(value=…, steps=n)` 会在 [min][CheatParam.min] 与 [max][CheatParam.max] 之间插入 n 个中间刻度，
 * 于是一共有 `n + 2` 个可选值；这里给出的正是「除了首尾之外的中间档位」个数。
 */
fun CheatParam.sliderSteps(): Int {
    if (step <= 0.0) return 0
    val gaps = ((max - min) / step).toInt() - 1
    return if (gaps > 0) gaps else 0
}
