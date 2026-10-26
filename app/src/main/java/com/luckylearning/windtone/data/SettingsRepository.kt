package com.luckylearning.windtone.data

/**
 * 设置域的**持久化门面**（v1.1.37 重构抽出）。
 *
 * ## 为什么需要它
 * 设置写入原先散落在两处：`OverlaySettings`（悬浮歌词 / 外观，17 个 setter）与
 * `PlaybackEngine`（速度 / 变调 / 静音 / 续播，5 个 setter）。每处都是
 * 「夹取 → 写 Prefs → 更新界面状态」三行一式。问题是：**哪天要把存储从
 * SharedPreferences 换成 DataStore，这 22 个落盘点加上几十个读点全得改**，
 * 而且没有一个地方能一眼看全「本应用到底有哪些设置」。
 *
 * 本类把**写**收敛成一处：每个设置一个方法，负责「落盘 + 返回真正生效的值」。
 * 读暂时仍直通 [prefs]（每帧刷新类消费方需要同步读）——  reactive 读随
 * DataStore 迁移（见开发清单 #22）一起到位，届时只改本类内部，调用方无感。
 *
 * ## 边界
 * - 只做持久化，**不做**「更新 `PlayerUiState` / 通知悬浮窗」—— 那属于播放域
 *   的编排（见 `playback/OverlaySettings`），留在一处才不会把状态广播逻辑撒得到处都是。
 * - 夹取区间以 `Prefs` 各 setter 自身为准（它们已经 `coerceIn` 过），本类落盘后
 *   **回读一次**返回真实生效值，避免区间规则出现第二份定义。
 *   ⚠️ 例外：速度与变调的区间定义在播放域（`playback/Pitch`、[PLAYBACK_SPEED_RANGE]），
 *   `Prefs` 的对应 setter 不夹取，由调用方先夹好再传进来。
 *
 * ## 已接入的域
 * 播放域（[com.luckylearning.windtone.playback.OverlaySettings] 与
 * `PlaybackEngine` 的全部设置写）。曲库扫描 / 应用内更新两域的写入仍直连
 * `Prefs`，待这两个域也接入时一并搬过来 —— 门面的意义就在于「搬」是一次集中改动。
 */
class SettingsRepository(val prefs: Prefs) {

    // ---------------------------------------------------------------- 播放

    /**
     * 播放速度（0.5..2.0）。区间见 [SettingsDefaults.PLAYBACK_SPEED_RANGE]。
     * @return 夹取后真正落盘的值
     */
    fun setSpeed(speed: Float): Float {
        val v = speed.coerceIn(
            SettingsDefaults.PLAYBACK_SPEED_RANGE.start,
            SettingsDefaults.PLAYBACK_SPEED_RANGE.endInclusive,
        )
        prefs.playbackSpeed = v
        return v
    }

    /**
     * 变调（半音）。区间由 `playback.Pitch` 权威定义，调用方需先夹取；
     * 这里只负责落盘，返回值原样回传（`Prefs` 不做二次夹取）。
     */
    fun setPitchSemitones(semitones: Int): Int {
        prefs.playbackPitchSemitones = semitones
        return semitones
    }

    fun setSkipSilence(enabled: Boolean) {
        prefs.skipSilence = enabled
    }

    fun setAutoResume(enabled: Boolean) {
        prefs.autoResume = enabled
    }

    fun setAutoPlayOnBoot(enabled: Boolean) {
        prefs.autoPlayOnBoot = enabled
    }

    // ---------------------------------------------------------------- 播放页歌词

    fun setLyricScale(scale: Float) {
        prefs.lyricScale = scale
    }

    /** 悬浮窗侧钮开关（v1.2.0） */
    fun setOverlayHandles(enabled: Boolean) {
        prefs.overlayHandles = enabled
    }

    /** 单颗侧钮开关（v1.3.0） */
    fun setOverlayHandleCollapse(enabled: Boolean) {
        prefs.overlayHandleCollapse = enabled
    }

