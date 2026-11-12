package com.anylyric.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 全部本地偏好。只有显示参数与调试开关，没有业务数据。
 *
 * 存坐标用 Int 快照，不存 View / WindowManager 引用 —— 与 WindTone 的既定约定一致，
 * 避免进程重建后持有失效窗口对象。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 调试开关：默认关闭，关闭时 Probe 零采集、零分配。 */
    var debugEnabled: Boolean
        get() = sp.getBoolean(KEY_DEBUG, false)
        set(value) = sp.edit().putBoolean(KEY_DEBUG, value).apply()

    var overlayX: Int
        get() = sp.getInt(KEY_X, DEFAULT_X)
        set(value) = sp.edit().putInt(KEY_X, value).apply()

    var overlayY: Int
        get() = sp.getInt(KEY_Y, DEFAULT_Y)
        set(value) = sp.edit().putInt(KEY_Y, value).apply()

    /** 主行字号（sp）。范围收窄到 12~40，避免车机上设成不可读或超大。 */
    var fontSp: Float
        get() = sp.getFloat(KEY_FONT, DEFAULT_FONT)
        set(value) = sp.edit().putFloat(KEY_FONT, value.coerceIn(12f, 40f)).apply()

    /** 播放器没暴露歌词时是否隐藏。默认 false：显示「暂无歌词」，让用户知道应用在工作。 */
    var hideWhenNoLyrics: Boolean
        get() = sp.getBoolean(KEY_HIDE_NO_LYRICS, false)
        set(value) = sp.edit().putBoolean(KEY_HIDE_NO_LYRICS, value).apply()

    fun resetPosition() {
        sp.edit().putInt(KEY_X, DEFAULT_X).putInt(KEY_Y, DEFAULT_Y).apply()
    }

    fun resetFont() {
        sp.edit().putFloat(KEY_FONT, DEFAULT_FONT).apply()
    }

    companion object {
        private const val NAME = "anylyric"
        private const val KEY_DEBUG = "debug_enabled"
        private const val KEY_X = "overlay_x"
        private const val KEY_Y = "overlay_y"
        private const val KEY_FONT = "font_sp"
        private const val KEY_HIDE_NO_LYRICS = "hide_when_no_lyrics"

        const val DEFAULT_X = 48
        const val DEFAULT_Y = 120
        const val DEFAULT_FONT = 22f
    }
}
