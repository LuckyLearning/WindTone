package com.luckylearning.windtone.ui

import com.luckylearning.windtone.data.SettingsDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 颜色弹层的预设色板（效果图 ㉙E 里的 8 颗色点）。
 *
 * ## 为什么要测
 *
 * 色板是「恢复默认」能不能真的生效的前提：默认值（悬浮歌词文字颜色 = 纯白）
 * 必须**就在这 8 颗色点里**，否则用户点开弹层找不到默认色，只能靠自定义滑块凑，
 * 「恢复默认」这个动作在界面上就变成了不可达的一步 —— 不报错、不崩溃，只是说不通。
 *
 * ⚠️ v1.1.8 起色板只剩「悬浮歌词文字颜色」一个消费方（播放页已唱色、悬浮窗已唱色
 * 都随逐字染色删掉了），色点也不再印色名，所以 [LyricsColors.presets] 现在就是一组色值。
 */
class LyricsColorsTest {

    @Test
    fun presetsAreDistinctOpaqueColors() {
        val argb = LyricsColors.presets
        assertEquals("预设色板里有重复颜色", argb.size, argb.distinct().size)
        // 白 / 暖黄 / 天蓝 / 青绿 / 草绿 / 桃粉 / 橘橙 / 雾紫
        assertEquals(8, argb.size)
        assertTrue(
            "预设色板缺了悬浮歌词文字颜色的默认值（纯白），「恢复默认」就点不回来了",
            argb.contains(SettingsDefaults.OVERLAY_TEXT_COLOR),
        )
        assertTrue(
            "色点必须是不透明的：带 alpha 的色点在深色底上会看不出选中描边",
            argb.all { it ushr 24 == 0xFF },
        )
    }

    /** 自定义滑块的起点色也必须在色板里，否则用户在弹层里找不到「我刚才那个颜色」 */
    @Test
    fun customSeedIsPartOfTheSwatches() {
        assertTrue(
            "DEFAULT_SEED 不在预设色板里",
            LyricsColors.presets.contains(LyricsColors.DEFAULT_SEED),
        )
    }
}