    fun setOverlayHandlePlayPause(enabled: Boolean) {
        prefs.overlayHandlePlayPause = enabled
    }

    fun setOverlayHandlePlayer(enabled: Boolean) {
        prefs.overlayHandlePlayer = enabled
    }

    fun setLyricTapSeeks(enabled: Boolean) {
        prefs.lyricTapSeeks = enabled
    }

    /** @return 夹取到 0..2 后的值 */

    fun setLyricsPaneVisible(visible: Boolean) {
        prefs.lyricsPaneVisible = visible
    }

    fun setCarMode(enabled: Boolean) {
        prefs.carMode = enabled
    }

    // ---------------------------------------------------------------- 桌面悬浮歌词

    fun setOverlayEnabled(enabled: Boolean) {
        prefs.lyricOverlay = enabled
    }

    /** @return 夹取到 [SettingsDefaults.OVERLAY_LINES_RANGE] 后的值 */
    fun setOverlayLines(lines: Int): Int {
        prefs.lyricOverlayLines = lines
        return prefs.lyricOverlayLines
    }

    fun setOverlayShowTitle(show: Boolean) {
        prefs.lyricOverlayShowTitle = show
    }

    /** @return 夹取到 [SettingsDefaults.OVERLAY_TITLE_PERCENT_RANGE] 后的值 */
    fun setOverlayTitlePercent(percent: Int): Int {
        prefs.lyricOverlayTitlePercent = percent
        return prefs.lyricOverlayTitlePercent
    }

    /** @return 夹取到 [SettingsDefaults.OVERLAY_TEXT_SP_RANGE] 后的值 */
    fun setOverlayTextSp(textSp: Int): Int {
        prefs.lyricOverlayTextSp = textSp
        return prefs.lyricOverlayTextSp
    }

    /** @return 夹取到 0..1 后的值 */
    fun setOverlayPauseMode(mode: Int): Int {
        prefs.lyricOverlayPauseMode = mode
        return prefs.lyricOverlayPauseMode
    }

    /** @return 夹取到 0..1 后的值 */
    fun setOverlayAlign(align: Int): Int {
        prefs.lyricOverlayAlign = align
        return prefs.lyricOverlayAlign
    }

    /** @return 夹取到 [SettingsDefaults.OVERLAY_WIDTH_RANGE] 后的值 */
    fun setOverlayWidthPercent(percent: Int): Int {
        prefs.lyricOverlayWidthPercent = percent
        return prefs.lyricOverlayWidthPercent
    }

    /** @return 夹取到 [SettingsDefaults.OVERLAY_FULL_WIDTH_RANGE] 后的值 */
    fun setOverlayFullWidthPercent(percent: Int): Int {
        prefs.lyricOverlayFullWidthPercent = percent
        return prefs.lyricOverlayFullWidthPercent
    }

    /** @return 夹取到 [SettingsDefaults.OVERLAY_FULL_OFFSET_RANGE] 后的值 */
    fun setOverlayFullOffsetPercent(percent: Int): Int {
        prefs.lyricOverlayFullOffsetPercent = percent
        return prefs.lyricOverlayFullOffsetPercent
    }

    fun setOverlayFullHeight(enabled: Boolean) {
        prefs.lyricOverlayFullHeight = enabled
    }

    // ------------------------------------------------ 自定义尺寸 / 位置 / 背景（v1.1.48）

    /** @return 夹到 0（自动）或 10..100 后的值 */
    fun setOverlayBoxHeightPercent(percent: Int): Int {
        prefs.lyricOverlayBoxHeightPercent = percent
        return prefs.lyricOverlayBoxHeightPercent
    }

    /** @return 夹到 30..100 后的值 */
    fun setOverlayFullHeightPercent(percent: Int): Int {
        prefs.lyricOverlayFullHeightPercent = percent
        return prefs.lyricOverlayFullHeightPercent
    }

    /** @return 夹到 0..100 后的值 */
    fun setOverlayXPercent(percent: Int): Int {
        prefs.lyricOverlayXPercent = percent
        return prefs.lyricOverlayXPercent
    }

