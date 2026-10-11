package com.lichtcui.pokelauncher.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lichtcui.pokelauncher.R
import kotlinx.coroutines.delay

/**
 * 精灵球资源准备动画（下载 / 解压阶段），对应鸿蒙版 `components/PokeballLoader.ets`。
 *
 * 左右摇晃 `±SHAKE_ANGLE`，每 `SHAKE_INTERVAL_MS` 换向、过渡 `SHAKE_ANIM_MS`；
 * 暂停或未开始（[PrepPhase.IDLE]）时停在正中。
 *
 * 与鸿蒙版的一处差异：**不需要 `appHidden`（切后台停表）**——ArkUI 那边是 60fps 的
 * `setInterval` 重绘，后台空转耗电；这里每次换向只是一次状态变更 + 一次 420ms 补间，
 * 且 Compose 在界面不可见时不会出新帧，代价可以忽略。
 */
@Composable
fun PokeballLoader(phase: PrepPhase, paused: Boolean = false, iconSize: Dp = 72.dp) {
    var targetAngle by remember { mutableFloatStateOf(0f) }
    val shaking = isShaking(phase, paused)

    LaunchedEffect(shaking) {
        if (!shaking) {
            // 暂停/静止：复位到正中（鸿蒙版 sync() 同样把 angle 置 0）
            targetAngle = 0f
            return@LaunchedEffect
        }
        targetAngle = SHAKE_ANGLE
        var direction = -1f
        while (true) {
            delay(SHAKE_INTERVAL_MS)
            targetAngle = direction * SHAKE_ANGLE
            direction = -direction
        }
    }

    val angle by animateFloatAsState(
        targetValue = targetAngle,
        animationSpec = tween(durationMillis = SHAKE_ANIM_MS, easing = FastOutSlowInEasing),
        label = "pokeball-shake"
    )

    Image(
        painter = painterResource(R.drawable.ic_pokeball),
        contentDescription = null,
        modifier = Modifier
            .size(iconSize)
            .rotate(angle)
    )
}
