package com.lichtcui.pokelauncher.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.lichtcui.pokelauncher.appupdate.AppUpdateRepository
import com.lichtcui.pokelauncher.appupdate.AppUpdateResult
import com.lichtcui.pokelauncher.cheat.CheatState
import com.lichtcui.pokelauncher.core.Const
import com.lichtcui.pokelauncher.core.Format
import com.lichtcui.pokelauncher.core.Version
import com.lichtcui.pokelauncher.install.DownloadPhase
import com.lichtcui.pokelauncher.install.DownloadStatus
import com.lichtcui.pokelauncher.install.DownloadUrls
import com.lichtcui.pokelauncher.install.Storage
import com.lichtcui.pokelauncher.server.GameServer
import kotlinx.coroutines.delay

/**
 * 首页（对应鸿蒙版 `pages/Index.ets` + `components/LauncherViews.ets`）。
 *
 * 四态由 [homeStateOf] 判定：判定中 → 未安装 / 下载流程 / 已就绪。
 * 页面本身不持有业务状态，全部读 [SpikeController]，动作也全部回写给它。
 *
 * 两处与鸿蒙版**有意**的差异（详见 `docs/android-port.md` §4.3.5）：
 * 1. 沿用本项目的深色主题（鸿蒙首页是白底），与其他安卓页面一致；
 * 2. 右上角除就绪态才出现的「设置」齿轮外，**常驻一个「诊断」入口**——安卓的 P0/P1 真机判据
 *    要在「没装资源」的状态下跑，鸿蒙版没有对应页面，只能这样留口子。
 */
