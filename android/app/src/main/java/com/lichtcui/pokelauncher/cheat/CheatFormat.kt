package com.lichtcui.pokelauncher.cheat

/**
 * 参数调整状态的**纯**编解码/取值逻辑（无 Android 依赖 → JVM 可单测）。
 *
 * 持久化格式与鸿蒙版 `model/CheatState.ets` 完全一致：
 * - `cheatEnabledIds`  → `id,id,id`
 * - `cheatParams`      → `id=value,id=value`
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
     * 读取数值参数并按 [CheatParam.min] / [CheatParam.max] 夹紧。
     *
     * 与鸿蒙版的两点细微差异（都是「更保守」的方向）：
     * - 空串：JS 的 `Number('')` 是 0，这里按「未设置」回到 default；
     * - 无法解析（`toDoubleOrNull()` 为 null）：与 JS 的 `NaN → fallback` 一致。
     */
    fun resolveNumber(param: CheatParam, raw: String?): Double {
        val trimmed = raw?.trim().orEmpty()
        val n = if (trimmed.isEmpty()) param.defaultValue else trimmed.toDoubleOrNull() ?: param.defaultValue
        if (n.isNaN()) return param.defaultValue
        return n.coerceIn(param.min, param.max)
    }

    /**
     * 生成 JS 数字字面量：整数值不带 `.0`（对齐 JS `n.toString()`：`10` 而不是 `10.0`）。
     */
    fun numberLiteral(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "0"
        val asLong = v.toLong()
        return if (v == asLong.toDouble()) asLong.toString() else v.toString()
    }

    /** 数值 → 展示文字（有条目声明 [CheatParam.labels] 时用档位名，否则用数值 + 单位） */
    fun displayValue(param: CheatParam, value: Double): String {
        val idx = value.toInt()
        if (param.labels.isNotEmpty() && idx >= 0 && idx < param.labels.size && value == idx.toDouble()) {
            return param.labels[idx]
        }
        return numberLiteral(value) + param.unit
    }
}
