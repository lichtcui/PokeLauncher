package com.lichtcui.pokelauncher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 主题色取自鸿蒙版图标（`design/app_icon.svg` 的 #DA3838 红色环） */
private val PokeRed = Color(0xFFDA3838)
private val PokeRedDark = Color(0xFF8C1F1F)
private val PokeGreen = Color(0xFF4CAF50)
private val PokeAmber = Color(0xFFFFB300)

/** 游戏本身是深色主题，启动器用深色可减少进出游戏时的亮度跳变 */
private val DarkColors = darkColorScheme(
    primary = PokeRed,
    onPrimary = Color.White,
    primaryContainer = PokeRedDark,
    onPrimaryContainer = Color.White,
    secondary = PokeAmber,
    onSecondary = Color.Black,
    tertiary = PokeGreen,
    onTertiary = Color.Black,
    background = Color(0xFF101014),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1B1B20),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF26262C),
    onSurfaceVariant = Color(0xFFB9B5BD),
    error = Color(0xFFEF5350)
)

@Composable
fun PokeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
