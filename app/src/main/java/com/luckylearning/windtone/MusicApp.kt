package com.luckylearning.windtone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.luckylearning.windtone.data.BootTrace
import com.luckylearning.windtone.data.CoverRepository
import com.luckylearning.windtone.data.CrashLog
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.UpdateChecker
import com.luckylearning.windtone.overlay.LyricOverlayBridge
import com.luckylearning.windtone.ui.AppStrings

class MusicApp : Application() {

    /** 应用级依赖容器；ViewModel 与各仓库都从这里取依赖 */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()

        // 非组合上下文的取串入口，必须最先就绪：下面的通知渠道、
        // 以及后续所有服务/数据层文案都依赖它。
        AppStrings.init(this)

        // 阶段追踪必须在最前面：上一轮的记录要先保下来（见 BootTrace 的注释）。
        BootTrace.begin(this)

        // 每一步都单独兜住。
        //
        // 起因是车机 DiLink 上的「装上打开全黑」：如果这一层里有任何一行抛异常，
        // 进程会在 MainActivity 之前就被杀掉，用户看到的只有黑屏 —— 崩溃页、日志、
        // 「设置 → 诊断」全都来不及出现，等于完全没有线索。这几步里没有一步是
        // 「失败了就宁可不启动」的，所以一律降级继续，把失败记下来。
        step("crash-log") { CrashLog.install(this) }
        step("cover") { CoverRepository.attach(this) }
        step("channel") { ensureNotificationChannel() }
        step("lyric-overlay") { restoreLyricOverlay() }
        step("pan-token") {
            // 百度网盘 dlink 必须拼 access_token 才能下载，见 BaiduRules.withAccessToken。
            // 这里只注入「怎么取 token」，不预先读 —— 用户随时可能重新扫码，读的时候再取最新的。
            val baidu = com.luckylearning.windtone.data.baidu.BaiduAuth(this)
            com.luckylearning.windtone.playback.OnlineMediaCache.panTokenProvider = {
                if (baidu.isConfigured()) baidu.currentToken()?.accessToken else null
            }
        }
        step("update-log") {
            // 调试日志开关（2026-10-15 补上的一处「挂了账」）：
            // 这个调用此前一直缺失，`UpdateChecker.verbose` 恒为 false，五处
            // `if (verbose) Log.w(...)` 从来不输出 —— 而它们记录的恰恰是
            // 「Atom 源不可用 / 检查更新失败 / 下载线路切换 / 拉起安装器失败 / 解析失败」
            // 这些排障时最需要的线索。Debug 包打开，Release 包保持关闭（不刷用户 logcat）。
            UpdateChecker.setVerbose(BuildConfig.DEBUG)
        }
        // 无障碍「重启后掉线」的自检（v1.1.85）：曾经开过而这次没开 → 发一条
        // 一键恢复的通知。放在最后、且单独兜住 —— 通知失败不该影响启动。
        step("a11y-watch") {
            com.luckylearning.windtone.overlay.AccessibilityWatchdog.checkAndNotify(this)
        }
        BootTrace.mark(this, "app:onCreate-done")
    }

    /** 执行一步启动初始化；失败只记账，不阻断启动 */
    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
            BootTrace.mark(this, "ok:$name")
        } catch (t: Throwable) {
            // Throwable 而不是 Exception：ROM 里偶发的 LinkageError / OOM 同样不能让应用起不来
            BootTrace.fail(this, name, t)
        }
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
            AppStrings.s(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = AppStrings.s(R.string.notif_channel_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "local_music_player_playback"
    }
}