@Composable
fun HomeScreen(
    controller: SpikeController,
    onOpenSettings: () -> Unit,
    onOpenCheats: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    val context = LocalContext.current

    // 选择 zip 导入（SAF：无需存储权限）
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.installFromUri(uri)
    }
    // 通知权限：启动前台下载服务前申请（API 33+ 才需要）
    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> controller.log("通知权限：${if (granted) "已授予" else "被拒绝（下载仍可进行，但没有进度通知）"}") }

    /** 开始/继续下载：先确保通知权限（下载由前台服务承载），再交给 controller */
    val startDownload: (Boolean) -> Unit = { fresh ->
        requestNotificationPermissionIfNeeded(context, requestNotifications::launch)
        controller.startDownload(fresh)
    }

    // 设置页「更新到新版本」的待办标记：取走并立刻发起下载
    LaunchedEffect(controller.pendingUpdate) {
        if (controller.pendingUpdate) {
            controller.consumePendingUpdate()
            startDownload(false)
        }
    }

    // 启动时自动检查应用更新（24h 节流；失败静默，只有发现新版本才弹窗）
    var updateResult by remember { mutableStateOf<AppUpdateResult?>(null) }
    val autoCheck = remember { AppUpdateRepository.shouldAutoCheck() }
    LaunchedEffect(Unit) {
        if (!autoCheck) return@LaunchedEffect
        val result = runCatching { AppUpdateRepository.check(force = true) }.getOrNull()
        if (result == null) {
            controller.log("自动检查应用更新失败（已静默）")
            return@LaunchedEffect
        }
        // 强制更新不写节流时间戳：下次启动重新检查并提示（与鸿蒙版一致）
        if (!result.forceUpdate) AppUpdateRepository.markChecked()
        if (result.hasUpdate && result.remote.androidPublished) updateResult = result
    }

    // 解压/导入完成 → 就绪页之间停 900ms，让「资源准备就绪」这句话被看见
    var holdReady by remember { mutableStateOf(false) }
    LaunchedEffect(controller.download.phase) {
        if (controller.download.phase == DownloadPhase.DONE) {
            holdReady = true
            delay(BURST_MS)
            holdReady = false
        }
    }

    val installing = controller.installing
    val state = homeStateOf(
        initializing = controller.initializing,
        inFlow = inFlowOf(controller.download, installing, holdReady),
        gameReady = controller.gameReady
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 右上角：诊断入口常驻；设置入口仅就绪态（与鸿蒙版一致）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, end = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onOpenDiagnostics) {
                Text("诊断", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state == HomeState.READY) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onOpenSettings),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⚙", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // 状态区：内容小于一屏时居中，超出时可滚动（小屏 + 大字体）
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 大屏（平板）上限宽居中，避免内容被拉得过宽（鸿蒙版权限 560）
                Column(
                    modifier = Modifier.widthIn(max = 560.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (state) {
                        HomeState.CHECKING -> CheckingView()

                        HomeState.NOT_DOWNLOADED -> NotDownloadedView(
                            controller = controller,
                            onStartDownload = { startDownload(false) },
                            onRetryFresh = { startDownload(true) },
                            onImport = { pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
                        )

                        HomeState.FLOW -> DownloadFlowView(
                            controller = controller,
                            installing = installing,
                            onTogglePause = {
                                if (controller.download.canResume) startDownload(false) else controller.pauseDownload()
                            },
                            onCancel = controller::cancelDownload
                        )

                        HomeState.READY -> ReadyView(
                            controller = controller,
                            onStartGame = controller::enterGame,
                            onOpenCheats = onOpenCheats
                        )
                    }

                    // 错误提示（四条状态共用，与鸿蒙版 `this.error` 同位）
                    if (controller.downloadError.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            controller.downloadError,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    updateResult?.let { result ->
        AppUpdateDialog(result = result, onDismiss = { updateResult = null })
    }
}

// ─────────────────────────── 判定中 ───────────────────────────

@Composable
private fun CheckingView() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("正在检查本地资源…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ─────────────────────────── 未安装 ───────────────────────────

@Composable
private fun NotDownloadedView(
    controller: SpikeController,
    onStartDownload: () -> Unit,
    onRetryFresh: () -> Unit,
    onImport: () -> Unit
) {
    val context = LocalContext.current
    val assetSize = controller.releaseInfo?.assetSize ?: 0L
    val assetUrl = controller.releaseInfo?.assetUrl.orEmpty()
    val mirrorUrl = DownloadUrls.mirrorOf(assetUrl)
    val totalText = if (assetSize > 0) Format.bytes(assetSize) else "约 531 MB"
    val requiredText = if (assetSize > 0) Format.bytes(Storage.requiredBytes(assetSize)) else "约 1.7 GB"
    // 预检是纯函数：空间不足时禁用主按钮并给出「需要多少 / 可用多少」
    val storageProblem = if (assetSize > 0) Storage.check(controller.freeBytes, assetSize) else null

    val pending = controller.pendingDownload
    val failed = controller.download.phase == DownloadPhase.FAILED
    val primaryLabel = if (pending != null || controller.download.phase == DownloadPhase.PAUSED) "继续下载" else "开始下载"

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PokeballLoader(phase = PrepPhase.IDLE)
        Spacer(Modifier.height(16.dp))
        Text("PokeRogue 离线资源未安装", fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(
            "本应用是 PokeRogue 的离线安卓版，安装资源后即可断网游玩",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        // 资源包信息卡片
        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InfoRow("来源", "PokeRogue 公开源")
                InfoRow("体积", totalText)
                InfoRow("需要存储", requiredText)
                if (controller.freeBytes > 0) InfoRow("可用空间", Format.bytes(controller.freeBytes))
            }
        }

        // 下载须知
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier.fillMaxWidth(0.92f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("· 下载中可切后台，完成后通知", fontSize = 13.sp, color = hint())
            Text("· 支持断点续传，中断可继续", fontSize = 13.sp, color = hint())
            Text("· 需要开启通知权限", fontSize = 13.sp, color = hint())
        }

        // 未完成下载提示（有分片才出现）
        if (pending != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                "发现未完成的下载：${pending.tag} 已完成 ${Format.bytes(pending.received)}（${pending.percent}%），" +
                    "可点「继续下载」从断点续传",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onStartDownload,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .height(52.dp),
            enabled = storageProblem == null && !controller.busy
        ) {
            Text(primaryLabel, fontSize = 17.sp)
        }

        // 换源重试：下载失败（或上次失败留下分片）时才出现；会丢弃分片从头下
        if (failed) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onRetryFresh) {
                Text("换源重试（丢弃已下载的分片）", fontSize = 13.sp)
            }
        }

        if (storageProblem != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                "$storageProblem（可用 ${Format.bytes(controller.freeBytes)}）",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }

        // 下载太慢时的备选：复制直链自行下载，再用「上传资源」导入（跳过下载与测速）
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier.fillMaxWidth(0.92f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (assetUrl.isNotEmpty()) {
                Text(
                    "下载太慢？复制下方直链自行下载资源包，再用「上传资源」导入",
                    fontSize = 12.sp,
                    color = hint()
                )
                if (mirrorUrl.isNotEmpty()) {
                    CopyUrlRow("镜像", mirrorUrl, context)
                }
                CopyUrlRow("GitHub", assetUrl, context)
            } else {
                Text(
                    if (controller.checkingRelease) "正在获取资源包地址…" else "暂未获取到资源包地址，请联网后重试",
                    fontSize = 12.sp,
                    color = hint()
                )
                TextButton(onClick = { controller.checkRelease(force = true) }) {
                    Text("重新获取", fontSize = 13.sp)
                }
            }

            OutlinedButton(
                onClick = onImport,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                Text("上传资源", fontSize = 16.sp)
            }
        }
    }
}

/** 可点击复制的直链行（点击即复制并 toast） */
@Composable
private fun CopyUrlRow(label: String, url: String, context: Context) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(label, fontSize = 12.sp, color = hint())
        Text(
            url,
            modifier = Modifier
                .weight(1f)
                .clickable {
                    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (manager == null) {
                        Toast.makeText(context, "复制失败，请手动长按选择", Toast.LENGTH_SHORT).show()
                    } else {
                        manager.setPrimaryClip(ClipData.newPlainText("PokeRogue 资源直链", url))
                        Toast.makeText(context, "直链已复制", Toast.LENGTH_SHORT).show()
                    }
                },
            fontSize = 12.sp,
            color = Color(0xFF6EA8FF),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ─────────────────────────── 下载 / 解压流程 ───────────────────────────

@Composable
private fun DownloadFlowView(
    controller: SpikeController,
    installing: Boolean,
    onTogglePause: () -> Unit,
    onCancel: () -> Unit
) {
    val status: DownloadStatus = controller.download
    val paused = !installing && status.phase == DownloadPhase.PAUSED
    val phase = prepPhaseOf(status.phase, installing, controller.gameReady)
    val title = flowTitleOf(
        phase = phase,
        paused = paused,
        installingText = if (installing) controller.busyText else ""
    )
    var confirmCancel by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PokeballLoader(phase = phase, paused = paused)
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)

        if (hidesProgress(phase)) {
            Spacer(Modifier.height(8.dp))
            Text("请勿关闭应用", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Spacer(Modifier.height(16.dp))
            // 进度条：百分比居中，白字 + 深色阴影（红底/灰底上都看得清）
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.84f)
                    .height(22.dp),
                contentAlignment = Alignment.Center
            ) {
                if (status.total > 0) {
                    LinearProgressIndicator(
                        progress = { status.percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .clip(RoundedCornerShape(11.dp))
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .clip(RoundedCornerShape(11.dp))
                    )
                }
                if (status.total > 0) {
                    Text(
                        "${status.percent}%",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                            style = TextStyle(
                            shadow = Shadow(color = Color(0x99000000), offset = Offset(0f, 1f), blurRadius = 2f)
                        )
                    )
                }
            }

            // 速度 · 剩余时间（两者都有值才加分隔符）
            val speedText = if (status.speedBps > 0) Format.speed(status.speedBps.toLong()) else ""
            val etaText = Format.duration(status.etaSeconds)
            if (speedText.isNotEmpty() || etaText.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    speedText + (if (speedText.isNotEmpty() && etaText.isNotEmpty()) " · " else "") + etaText,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(0.84f),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onTogglePause,
                    modifier = Modifier
                        .width(140.dp)
                        .height(44.dp)
                ) {
                    Text(if (paused) "继续" else "暂停", fontSize = 16.sp)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { confirmCancel = true }) {
                    Text("取消下载", fontSize = 14.sp, color = hint())
                }
            }
        }
    }

    // 取消是重操作（会丢掉已下载的分片）：先二次确认
    if (confirmCancel) {
        val downloaded = if (status.received > 0) Format.bytes(status.received) else "0 MB"
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("取消下载") },
            text = { Text("确定取消下载吗？已下载的 $downloaded 将全部丢失。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    onCancel()
                }) { Text("确定取消", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text("继续下载") }
            }
        )
    }
}

