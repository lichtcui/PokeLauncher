package com.lichtcui.pokelauncher.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichtcui.pokelauncher.BuildConfig
import com.lichtcui.pokelauncher.appupdate.ApkUpdatePhase
import com.lichtcui.pokelauncher.appupdate.AppUpdateRepository
import com.lichtcui.pokelauncher.appupdate.AppUpdateResult
import com.lichtcui.pokelauncher.appupdate.apkActionOf
import com.lichtcui.pokelauncher.appupdate.apkRowLabel
import com.lichtcui.pokelauncher.appupdate.apkRowValue
import com.lichtcui.pokelauncher.cheat.CheatState
import com.lichtcui.pokelauncher.core.Const
import com.lichtcui.pokelauncher.core.OrientationPref
import com.lichtcui.pokelauncher.core.Version
import kotlinx.coroutines.launch

/**
 * 设置页（对应鸿蒙版 `pages/Settings.ets`）。
 *
 * 与鸿蒙版的**唯一刻意差异**：「参数调整」在这里只是一个跳转入口，总开关在参数调整页顶部
 * （鸿蒙版把开关放在本页）。开关只有一处 → 不会出现两份状态不同步；本页只显示「已开启/已关闭」。
 *
 * ⚠️ 用户可见文案一律「参数调整」，不得出现「作弊」字样（应用商店审核风险）。
 */
