package com.lichtcui.pokelauncher.cheat

/**
 * 参数调整状态的**纯**编解码 / 取值 / 展示逻辑（无 Android 依赖 → JVM 可单测）。
 *
 * 持久化格式与鸿蒙版 `model/CheatState.ets` 完全一致：
 * - `cheatEnabledIds`  → `id,id,id`
 * - `cheatParams`      → `id=value,id=value`
 *
 * 取值与展示的期望值是**两端共用**的 `shared/cases/cheat.json`（生成物 `CasesGen.CHEAT_VALUE_CASES`，
 * 鸿蒙侧还有 `entry/src/ohosTest/.../CheatFormat.test.ets` + `scripts/check-harmony-vectors.mjs`）——
 * 这两端的实现曾经真分叉过：单位拼接方向相反（`x10` vs `10x`）、labels 档位判定的宽严不同，
 * 而没有任何机械护栏。现在改实现就会跑向量。
 *
 * 与鸿蒙版在**向量之外**的输入上仍可能有细节差异（如 JS `Number("0x10")` = 16、
 * Kotlin `toDoubleOrNull()` 给 null），但都不是注册表会产出的取值；数值量级约定见 `shared/cases/cheat.json`。
 */
object CheatFormat {

    /** `List<String>` → 逗号串 */
    fun encodeIds(ids: List<String>): String = ids.joinToString(",")

    /** 逗号串 → `List<String>`（去空白、丢空项） */
    fun parseIds(raw: String): List<String> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** `Map<String,String>` → `id=value,...`（保留插入顺序） */
    fun encodeParams(params: Map<String, String>): String =
        params.entries.joinToString(",") { "${it.key}=${it.value}" }

    /** `id=value,...` → 有序 `Map`（与鸿蒙版一致：只在 `=` 前有内容时才算一项） */
    fun parseParams(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (raw.isEmpty()) return out
        for (part in raw.split(',')) {
            val eq = part.indexOf('=')
            if (eq > 0) {
                out[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
            }
        }
        return out
    }

    /**
     * 读取数值参数 → 可用值：空 / 无法解析回退 [CheatParam.defaultValue]，
     * 再按 [CheatParam.min] / [CheatParam.max] 夹紧（期望值见 `shared/cases/cheat.json`）。
     */
    fun resolveNumber(param: CheatParam, raw: String?): Double {
        val trimmed = raw?.trim().orEmpty()
        val n = if (trimmed.isEmpty()) param.defaultValue else trimmed.toDoubleOrNull() ?: param.defaultValue
        if (n.isNaN()) return param.defaultValue
        return snapToStep(param, n.coerceIn(param.min, param.max))
    }

    /**
     * 消除滑块带来的浮点漂移：只在**非常接近**某个档位时吸附回该档。
     *
     * Compose `Slider(steps=…)` 给出的档位值是 `min + i * (max-min)/steps`，浮点除法的
     * 结果是 `10.999999999` 这类值 —— 不处理就会显示成「10.999999x」并把脏数字写进存档。
     * 容差 `1e-6` 只吃浮点误差：真实的中间值（如 `7.5`）不动，与鸿蒙版取值语义保持一致。
     */
    fun snapToStep(param: CheatParam, value: Double): Double {
        if (param.step <= 0.0) return value
        val steps = (value - param.min) / param.step
        val nearest = Math.round(steps).toDouble()
        if (Math.abs(steps - nearest) > 1e-6) return value
        return param.min + nearest * param.step
    }

    /**
     * 生成 JS 数字字面量：整数值不带 `.0`（对齐 JS `n.toString()`：`10` 而不是 `10.0`）。
     */
    fun numberLiteral(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "0"
        val asLong = v.toLong()
        return if (v == asLong.toDouble()) asLong.toString() else v.toString()
    }

    /**
     * 数值 → 展示文字：整数且落在 [CheatParam.labels] 范围内用档位名，否则 **单位 + 数值**。
     *
     * 拼接方向必须与鸿蒙 `common/CheatFormat.ets` 一致（曾经相反：这里 `10x`、鸿蒙 `x10`）。
     */
    fun displayValue(param: CheatParam, value: Double): String {
        val idx = value.toInt()
        if (param.labels.isNotEmpty() && idx >= 0 && idx < param.labels.size && value == idx.toDouble()) {
            return param.labels[idx]
        }
        return param.unit + numberLiteral(value)
    }
}
