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
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.luckylearning.windtone.data.BootTrace
import com.luckylearning.windtone.data.CrashLog
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.overlay.AccessibilityWatchdog
import com.luckylearning.windtone.overlay.ForegroundAppDetector
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

    /**
     * 进程内能拿到 Activity 实例的**最早**时刻（2026-11-01 加）。
     *
     * 动机是 v1.2.44 车机诊断包里那 12 秒冷启动：`app:onCreate` 在 1ms、
     * `activity:onCreate` 在 11534ms，中间 11.5 秒完全空白 —— 分不清是
     * 「系统加载 Activity 与 Compose 类慢」还是「我们的 onCreate 慢」。
     *
     * 类加载发生在构造与 attach 之前、埋不到点，但**这一行能当分界线**：
     *  - `activity:attach` 就很晚 → 时间在系统侧（类加载 / dexopt），我们改代码没用；
     *  - `activity:attach` 很早、`activity:onCreate` 很晚 → 是我们的 onCreate 在拖。
     */
    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(newBase)
        if (newBase != null) BootTrace.mark(newBase, "activity:attach")
    }

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
        BootTrace.mark(this, "boot:prefs")

        enableEdgeToEdge()
        BootTrace.mark(this, "boot:edge")

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
        // 2026-10-31：车机诊断包里 `activity:onCreate → setContent` 有一段 2.45s 的空档，
        // 而这两行之间只有下面三处 I/O。分三段埋点，下次冷启动就能看 trace 直接定位，
        // 不必再靠猜。埋点本身在 v1.2.40 已改为攒批落盘（只在首行 / 失败 / 首帧 / 攒够
        // 12 行时写），所以「能留下进程被杀时的痕迹」和「不刷十几次 I/O」两者兼得。
        BootTrace.mark(this, "boot:crash-scan")
        val previousBoot = runCatching { BootTrace.previous(applicationContext) }.getOrNull()
        // 判据只在 BootTrace 里写一份，这里不重复实现；顺带复用上面那一次读，
        // 不为同一个 prev.txt 再开一遍流。
        val incomplete = previousBoot?.takeIf { BootTrace.isIncomplete(it) }
        BootTrace.mark(this, "boot:trace-read")

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

    override fun onResume() {
        super.onResume()
        // 车机重启后无障碍常被抹掉；回到前台再检一次（通知可能被划掉）
        runCatching { AccessibilityWatchdog.checkAndNotify(applicationContext) }
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
            val playerState by playerVm.player.collectAsStateWithLifecycle()
            // UI v2 阶段三：形态判定的两项事实只在这里取一次，主题根部注入，全 App 共用。
            // 设备识别是静态事实，remember 住避免每次重组都去问 PackageManager。
            val carHardware = remember { ForegroundAppDetector.isCarHeadUnit(applicationContext) }
            MusicPlayerTheme(
                seedColor = if (playerVm.dynamicThemeEnabled()) seed else null,
                carMode = playerState.carMode,
                carHardware = carHardware,
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
