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
        // L1 缓冲是「历史 + 当前行在末尾」：锚点指向最后一行
        assertEquals(1, outcome.hit?.currentLineIndex)
        // L0 先试过，留下一条 miss 记录
        assertEquals(2, outcome.attempts.size)
    }

    /** 0.2.7：L2 多行通知第一行才是当前句 —— 旧实现一律取末行，等于把下一句当当前句画。 */
    @Test
    fun l2MultilineAnchorsAtFirstLine() {
        val source = L2NotificationSource()
        val ctx = TrackContext(
            pseudoTitle = true,
            notificationTexts = mapOf("android.text" to "当前句\n下一句")
        )
        val result = source.tryResolve(ctx)

        assertTrue(result is LyricResult.Hit)
        result as LyricResult.Hit
        assertEquals(2, result.lines.size)
        assertEquals(0, result.currentLineIndex)
        assertEquals("当前句", result.lines[0].text)
    }

    /** L2 的 LRC 形态带完整时间轴，交给时钟定位，锚点保持 -1。 */
    @Test
    fun l2LrcHitHasNoExplicitAnchor() {
        val ctx = TrackContext(
            notificationTexts = mapOf("android.text" to "[00:01.00]a\n[00:02.00]b")
        )
        val result = L2NotificationSource().tryResolve(ctx)

        assertTrue(result is LyricResult.Hit)
        assertEquals(-1, (result as LyricResult.Hit).currentLineIndex)
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
