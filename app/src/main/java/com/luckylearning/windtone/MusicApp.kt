package com.luckylearning.windtone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.os.Build
import com.luckylearning.windtone.data.BootTrace
import com.luckylearning.windtone.data.CoverRepository
import com.luckylearning.windtone.data.CoverTrimLevel
import com.luckylearning.windtone.data.CrashLog
import com.luckylearning.windtone.data.PinyinIndex
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.StorageHotplugMonitor
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
        // 安装包签名自检（v1.1.97）：改包版拿不到自更新，官方包不受影响。
        // 放在最前面：后面几步的日志/诊断都要能读到这个结论。
        step("integrity") {
            com.luckylearning.windtone.data.AppIntegrity.evaluate(this)
            com.luckylearning.windtone.diag.DiagLog.log(
                "APP",
                "签名：${com.luckylearning.windtone.data.AppIntegrity.describe()}",
            )
        }
        step("cover") { CoverRepository.attach(this) }
        step("channel") { ensureNotificationChannel() }
        step("lyric-overlay") { restoreLyricOverlay() }
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
        // 存储热插拔：U 盘 / SD 挂载后节流重扫；卸载时播放域按策略跳过或停住。
        // 必须动态注册（target 26+ 收不到 manifest 里的 MEDIA_* 隐式广播）。
        step("storage-hotplug") {
            StorageHotplugMonitor(this).start()
        }
        BootTrace.mark(this, "app:onCreate-done")
    }

    /**
     * 内存压力回调（v1.2.12）。
     *
     * 在这之前全工程**没有任何 trim 入口** —— 系统内存紧张时封面缓存照样占着，
     * 同等曲库规模下比同类应用更容易被 LMK 杀掉，车机切后台回来要重解整屏封面。
     *
     * 判档依据是 Android 常量的**语义**而不是数值大小：`RUNNING_CRITICAL = 15`
     * 比 `UI_HIDDEN = 20` 更急，直接用大小比较一定写错。
     *
     * 为什么用 `runCatching` 而不是 `step(...)`：后者会往 BootTrace 写一条启动阶段记录，
     * 而 trim 可能被系统高频触发，不该把排障日志刷满。
     *
     * `@Suppress("DEPRECATION")`：`TRIM_MEMORY_RUNNING_CRITICAL` / `MODERATE` / `COMPLETE`
     * 在 API 35 被标记 deprecated，但**没有替代常量** —— 系统回传的仍是这些值，
     * 只是官方不再推荐应用按完整体验去区分它们。这里只是读常量做映射，行为不变。
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        // 必须调 super：Application 的默认实现虽然是空的，但 lint 的 MissingSuperCall
        // 是 Error 级 —— 而且将来父类真做了事（比如清它自己的缓存）时不调就会漏。
        super.onTrimMemory(level)
        val coverLevel = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            -> CoverTrimLevel.RUNNING_LOW

            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            -> CoverTrimLevel.UI_HIDDEN

            else -> return
        }
        runCatching { CoverRepository.trim(coverLevel) }
        // 进程排在 LRU 末尾（即将被回收）：连拼音字符缓存一起丢，反正下次重建只要几毫秒
        if (level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            runCatching { PinyinIndex.dropCaches() }
        }
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
