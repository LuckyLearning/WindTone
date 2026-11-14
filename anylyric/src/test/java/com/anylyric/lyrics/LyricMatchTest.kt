package com.anylyric.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌名匹配规则。
 *
 * 这里的每一条都对应一次真机上「词是别人的」或「明明有词却取不到」：
 *  · 括号里的 `(Live)` `【伴奏】` 会把标题洗得搜不到；
 *  · `周杰伦 - 稻香` 与 `稻香` 只取整串时互不命中；
 *  · 同名不同人、剪辑版只靠标题分不开，必须有歌手与时长参与打分。
 */
class LyricMatchTest {

    @Test
    fun `core title drops bracketed noise`() {
        assertEquals("晴天", LyricMatch.coreTitle("晴天 (Live)"))
        assertEquals("不悟兰因", LyricMatch.coreTitle("不悟兰因（伴奏）"))
        assertEquals("稻香", LyricMatch.coreTitle("【纯音乐】《稻香》"))
    }

    @Test
    fun `core title keeps text outside brackets`() {
        assertEquals("起风了", LyricMatch.coreTitle("起风了 feat. 某人"))
    }

    @Test
    fun `normalize keeps letters and digits only`() {
        assertEquals("helloworld", LyricMatch.normalize("Hello, World!"))
        assertEquals("稻香2024", LyricMatch.normalize("稻香 2024"))
    }

    @Test
    fun `title forms include both halves of a dashed title`() {
        val forms = LyricMatch.titleForms("周杰伦 - 稻香")
        assertTrue(forms.contains("稻香"))
        assertTrue(forms.contains("周杰伦"))
    }

    @Test
    fun `cache key ignores case brackets and spaces but separates artists`() {
        val a = LyricMatch.cacheKey("晴天 (Live)", "周杰伦")
        val b = LyricMatch.cacheKey("晴天", "周杰伦")
        val c = LyricMatch.cacheKey("晴天", "蔡依林")
        assertEquals(a, b)
        assertNotEquals(b, c)
        assertTrue(a.isNotEmpty())
    }

    @Test
    fun `cache key is empty without a title`() {
        assertEquals("", LyricMatch.cacheKey("  ", "周杰伦"))
    }

    @Test
    fun `queries put title plus artist first`() {
        val queries = LyricMatch.queries("稻香", "周杰伦")
        assertEquals("稻香 周杰伦", queries.first())
        assertTrue(queries.contains("稻香"))
    }

    @Test
    fun `exact title artist and duration scores full`() {
        val score = LyricMatch.score("稻香", "周杰伦", 270, "稻香", "周杰伦", 269)
        assertEquals(100, score)
    }

    @Test
    fun `artist embedded in title still matches`() {
        val score = LyricMatch.score(
            "不悟兰因", "AkI阿杰", 240,
            "不悟兰因 - AkI阿杰", "", 240
        )
        assertTrue(LyricMatch.acceptable(score))
    }

    @Test
    fun `different song is rejected`() {
        val score = LyricMatch.score("晴天", "周杰伦", 269, "稻香", "周杰伦", 270)
        assertFalse(LyricMatch.acceptable(score))
    }

    @Test
    fun `wrong artist but exact title is still accepted`() {
        // 合辑、翻唱合辑里歌手字段经常不是原唱，标题完全一致时仍应采信。
        val score = LyricMatch.score("稻香", "某合唱团", 270, "稻香", "周杰伦", 270)
        assertTrue(LyricMatch.acceptable(score))
    }

    @Test
    fun `duration far off pulls the score below the floor`() {
        val close = LyricMatch.score("稻香", "周杰伦", 271, "稻香", "周杰伦", 270)
        val far = LyricMatch.score("稻香", "周杰伦", 420, "稻香", "周杰伦", 270)
        assertTrue(close > far)
        assertFalse(LyricMatch.acceptable(far))
    }

    @Test
    fun `longer candidate title scores lower than exact`() {
        val exact = LyricMatch.score("晴天", "周杰伦", 269, "晴天", "周杰伦", 269)
        val longer = LyricMatch.score("晴天娃娃", "周杰伦", 269, "晴天", "周杰伦", 269)
        assertTrue(exact > longer)
    }
}
