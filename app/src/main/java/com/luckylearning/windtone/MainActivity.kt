package com.luckylearning.windtone

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.ui.MainScreen
import com.luckylearning.windtone.ui.MusicViewModel
import com.luckylearning.windtone.ui.theme.MusicPlayerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 车载模式（设置 → 外观 → 车载模式）：
        //  · 仅 `persistent=true`（用户主动选择的车机安装）时主动请求横屏，
        //    车机本身多为固定横屏，这里主要覆盖「手机横置当车机用」的场景；
        //  · `unspecified`（默认）完全跟随系统，不改变手机上的原有行为。
        // 注意：必须在 super.onCreate() 之前设置，否则会被重建流程吞掉。
        val carMode = Prefs(applicationContext).carMode
        requestedOrientation = if (carMode) {
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        enableEdgeToEdge()
        setContent {
            val vm: MusicViewModel = viewModel()
            val seed by vm.themeSeed.collectAsStateWithLifecycle()
            MusicPlayerTheme(
                seedColor = if (vm.dynamicThemeEnabled()) seed else null,
            ) {
                MainScreen(vm)
            }
        }
    }
}
