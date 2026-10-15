package com.luckylearning.windtone.ui

import com.luckylearning.windtone.data.SettingsDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词颜色设置项的取值与显示（v1.1.4 需求 6+7）。
 *
 * ## 为什么要测
 *
 * 设置行右侧要回答「现在是什么颜色」，而颜色的取值有三种形态：
 * **跟随主题哨兵 / 预设色 / 用户自定义的任意 ARGB**。把它们印成人话的规则
 * 一旦错位（比如哨兵掉进「#RRGGBB」分支），界面上就会出现
 * `#80000000` 这种谁都看不懂、还看着像 bug 的东西 —— 不报错，只是难看且费解。
 */
class LyricsColorsTest {

    @Test
    fun presetColorsGetTheirOwnNames() {
        assertEquals("暖黄", LyricsColors.label(0xFFFFCD56.toInt()))
        assertEquals("纯白", LyricsColors.label(0xFFFFFFFF.toInt()))
    }

    /**
     * 「跟随主题」是一个哨兵值（`Int.MIN_VALUE`），不是颜色。
     *
     * 它必须**先于**十六进制分支被认出来：否则会印成 `#000000`，
     * 而用户看到的悬浮歌词明明在跟着封面变色 —— 界面在说谎。
     */
    @Test
    fun followThemeSentinelNeverLooksLikeAColor() {
        val label = LyricsColors.label(SettingsDefaults.COLOR_FOLLOW_THEME)
        assertEquals("跟随主题", label)
        assertFalse("哨兵被当成颜色的十六进制印出来了", label.startsWith("#"))
    }

    @Test
    fun customColorFallsBackToUppercaseHexWithoutAlpha() {
        assertEquals("#123456", LyricsColors.label(0xFF123456.toInt()))
        // 带 alpha 时只印 RGB：这三处颜色都是不透明的，多印两位只会让人困惑
        assertEquals("#0A0B0C", LyricsColors.label(0x7F0A0B0C))
        // 前导零必须补到 6 位，否则 #0000FF 会变成 "#FF"
        assertEquals("#0000FF", LyricsColors.label(0xFF0000FF.toInt()))
        assertEquals("#000000", LyricsColors.label(0xFF000000.toInt()))
    }

    @Test
    fun presetsAreDistinctOpaqueColors() {
        val argb = LyricsColors.presets.map { it.argb }
        assertEquals("预设色板里有重复颜色", argb.size, argb.distinct().size)
        assertFalse(
            "预设色板里不该出现「跟随主题」哨兵 —— 它是单独一段，不是色板里的一颗色点",
            argb.contains(SettingsDefaults.COLOR_FOLLOW_THEME),
        )
        // 白 / 暖黄 / 天蓝 / 青绿 / 草绿 / 桃粉 / 橘橙 / 雾紫
        assertEquals(8, LyricsColors.presets.size)
        assertTrue(
            "预设色板缺了封面取色的常见落点（暖黄），设置页的默认值就点不回来",
            argb.contains(SettingsDefaults.OVERLAY_SUNG_COLOR),
        )
        assertTrue(
            "预设色板缺了悬浮窗正文字色的默认值（纯白）",
            argb.contains(SettingsDefaults.OVERLAY_TEXT_COLOR),
        )
    }

    /** 自定义滑块的起点色也必须在色板里，否则用户在弹层里找不到「我刚才那个颜色」 */
    @Test
    fun customSeedIsPartOfTheSwatches() {
        assertTrue(
            "DEFAULT_SEED 不在预设色板里",
            LyricsColors.presets.any { it.argb == LyricsColors.DEFAULT_SEED },
        )
    }
}
