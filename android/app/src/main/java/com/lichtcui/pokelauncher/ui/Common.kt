package com.lichtcui.pokelauncher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 页面外壳的公共件（设置页 / 法律页 / 参数调整页共用）。
 * 数值尽量与鸿蒙版 `pages/Settings.ets` / `pages/Legal.ets` 对齐，
 * 免得两个平台的同一个页面看起来像两个 App。
 */

/** 返回栏：左侧文字箭头（不引 material-icons 依赖），标题居中偏左，右侧留给 actions */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LauncherTopBar(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = { Text(title, fontSize = 20.sp) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Text("‹", fontSize = 30.sp) }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

/** 毫秒显示：负值（未测到）显示破折号 */
internal fun formatMs(value: Long): String = if (value < 0) "—" else "$value ms"

/** 分组小标题（对应鸿蒙版的 `groupTitle` builder） */
@Composable
internal fun GroupTitle(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 6.dp)
    )
}

/** 行间分隔线：左侧留 16dp（对应鸿蒙版 `divider` builder） */
@Composable
internal fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun Chevron() {
    Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.outline)
}

/**
 * 导航行（标签 + 右侧值 + 箭头）。
 * `onClick` 为 null 表示纯展示行（「关于」里的版本号等）。
 */
@Composable
internal fun NavRow(
    label: String,
    value: String = "",
    showArrow: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null
) {
    // 不用 Modifier.then(...)，也不抽 Modifier 扩展函数：本机的 compose lint 检查器
    // （SuspiciousModifierThen / ModifierDeclaration）会以 NoClassDefFoundError 崩掉整个
    // lintDebug 任务。if/else 直接选 Modifier 同样清楚。
    val rowModifier: Modifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    } else {
        Modifier.fillMaxWidth()
    }
    Row(
        modifier = rowModifier.padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (value.isNotEmpty()) {
            Text(
                value,
                fontSize = 14.sp,
                color = valueColor,
                modifier = Modifier.padding(end = if (showArrow) 6.dp else 0.dp)
            )
        }
        if (showArrow) Chevron()
    }
}

/**
 * 状态行：标签 + 右侧状态文案（「检查中… / 发现新版本 / 已是最新」），整行可点。
 *
 * ⚠️ 状态文案必须由调用方从 `@State` 直接读出来当参数传（不要在 @Composable 里缓存），
 * 否则点一下不会刷新——鸿蒙版在 `@Builder` 值参数上踩过同一个坑（见 AGENTS.md §2）。
 */
@Composable
internal fun StatusRow(
    label: String,
    status: String,
    statusColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(status, fontSize = 14.sp, color = statusColor)
    }
}
