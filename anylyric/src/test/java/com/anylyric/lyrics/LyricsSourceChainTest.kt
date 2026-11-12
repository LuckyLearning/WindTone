package com.anylyric.lyrics

import com.anylyric.lyrics.sources.L0MetadataSource
import com.anylyric.lyrics.sources.L1TitleStreamSource
import com.anylyric.lyrics.sources.L2NotificationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSourceChainTest {

    private fun chain(l1: L1TitleStreamSource = L1TitleStreamSource()) =
        LyricsSourceChain(listOf(L0MetadataSource(), l1, L2NotificationSource()))

    @Test
    fun l0WinsWhenMetadataCarriesLyrics() {
        val ctx = TrackContext(
            metadata = mapOf("lyrics" to "[00:01.00]第一行\n[00:02.00]第二行")
        )
        val outcome = chain().resolve(ctx)

        assertNotNull(outcome.hit)
        assertEquals("L0", outcome.hit?.sourceId)
        assertEquals(2, outcome.hit?.lines?.size)
        assertEquals(1, outcome.attempts.size)
    }

    @Test
    fun fallsBackToL1WhenPseudoTitle() {
        val l1 = L1TitleStreamSource().apply {
            onTitleLine("第一句")
            onTitleLine("第二句")
        }
        val ctx = TrackContext(pseudoTitle = true)
        val outcome = chain(l1).resolve(ctx)

        assertEquals("L1", outcome.hit?.sourceId)
        assertEquals(2, outcome.hit?.lines?.size)
        assertNull(outcome.hit?.lines?.get(0)?.timeMs)
        // L0 先试过，留下一条 miss 记录
        assertEquals(2, outcome.attempts.size)
    }

    @Test
    fun fallsBackToL2NotificationLrc() {
        val ctx = TrackContext(
            notificationTexts = mapOf("android.text" to "[00:01.00]a\n[00:02.00]b")
        )
        val outcome = chain().resolve(ctx)

        assertEquals("L2", outcome.hit?.sourceId)
        assertEquals(3, outcome.attempts.size)
    }

    @Test
    fun reportsEveryMissWhenNothingMatches() {
        val outcome = chain().resolve(TrackContext(title = "普通歌曲"))
        assertNull(outcome.hit)
        assertEquals(3, outcome.attempts.size)
        assertTrue(outcome.attempts.all { it is LyricResult.Miss })
        assertEquals(
            listOf("L0", "L1", "L2"),
            outcome.attempts.map { it.sourceId }
        )
    }

    @Test
    fun l1DoesNotEmitWhenTitleIsStable() {
        val l1 = L1TitleStreamSource().apply {
            onTitleLine("只是一首歌的歌名")
            onTitleLine("另一个字")
        }
        val result = l1.tryResolve(TrackContext(pseudoTitle = false))
        assertTrue(result is LyricResult.Miss)
        assertEquals("stable_title", (result as LyricResult.Miss).reason)
    }

    @Test
    fun l1IgnoresConsecutiveDuplicates() {
        val l1 = L1TitleStreamSource().apply {
            onTitleLine("同一句")
            onTitleLine("同一句")
            onTitleLine("同一句")
        }
        assertEquals(1, l1.bufferedCount())
    }

    /**
     * 判定成立那一刻，判定之前已经看到过的行必须整体回填。
     * 否则首屏要等到「判定成立 + 再攒两句」，用户会以为没歌词。
     */
    @Test
    fun l1BackfillsLinesBufferedBeforeVerdict() {
        val l1 = L1TitleStreamSource().apply {
            onTitleLine("第一句")
            onTitleLine("第二句")
            onTitleLine("第三句")
        }
        // 判定还没成立时不应输出
        assertTrue(l1.tryResolve(TrackContext(pseudoTitle = false)) is LyricResult.Miss)

        val hit = l1.tryResolve(TrackContext(pseudoTitle = true)) as LyricResult.Hit
        assertEquals(3, hit.lines.size)
        assertEquals("第一句", hit.lines.first().text)
    }

    @Test
    fun l1DropsSuffixVariantOfTitle() {
        val l1 = L1TitleStreamSource().apply {
            onTitleLine("普通歌名")
            onTitleLine("普通歌名 - 播放中")
        }
        assertEquals(1, l1.bufferedCount())
    }
}
