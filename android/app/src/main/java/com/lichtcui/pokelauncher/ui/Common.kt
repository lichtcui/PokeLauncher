package com.lichtcui.pokelauncher.ui

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.lichtcui.pokelauncher.appupdate.ApkUpdateAction
import com.lichtcui.pokelauncher.appupdate.ApkUpdater
import com.lichtcui.pokelauncher.appupdate.AppUpdateResult
import com.lichtcui.pokelauncher.appupdate.UNKNOWN_SOURCES_TEXT
import com.lichtcui.pokelauncher.appupdate.AppUpdateRepository
import com.lichtcui.pokelauncher.core.Const

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
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            // 返回箭头显式 pin 成 textPrimary：Material3 默认走 onSurfaceVariant（= textTertiary #999999），
            // 而鸿蒙那边同一个「‹」是 THEME.textPrimary（#333333）—— 30sp 的大字形，差一档就看得出来。
            navigationIconContentColor = THEME.textPrimary
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

// ─────────────────────────── 应用内自更新（P4） ───────────────────────────

/**
 * 把已下载好的更新包交给系统安装器 —— 自更新的最后一步（首页弹窗与设置页共用）。
 *
 * 两种「没开权限」都要在这里给出去路，否则用户点了没反应：
 * 1. 系统级「安装未知应用」没开 → 直接把用户送到本应用的那个开关页（带包名）；
 * 2. 文件不见了（被清理工具删了 / 换了设备）→ 提示重新下载（会重新走一遍下载）。
 *
 * @return true 表示已经拉起安装界面
 */
fun launchApkInstaller(context: Context, fileName: String): Boolean {
    if (fileName.isEmpty()) return false
    val file = ApkUpdater.targetFile(context, fileName)
    if (!file.isFile) {
        Toast.makeText(context, "更新包不见了，请重新下载", Toast.LENGTH_SHORT).show()
        return false
    }
    if (!ApkUpdater.canInstall(context)) {
        Toast.makeText(context, UNKNOWN_SOURCES_TEXT, Toast.LENGTH_LONG).show()
        runCatching { context.startActivity(ApkUpdater.unknownSourcesIntent(context)) }
            .onFailure { Log.w(Const.TAG, "打开「安装未知应用」设置失败：${it.message}") }
        return false
    }
    return runCatching {
        context.startActivity(ApkUpdater.installIntent(context, file))
        true
    }.getOrElse {
        Log.w(Const.TAG, "拉起安装器失败：${it.message}")
        Toast.makeText(context, "无法打开安装器：${it.message}", Toast.LENGTH_LONG).show()
        false
    }
}

/**
 * 自更新动作分发（首页弹窗 / 设置页共用）。
 *
 * 第三个分支是端到端兼容：清单里没填 `apkUrl` 时安卓端做不了应用内下载，
 * 只能退回浏览器（与鸿蒙版行为一致），顺手 `openUrl` 会拿 `pageUrl`。
 */
fun runApkUpdateAction(
    controller: SpikeController,
    context: Context,
    result: AppUpdateResult,
    action: ApkUpdateAction
) {
    when (action) {
        ApkUpdateAction.CANCEL -> controller.cancelApkUpdate()
        ApkUpdateAction.INSTALL -> launchApkInstaller(context, controller.apkState.fileName)
        ApkUpdateAction.START -> {
            val remote = result.remote
            if (remote.apkUrl.isNotEmpty()) {
                controller.startApkUpdate(remote.apkUrl, remote.displayVersionName)
            } else {
                AppUpdateRepository.openUrl(context, remote.updateUrl)
            }
        }
    }
}

/**
 * 自更新进度条（下载/校验中才出现）。用 `progress = { ... }` 的 lambda 形式
 * （M3 1.3 起非 lambda 版本已废弃，仓库里首页也是这个写法）。
 */
@Composable
internal fun ApkProgressBar(percent: Int) {
    LinearProgressIndicator(
        progress = { percent / 100f },
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
    )
}
