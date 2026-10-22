package com.luckylearning.windtone.playback

/**
 * 悬浮歌词 / 外观设置的对外动作。
 *
 * 从 `PlayerActions` 拆出：这一簇方法只读写 Prefs 与 `PlayerUiState`、驱动
 * `LyricOverlayBridge`，与播放内核（队列 / 恢复 / 变速）无关。拆开后
 * `OverlaySettings` 可以单独演进、单独测试，`PlayerActions` 也不再是一张
 * 五十多个方法的巨表。
 */
interface OverlayActions {
    fun setLyricOverlay(enabled: Boolean)
    fun setLyricOverlayLines(lines: Int)

    /** 悬浮歌词全屏高度（铺满屏宽、避开底部车机 Dock，内容按播放页歌词区排版） */
    fun setLyricOverlayFullHeight(enabled: Boolean)

    /** 全屏歌词的宽度：屏宽百分比（30..100），与单条字幕的宽度各存一份 */
    fun setLyricOverlayFullWidthPercent(percent: Int)

    /** 全屏歌词的水平位置：余量百分比（0 最左 / 50 居中 / 100 最右） */
    fun setLyricOverlayFullOffsetPercent(percent: Int)
    fun setLyricOverlayShowTitle(show: Boolean)
    fun setLyricOverlayTitleScale(scale: Int)
    fun setLyricOverlayTextScale(scale: Int)
    fun setLyricOverlayBackdrop(mode: Int)
    fun setLyricOverlayPauseMode(mode: Int)
    fun setLyricOverlayAlign(align: Int)

    /**
     * 「不显示悬浮窗的应用」名单（v1.1.29）。
     *
     * 不触发预览 —— 隐藏不是能在设置页里「看一眼」的效果；但必须推高外观版本
     * 把悬浮窗服务叫醒：它靠这个信号决定启停前台检测轮询。
     */
    fun setLyricOverlayHiddenApps(apps: Set<String>)

    /** 「前台应用记录」开关（v1.1.29，默认关）；同样只叫醒服务、不预览 */
    fun setForegroundLogEnabled(enabled: Boolean)

    /** 调试分区解锁开关（v1.1.31，默认关 = 隐藏） */
    fun setDebugUnlocked(enabled: Boolean)

    /** 悬浮窗宽度：屏幕宽度百分比（1..100） */
    fun setLyricOverlayWidthPercent(percent: Int)

    // ------------------------------------------------ 自定义尺寸 / 位置 / 背景（v1.1.48）

    /** 单条字幕高度：屏高百分比（0 = 自动，按行数撑开） */
    fun setLyricOverlayBoxHeightPercent(percent: Int)

    /** 全屏形态高度：屏高百分比（30..100） */
    fun setLyricOverlayFullHeightPercent(percent: Int)

    /** 单条字幕横向位置：可移动余量的百分比（0..100） */
    fun setLyricOverlayXPercent(percent: Int)

    /** 单条字幕纵向位置：可移动余量的百分比（0..100） */
    fun setLyricOverlayYPercent(percent: Int)

    /** 全屏形态纵向位置：可移动余量的百分比（0..100） */
    fun setLyricOverlayFullYPercent(percent: Int)

    /** 悬浮窗背景颜色（ARGB，只用 RGB） */
    fun setLyricOverlayBgColor(color: Int)

    /** 悬浮窗背景不透明度（0..100） */
    fun setLyricOverlayBgAlphaPercent(percent: Int)

    /** 是否启用自定义背景（关闭时走「无 / 半透明 / 实底」三档） */
    fun setLyricOverlayCustomBg(enabled: Boolean)

    /** 悬浮窗歌词文字颜色（ARGB） */
    fun setLyricOverlayTextColor(color: Int)

    fun canDrawOverlay(): Boolean
    fun setOverlayEditing(editing: Boolean)
    fun setAppForeground(foreground: Boolean)
    fun restoreLyricOverlay()
    fun previewOverlay()
    fun setDynamicTheme(enabled: Boolean)
    fun dynamicThemeEnabled(): Boolean
}
