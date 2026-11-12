package com.anylyric.data

import android.content.Context
import android.content.SharedPreferences
import com.anylyric.overlay.Align
import com.anylyric.overlay.OverlayDefaults
import com.anylyric.overlay.OverlayStyle
import com.anylyric.overlay.Preset
import com.anylyric.overlay.Secondary

/**
 * 全部本地偏好。只有显示参数与调试开关，没有业务数据。
 *
 * 两条约定：
 *  · 位置存**比例**不存像素（换分辨率、改字号都不会把窗口丢到屏幕外）；
 *  · 样式默认值只在 [OverlayDefaults] 定义一次，这里只做读写与钳制。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 调试开关：默认关闭，关闭时 Probe 零采集、零分配。 */
    var debugEnabled: Boolean
        get() = sp.getBoolean(KEY_DEBUG, false)
        set(value) = sp.edit().putBoolean(KEY_DEBUG, value).apply()

    /** 播放器没暴露歌词时是否直接隐藏。默认 false：显示提示，让用户知道应用在工作。 */
    var hideWhenNoLyrics: Boolean
        get() = sp.getBoolean(KEY_HIDE_NO_LYRICS, false)
        set(value) = sp.edit().putBoolean(KEY_HIDE_NO_LYRICS, value).apply()

    /** 悬浮层输出到哪块屏幕。默认主屏。 */
    var displayId: Int
        get() = sp.getInt(KEY_DISPLAY_ID, DEFAULT_DISPLAY_ID)
        set(value) = sp.edit().putInt(KEY_DISPLAY_ID, value).apply()

    /** 左侧收起/展开把手。默认开：没有它就没办法临时让出屏幕中间的位置。 */
    var handleEnabled: Boolean
        get() = sp.getBoolean(KEY_HANDLE, true)
        set(value) = sp.edit().putBoolean(KEY_HANDLE, value).apply()

    fun style(): OverlayStyle = OverlayDefaults.sanitize(
        OverlayStyle(
            preset = Preset.of(sp.getString(KEY_PRESET, null)),
            fontSp = sp.getFloat(KEY_FONT, OverlayDefaults.FONT_SP),
            bold = sp.getBoolean(KEY_BOLD, OverlayDefaults.BOLD),
            maxLines = sp.getInt(KEY_LINES, OverlayDefaults.MAX_LINES),
            opacity = sp.getFloat(KEY_OPACITY, OverlayDefaults.OPACITY),
            cornerDp = sp.getFloat(KEY_CORNER, OverlayDefaults.CORNER_DP),
            stroke = sp.getBoolean(KEY_STROKE, OverlayDefaults.STROKE),
            secondaryAlpha = sp.getFloat(KEY_SECONDARY_ALPHA, OverlayDefaults.SECONDARY_ALPHA),
            align = Align.of(sp.getString(KEY_ALIGN, null)),
            fadeMs = sp.getInt(KEY_FADE, OverlayDefaults.FADE_MS),
            secondary = Secondary.of(sp.getString(KEY_SECONDARY, null)),
            stateMark = sp.getBoolean(KEY_STATE_MARK, OverlayDefaults.STATE_MARK),
            primaryColor = sp.getInt(KEY_PRIMARY_COLOR, OverlayDefaults.PRIMARY_COLOR),
            secondaryColor = sp.getInt(KEY_SECONDARY_COLOR, OverlayDefaults.SECONDARY_COLOR),
            bgColor = sp.getInt(KEY_BG_COLOR, OverlayDefaults.BG_COLOR),
            widthRatio = sp.getFloat(KEY_WIDTH_RATIO, OverlayDefaults.WIDTH_RATIO),
            heightRatio = sp.getFloat(KEY_HEIGHT_RATIO, OverlayDefaults.HEIGHT_RATIO)
        )
    )

    fun saveStyle(style: OverlayStyle) {
        val safe = OverlayDefaults.sanitize(style)
        sp.edit()
            .putString(KEY_PRESET, safe.preset.id)
            .putFloat(KEY_FONT, safe.fontSp)
            .putBoolean(KEY_BOLD, safe.bold)
            .putInt(KEY_LINES, safe.maxLines)
            .putFloat(KEY_OPACITY, safe.opacity)
            .putFloat(KEY_CORNER, safe.cornerDp)
            .putBoolean(KEY_STROKE, safe.stroke)
            .putFloat(KEY_SECONDARY_ALPHA, safe.secondaryAlpha)
            .putString(KEY_ALIGN, safe.align.id)
            .putInt(KEY_FADE, safe.fadeMs)
            .putString(KEY_SECONDARY, safe.secondary.id)
            .putBoolean(KEY_STATE_MARK, safe.stateMark)
            .putInt(KEY_PRIMARY_COLOR, safe.primaryColor)
            .putInt(KEY_SECONDARY_COLOR, safe.secondaryColor)
            .putInt(KEY_BG_COLOR, safe.bgColor)
            .putFloat(KEY_WIDTH_RATIO, safe.widthRatio)
            .putFloat(KEY_HEIGHT_RATIO, safe.heightRatio)
            .apply()
    }

    fun resetStyle() {
        saveStyle(OverlayDefaults.STYLE)
    }

    fun ratioX(landscape: Boolean): Float = sp.getFloat(
        if (landscape) KEY_RATIO_X_LAND else KEY_RATIO_X_PORT,
        OverlayDefaults.RATIO_X
    )

    fun ratioY(landscape: Boolean): Float = sp.getFloat(
        if (landscape) KEY_RATIO_Y_LAND else KEY_RATIO_Y_PORT,
        OverlayDefaults.RATIO_Y
    )

    fun setRatio(x: Float, y: Float, landscape: Boolean) {
        sp.edit()
            .putFloat(if (landscape) KEY_RATIO_X_LAND else KEY_RATIO_X_PORT, x)
            .putFloat(if (landscape) KEY_RATIO_Y_LAND else KEY_RATIO_Y_PORT, y)
            .apply()
    }

    fun resetPosition() {
        setRatio(OverlayDefaults.RATIO_X, OverlayDefaults.RATIO_Y, landscape = false)
        setRatio(OverlayDefaults.RATIO_X, OverlayDefaults.RATIO_Y, landscape = true)
    }

    companion object {
        private const val NAME = "anylyric"
        private const val KEY_DEBUG = "debug_enabled"
        private const val KEY_HIDE_NO_LYRICS = "hide_when_no_lyrics"
        private const val KEY_DISPLAY_ID = "display_id"

        private const val KEY_PRESET = "style_preset"
        private const val KEY_FONT = "font_sp"
        private const val KEY_BOLD = "style_bold"
        private const val KEY_LINES = "style_lines"
        private const val KEY_OPACITY = "style_opacity"
        private const val KEY_CORNER = "style_corner_dp"
        private const val KEY_STROKE = "style_stroke"
        private const val KEY_SECONDARY_ALPHA = "style_secondary_alpha"
        private const val KEY_ALIGN = "style_align"
        private const val KEY_FADE = "style_fade_ms"
        private const val KEY_SECONDARY = "style_secondary"
        private const val KEY_STATE_MARK = "style_state_mark"
        private const val KEY_PRIMARY_COLOR = "style_primary_color"
        private const val KEY_SECONDARY_COLOR = "style_secondary_color"
        private const val KEY_BG_COLOR = "style_bg_color"
        private const val KEY_WIDTH_RATIO = "style_width_ratio"
        private const val KEY_HEIGHT_RATIO = "style_height_ratio"
        private const val KEY_HANDLE = "overlay_handle_enabled"

        private const val KEY_RATIO_X_PORT = "ratio_x_port"
        private const val KEY_RATIO_Y_PORT = "ratio_y_port"
        private const val KEY_RATIO_X_LAND = "ratio_x_land"
        private const val KEY_RATIO_Y_LAND = "ratio_y_land"

        const val DEFAULT_DISPLAY_ID = 0
    }
}