@Composable
fun SettingsScreen(
    spike: SpikeController,
    onBack: () -> Unit,
    onOpenCheats: () -> Unit,
    onOpenLegal: () -> Unit,
    /** 开发者诊断页（调试与发布包都常驻：真机判据要在「还没装资源」时也能跑） */
    onOpenSpike: () -> Unit,
    onTriggerUpdate: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var orientation by remember { mutableStateOf(OrientationPref.read()) }
    var cheatMaster by remember { mutableStateOf(CheatState.isMasterEnabled()) }

    // 应用更新（自己的版本）：检查结果落在内存缓存（AppUpdateRepository 是模块级单例）
    var appResult by remember { mutableStateOf<AppUpdateResult?>(null) }
    var appChecking by remember { mutableStateOf(false) }
    var appCheckFailed by remember { mutableStateOf(false) }

    var deleting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    /** 进页面就检查一次（TTL 内复用缓存）；游戏资源没查过才发请求 */
    LaunchedEffect(Unit) {
        if (spike.releaseInfo == null && !spike.checkingRelease) spike.checkRelease(false)
        runCatching { AppUpdateRepository.check(force = false) }
            .onSuccess { appResult = it }
            .onFailure { appCheckFailed = true }
    }

    val checkAppUpdate: (Boolean) -> Unit = { force ->
        if (!appChecking) {
            appChecking = true
            scope.launch {
                runCatching { AppUpdateRepository.check(force = force) }
                    .onSuccess {
                        appResult = it
                        appCheckFailed = false
                    }
                    .onFailure { appCheckFailed = true }
                appChecking = false
            }
        }
    }

    Scaffold(
        topBar = { LauncherTopBar(title = "设置", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ---------------------------------------------------- 运行设置
            GroupTitle("运行设置")
            OrientationRow(orientation) { value ->
                orientation = value
                OrientationPref.save(value)
            }
            RowDivider()
            // 参数调整入口（开关在参数调整页里，见本页注释）
            NavRow(
                label = "参数调整",
                value = if (cheatMaster) "已开启" else "已关闭",
                valueColor = if (cheatMaster) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                showArrow = true,
                onClick = onOpenCheats
            )

            // ---------------------------------------------------- 数据管理
            GroupTitle("数据管理")
            val appStatus = appResult?.statusText(appChecking, appCheckFailed)
                ?: if (appChecking) "检查中…" else if (appCheckFailed) "检查失败" else "未检查"
            StatusRow(
                label = "应用更新",
                status = appStatus,
                statusColor = if (appResult?.hasUpdate == true) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                enabled = !appChecking,
                onClick = { checkAppUpdate(true) }
            )
            val result = appResult
            if (result != null && result.hasUpdate) {
                RowDivider()
                // 应用内自更新（P4）：同一个入口在「去更新 / 取消 / 安装更新」之间切换，
                // 文案与动作都由 appupdate 层的纯函数决定（首页弹窗用同一套）
                val apk = spike.apkState
                val action = apkActionOf(apk.phase)
                NavRow(
                    label = apkRowLabel(apk, result.remote.displayVersionName),
                    value = apkRowValue(apk, result.forceUpdate),
                    showArrow = true,
                    valueColor = when (apk.phase) {
                        ApkUpdatePhase.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                    onClick = { runApkUpdateAction(spike, context, result, action) }
                )
                if (apk.phase == ApkUpdatePhase.DOWNLOADING) {
                    ApkProgressBar(apk.progressPercent)
                }
                val apkDetail = apk.detailText()
                if (apkDetail.isNotEmpty()) {
                    Text(
                        apkDetail,
                        fontSize = 12.sp,
                        color = if (apk.phase == ApkUpdatePhase.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    )
                }
                val changelog = result.remote.androidChangelog
                if (changelog.isNotEmpty()) {
                    Text(
                        changelog,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                    )
                }
            }

            RowDivider()
            // 离线资源更新（游戏资源包）
            val installed = spike.installedVersion
            val remoteTag = spike.releaseInfo?.tag.orEmpty()
            val gameStatus = when {
                spike.checkingRelease -> "检查中…"
                installed.isEmpty() -> "未安装"
                remoteTag.isEmpty() -> "未知"
                Version.same(installed, remoteTag) -> "已是最新"
                else -> "发现新版本"
            }
            val gameUpdateAvailable = installed.isNotEmpty() && remoteTag.isNotEmpty() &&
                !Version.same(installed, remoteTag)
            StatusRow(
                label = "离线资源更新",
                status = gameStatus,
                statusColor = if (gameUpdateAvailable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                enabled = !spike.checkingRelease,
                onClick = { spike.checkRelease(true) }
            )
            if (gameUpdateAvailable) {
                RowDivider()
                NavRow(
                    label = "更新到新版本",
                    value = remoteTag,
                    showArrow = true,
                    onClick = onTriggerUpdate
                )
            } else if (installed.isEmpty()) {
                Text(
                    "还没有离线资源：回首页点「开始下载」拉取资源包（约 500MB），或在打靶页导入本地 game.zip。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                )
            }

            RowDivider()
            NavRow(
                label = if (deleting) "正在删除…" else "删除本地数据",
                showArrow = true,
                onClick = { if (!spike.busy && !deleting) confirmDelete = true }
            )

            // ---------------------------------------------------- 关于
            GroupTitle("关于")
            NavRow(label = "应用版本", value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            RowDivider()
            NavRow(
                label = "资源版本",
                value = installed.ifEmpty { "未安装" }
            )
            RowDivider()
            NavRow(label = "资源来源", value = "PokeRogue 公开源")
            RowDivider()
            NavRow(label = "作者", value = "lichtcui")
            RowDivider()
            NavRow(
                label = "GitHub",
                value = "lichtcui/PokeLauncher",
                showArrow = true,
                onClick = { AppUpdateRepository.openUrl(context, Const.PROJECT_GITHUB_URL) }
            )
            RowDivider()
            NavRow(label = "法律与免责声明", showArrow = true, onClick = onOpenLegal)

            // ---------------------------------------------------- 开发者
            GroupTitle("开发者")
            NavRow(
                label = "开发者诊断（打靶页）",
                showArrow = true,
                onClick = onOpenSpike
            )

            Text(
                "PokeRogue 离线版 · 仅供学习交流",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp, bottom = 40.dp),
                textAlign = TextAlign.Center
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除本地数据") },
            text = { Text("将删除已下载的离线资源，本地存档不受影响。确定删除吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        deleting = true
                        // 删 1.5GB 不能卡住主线程：controller 内部切到 IO，完成后回调
                        spike.deleteGame {
                            deleting = false
                            Toast.makeText(context, "本地数据已删除", Toast.LENGTH_SHORT).show()
                            onBack()
                        }
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

/** 屏幕方向分段控件（对应鸿蒙版 `segmentItem`，选中态随 `orientation` 刷新） */
@Composable
private fun OrientationRow(value: String, onSelect: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text("屏幕方向", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for ((segmentValue, label) in OrientationPref.SEGMENTS) {
                val selected = value == segmentValue
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(18.dp)
                        )
                        .clickable { onSelect(segmentValue) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = if (selected) THEME.onBrand else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        Text(
            "只影响游戏页：启动器自己的页面在手机上恒竖屏，平板上跟随系统。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
