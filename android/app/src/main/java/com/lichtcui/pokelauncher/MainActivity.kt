package com.lichtcui.pokelauncher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.lichtcui.pokelauncher.ui.PokeLauncherApp
import com.lichtcui.pokelauncher.ui.SpikeController

/**
 * 唯一的 Activity。对应鸿蒙版 `entryability/EntryAbility` + `pages/Index`。
 *
 * 旋转/尺寸变化在 manifest 里通过 `configChanges` 自行处理，不重建 Activity，
 * 因此 [controller] 里的状态（计时指标、日志）在旋转后不会丢。
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: SpikeController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = SpikeController(applicationContext)
        setContent {
            PokeLauncherApp(controller)
        }
    }

    override fun onDestroy() {
        // WebView 由 Compose 的 DisposableEffect 负责销毁；这里收尾本地服务
        controller.server.stop()
        super.onDestroy()
    }
}
