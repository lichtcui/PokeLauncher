package com.lichtcui.pokelauncher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 跨端共享的视觉令牌（颜色）+ Material3 亮色方案。
 *
 * ⚠️ **本文件由 `scripts/gen-shared.mjs` 自动生成，不要手改**：唯一真相在仓库根 `shared/theme.json`。
 * 改色值跑 `node scripts/gen-shared.mjs`，两端（鸿蒙 / 安卓）一起更新。
 *
 * 为什么要有它：Compose 组件只认 Material3 角色（`colorScheme.surfaceVariant` …），
 * 以前这些角色被映射到一套自成一派的深色方案（首页 #101014），而鸿蒙是亮色（#FFFFFF），
 * 两端各自「正确」却看着不是一个 App。现在统一到鸿蒙现状：亮色 + 品牌红 #DA3838。
 *
 * 两种用法都对，按语义选：
 * - Material3 组件内部角色 → 保留 `MaterialTheme.colorScheme.*`（本文件的 scheme 已映射到令牌）；
 * - 自己写死的颜色 → `THEME.xxx`。
 *
 * 生成器那边的 `checkThemeUsage` 会反向要求：`Color(0x…)` / `Color.White` 这类字面量
 * 不许再出现在 UI 代码里（生成物除外），令牌也不许没人引用。
 */

/** 令牌唯一实例：业务代码只通过它取色（`THEME.textPrimary`） */
object THEME {
    /** 品牌红：主按钮底色、强调文字、错误文字（与鸿蒙现状一致：错误态也用品牌红） */
    val brand: Color = Color(0xFFDA3838)

    /** 品牌红底上的文字（主按钮文案、进度条百分比、选中分段） */
    val onBrand: Color = Color(0xFFFFFFFF)

    /** 页面大标题（法律页 / 参数调整页） */
    val textTitle: Color = Color(0xFF1A1A1A)

    /** 正文与列表项标题（默认文字色） */
    val textPrimary: Color = Color(0xFF333333)

    /** 法律页正文（比默认略淡，长段落更耐读） */
    val textBody: Color = Color(0xFF555555)

    /** 次要按钮文案、未选中分段文字 */
    val textMuted: Color = Color(0xFF666666)

    /** 字段名与说明文字 */
    val textSecondary: Color = Color(0xFF888888)

    /** 提示文字、设置项的当前值（最常用的一种淡字） */
    val textTertiary: Color = Color(0xFF999999)

    /** 页脚、极淡说明 */
    val textFaint: Color = Color(0xFFBBBBBB)

    /** 列表箭头「›」这类装饰性图形 */
    val iconFaint: Color = Color(0xFFCCCCCC)

    /** 启动器窗口底色（鸿蒙侧为 HOME_BG_COLOR，显示在安全区 / 系统栏留白处） */
    val background: Color = Color(0xFFFFFFFF)

    /** 卡片 / 弹窗底色 */
    val surface: Color = Color(0xFFFFFFFF)

    /** 次级容器（未选中分段底色、分组卡片） */
    val surfaceVariant: Color = Color(0xFFF2F2F2)

    /** 浅灰信息块 */
    val surfaceMuted: Color = Color(0xFFF7F7F7)

    /** 进度条轨道 */
    val track: Color = Color(0xFFEEEEEE)

    /** 分隔线 */
    val divider: Color = Color(0xFFEDEDED)

    /** 警示文字（琥珀色） */
    val warning: Color = Color(0xFFC77700)

    /** 警示块底色 */
    val warningBg: Color = Color(0xFFFFF8E8)

    /** 危险提示块底色（账号风险 / 删除数据） */
    val dangerBg: Color = Color(0xFFFFF3F3)

    /** 可点击链接（资源直链 / 镜像地址） */
    val link: Color = Color(0xFF3A6FD8)

    /** 成功 / 运行中（仅安卓打靶页的诊断指标用；鸿蒙没有对应界面） */
    val success: Color = Color(0xFF4CAF50)

    /** 遮罩 60% 黑（游戏页错误横幅 / 弹窗遮罩） */
    val scrim: Color = Color(0x99000000)

    /** 遮罩 40% 黑（游戏页返回按钮） */
    val scrimLight: Color = Color(0x66000000)

    /** 遮罩 80% 黑（游戏页加载浮层 / 诊断面板） */
    val scrimHeavy: Color = Color(0xCC000000)

    /** 深色遮罩上的文字（游戏页「返回」「正在载入游戏…」） */
    val onScrim: Color = Color(0xFFFFFFFF)

    /** 文字投影色（红条上的百分比数字靠它压出对比） */
    val shadow: Color = Color(0x99000000)

    /** 游戏页诊断面板 / 错误卡片底色（仅安卓） */
    val gamePanel: Color = Color(0xF2202020)

    /** 透明（纯文字按钮的底色） */
    val transparent: Color = Color(0x00000000)
}

/**
 * Material3 亮色方案：每个角色都显式映射到 `shared/theme.json` 的令牌。
 *
 * 别删「没用到」的角色：Compose 组件内部会读它们（弹窗容器、开关轨道…），漏掉就变紫。
 */
private val LightColors = lightColorScheme(
    primary = THEME.brand,
    onPrimary = THEME.onBrand,
    primaryContainer = THEME.brand,
    onPrimaryContainer = THEME.onBrand,
    inversePrimary = THEME.brand,
    secondary = THEME.warning,
    onSecondary = THEME.onBrand,
    secondaryContainer = THEME.warningBg,
    onSecondaryContainer = THEME.warning,
    tertiary = THEME.success,
    onTertiary = THEME.onBrand,
    tertiaryContainer = THEME.surface,
    onTertiaryContainer = THEME.textPrimary,
    background = THEME.background,
    onBackground = THEME.textPrimary,
    surface = THEME.surface,
    onSurface = THEME.textPrimary,
    surfaceVariant = THEME.surfaceVariant,
    onSurfaceVariant = THEME.textTertiary,
    surfaceTint = THEME.surface,
    inverseSurface = THEME.textTitle,
    inverseOnSurface = THEME.surface,
    error = THEME.brand,
    onError = THEME.onBrand,
    errorContainer = THEME.dangerBg,
    onErrorContainer = THEME.brand,
    outline = THEME.iconFaint,
    outlineVariant = THEME.divider,
    scrim = THEME.scrim,
    surfaceBright = THEME.surface,
    surfaceDim = THEME.surfaceVariant,
    surfaceContainer = THEME.surfaceMuted,
    surfaceContainerLow = THEME.surfaceMuted,
    surfaceContainerLowest = THEME.surface,
    surfaceContainerHigh = THEME.surfaceVariant,
    surfaceContainerHighest = THEME.surfaceVariant,
)

@Composable
fun PokeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
