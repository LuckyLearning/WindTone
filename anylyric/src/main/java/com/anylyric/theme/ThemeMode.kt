package com.anylyric.theme

import android.content.Context
import android.content.res.Configuration

/**
 * 界面明暗主题。
 *
 * 刻意**不引 AppCompat**（与 :app 的取舍一致，整个 anylyric 零第三方依赖），
 * 所以不用 `AppCompatDelegate.setDefaultNightMode`，而是走「给 Activity 换一份
 * Configuration」这条平台原生路线：`values-night/` 里的那份 style 自然生效，
 * `uiMode` 也真的变成用户选的那个，界面里所有按 `uiMode` 取色的地方一次性对齐。
 *
 * 只影响本应用的两个界面（设置、诊断）。歌词悬浮层有自己的一套配色，
 * 在「配色」卡里单独调 —— 车机上把它调成夜间色而界面保持浅色是常见用法。
 */
enum class ThemeMode(val key: String) {
    /** 跟随系统（车机多数没有夜间切换，实际等同浅色，但系统换了我们跟着换）。 */
    AUTO("auto"),

    /** 强制浅色。 */
    LIGHT("light"),

    /** 强制深色。 */
    DARK("dark");

    companion object {
        /** 默认跟随系统：不替用户做主，也不改变老用户升级后的观感。 */
        val DEFAULT = AUTO

        /** 认不出的值（老版本残留、手工改坏）一律回默认，绝不抛异常。 */
        fun of(key: String?): ThemeMode = values().firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * 把 [ThemeMode] 落成一份 Context。
 *
 * 用 `createConfigurationContext` 而不是 `setTheme()`：后者只能换 style 资源，
 * `resources.configuration.uiMode` 还是系统值，于是 `isNight` 与 `values-night`
 * 会指向两套不同的答案（一个说白天、一个画夜间色），界面必然花。
 */
object ThemeContext {

    fun wrap(base: Context, mode: ThemeMode): Context {
        if (mode == ThemeMode.AUTO) return base
        val night = if (mode == ThemeMode.DARK) {
            Configuration.UI_MODE_NIGHT_YES
        } else {
            Configuration.UI_MODE_NIGHT_NO
        }
        val config = Configuration(base.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(config)
    }

    fun isNight(base: Context): Boolean =
        (base.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}
