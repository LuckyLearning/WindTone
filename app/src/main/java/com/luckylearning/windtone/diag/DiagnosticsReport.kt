package com.luckylearning.windtone.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.luckylearning.windtone.BuildConfig
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.CrashLog
import com.luckylearning.windtone.data.Prefs
import androidx.media3.common.util.UnstableApi
import com.luckylearning.windtone.playback.PlaybackQualityProbe
import com.luckylearning.windtone.playback.PlayerHandle
import com.luckylearning.windtone.data.UpdateChecker
import com.luckylearning.windtone.overlay.AccessibilityWatchdog
import com.luckylearning.windtone.overlay.ForegroundAppDetector
import com.luckylearning.windtone.overlay.ForegroundProbe
import com.luckylearning.windtone.state.LibraryUiState
import com.luckylearning.windtone.state.PlayerUiState
import com.luckylearning.windtone.ui.AppStrings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 完整诊断包（v1.1.88）。
 *
 * 目标只有一个：**用户贴一段文本，我们就能定位**。车机上装不了 profiler、也不一定有 adb，
 * 所以这里把「排查时需要问用户的问题」一次性变成机器自己回答：
 *
 *  环境（机型 / 系统 / ABI / 内存 / 存储）、权限（通知 / 悬浮窗 / 电池 / 媒体 / 无障碍 / 使用情况）、
 *  播放（当前曲目 / 状态 / 音源 scheme）、悬浮窗（开关与关键参数）、曲库（数量 / 扫描态）、
 *  前台识别（复用 [ForegroundProbe] 的整份报告）、以及最近 [DiagLog] 的事件流水。
 *
 * 两条纪律：
 *  - **不打印用户数据**：只给曲名 / 歌手这种「本来就在屏幕上」的信息；
 *  - **不打印凭证**：云盘 token、cookie 一律不读，只报「已登录 / 未登录」这类布尔量。
 *
 * i18n 第 24 批：用户可见外壳进 `dr_*`；嵌套 Probe（Foreground / SoftEq / DefaultMusic）各自 i18n。
 * Memory / Scene / PlaybackQuality / PerfMarks / ForensicsScript 正文留待后续批次。
 * **不改** SoftEq / 播放内核行为；机器可读字段名保持稳定。
 */
internal object DiagnosticsReport {

    /** 取好值的快照（[build] 是纯函数，可单测） */
    data class Facts(
        val collectedAt: Long,
        val appVersion: String,
        /** 安装包签名状态（v1.1.97）：官方 / 非官方 */
        val integrity: String = "—",
        val buildType: String,
        val packageName: String,
        val androidRelease: String,
        val sdkInt: Int,
        val manufacturer: String,
        val model: String,
        val device: String,
        val abi: String,
        val locale: String,
        val timeZone: String,
        val screenPx: String,
        val density: String,
        val availMemMb: Long,
        val lowMemory: Boolean,
        val freeStorageMb: Long,
        val notificationAllowed: Boolean,
        val canDrawOverlay: Boolean,
        val ignoringBattery: Boolean,
        val mediaAudioGranted: Boolean,
        val installUnknownAllowed: Boolean,
        val accessibilityOn: Boolean,
        val usageStatsOn: Boolean,
        val watchdogSeenOn: Boolean,
        // 播放
        val songTitle: String,
        val songArtist: String,
        val songSource: String,
        val playing: Boolean,
        val durationMs: Long,
        val repeatMode: Int,
        val shuffle: Boolean,
        val lyricLines: Int,
        val lyricOffsetMs: Int,
        val lyricScale: Float,
        // 悬浮窗
        val overlayOn: Boolean,
        val overlayFullHeight: Boolean,
        val overlayLines: Int,
        val overlayTextSp: Int,
        val overlayPauseMode: Int,
        val hiddenAppsCount: Int,
        // 曲库
        val librarySongs: Int,
        val libraryVisible: Int,
        val libraryScanning: Boolean,
        val libraryStatus: String,
        val librarySkipped: Int,
        // 前台识别（整份报告原文）
        val foregroundReport: String,
        val logLines: List<String>,
        /** 性能段原文（冷启动 / 首帧 / 悬浮窗挂载） */
        val performanceBlock: String = "",
        /** 播放质量段原文 */
        val playbackQualityBlock: String = "",
        /** 「默认音乐候选资格」自检（2026-10-27），见 DefaultMusicProbe */
        val defaultMusicBlock: String = "",
        /** 内存画像（2026-10-29），见 MemoryProbe */
        val memoryBlock: String = "",
        /** 场景化摘要（当前档 / 冷启动 / stash / 最近切换） */
        val sceneBlock: String = "",
        /** Soft EQ phase / confirm Prefs / curve digest (E5) */
        val softEqBlock: String = "",
        val versionCode: Long = 0L,
        /**
         * 悬浮窗自身开销（2026-10-31），见 [com.luckylearning.windtone.overlay.OverlayMetrics]。
         * 与整机「内存 / CPU」是两回事：这里回答「悬浮窗自己花了多少」。
         */
        val overlayCostBlock: String = "",
    )