    /** @return 夹到 0..100 后的值 */
    fun setOverlayYPercent(percent: Int): Int {
        prefs.lyricOverlayYPercent = percent
        return prefs.lyricOverlayYPercent
    }

    /** @return 夹到 0..100 后的值 */
    fun setOverlayFullYPercent(percent: Int): Int {
        prefs.lyricOverlayFullYPercent = percent
        return prefs.lyricOverlayFullYPercent
    }

    /** 背景颜色（只保留 RGB，不透明度走单独一项） */
    fun setOverlayBgColor(color: Int) {
        prefs.lyricOverlayBgColor = color
    }

    /** @return 夹到 0..100 后的值 */
    fun setOverlayBgAlphaPercent(percent: Int): Int {
        prefs.lyricOverlayBgAlphaPercent = percent
        return prefs.lyricOverlayBgAlphaPercent
    }

    fun setLyricOverlayCornerRadiusDp(value: Int): Int {
        prefs.lyricOverlayCornerRadiusDp = value
        return prefs.lyricOverlayCornerRadiusDp
    }

    fun setLyricOverlayBorderWidthDp(value: Int): Int {
        prefs.lyricOverlayBorderWidthDp = value
        return prefs.lyricOverlayBorderWidthDp
    }

    fun setLyricOverlayBorderColor(value: Int): Int {
        prefs.lyricOverlayBorderColor = value
        return prefs.lyricOverlayBorderColor
    }

    fun setLyricOverlayLineSpacingDp(value: Int): Int {
        prefs.lyricOverlayLineSpacingDp = value
        return prefs.lyricOverlayLineSpacingDp
    }

    fun setLyricOverlayCurrentColor(value: Int): Int {
        prefs.lyricOverlayCurrentColor = value
        return prefs.lyricOverlayCurrentColor
    }

    fun setLyricOverlayDimPercent(value: Int): Int {
        prefs.lyricOverlayDimPercent = value
        return prefs.lyricOverlayDimPercent
    }

    fun setLyricOverlayStrokeWidthDp(value: Int): Int {
        prefs.lyricOverlayStrokeWidthDp = value
        return prefs.lyricOverlayStrokeWidthDp
    }

    fun setLyricOverlayStrokeColor(value: Int): Int {
        prefs.lyricOverlayStrokeColor = value
        return prefs.lyricOverlayStrokeColor
    }

    fun setLyricOverlayFadeMs(value: Int): Int {
        prefs.lyricOverlayFadeMs = value
        return prefs.lyricOverlayFadeMs
    }

    fun setLyricOverlayTitleMode(value: Int): Int {
        prefs.lyricOverlayTitleMode = value
        return prefs.lyricOverlayTitleMode
    }

    fun setOverlayTextColor(color: Int) {
        prefs.lyricOverlayTextColor = color
    }

    /** 清掉空白包名后落盘；`Prefs` setter 会再拷一份集合，防止调用方就地改 */
    fun setOverlayHiddenApps(apps: Set<String>) {
        prefs.lyricOverlayHiddenApps = apps
    }

    // ---------------------------------------------------------------- 前台检测与调试

    fun setForegroundLogEnabled(enabled: Boolean) {
        prefs.foregroundLogEnabled = enabled
    }

    fun setDebugUnlocked(enabled: Boolean) {
        prefs.debugUnlocked = enabled
    }

    // ---------------------------------------------------------------- 外观与主题

    /** 界面明暗模式（跟随系统 / 浅色 / 深色，v1.2.4） */
    fun setThemeMode(mode: ThemeMode) {
        prefs.themeMode = mode
    }

    fun setDynamicTheme(enabled: Boolean) {
        prefs.dynamicTheme = enabled
    }

    // ---------------------------------------------------------------- 应用内更新

    fun setAutoCheckUpdate(enabled: Boolean) {
        prefs.autoCheckUpdate = enabled
    }
}
