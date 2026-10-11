package com.lichtcui.pokelauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 应用根：主题 + 页面路由 + 屏幕方向 + 返回键。
 *
 * 路由用几个布尔量而不是 navigation-compose：页面总数只有六个
 * （首页 / 游戏页 / 设置页 / 参数调整页 / 法律页 / 开发者诊断页），
 * 引库会多一层依赖与混淆面，收益为负。层级关系：
 *
 * ```
 *                                 ┌→ 参数调整页
 * 首页 ─→ 设置页 ─┼→ 法律页
 *   └→ 开发者诊断页   └→ 开发者诊断页
 * 首页 ─→ 游戏页
 * ```
 *
 * 两条不变量：
 * 1. **只有游戏页用「屏幕方向」偏好**（[ApplyPageOrientation]），其余页面手机上恒竖屏；
 * 2. **只有一个 [BackHandler]**，其 `when` 分支顺序必须与下面显示用的 `when` 完全一致。
 *    用多个 BackHandler 时，`showCheats` 与 `showSettings` 同时为真会出现
 *    「退出设置却还停在参数调整页」的抢事件问题。
 */
@Composable
fun PokeLauncherApp(controller: SpikeController) {
    PokeTheme {
        var showCheats by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var showLegal by remember { mutableStateOf(false) }
        var showSpike by remember { mutableStateOf(false) }

        ApplyPageOrientation(gamePageActive = controller.showGame)

        BackHandler(
            enabled = controller.showGame || showLegal || showCheats || showSettings || showSpike
        ) {
            when {
                controller.showGame -> controller.exitGame()
                // 参数调整页 / 法律页 / 诊断页都是从设置页进的，先回设置页
                showLegal -> showLegal = false
                showCheats -> showCheats = false
                showSpike -> showSpike = false
                else -> showSettings = false
            }
        }

        when {
            controller.showGame -> GameScreen(controller)
            showLegal -> LegalScreen(onBack = { showLegal = false })
            showCheats -> CheatScreen(onBack = { showCheats = false })
            showSettings -> SettingsScreen(
                spike = controller,
                onBack = { showSettings = false },
                onOpenCheats = { showCheats = true },
                onOpenLegal = { showLegal = true },
                onOpenSpike = { showSpike = true },
                onTriggerUpdate = {
                    // 回首页后由首页发起下载（只有首页能申请通知权限：需要 Activity 的 launcher）
                    controller.requestUpdate()
                    showSettings = false
                }
            )
            showSpike -> SpikeScreen(controller, onBack = { showSpike = false })
            else -> HomeScreen(
                controller = controller,
                onOpenSettings = { showSettings = true },
                onOpenCheats = { showCheats = true },
                onOpenDiagnostics = { showSpike = true }
            )
        }
    }
}
