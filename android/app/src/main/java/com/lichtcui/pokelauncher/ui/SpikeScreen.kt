package com.lichtcui.pokelauncher.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichtcui.pokelauncher.core.Const
import com.lichtcui.pokelauncher.core.Format
import com.lichtcui.pokelauncher.server.GameServer
import com.lichtcui.pokelauncher.web.PokeWebView

@Composable
fun PokeLauncherApp(controller: SpikeController) {
    PokeTheme {
        // 系统返回键：游戏页 → 打靶页；打靶页由系统处理（退出）
        BackHandler(enabled = controller.showGame) { controller.exitGame() }
        if (controller.showGame) GameScreen(controller) else SpikeScreen(controller)
    }
}

// ==================================================================== 打靶页

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpikeScreen(controller: SpikeController) {
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // zip 的 MIME 在不同文件管理器里可能是 application/zip / octet-stream / 空，
        // 所以选择器必须放开到 */*，只在回调里做校验
        if (uri != null) controller.installFromUri(uri)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("P0 打靶 · canvas 补丁 A/B", fontSize = 18.sp) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        bottomBar = { BottomBar(controller, onPickZip = { pickZip.launch(arrayOf("*/*")) }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { EnvCard(controller) }
            item { ServerCard(controller) }
            item { AssetCard(controller, onPickZip = { pickZip.launch(arrayOf("*/*")) }) }
            item { PatchCard(controller) }
            item { TimingCard(controller) }
            item { LogCard(controller) }
        }
    }
}

@Composable
private fun BottomBar(controller: SpikeController, onPickZip: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!controller.canEnterGame) {
            Text(
                text = when {
                    !controller.gameReady -> "未安装游戏资源：先导入 game.zip 或 adb push 到沙箱"
                    controller.chromiumMajor < Const.MIN_CHROMIUM_MAJOR -> "WebView Chromium ${controller.chromiumMajor} 低于底线 ${Const.MIN_CHROMIUM_MAJOR}，不支持"
                    controller.serverError.isNotEmpty() -> controller.serverError
                    else -> "本地服务未就绪"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        val enabled = controller.canEnterGame && !controller.busy
        Button(
            onClick = { controller.restartServer(); controller.enterGame() },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled
        ) {
            Text(if (controller.busy) controller.busyText else "进入游戏")
        }
        OutlinedButton(onClick = onPickZip, modifier = Modifier.fillMaxWidth(), enabled = !controller.busy) {
            Text("导入 game.zip")
        }
    }
}

@Composable
private fun EnvCard(controller: SpikeController) {
    val versionColor = if (controller.chromiumMajor >= Const.MIN_CHROMIUM_MAJOR) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.error
    }
    Section("设备 / WebView 环境") {
        LabeledRow("WebView 包名", controller.webViewPackageName.ifEmpty { "未知" })
        LabeledRow("WebView 版本", controller.webViewVersion, versionColor)
        LabeledRow("Chromium 主版本", controller.chromiumMajor.toString(), versionColor)
        LabeledRow("版本底线", ">= ${Const.MIN_CHROMIUM_MAJOR}")
        LabeledRow("MUTE_AUDIO", if (controller.muteAudioSupported) "支持" else "不支持（P3 降级注入）")
        LabeledRow("沙箱目录", controller.installer.rootDir.absolutePath)
        LabeledRow("可用空间", Format.bytes(controller.installer.availableBytes()))
    }
}

