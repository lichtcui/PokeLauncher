package com.lichtcui.pokelauncher.ui

import android.net.Uri
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichtcui.pokelauncher.core.Const
import com.lichtcui.pokelauncher.core.SharedUi
import com.lichtcui.pokelauncher.web.PokeWebView
import kotlinx.coroutines.delay

/**
 * `<input type=file>` 的待回调槽位。
 *
 * 用普通可空字段（而不是 Compose state）：回调对象既不参与渲染，也不应该引起重组。
 */
private class FileChooserSlot {
    var callback: ValueCallback<Array<Uri>>? = null

    fun take(): ValueCallback<Array<Uri>>? {
        val cb = callback
        callback = null
        return cb
    }

    /** 必须回一次 `null`，否则 WebView 会一直等这次文件选择（后续选择器再也弹不出来） */
    fun cancel() {
        callback?.onReceiveValue(null)
        callback = null
    }
}

/**
 * 游戏页：全屏 WebView + 返回按钮（自动淡出）+ 加载/错误浮层 + 打靶指标面板。
 *
 * 与鸿蒙 `pages/GamePage.ets` 的对应关系：
 * - 加载 WebView / 存档桥 / 静音 / 方向：都在这里或 `web/PokeWebView.kt`、`ui/PageOrientation.kt`；
 * - 返回按钮的「3 秒后淡出、淡出时点一下只把它唤回」逻辑照搬（纯常量与判定在 `ui/GameState.kt`）；
 * - 右上角的**指标面板是打靶期产物**（P0–P2 判据要在截图里同时拍到游戏画面与计时），
 *   发布前必须挪到 debug 开关后面（见 `docs/android-port.md` §4.3.6）。
 * - 浮层判定 `gameOverlayOf` 刻意做成「错误 > 加载中」：加载失败时 `pageLoaded` 永远是 false，
 *   顺序反了用户就只看到「正在载入…」而看不到错误。
 *
 * 两处有意偏离鸿蒙：
 * 1. 返回按钮放**左上角**（鸿蒙在右上角）——右上角被指标面板占了，两者叠在一起会互相吃掉点击；
 * 2. 错误提示做成居中卡片（鸿蒙是右上角一行 12sp 小字），并多一个「重试」按钮。
 *
 * 存档导入/导出（P4，对应鸿蒙 `WebDownloadDelegate` + `Web` 组件自带的选择器）：
 * - 导出：JS 拦截 → `PokeNative.saveDownload` → `SpikeController.onSaveExport` → 这里弹结果 Toast；
 * - 导入：`onShowFileChooser` → SAF 选择器 → 把选中的 `content://` 交回 WebView。
 */
