package com.luckylearning.windtone.data

/**
 * 悬浮窗外观的「主题作用域」（v1.2.52）。
 *
 * ## 为什么要有这个东西
 * 悬浮歌词白天贴在浅色桌面（导航浅色地图）上，夜里贴在深色画面上 —— 同一套白字、
 * 同一个底板不透明度在两种场景下观感完全不同：白天需要更实的底板才压得住光，
 * 夜里底板过实又会变成一块突兀的黑砖。所以外观（字号 / 底板 / 描边 / 位置 / 尺寸）
 * 分**白天**与**夜间**两套，跟着主题走，系统到点自动切。
 *
 * ## 键位约定
 * 白天那套用**原键**（`lyric_overlay_text_sp`），夜间那套用 `d_` 前缀
 * （`d_lyric_overlay_text_sp`）。这么定的理由有两个：
 *  1. 老用户升级后，原有设置原样就是「白天」那套 —— 不需要任何迁移写入；
 *  2. 夜间**逐键继承**白天：某个键没有 `d_` 版本时读白天的值（见 `data/OverlayStylePrefs`），
 *     用户只想在夜里把字号调小一点，不必把 27 项全部重配一遍。
 *
 * ## 只作用于外观，不作用于行为
 * 归属哪一套由 `Prefs.STYLE_KEYS` 决定。**开关类键永远只有一套**：悬浮歌词总开关、
 * 隐藏名单、侧钮、全屏形态开关、输出屏幕 —— 这些是「要不要显示 / 显示在哪」，
 * 不是「长什么样」。若它们也分两套，用户会遇到最莫名其妙的故障：
 * 白天开着悬浮窗，天一黑它自己没了。
 *
 * 本对象刻意零 Android 依赖（`uiMode` 的位运算直接写常量），
 * 既给 `Prefs` 用，也能在纯 JVM 单测里跑。
 */
object OverlayStyleScope {

    /** 夜间那套的键前缀；`d_` 取自 dark */
    const val DARK_PREFIX = "d_"

    /** `Configuration.UI_MODE_NIGHT_MASK`（0x30）：低 4 位里只有这三位是「明暗」 */
    const val UI_MODE_NIGHT_MASK = 0x30

    /** `Configuration.UI_MODE_NIGHT_YES`（0x20） */
    const val UI_MODE_NIGHT_YES = 0x20

    /** 白天 / 夜间两套里实际读写的键名 */
    fun key(base: String, dark: Boolean): String = if (dark) DARK_PREFIX + base else base

    /**
     * 系统是否处于深色。
     *
     * ⚠️ 必须用 `and MASK == YES` 而不是 `== NO`：`uiMode` 的高位还带着
     * 「是不是车机 / 是不是电视」这类类型位（`UI_MODE_TYPE_CAR` 等），
     * 直接拿整个 int 比较会把车机永远判成浅色。
     */
    fun isNightUiMode(uiMode: Int): Boolean =
        (uiMode and UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES

    /**
     * 当前该取哪一套：主题固定浅 / 固定深时与系统无关，只有「跟随系统」才看 uiMode。
     *
     * 与界面主题的解析口径完全一致（`ThemeMode` → `MusicPlayerTheme` → `DesignTokens`），
     * 否则会出现「界面已经是深色，悬浮窗还在用白天那套」。
     */
    fun isDark(mode: ThemeMode, uiMode: Int): Boolean = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isNightUiMode(uiMode)
    }
}