@Composable
private fun ServerCard(controller: SpikeController) {
    val (text, color) = when (controller.serverState) {
        GameServer.State.RUNNING -> "运行中" to MaterialTheme.colorScheme.tertiary
        GameServer.State.FAILED -> "启动失败" to MaterialTheme.colorScheme.error
        GameServer.State.STOPPED -> "已停止" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Section("本地服务") {
        LabeledRow("状态", text, color)
        LabeledRow("地址", controller.gameUrl.ifEmpty { "—" })
        if (controller.serverError.isNotEmpty()) {
            Text(controller.serverError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        LabeledRow("已服务文件", "${controller.server.servedCount} 个 / ${Format.bytes(controller.server.servedBytes)}")
        LabeledRow("端口", "${Const.GAME_PORT}（固定，改端口丢存档）")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { controller.restartServer() }) { Text("重启服务") }
            OutlinedButton(onClick = { controller.deleteGame() }, enabled = controller.gameReady && !controller.busy) {
                Text("删除资源")
            }
        }
    }
}

@Composable
private fun AssetCard(controller: SpikeController, onPickZip: () -> Unit) {
    val zip = controller.installer.localZipFile
    Section("游戏资源") {
        LabeledRow("已安装", if (controller.gameReady) "是" else "否",
            if (controller.gameReady) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
        LabeledRow("沙箱 game.zip", if (zip.isFile) Format.bytes(zip.length()) else "无")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { controller.installLocalZip() },
                enabled = zip.isFile && !controller.busy
            ) { Text("安装本地 game.zip") }
            OutlinedButton(onClick = onPickZip, enabled = !controller.busy) { Text("选择 zip") }
        }
        Text(
            "推荐：adb push game.zip ${controller.installer.rootDir.absolutePath}/ （507MB 走文件选择器会很慢）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PatchCard(controller: SpikeController) {
    Section("A/B 开关 · canvas 补丁") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("willReadFrequently=true", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "强制 2D canvas 走 CPU 后备，消除 Phaser 创建约 2 万个 Text 时的 GPU 读回开销",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = controller.patchCanvas, onCheckedChange = { controller.toggleCanvasPatch(it) })
        }
        Text(
            "切换后需重新进入游戏（index.html 每次都会重新注入）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TimingCard(controller: SpikeController) {
    Section("计时指标（同一台机器开/关补丁各跑一次对比）") {
        LabeledRow("DOM 载入（原生）", ms(controller.lastPageLoadMs))
        LabeledRow("页面已加载", if (controller.pageLoaded) "是" else "否")
        for (mark in controller.timings) {
            val label = when (mark.key) {
                "head" -> "boot 脚本执行"
                "phaser" -> "Phaser 就绪"
                "game" -> "Game 实例创建"
                "ready" -> "Game ready ✅"
                else -> mark.key
            }
            LabeledRow(label, ms(mark.ms), if (mark.key == "ready") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
        }
        LabeledRow("getImageData 次数", controller.getImageDataCount.toString())
        LabeledRow("canvas 数量", controller.canvasCount.toString())
        if (controller.fatalPageError.isNotEmpty()) {
            Text(controller.fatalPageError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(
            "判据：不开补丁 getImageData 约 2 万次且 ready 需数十秒；开补丁后两者都应显著下降。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LogCard(controller: SpikeController) {
    Section("日志（最新在上）") {
        OutlinedButton(onClick = { controller.clearLogs() }) { Text("清空") }
        // 日志用固定宽度的等宽小字，方便截图后直接读
        val shown = controller.logs.asReversed().take(120)
        for (line in shown) {
            Text(
                line,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ==================================================================== 游戏页

@Composable
private fun GameScreen(controller: SpikeController) {
    Box(Modifier.fillMaxSize()) {
        // key(gameSession)：加一就重建 WebView（DisposableEffect 会先把旧的 destroy 掉）。
        // 只调 loadUrl 复现不了冷启动，所以 A/B 对比必须真的重建。
        key(controller.gameSession) {
            PokeWebView(
                url = controller.gameUrl,
                modifier = Modifier.fillMaxSize(),
                onLog = { controller.log(it) },
                onTiming = { controller.onTiming(it) },
                onState = { controller.onState(it) },
                onPageFinished = { controller.onPageFinished(it) },
                onError = { controller.onPageError(it) }
            )
        }
        // 悬浮面板：截图时能把「游戏画面 + 计时指标」拍进同一张图
        Card(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .width(210.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xCC000000))
        ) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("canvas 补丁: ${if (controller.patchCanvas) "开" else "关"}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                Text("DOM 载入: ${ms(controller.lastPageLoadMs)}", fontSize = 11.sp, color = Color.White)
                for (mark in controller.timings) {
                    Text("${mark.key}: ${mark.ms}ms", fontSize = 11.sp, color = Color.White)
                }
                Text("gid: ${controller.getImageDataCount}", fontSize = 11.sp, color = Color.White)
                if (controller.fatalPageError.isNotEmpty()) {
                    Text(controller.fatalPageError.take(80), fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { controller.reloadGame() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("重载", fontSize = 11.sp) }
                    OutlinedButton(
                        onClick = { controller.exitGame() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("退出", fontSize = 11.sp) }
                }
            }
        }
    }
}

// ==================================================================== 小组件

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun LabeledRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor)
    }
}

private fun ms(value: Long): String = if (value < 0) "—" else "$value ms"