@Composable
internal fun GameScreen(controller: SpikeController) {
    val context = LocalContext.current

    // 返回按钮：进入/重载后全显 3 秒 → 淡出；淡出状态下点一下只把它唤回（与鸿蒙一致）
    var backVisible by remember { mutableStateOf(true) }
    var backTick by remember { mutableStateOf(0) }
    LaunchedEffect(controller.gameSession, backTick) {
        backVisible = true
        delay(BACK_FADE_MS)
        backVisible = false
    }

    // 存档从快照回填过 → 明确告知（鸿蒙在 onPageEnd 里弹 toast）
    LaunchedEffect(controller.saveRestoredSeq) {
        if (controller.saveRestoredSeq > 0) {
            saveRestoredText(controller.saveRestoredCount)?.let {
                Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            }
        }
    }

    // 存档导出结果（鸿蒙在 onDownloadFinish / onDownloadFailed 里提示）
    LaunchedEffect(controller.saveExportSeq) {
        if (controller.saveExportSeq > 0 && controller.saveExportText.isNotEmpty()) {
            Toast.makeText(context, controller.saveExportText, Toast.LENGTH_LONG).show()
        }
    }

    // 游戏内「导入数据」的 `<input type=file>`：鸿蒙由 Web 组件自行弹选择器，
    // 安卓必须走 onShowFileChooser + SAF（游戏给什么 accept 就用它给的 intent）
    val slot = remember { FileChooserSlot() }
    val chooser = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = slot.take() ?: return@rememberLauncherForActivityResult
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        Log.i(Const.TAG, "file chosen: " + (uris?.size ?: 0))
        cb.onReceiveValue(uris)
    }
    DisposableEffect(Unit) {
        onDispose {
            // 离开游戏页时把悬着的回调弃掉：否则选择器回来后 WebView 会操作已销毁的实例
            slot.cancel()
        }
    }

    val overlay = gameOverlayOf(controller.pageLoaded, controller.fatalPageError)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(SharedUi.GAME_BG_COLOR))
    ) {
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
                onError = { controller.onPageError(it) },
                onSaveRestored = { controller.onSaveRestored(it) },
                onSaveExport = { name, mime, base64 -> controller.onSaveExport(name, mime, base64) },
                onShowFileChooser = { params, callback ->
                    // 上一次没回掉的先取消（游戏里连续点两次「导入数据」时会出现）
                    slot.cancel()
                    slot.callback = callback
                    try {
                        chooser.launch(params.createIntent())
                        true
                    } catch (t: Throwable) {
                        // 设备上没有能处理这个 intent 的选择器（极简 ROM / 未装文件管理器）
                        Log.w(Const.TAG, "file chooser unavailable: ${t.message}")
                        slot.cancel()
                        false
                    }
                }
            )
        }

        when (overlay) {
            GameOverlay.LOADING -> LoadingOverlay()
            GameOverlay.ERROR -> ErrorOverlay(
                detail = controller.fatalPageError,
                onRetry = { controller.reloadGame() }
            )
            GameOverlay.NONE -> Unit
        }

        // 悬浮面板：截图时能把「游戏画面 + 计时指标」拍进同一张图
        DevPanel(controller, Modifier.align(Alignment.TopEnd))

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = {
                    // 淡出后第一次点击只唤回按钮（避免误触退出游戏）；全显时才真的返回
                    if (backVisible) controller.exitGame() else backTick++
                },
                modifier = Modifier
                    .background(THEME.scrimLight)
                    .alpha(if (backVisible) BACK_FULL_ALPHA else BACK_FADED_ALPHA),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
            ) {
                Text("返回", fontSize = 12.sp, color = THEME.onScrim)
            }
        }
    }
}

/** 加载浮层：半透明蒙层 + 精灵球（盖住尚未出画面的 WebView，避免一片惨白） */
@Composable
private fun LoadingOverlay() {
    Box(
        Modifier
            .fillMaxSize()
            .background(THEME.scrimHeavy),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PokeballLoader(phase = PrepPhase.PROBING, iconSize = 64.dp)
            Text("正在载入游戏…", fontSize = 14.sp, color = THEME.onScrim)
        }
    }
}

/** 错误浮层：居中卡片（游戏画面仍可见）+ 明细 + 重试 */
@Composable
private fun ErrorOverlay(detail: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            Modifier
                .fillMaxWidth(0.86f)
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = THEME.gamePanel)
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("游戏加载失败", fontSize = 15.sp, color = MaterialTheme.colorScheme.error)
                Text(
                    loadErrorText(detail),
                    fontSize = 12.sp,
                    color = THEME.onScrim
                )
                Text(
                    "本地资源服务可能没起来，或资源包不完整。可以重试，或返回首页重新安装资源。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
                TextButton(onClick = onRetry) { Text("重试", fontSize = 12.sp) }
            }
        }
    }
}

/** 打靶指标面板（P0–P2 判据用；发布前必须挪到 debug 开关后面） */
@Composable
private fun DevPanel(controller: SpikeController, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .padding(8.dp)
            .width(210.dp),
        colors = CardDefaults.cardColors(containerColor = THEME.scrimHeavy)
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "canvas 补丁: ${if (controller.patchCanvas) "开" else "关"}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.secondary
            )
            Text("DOM 载入: ${formatMs(controller.lastPageLoadMs)}", fontSize = 11.sp, color = THEME.onScrim)
            for (mark in controller.timings) {
                Text("${mark.key}: ${mark.ms}ms", fontSize = 11.sp, color = THEME.onScrim)
            }
            Text("gid: ${controller.getImageDataCount}", fontSize = 11.sp, color = THEME.onScrim)
            if (controller.fatalPageError.isNotEmpty()) {
                // 只留一行短提示：完整错误在居中浮层里，别把面板撑爆
                Text("页面有报错", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
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
