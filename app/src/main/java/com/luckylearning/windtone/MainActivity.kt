package com.luckylearning.windtone

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.luckylearning.windtone.data.BootTrace
import com.luckylearning.windtone.data.CrashLog
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.ThemeMode
import com.luckylearning.windtone.ui.AppShortcut
import com.luckylearning.windtone.ui.BootDiagView
import com.luckylearning.windtone.ui.ExternalMedia
import com.luckylearning.windtone.ui.LibraryViewModel
import com.luckylearning.windtone.ui.MainScreen
import com.luckylearning.windtone.ui.PlayerViewModel
import com.luckylearning.windtone.ui.SettingsViewModel
import com.luckylearning.windtone.ui.SplitProbeView
import com.luckylearning.windtone.ui.UpdateViewModel
import com.luckylearning.windtone.ui.theme.MusicPlayerTheme

class MainActivity : ComponentActivity() {

    /** 首帧只记一次 */
    private var frameMarked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BootTrace.mark(this, BootTrace.STAGE_ACTIVITY)

        // 桌面快捷方式（批次 9.4）只把意图记下来，真正的执行在 MainScreen 里 ——
        // 曲库此时还没装载完，这里直接调 playQueue 只会拿到空列表。
        AppShortcut.put(AppShortcut.from(intent))

        // 外部入口（2026-10-27）：文件管理器点歌（ACTION_VIEW audio/*）与系统语音点歌
        // （MEDIA_PLAY_FROM_SEARCH）。同样只登记、不执行，理由同上。
        ExternalMedia.put(ExternalMedia.from(intent))

        // 车载模式（设置 → 外观 → 车载模式）：
        //  · 仅 `persistent=true`（用户主动选择的车机安装）时主动请求横屏，
        //    车机本身多为固定横屏，这里主要覆盖「手机横置当车机用」的场景；
        //  · `unspecified`（默认）完全跟随系统，不改变手机上的原有行为。
        // `runCatching`：偏好文件损坏也不该让应用起不来。
        val carMode = runCatching { Prefs(applicationContext).carMode }.getOrDefault(false)
        requestedOrientation = if (carMode) {
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        enableEdgeToEdge()

        // 半屏对照测试构建（`-Pprobe=split`，见 app/build.gradle.kts 的说明）。
        // 车机上已经确认原生 View 能正常显示，所以这里只需要回答一个问题：
        // Compose 到底能不能在这台设备上画出东西。上原生 / 下 Compose，一张照片定案。
        if (BuildConfig.PROBE == "split") {
            setContentView(SplitProbeView.create(this))
            markFirstFrame()
            SplitProbeView.mark(this)
            return
        }

        // 三个「进去以前」的判据，任一成立就先摆诊断页（见 BootDiagView 的注释）：
        //  · BOOT_DIAG        —— 诊断构建，每次启动都必显，用来确认这台设备能不能画出窗口；
        //  · pendingCrash     —— 上一轮崩过、还没给用户看过；
        //  · lastIncompleteBoot —— 上一轮**进程活着但一帧都没画出去**（车机黑屏的核心线索）。
        val pendingCrash = runCatching { CrashLog.latestUnseen(applicationContext) }.getOrNull()
        val previousBoot = runCatching { BootTrace.previous(applicationContext) }.getOrNull()
        // 判据只在 BootTrace 里写一份，这里不重复实现
        val incomplete = runCatching { BootTrace.lastIncompleteBoot(applicationContext) }.getOrNull()

        if (BuildConfig.BOOT_DIAG || pendingCrash != null || incomplete != null) {
            setContentView(
                BootDiagView.create(
                    activity = this,
                    // 传「上一轮的完整记录」而不是只传坏的那一轮：
                    // 「画过首帧但仍全黑」与「一帧都没画」是完全不同的故障，诊断页必须能区分。
                    record = previousBoot,
                    forced = BuildConfig.BOOT_DIAG,
                    onEnter = {
                        runCatching { CrashLog.markSeen(applicationContext) }
                        enterApp()
                    },
                )
            )
            return
        }

        enterApp()
    }

    /**
     * 进入正常界面。
     *
     * 单独抽出来是因为它可能被诊断页的「进入应用」二次调用 ——
     * `setContent` 会替换掉诊断页，用户点一下就能对照「原生 View 能显示 / Compose 能不能显示」。
     */
    private fun enterApp() {
        BootTrace.mark(this, BootTrace.STAGE_CONTENT)
        setContent {
            // 四个分域 ViewModel：曲库 / 播放 / 更新 / 设置与诊断。
            // 它们都作用域在同一个 Activity 的 ViewModelStore 上，
            // 真正的共享状态（播放引擎、曲库索引、消息总线）在 AppContainer 里。
            val lib: LibraryViewModel = viewModel()
            val playerVm: PlayerViewModel = viewModel()
            val updateVm: UpdateViewModel = viewModel()
            val settings: SettingsViewModel = viewModel()

            val seed by playerVm.themeSeed.collectAsStateWithLifecycle()
            val themeMode by playerVm.themeMode.collectAsStateWithLifecycle()
            MusicPlayerTheme(
                seedColor = if (playerVm.dynamicThemeEnabled()) seed else null,
                // 明暗模式（设置 → 外观 → 主题模式，v1.2.5）：
                // 「跟随系统」才去问系统，浅色 / 深色是**越过系统**的明确选择。
                // 只在这一层决定，子页面一律从 `MaterialTheme.colorScheme` 取色 ——
                // 任何地方再写一次 `isSystemInDarkTheme()` 都会在强制模式下画错。
                darkTheme = when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                },
            ) {
                MainScreen(lib = lib, playerVm = playerVm, updateVm = updateVm, settings = settings)
            }
        }
        markFirstFrame()
    }

    /**
     * 记「第一帧画出来了」。
     *
     * 用 `onPreDraw` 而不是 `post`：要的是「真的走到绘制」，`post` 只说明消息队列转起来了。
     * 这一行是 [BootTrace] 判断「上次到底有没有出画面」的唯一依据，所以必须在真正
     * 绘制前一刻记，且只记一次。
     */
    private fun markFirstFrame() {
        val decor = window.decorView
        decor.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (decor.viewTreeObserver.isAlive) {
                        decor.viewTreeObserver.removeOnPreDrawListener(this)
                    }
                    if (!frameMarked) {
                        frameMarked = true
                        runCatching { BootTrace.markFrame(applicationContext) }
                    }
                    return true
                }
            }
        )
    }

    /**
     * 应用已经活着（前台或后台）时再点桌面快捷方式。
     *
     * 必须重新把意图交给 [AppShortcut]：`launchMode="singleTop"` 下系统不会新建实例，
     * 只会把 Intent 送到这里，`onCreate` 里那次读取早就过去了。
     * `setIntent` 也是必需的——不替换的话 `getIntent()` 一直是首次启动那个。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppShortcut.put(AppShortcut.from(intent))
        ExternalMedia.put(ExternalMedia.from(intent))
    }
}