    /** 取数 */
    @androidx.annotation.OptIn(UnstableApi::class)
    fun collect(
        context: Context,
        player: PlayerUiState,
        library: LibraryUiState,
    ): Facts {
        val dm = context.resources.displayMetrics
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val stat = runCatching { StatFs(context.filesDir.path) }.getOrNull()
        val freeMb = if (stat != null) {
            stat.availableBytes / (1024L * 1024L)
        } else {
            -1L
        }
        val song = player.current
        runCatching { PlaybackQualityProbe.refreshFromPlayer(PlayerHandle.player) }

        return Facts(
            collectedAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            integrity = com.luckylearning.windtone.data.AppIntegrity.describe(),
            buildType = BuildConfig.BUILD_TYPE,
            packageName = context.packageName,
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            abi = runCatching { UpdateChecker.primaryAbiLabel() }.getOrDefault("—"),
            locale = Locale.getDefault().toLanguageTag(),
            timeZone = java.util.TimeZone.getDefault().id,
            screenPx = "${dm.widthPixels}x${dm.heightPixels}",
            density = "%.1f (dp ${dm.densityDpi})".format(dm.density),
            availMemMb = mem.availMem / (1024L * 1024L),
            lowMemory = mem.lowMemory,
            freeStorageMb = freeMb,
            notificationAllowed = runCatching {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }.getOrDefault(false),
            canDrawOverlay = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false),
            ignoringBattery = ForegroundAppDetector.ignoringBatteryOptimizations(context),
            // v1.1.89：READ_MEDIA_AUDIO 是 Android 13+ 的权限；12 及以下必须查
            // READ_EXTERNAL_STORAGE，否则车机上会误报「读取音频：否」——
            // 而曲库明明有 169 首，这种自相矛盾会误导排查。
            mediaAudioGranted = runCatching {
                val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    android.Manifest.permission.READ_MEDIA_AUDIO
                } else {
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                }
                context.checkSelfPermission(perm) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false),
            installUnknownAllowed = runCatching {
                // canRequestPackageInstalls 是 API 26 才有的（minSdk 24）：
                // 直接调在 24/25 上会抛 NoSuchMethodError，被 runCatching 吞成「否」——
                // 表现为老设备上永远显示「未知来源安装：否」，而实际是开着的。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.packageManager.canRequestPackageInstalls()
                } else {
                    // Android 7.x 只有全局「未知来源」总开关
                    android.provider.Settings.Secure.getInt(
                        context.contentResolver,
                        android.provider.Settings.Secure.INSTALL_NON_MARKET_APPS,
                        0,
                    ) == 1
                }
            }.getOrDefault(false),
            accessibilityOn = ForegroundAppDetector.accessibilityEnabled(context),
            usageStatsOn = ForegroundAppDetector.usageStatsGranted(context),
            watchdogSeenOn = AccessibilityWatchdog.seenOn(context),
            songTitle = song?.title ?: AppStrings.s(R.string.dr_none).ifEmpty { "（无）" },
            songArtist = song?.artist ?: "",
            songSource = describeSource(song?.uri?.toString()),
            playing = player.isPlaying,
            durationMs = player.durationMs,
            repeatMode = player.repeatMode,
            shuffle = player.shuffle,
            lyricLines = player.lyrics.lines.size,
            lyricOffsetMs = player.lyricOffsetMs,
            lyricScale = player.lyricScale,
            overlayOn = player.lyricOverlay,
            overlayFullHeight = player.lyricOverlayFullHeight,
            overlayLines = player.lyricOverlayLines,
            overlayTextSp = player.lyricOverlayTextSp,
            overlayPauseMode = player.lyricOverlayPauseMode,
            hiddenAppsCount = player.lyricOverlayHiddenApps.size,
            librarySongs = library.songs.size,
            libraryVisible = library.visible.size,
            libraryScanning = library.scanning,
            libraryStatus = library.statusText,
            librarySkipped = library.skippedUnsupported,
            foregroundReport = ForegroundProbe.buildReport(ForegroundProbe.collect(context)),
            logLines = DiagLog.snapshot(),
            performanceBlock = PerfMarks.buildReport(context),
            playbackQualityBlock = buildString {
                appendLine(PlaybackQualityProbe.report())
                val prefs = Prefs(context)
                appendLine(
                    AppStrings.s(
                        R.string.dr_gapless,
                        if (prefs.gaplessPlayback) onOff(true) else onOff(false),
                    ),
                )
                appendLine(AppStrings.s(R.string.dr_skip_silence, onOff(prefs.skipSilence)))
                appendLine(AppStrings.s(R.string.dr_skip_intro, prefs.skipIntroSeconds))
                appendLine(AppStrings.s(R.string.dr_skip_outro, prefs.skipOutroSeconds))
            }.trimEnd(),
            defaultMusicBlock = DefaultMusicProbe.buildReport(context),
            memoryBlock = MemoryProbe.buildReport(context, library.songs.size),
            sceneBlock = SceneProbe.buildReport(context),
            softEqBlock = SoftEqProbe.buildReport(context),
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            overlayCostBlock = com.luckylearning.windtone.overlay.OverlayMetrics.summary(),
        )
    }

    /** 音源只报 scheme / 来源类型，绝不打印完整 URL（可能带签名） */
    private fun describeSource(uri: String?): String {
        val none = AppStrings.s(R.string.dr_none).ifEmpty { "（无）" }
        if (uri.isNullOrBlank()) return none
        val scheme = uri.substringBefore(':', missingDelimiterValue = "")
        return when {
            scheme == "file" || scheme == "content" ->
                AppStrings.s(R.string.dr_src_local, scheme).ifEmpty { "$scheme（本地）" }
            scheme == "http" || scheme == "https" ->
                AppStrings.s(R.string.dr_src_online, scheme).ifEmpty { "$scheme（在线）" }
            else ->
                AppStrings.s(R.string.dr_src_unknown, scheme).ifEmpty { "$scheme（未知）" }
        }
    }

    /** 拼装（纯函数） */
    fun build(f: Facts): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(f.collectedAt))
        val sb = StringBuilder()

        sb.appendLine(AppStrings.s(R.string.dr_title))
        sb.appendLine(AppStrings.s(R.string.dr_collected_at, time))
        sb.appendLine()

        sb.appendLine(AppStrings.s(R.string.dr_sec_env))
        sb.appendLine(AppStrings.s(R.string.dr_app_line, f.appVersion, f.buildType, f.packageName))
        sb.appendLine(AppStrings.s(R.string.dr_mapping, CrashLog.mappingAssetName()))
        sb.appendLine(AppStrings.s(R.string.dr_version_code, f.versionCode))
        sb.appendLine(AppStrings.s(R.string.dr_integrity, f.integrity))
        sb.appendLine(
            AppStrings.s(
                R.string.dr_system,
                f.androidRelease,
                f.sdkInt,
                f.manufacturer,
                f.model,
                f.device,
            ),
        )
        sb.appendLine(AppStrings.s(R.string.dr_abi_locale, f.abi, f.locale, f.timeZone))
        sb.appendLine(AppStrings.s(R.string.dr_screen, f.screenPx, f.density))
        sb.appendLine(
            AppStrings.s(
                R.string.dr_avail_mem,
                f.availMemMb,
                if (f.lowMemory) AppStrings.s(R.string.dr_low_mem) else "",
            ),
        )
        val storage = if (f.freeStorageMb >= 0) {
            AppStrings.s(R.string.dr_storage_mb, f.freeStorageMb)
        } else {
            AppStrings.s(R.string.dr_query_failed)
        }
        sb.appendLine(AppStrings.s(R.string.dr_avail_storage, storage))
        sb.appendLine()

        // 应用侧内存画像（v1.2.12）：车机上唯一能拿到的量化入口，不要挪到文末
        if (f.memoryBlock.isNotBlank()) {
            sb.appendLine(f.memoryBlock.trimEnd())
            sb.appendLine()
        }
        sb.appendLine(AppStrings.s(R.string.dr_sec_perm))
        sb.appendLine(
            AppStrings.s(R.string.dr_notif_overlay, yn(f.notificationAllowed), yn(f.canDrawOverlay)),
        )
        sb.appendLine(AppStrings.s(R.string.dr_battery_wl, yn(f.ignoringBattery)))
        sb.appendLine(
            AppStrings.s(R.string.dr_media_install, yn(f.mediaAudioGranted), yn(f.installUnknownAllowed)),
        )
        sb.appendLine(
            AppStrings.s(
                R.string.dr_a11y_usage,
                yn(f.accessibilityOn),
                yn(f.watchdogSeenOn),
                yn(f.usageStatsOn),
            ),
        )
        sb.appendLine()

        // 「默认音乐」列表里看不到本应用时，这一段能直接说明是没声明还是车机不认
        if (f.defaultMusicBlock.isNotBlank()) {
            sb.appendLine(f.defaultMusicBlock.trimEnd())
            sb.appendLine()
        }

        sb.appendLine(AppStrings.s(R.string.dr_sec_play))
        sb.appendLine(
            if (f.songArtist.isBlank()) {
                AppStrings.s(R.string.dr_track, f.songTitle)
            } else {
                AppStrings.s(R.string.dr_track_artist, f.songTitle, f.songArtist)
            },
        )
        sb.appendLine(
            AppStrings.s(
                R.string.dr_play_state,
                if (f.playing) AppStrings.s(R.string.dr_playing) else AppStrings.s(R.string.dr_paused),
                f.durationMs / 1000,
            ),
        )
        sb.appendLine(
            AppStrings.s(R.string.dr_play_mode, f.repeatMode, f.shuffle, f.songSource),
        )
        sb.appendLine(
            AppStrings.s(R.string.dr_lyrics, f.lyricLines, f.lyricOffsetMs, f.lyricScale.toString()),
        )
        sb.appendLine()

        if (f.sceneBlock.isNotBlank()) {
            sb.appendLine(f.sceneBlock.trimEnd())
            sb.appendLine()
        }

        // E5: soft EQ phase / confirm / curve — car HU has no adb for A/B
        if (f.softEqBlock.isNotBlank()) {
            sb.appendLine(f.softEqBlock.trimEnd())
            sb.appendLine()
        }

        sb.appendLine(AppStrings.s(R.string.dr_sec_overlay))
        sb.appendLine(
            AppStrings.s(
                R.string.dr_overlay_flags,
                yn(f.overlayOn),
                yn(f.overlayFullHeight),
                f.overlayLines,
            ),
        )
        sb.appendLine(
            AppStrings.s(
                R.string.dr_overlay_style,
                f.overlayTextSp,
                if (f.overlayPauseMode == 1) {
                    AppStrings.s(R.string.dr_overlay_hide)
                } else {
                    AppStrings.s(R.string.dr_overlay_fade)
                },
                f.hiddenAppsCount,
            ),
        )
        if (f.overlayCostBlock.isNotBlank()) sb.appendLine(f.overlayCostBlock.trimEnd())
        sb.appendLine()

        sb.appendLine(AppStrings.s(R.string.dr_sec_library))
        sb.appendLine(
            AppStrings.s(
                R.string.dr_library_counts,
                f.librarySongs,
                f.libraryVisible,
                f.librarySkipped,
            ),
        )
        val libStatus = f.libraryStatus.ifBlank {
            AppStrings.s(R.string.dr_empty).ifEmpty { "（空）" }
        }
        sb.appendLine(AppStrings.s(R.string.dr_library_scan, yn(f.libraryScanning), libStatus))
        sb.appendLine()

        if (f.performanceBlock.isNotBlank()) {
            sb.appendLine(f.performanceBlock.trimEnd())
            sb.appendLine()
        }
        if (f.playbackQualityBlock.isNotBlank()) {
            sb.appendLine(f.playbackQualityBlock.trimEnd())
            sb.appendLine()
        }
        sb.appendLine(f.foregroundReport.trimEnd())
        sb.appendLine()

        sb.appendLine(AppStrings.s(R.string.dr_sec_events, f.logLines.size))
        if (f.logLines.isEmpty()) {
            sb.appendLine(AppStrings.s(R.string.dr_events_empty))
        } else {
            f.logLines.forEach { sb.appendLine(it) }
        }
        sb.appendLine()
        sb.appendLine(AppStrings.s(R.string.dr_sec_forensics))
        sb.appendLine(ForensicsScript.shortHint(f.packageName, f.appVersion))
        sb.appendLine(AppStrings.s(R.string.dr_forensics_script))
        sb.appendLine(
            ForensicsScript.build(
                packageName = f.packageName,
                versionName = f.appVersion,
                versionCode = f.versionCode,
            ).trimEnd(),
        )
        sb.appendLine()
        sb.appendLine()
        sb.appendLine(AppStrings.s(R.string.dr_end))
        return sb.toString()
    }

    private fun yn(v: Boolean) =
        AppStrings.s(if (v) R.string.dr_yes else R.string.dr_no).ifEmpty { if (v) "是" else "否" }

    private fun onOff(v: Boolean) =
        AppStrings.s(if (v) R.string.dr_on else R.string.dr_off).ifEmpty { if (v) "开" else "关" }

    /** 诊断包保留份数。单份含完整 smaps，可达数 MB，不清理会一直堆在用户存储里 */
    private const val KEEP_FILES = 3

    /**
     * 落盘到应用私有目录（卸载即清），返回路径。
     *
     * 写完后按份数淘汰：这个目录此前只增不减，而诊断包带完整 `smaps`
     * 分类（1~2MB 文本），导几次就是几十 MB 白占着。
     */
    fun saveToFile(context: Context, report: String): String? = runCatching {
        val dir = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics")
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = java.io.File(dir, "diag_$stamp.txt")
        file.writeText(report)
        prune(dir)
        file.absolutePath
    }.getOrElse { null }

    private fun prune(dir: java.io.File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= KEEP_FILES) return
        files.sortedByDescending { it.lastModified() }
            .drop(KEEP_FILES)
            .forEach { runCatching { it.delete() } }
    }
}
