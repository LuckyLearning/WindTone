package com.luckylearning.windtone.playback

import android.app.Application
import com.luckylearning.windtone.data.MessageBus
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.overlay.LyricOverlayBridge
import com.luckylearning.windtone.state.PlayerUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 悬浮歌词 / 外观设置的动作实现（从 `PlaybackEngine` 抽出）。
 *
 * 这一簇方法的共同点：只做三件事——**写 Prefs、更新 `PlayerUiState`、通知悬浮窗服务**。
 * 它们不需要知道队列、断点续播、变速这些播放内核细节；反过来，播放内核也不该为
 * 改一个字号而摊开二十个 `override`。
 *
 * 与世界的边界用 [Host] 表达：读当前进度 / 是否在播、把一帧歌词推给悬浮窗、
 * 起进度计时器、记录前后台状态。引擎实现这个接口，其余全部留在这里。
 */
class OverlaySettings(
    private val app: Application,
    private val prefs: Prefs,
    private val messages: MessageBus,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<PlayerUiState>,
    private val themeSeed: MutableStateFlow<Int?>,
    private val host: Host,
) : OverlayActions {

    /** 引擎侧需要提供的少量能力；刻意保持窄，避免又变成对引擎的全面依赖 */
    interface Host {
        val hasController: Boolean
        val currentPositionMs: Long
        val isPlaying: Boolean
        fun publishOverlay(positionMs: Long, playing: Boolean)
        fun startTicker()
        fun setAppForegroundFlag(foreground: Boolean)
    }

    private var previewJob: Job? = null

    /**
     * 桌面悬浮歌词开关。
     *
     * 落盘 + 更新界面状态 + 同步起停悬浮窗服务。
     * 刚打开时启动一段前台预览：按设计悬浮窗只在 App 退到后台时显示，
     * 而在设置页里打开的这一刻 App 恰恰在前台 —— 没有预览的话用户点完开关
     * 看不到任何变化，会直接把功能判定为「没有效果」。
     */
    override fun setLyricOverlay(enabled: Boolean) {
        prefs.lyricOverlay = enabled
        state.update { it.copy(lyricOverlay = enabled) }
        LyricOverlayBridge.sync(app, enabled)
        if (enabled) {
            if (host.hasController) {
                host.publishOverlay(host.currentPositionMs, host.isPlaying)
            }
            startOverlayPreview()
        } else {
            // 关掉时顺手退出调整态：否则 overlayEditing 会变成没有出口的孤儿
            if (state.value.overlayEditing) {
                LyricOverlayBridge.setEditing(false)
                state.update { it.copy(overlayEditing = false) }
            }
        }
    }

    override fun setLyricOverlayLines(lines: Int) {
        val normalized = lines.coerceIn(
            SettingsDefaults.OVERLAY_LINES_RANGE.first,
            SettingsDefaults.OVERLAY_LINES_RANGE.last,
        )
        prefs.lyricOverlayLines = normalized
        state.update { it.copy(lyricOverlayLines = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayFullHeight(enabled: Boolean) {
        prefs.lyricOverlayFullHeight = enabled
        state.update { it.copy(lyricOverlayFullHeight = enabled) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayFullWidthPercent(percent: Int) {
        val normalized = percent.coerceIn(
            SettingsDefaults.OVERLAY_FULL_WIDTH_RANGE.first,
            SettingsDefaults.OVERLAY_FULL_WIDTH_RANGE.last,
        )
        prefs.lyricOverlayFullWidthPercent = normalized
        state.update { it.copy(lyricOverlayFullWidthPercent = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayFullOffsetPercent(percent: Int) {
        val normalized = percent.coerceIn(
            SettingsDefaults.OVERLAY_FULL_OFFSET_RANGE.first,
            SettingsDefaults.OVERLAY_FULL_OFFSET_RANGE.last,
        )
        prefs.lyricOverlayFullOffsetPercent = normalized
        state.update { it.copy(lyricOverlayFullOffsetPercent = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayShowTitle(show: Boolean) {
        prefs.lyricOverlayShowTitle = show
        state.update { it.copy(lyricOverlayShowTitle = show) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayTitleScale(scale: Int) {
        val normalized = scale.coerceIn(0, 2)
        prefs.lyricOverlayTitleScale = normalized
        state.update { it.copy(lyricOverlayTitleScale = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayTextScale(scale: Int) {
        val normalized = scale.coerceIn(0, 2)
        prefs.lyricOverlayTextScale = normalized
        state.update { it.copy(lyricOverlayTextScale = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayBackdrop(mode: Int) {
        val normalized = mode.coerceIn(0, 2)
        prefs.lyricOverlayBackdrop = normalized
        state.update { it.copy(lyricOverlayBackdrop = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayPauseMode(mode: Int) {
        val normalized = mode.coerceIn(0, 1)
        prefs.lyricOverlayPauseMode = normalized
        state.update { it.copy(lyricOverlayPauseMode = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayAlign(align: Int) {
        val normalized = align.coerceIn(0, 1)
        prefs.lyricOverlayAlign = normalized
        state.update { it.copy(lyricOverlayAlign = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayHiddenApps(apps: Set<String>) {
        val normalized = apps.filterTo(LinkedHashSet()) { it.isNotBlank() }
        prefs.lyricOverlayHiddenApps = normalized
        state.update { it.copy(lyricOverlayHiddenApps = normalized) }
        // 不预览（隐藏效果在 App 内看不到），但要把服务叫醒去启停检测
        LyricOverlayBridge.bumpAppearance()
    }

    override fun setForegroundLogEnabled(enabled: Boolean) {
        prefs.foregroundLogEnabled = enabled
        state.update { it.copy(foregroundLogEnabled = enabled) }
        LyricOverlayBridge.bumpAppearance()
    }

    override fun setDebugUnlocked(enabled: Boolean) {
        prefs.debugUnlocked = enabled
        state.update { it.copy(debugUnlocked = enabled) }
    }

    override fun setLyricOverlayWidthPercent(percent: Int) {
        val normalized = percent.coerceIn(
            SettingsDefaults.OVERLAY_WIDTH_RANGE.first,
            SettingsDefaults.OVERLAY_WIDTH_RANGE.last,
        )
        prefs.lyricOverlayWidthPercent = normalized
        state.update { it.copy(lyricOverlayWidthPercent = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayTextColor(color: Int) {
        prefs.lyricOverlayTextColor = color
        state.update { it.copy(lyricOverlayTextColor = color) }
        previewOverlayAfterConfigChange()
    }

    override fun canDrawOverlay(): Boolean = LyricOverlayBridge.canDraw(app)

    override fun setOverlayEditing(editing: Boolean) {
        LyricOverlayBridge.setEditing(editing)
        state.update { it.copy(overlayEditing = editing) }
    }

    override fun setAppForeground(foreground: Boolean) {
        host.setAppForegroundFlag(foreground)
        LyricOverlayBridge.setAppForeground(foreground)
        if (foreground) host.startTicker()
    }

    override fun restoreLyricOverlay() {
        val enabled = prefs.lyricOverlay && canDrawOverlay()
        if (prefs.lyricOverlay && !enabled) {
            // 授权已被撤销：开关回置为关，避免界面骗人
            setLyricOverlay(false)
        } else {
            LyricOverlayBridge.sync(app, enabled)
        }
    }

    override fun previewOverlay() {
        if (!prefs.lyricOverlay) return
        LyricOverlayBridge.sync(app, true)
        // 全屏这一档在 App 前台不画（整屏浮层会盖住设置页），直接告诉用户去哪儿看
        if (prefs.lyricOverlayFullHeight) {
            messages.post("全屏歌词不在设置页内显示（否则会盖住控件），退到桌面即可看到")
            return
        }
        startOverlayPreview()
    }

    override fun setDynamicTheme(enabled: Boolean) {
        prefs.dynamicTheme = enabled
        if (!enabled) themeSeed.value = null
    }

    override fun dynamicThemeEnabled(): Boolean = prefs.dynamicTheme

    /**
     * 外观 / 排版配置改完的统一收尾：通知服务重绘 + 给一次前台预览。
     * 用户改完字号 / 底板 / 行数能**当场**看到效果，不用退到桌面验证。
     */
    internal fun previewOverlayAfterConfigChange() {
        LyricOverlayBridge.bumpAppearance()
        if (!prefs.lyricOverlay) return
        if (host.hasController) {
            host.publishOverlay(host.currentPositionMs, host.isPlaying)
        }
        startOverlayPreview()
    }

    /** 前台预览：开启开关后让悬浮窗在前台也显示一小段时间 */
    private fun startOverlayPreview() {
        previewJob?.cancel()
        LyricOverlayBridge.setPreview(true)
        previewJob = scope.launch {
            delay(OVERLAY_PREVIEW_MS)
            LyricOverlayBridge.setPreview(false)
        }
    }

    private companion object {
        /** 前台预览时长；与旧实现一致 */
        const val OVERLAY_PREVIEW_MS = 6_000L
    }
}
