package com.luckylearning.windtone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.luckylearning.windtone.data.CoverRepository
import com.luckylearning.windtone.data.CrashLog
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.UpdateChecker
import com.luckylearning.windtone.overlay.LyricOverlayBridge

class MusicApp : Application() {

    /** 应用级依赖容器；ViewModel 与各仓库都从这里取依赖 */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        CoverRepository.attach(this)
        ensureNotificationChannel()
        restoreLyricOverlay()

        // 调试日志开关（2026-10-15 补上的一处「挂了账」）：
        // 这个调用此前一直缺失，`UpdateChecker.verbose` 恒为 false，五处
        // `if (verbose) Log.w(...)` 从来不输出 —— 而它们记录的恰恰是
        // 「Atom 源不可用 / 检查更新失败 / 下载线路切换 / 拉起安装器失败 / 解析失败」
        // 这些排障时最需要的线索。Debug 包打开，Release 包保持关闭（不刷用户 logcat）。
        UpdateChecker.setVerbose(BuildConfig.DEBUG)
    }

    /**
     * 冷启动时对齐悬浮歌词服务。
     *
     * 场景：用户上次开着悬浮歌词，但车机重启 / 应用被清后台后服务没了。
     * 这里只做「开关是开的、授权也在 → 把服务补起来」，
     * 授权若已被撤销则把开关回置为关，避免界面显示开启却什么都看不到。
     */
    private fun restoreLyricOverlay() {
        val enabled = Prefs(this).lyricOverlay
        if (!enabled) return
        if (!LyricOverlayBridge.canDraw(this)) {
            Prefs(this).lyricOverlay = false
            return
        }
        LyricOverlayBridge.sync(this, true)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "音乐播放",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "播放控制与当前曲目信息"
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "local_music_player_playback"
    }
}
