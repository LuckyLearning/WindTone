package com.anylyric.session

import com.anylyric.lyrics.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌名 / 歌手取值。
 *
 * 「时不时取不到歌名」的两个根因就钉在这里：多键兜底要按顺序取第一个非空；
 * 伪歌名强信号必须逐字符比较，且不能把 null / 空串当命中。
 */
class TitleResolverTest {

    @Test
    fun `first non blank wins`() {
        assertEquals("歌名", TitleResolver.firstNonBlank(null, "", "  ", " 歌名 "))
        assertEquals("A", TitleResolver.firstNonBlank("A", "B"))
        assertEquals("B", TitleResolver.firstNonBlank(null, "B"))
    }

    @Test
    fun `all blank returns null`() {
        assertNull(TitleResolver.firstNonBlank())
        assertNull(TitleResolver.firstNonBlank(null, "", "   "))
    }

    @Test
    fun `title equal to a lyric line is detected`() {
        val lines = listOf(
            LyricLine(0L, "第一句"),
            LyricLine(5_000L, "我穿上大头皮鞋")
        )
        assertTrue(TitleResolver.isLyricTitle("我穿上大头皮鞋", lines))
        assertTrue(TitleResolver.isLyricTitle("  我穿上大头皮鞋  ", lines))
    }

    @Test
    fun `real title is not treated as lyric`() {
        val lines = listOf(LyricLine(0L, "第一句"))
        assertFalse(TitleResolver.isLyricTitle("沙漠骆驼", lines))
        assertFalse(TitleResolver.isLyricTitle(null, lines))
        assertFalse(TitleResolver.isLyricTitle("", lines))
        assertFalse(TitleResolver.isLyricTitle("第一句（live）", lines))
    }
}
