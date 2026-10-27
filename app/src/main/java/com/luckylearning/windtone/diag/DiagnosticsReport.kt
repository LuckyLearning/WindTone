package com.luckylearning.windtone.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.luckylearning.windtone.BuildConfig
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
        val versionCode: Long = 0L,
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
            songTitle = song?.title ?: "（无）",
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
                    "无缝播放：${if (prefs.gaplessPlayback) "开（偏好；Media3 单实例队列 + 分轨 encoder delay/padding）" else "关（偏好声明；播放仍为单 ExoPlayer 实例）"}",
                )
                appendLine("跳过静音：${if (prefs.skipSilence) "开" else "关"}")
            }.trimEnd(),
            defaultMusicBlock = DefaultMusicProbe.buildReport(context),
            versionCode = BuildConfig.VERSION_CODE.toLong(),
        )
    }

    /** 音源只报 scheme / 来源类型，绝不打印完整 URL（可能带签名） */
    private fun describeSource(uri: String?): String {
        if (uri.isNullOrBlank()) return "（无）"
        val scheme = uri.substringBefore(':', missingDelimiterValue = "")
        return when {
            scheme == "file" || scheme == "content" -> "$scheme（本地）"
            scheme == "http" || scheme == "https" -> "$scheme（在线）"
            else -> "$scheme（未知）"
        }
    }

    /** 拼装（纯函数） */
    fun build(f: Facts): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(f.collectedAt))
        val sb = StringBuilder()

        sb.appendLine("=== 听风 · 诊断包 ===")
        sb.appendLine("采集时间：$time")
        sb.appendLine()

        sb.appendLine("[环境]")
        sb.appendLine("应用：v${f.appVersion}（${f.buildType}）  ${f.packageName}")
        sb.appendLine("mapping：${CrashLog.mappingAssetName()}（与 Release 资产同名）")
        sb.appendLine("versionCode：${f.versionCode}")
        sb.appendLine("安装包签名：${f.integrity}")
        sb.appendLine("系统：Android ${f.androidRelease}（API ${f.sdkInt}）  ${f.manufacturer} ${f.model}（${f.device}）")
        sb.appendLine("ABI：${f.abi}   语言：${f.locale}   时区：${f.timeZone}")
        sb.appendLine("屏幕：${f.screenPx}   ${f.density}")
        sb.appendLine("可用内存：${f.availMemMb} MB${if (f.lowMemory) "（系统处于低内存）" else ""}")
        sb.appendLine("可用存储：${if (f.freeStorageMb >= 0) "${f.freeStorageMb} MB" else "查询失败"}")
        sb.appendLine()

        sb.appendLine("[权限]")
        sb.appendLine("通知：${yn(f.notificationAllowed)}   悬浮窗：${yn(f.canDrawOverlay)}")
        sb.appendLine("电池优化白名单：${yn(f.ignoringBattery)}")
        sb.appendLine("读取音频：${yn(f.mediaAudioGranted)}   安装未知应用：${yn(f.installUnknownAllowed)}")
        sb.appendLine(
            "无障碍服务：${yn(f.accessibilityOn)}（曾开启过：${yn(f.watchdogSeenOn)}）   " +
                "使用情况访问：${yn(f.usageStatsOn)}",
        )
        sb.appendLine()

        // 「默认音乐」列表里看不到本应用时，这一段能直接说明是没声明还是车机不认
        if (f.defaultMusicBlock.isNotBlank()) {
            sb.appendLine(f.defaultMusicBlock.trimEnd())
            sb.appendLine()
        }

        sb.appendLine("[播放]")
        sb.appendLine("曲目：${f.songTitle}${if (f.songArtist.isBlank()) "" else " - ${f.songArtist}"}")
        sb.appendLine("状态：${if (f.playing) "播放中" else "暂停"}   时长：${f.durationMs / 1000}s")
        sb.appendLine("模式：repeat=${f.repeatMode} shuffle=${f.shuffle}   音源：${f.songSource}")
        sb.appendLine(
            "歌词：${f.lyricLines} 行   偏移：${f.lyricOffsetMs}ms   字号系数：${f.lyricScale}",
        )
        sb.appendLine()

        sb.appendLine("[悬浮窗]")
        sb.appendLine(
            "开关：${yn(f.overlayOn)}   全屏高度：${yn(f.overlayFullHeight)}   行数：${f.overlayLines}",
        )
        sb.appendLine(
            "正文字号：${f.overlayTextSp}sp   暂停表现：${if (f.overlayPauseMode == 1) "隐藏" else "淡出"}   " +
                "隐藏名单：${f.hiddenAppsCount} 个",
        )
        sb.appendLine()

        sb.appendLine("[曲库]")
        sb.appendLine(
            "歌曲：${f.librarySongs}   当前视图可见：${f.libraryVisible}   跳过不支持：${f.librarySkipped}",
        )
        sb.appendLine("扫描中：${yn(f.libraryScanning)}   状态：${f.libraryStatus.ifBlank { "（空）" }}")
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

        sb.appendLine("[事件日志（最近 ${f.logLines.size} 条，旧→新）]")
        if (f.logLines.isEmpty()) {
            sb.appendLine("（空：进程刚启动，或服务尚未发生任何切换）")
        } else {
            f.logLines.forEach { sb.appendLine(it) }
        }
        sb.appendLine()
        sb.appendLine("[一键取证]")
        sb.appendLine(ForensicsScript.shortHint(f.packageName, f.appVersion))
        sb.appendLine("--- 完整脚本（复制到电脑执行）---")
        sb.appendLine(
            ForensicsScript.build(
                packageName = f.packageName,
                versionName = f.appVersion,
                versionCode = f.versionCode,
            ).trimEnd(),
        )
        sb.appendLine()
        sb.appendLine()
        sb.appendLine("=== 诊断包结束 ===")
        return sb.toString()
    }

    private fun yn(v: Boolean) = if (v) "是" else "否"

    /**
     * 落盘到应用私有目录（卸载即清），返回路径。
     */
    fun saveToFile(context: Context, report: String): String? = runCatching {
        val dir = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics")
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = java.io.File(dir, "diag_$stamp.txt")
        file.writeText(report)
        file.absolutePath
    }.getOrElse { null }
}