// ─────────────────────────── 已就绪 ───────────────────────────

@Composable
private fun ReadyView(
    controller: SpikeController,
    onStartGame: () -> Unit,
    onOpenCheats: () -> Unit
) {
    // 进入首页时读一次即可：参数调整页与首页互斥（换页会重建这一层 composition）
    val cheatMasterOn = remember { CheatState.isMasterEnabled() }
    val versionText = if (controller.installedVersion.isNotEmpty()) {
        Version.normalize(controller.installedVersion)
    } else {
        "未知"
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PokeballLoader(phase = PrepPhase.IDLE, iconSize = 96.dp)
        Spacer(Modifier.height(12.dp))
        Text("离线资源已就绪", fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text("版本 $versionText", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onStartGame,
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .height(54.dp),
            enabled = controller.canEnterGame && !controller.busy
        ) {
            Text("开始运行", fontSize = 18.sp)
        }

        // 未就绪时说明原因（服务器没起来 / WebView 太旧），否则点了只会白屏
        if (!controller.canEnterGame) {
            Spacer(Modifier.height(12.dp))
            Text(
                enterGameBlockReason(controller),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }

        // 参数调整入口：仅在设置里开启后显示；更改在重新进入游戏时生效
        if (cheatMasterOn) {
            Spacer(Modifier.height(10.dp))
            Text(
                "参数调整",
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenCheats)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 进不去游戏的原因（按钮下方红字；都在 home 页可见，故放这里而非 controller） */
private fun enterGameBlockReason(controller: SpikeController): String = when {
    !controller.gameReady -> "离线资源未就绪"
    controller.serverState != GameServer.State.RUNNING ->
        "本地服务未就绪：${controller.serverError.ifEmpty { "请稍候或重启应用" }}"
    controller.chromiumMajor < Const.MIN_CHROMIUM_MAJOR ->
        "系统 WebView 版本过低（Chromium ${controller.chromiumMajor}，需 ${Const.MIN_CHROMIUM_MAJOR}+），请先更新 Android System WebView"
    else -> "暂不可进入游戏"
}

// ─────────────────────────── 应用更新弹窗 ───────────────────────────

/** 发现新版本：普通更新可稍后，强制更新只有「去更新」且不可关闭（与鸿蒙版 `showAppUpdateDialog` 一致） */
@Composable
private fun AppUpdateDialog(result: AppUpdateResult, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val remote = result.remote
    val name = remote.displayVersionName.ifEmpty { remote.androidVersionCode.toString() }
    // 安卓只用安卓专用说明：鸿蒙的 changelog 说的事跟这个包无关
    val message = if (remote.androidChangelog.isNotEmpty()) {
        "发现新版本 $name\n\n${remote.androidChangelog}"
    } else {
        "发现新版本 $name"
    }
    AlertDialog(
        onDismissRequest = { if (!result.forceUpdate) onDismiss() },
        title = { Text(if (result.forceUpdate) "需要更新" else "发现新版本") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = {
                AppUpdateRepository.openUrl(context, remote.updateUrl)
                onDismiss()
            }) { Text("去更新") }
        },
        dismissButton = if (result.forceUpdate) {
            null
        } else {
            { TextButton(onClick = onDismiss) { Text("稍后") } }
        }
    )
}

// ─────────────────────────── 小工具 ───────────────────────────

@Composable
private fun hint(): Color = MaterialTheme.colorScheme.onSurfaceVariant

/** API 33+ 且未授权时申请通知权限（下载由前台服务承载，没有通知体验很差） */
private fun requestNotificationPermissionIfNeeded(context: Context, launch: (String) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    if (!granted) launch(Manifest.permission.POST_NOTIFICATIONS)
}
