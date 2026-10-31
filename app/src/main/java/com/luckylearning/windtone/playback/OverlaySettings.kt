package com.luckylearning.windtone.playback

import android.app.Application
import com.luckylearning.windtone.data.MessageBus
import com.luckylearning.windtone.data.OverlayPreset
import com.luckylearning.windtone.data.OverlayPresetTracker
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.SettingsRepository
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
 *
 * 设置落盘一律走 [settings]（`data/SettingsRepository`）—— 那是唯一接缝，
 * DataStore 迁移时只改那一处。读仍直通 `prefs`：外观每帧都要对一次账，
 * 需要同步读，reactive 读随迁移一起到位。
 */
class OverlaySettings(
    private val app: Application,
    private val settings: SettingsRepository,
    private val messages: MessageBus,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<PlayerUiState>,
    private val themeSeed: MutableStateFlow<Int?>,
    private val host: Host,
) : OverlayActions {

    /** 读直通：本类的读全是「单值同步判断」，与落盘解耦 */
    private val prefs: Prefs get() = settings.prefs

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
        settings.setOverlayEnabled(enabled)
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
        val normalized = settings.setOverlayLines(lines)
        state.update { it.copy(lyricOverlayLines = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setOverlayHandle(kind: Int, enabled: Boolean) {
        when (kind) {
            0 -> {
                settings.setOverlayHandleCollapse(enabled)
                state.update { it.copy(overlayHandleCollapse = enabled) }
            }
            1 -> {
                settings.setOverlayHandlePlayPause(enabled)
                state.update { it.copy(overlayHandlePlayPause = enabled) }
            }
            2 -> {
                settings.setOverlayHandlePlayer(enabled)
                state.update { it.copy(overlayHandlePlayer = enabled) }
            }
            3 -> {
                settings.setOverlayHandleA11y(enabled)
                state.update { it.copy(overlayHandleA11y = enabled) }
            }
            else -> return
        }
        // 由服务在下一帧按新的掩码整组重建（syncHandlesVisibility）
        previewOverlayAfterConfigChange()
    }

    override fun setOverlayHandles(enabled: Boolean) {
        settings.setOverlayHandles(enabled)
        state.update { it.copy(overlayHandles = enabled) }
        // 侧钮的增删由服务在下一帧自检（syncHandlesVisibility），这里只需催一次重绘
        previewOverlayAfterConfigChange()
    }

    override fun setOverlayHandleOrder(orderCsv: String) {
        settings.setOverlayHandleOrder(orderCsv)
        state.update { it.copy(overlayHandleOrder = settings.prefs.overlayHandleOrder) }
        // 顺序变了不增删 View，但要让服务按新序重排 y；bumpAppearance → syncHandlesVisibility
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayFullHeight(enabled: Boolean) {
        settings.setOverlayFullHeight(enabled)
        state.update { it.copy(lyricOverlayFullHeight = enabled) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayFullWidthPercent(percent: Int) {
        val normalized = settings.setOverlayFullWidthPercent(percent)
        state.update { it.copy(lyricOverlayFullWidthPercent = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayFullOffsetPercent(percent: Int) {
        val normalized = settings.setOverlayFullOffsetPercent(percent)
        state.update { it.copy(lyricOverlayFullOffsetPercent = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayShowTitle(show: Boolean) {
        settings.setOverlayShowTitle(show)
        state.update { it.copy(lyricOverlayShowTitle = show) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayTitlePercent(percent: Int) {
        val normalized = settings.setOverlayTitlePercent(percent)
        state.update { it.copy(lyricOverlayTitlePercent = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayTextSp(textSp: Int) {
        val normalized = settings.setOverlayTextSp(textSp)
        state.update { it.copy(lyricOverlayTextSp = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayPauseMode(mode: Int) {
        val normalized = settings.setOverlayPauseMode(mode)
        state.update { it.copy(lyricOverlayPauseMode = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayAlign(align: Int) {
        val normalized = settings.setOverlayAlign(align)
        state.update { it.copy(lyricOverlayAlign = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayHiddenApps(apps: Set<String>) {
        val normalized = apps.filterTo(LinkedHashSet()) { it.isNotBlank() }
        settings.setOverlayHiddenApps(normalized)
        state.update { it.copy(lyricOverlayHiddenApps = normalized) }
        // 不预览（隐藏效果在 App 内看不到），但要把服务叫醒去启停检测
        LyricOverlayBridge.bumpAppearance()
    }

    override fun setForegroundLogEnabled(enabled: Boolean) {
        settings.setForegroundLogEnabled(enabled)
        state.update { it.copy(foregroundLogEnabled = enabled) }
        LyricOverlayBridge.bumpAppearance()
    }

    override fun setDebugUnlocked(enabled: Boolean) {
        settings.setDebugUnlocked(enabled)
        state.update { it.copy(debugUnlocked = enabled) }
    }

    override fun setLyricOverlayWidthPercent(percent: Int) {
        val normalized = settings.setOverlayWidthPercent(percent)
        state.update { it.copy(lyricOverlayWidthPercent = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayTextColor(color: Int) {
        settings.setOverlayTextColor(color)
        state.update { it.copy(lyricOverlayTextColor = color) }
        previewOverlayAfterConfigChange()
    }

    // ------------------------------------------------ 自定义尺寸 / 位置 / 背景（v1.1.48）

    override fun setLyricOverlayBoxHeightPercent(percent: Int) {
        val normalized = settings.setOverlayBoxHeightPercent(percent)
        state.update { it.copy(lyricOverlayBoxHeightPercent = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayFullHeightPercent(percent: Int) {
        val normalized = settings.setOverlayFullHeightPercent(percent)
        state.update { it.copy(lyricOverlayFullHeightPercent = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayXPercent(percent: Int) {
        val normalized = settings.setOverlayXPercent(percent)
        state.update { it.copy(lyricOverlayXPercent = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayYPercent(percent: Int) {
        val normalized = settings.setOverlayYPercent(percent)
        state.update { it.copy(lyricOverlayYPercent = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayFullYPercent(percent: Int) {
        val normalized = settings.setOverlayFullYPercent(percent)
        state.update { it.copy(lyricOverlayFullYPercent = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayBgColor(color: Int) {
        settings.setOverlayBgColor(color)
        // Prefs 会把 alpha 位置成不透明，这里读回同一个值，避免 UI 与落盘不一致
        state.update { it.copy(lyricOverlayBgColor = prefs.lyricOverlayBgColor) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayBgAlphaPercent(percent: Int) {
        val normalized = settings.setOverlayBgAlphaPercent(percent)
        state.update { it.copy(lyricOverlayBgAlphaPercent = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayCornerRadiusDp(value: Int) {
        val normalized = settings.setLyricOverlayCornerRadiusDp(value)
        state.update { it.copy(lyricOverlayCornerRadiusDp = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayBorderWidthDp(value: Int) {
        val normalized = settings.setLyricOverlayBorderWidthDp(value)
        state.update { it.copy(lyricOverlayBorderWidthDp = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayBorderColor(value: Int) {
        val normalized = settings.setLyricOverlayBorderColor(value)
        state.update { it.copy(lyricOverlayBorderColor = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayLineSpacingDp(value: Int) {
        val normalized = settings.setLyricOverlayLineSpacingDp(value)
        state.update { it.copy(lyricOverlayLineSpacingDp = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayCurrentColor(value: Int) {
        val normalized = settings.setLyricOverlayCurrentColor(value)
        state.update { it.copy(lyricOverlayCurrentColor = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayDimPercent(value: Int) {
        val normalized = settings.setLyricOverlayDimPercent(value)
        state.update { it.copy(lyricOverlayDimPercent = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayStrokeWidthDp(value: Int) {
        val normalized = settings.setLyricOverlayStrokeWidthDp(value)
        state.update { it.copy(lyricOverlayStrokeWidthDp = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayStrokeColor(value: Int) {
        val normalized = settings.setLyricOverlayStrokeColor(value)
        state.update { it.copy(lyricOverlayStrokeColor = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayFadeMs(value: Int) {
        val normalized = settings.setLyricOverlayFadeMs(value)
        state.update { it.copy(lyricOverlayFadeMs = normalized) }
        previewOverlayAfterConfigChange()
    }

    override fun setLyricOverlayTitleMode(value: Int) {
        val normalized = settings.setLyricOverlayTitleMode(value)
        state.update { it.copy(lyricOverlayTitleMode = normalized) }
        previewOverlayAfterConfigChange()
    }

    /**
     * 套用样式预设（v1.1.78）：24 项外观参数一次性批量写盘 + 一次状态合并 +
     * 一次预览收尾。逐项走 setter 会触发 20 多次 bumpAppearance / 起预览，
     * 用户会在一眨眼间看到样式连闪——预设切换必须是「一次到位」的（NFR-1）。
     *
     * 刻意不碰：主开关、全屏形态及其尺寸位置、隐藏名单（FR-05；
     * 全屏形态是「结构切换」不是「皮肤」，见设计文档 §7.2 末尾）。
     */
    /** FR-07 车机大卡与手机预设网格同源：23 项外观一次性写盘（与 OverlayPresetParams / toParams 对齐），不碰主开关/全屏/隐藏名单（FR-05）。 */
    override fun applyOverlayPreset(preset: OverlayPreset) {
        val lines = settings.setOverlayLines(preset.lines)
        val width = settings.setOverlayWidthPercent(preset.widthPercent)
        val boxHeight = settings.setOverlayBoxHeightPercent(preset.boxHeightPercent)
        val x = settings.setOverlayXPercent(preset.xPercent)
        val y = settings.setOverlayYPercent(preset.yPercent)
        val textSp = settings.setOverlayTextSp(preset.textSp)
        val align = settings.setOverlayAlign(preset.align)
        settings.setOverlayTextColor(preset.textColor)
        val currentColor = settings.setLyricOverlayCurrentColor(preset.currentColor)
        val dim = settings.setLyricOverlayDimPercent(preset.dimPercent)
        val strokeWidth = settings.setLyricOverlayStrokeWidthDp(preset.strokeWidthDp)
        val strokeColor = settings.setLyricOverlayStrokeColor(preset.strokeColor)
        val spacing = settings.setLyricOverlayLineSpacingDp(preset.lineSpacingDp)
        settings.setOverlayShowTitle(preset.showTitle)
        val titlePercent = settings.setOverlayTitlePercent(preset.titlePercent)
        val titleMode = settings.setLyricOverlayTitleMode(preset.titleMode)
        val bgAlpha = settings.setOverlayBgAlphaPercent(preset.bgAlphaPercent)
        settings.setOverlayBgColor(preset.bgColor)
        val corner = settings.setLyricOverlayCornerRadiusDp(preset.cornerRadiusDp)
        val borderWidth = settings.setLyricOverlayBorderWidthDp(preset.borderWidthDp)
        val borderColor = settings.setLyricOverlayBorderColor(preset.borderColor)
        val pause = settings.setOverlayPauseMode(preset.pauseMode)
        val fade = settings.setLyricOverlayFadeMs(preset.fadeMs)
        state.update {
            it.copy(
                lyricOverlayLines = lines,
                lyricOverlayWidthPercent = width,
                lyricOverlayBoxHeightPercent = boxHeight,
                lyricOverlayXPercent = x,
                lyricOverlayYPercent = y,
                lyricOverlayTextSp = textSp,
                lyricOverlayAlign = align,
                lyricOverlayTextColor = preset.textColor,
                lyricOverlayCurrentColor = currentColor,
                lyricOverlayDimPercent = dim,
                lyricOverlayStrokeWidthDp = strokeWidth,
                lyricOverlayStrokeColor = strokeColor,
                lyricOverlayLineSpacingDp = spacing,
                lyricOverlayShowTitle = preset.showTitle,
                lyricOverlayTitlePercent = titlePercent,
                lyricOverlayTitleMode = titleMode,
                lyricOverlayBgAlphaPercent = bgAlpha,
                // Prefs 的 bgColor setter 只保留 RGB，读回同一个值避免 UI 与落盘不一致
                lyricOverlayBgColor = prefs.lyricOverlayBgColor,
                lyricOverlayCornerRadiusDp = corner,
                lyricOverlayBorderWidthDp = borderWidth,
                lyricOverlayBorderColor = borderColor,
                lyricOverlayPauseMode = pause,
                lyricOverlayFadeMs = fade,
            )
        }
        // 「已修改 / 恢复预设原值」的会话级锚点（R2；不落盘，见 OverlayPresetTracker）
        OverlayPresetTracker.lastAppliedId = preset.id
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
        settings.setDynamicTheme(enabled)
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
