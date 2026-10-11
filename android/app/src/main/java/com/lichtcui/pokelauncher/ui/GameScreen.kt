package com.lichtcui.pokelauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichtcui.pokelauncher.web.PokeWebView

/**
 * 游戏页：全屏 WebView + 右上角悬浮指标面板。
 *
 * 悬浮面板是 P0–P2 打靶期的产物（截图时能把「游戏画面 + 计时指标」拍进同一张图，
 * 见 `docs/android-port.md` §4.4 判据）。P3 的游戏页还要补：返回按钮淡出、
 * 加载/错误浮层、方向与静音——那是下一个切片的事，本切片只把 `GameScreen` 从
 * `SpikeScreen.kt` 里拆出来，逻辑一行未改。
 */
@Composable
internal fun GameScreen(controller: SpikeController) {
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
                Text(
                    "canvas 补丁: ${if (controller.patchCanvas) "开" else "关"}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text("DOM 载入: ${formatMs(controller.lastPageLoadMs)}", fontSize = 11.sp, color = Color.White)
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
