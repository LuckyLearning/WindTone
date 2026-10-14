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
import com.luckylearning.windtone.ui.LibraryViewModel
import com.luckylearning.windtone.ui.MainScreen
import com.luckylearning.windtone.ui.PlayerViewModel
import com.luckylearning.windtone.ui.SettingsViewModel
import com.luckylearning.windtone.ui.UpdateViewModel
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
            // 四个分域 ViewModel：曲库 / 播放 / 更新 / 设置与诊断。
            // 它们都作用域在同一个 Activity 的 ViewModelStore 上，
            // 真正的共享状态（播放引擎、曲库索引、消息总线）在 AppContainer 里。
            val lib: LibraryViewModel = viewModel()
            val playerVm: PlayerViewModel = viewModel()
            val updateVm: UpdateViewModel = viewModel()
            val settings: SettingsViewModel = viewModel()

            val seed by playerVm.themeSeed.collectAsStateWithLifecycle()
            MusicPlayerTheme(
                seedColor = if (playerVm.dynamicThemeEnabled()) seed else null,
            ) {
                MainScreen(lib = lib, playerVm = playerVm, updateVm = updateVm, settings = settings)
            }
        }
    }
}
