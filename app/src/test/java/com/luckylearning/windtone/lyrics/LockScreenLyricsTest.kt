package com.luckylearning.windtone.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁屏 / 通知栏歌词行选取（纯 JVM）。
 * 时间轴复用 OverlayContentResolver；本测锁定「有歌词取当前行、无歌词回退标题·艺人、前奏不把歌名当 SYNOPSIS」。
 */
class LockScreenLyricsTest {

    private fun synced(vararg lines: LyricLine) = Lyrics(lines = lines.toList(), synced = true)

    private fun line(sec: Int, text: String) = LyricLine(timeMs = sec * 1000L, text = text)

    @Test
    fun picksCurrentLyricLine() {
        val lyrics = synced(line(10, "第一句"), line(20, "第二句"), line(30, "第三句"))
        val d = LockScreenLyrics.current(lyrics, 21_000L, 0, "歌名", "歌手")
        assertEquals("第二句", d.text)
        assertTrue(d.fromLyrics)
    }

    @Test
    fun noLyricsFallsBackToTitleArtist() {
        val d = LockScreenLyrics.current(Lyrics.EMPTY, 0L, 0, "歌名", "歌手")
        assertEquals("歌名 · 歌手", d.text)
        assertFalse(d.fromLyrics)
    }

    @Test
    fun unknownArtistOmittedFromFallback() {
        val d = LockScreenLyrics.fallback("歌名", "<unknown>")
        assertEquals("歌名", d.text)
        assertFalse(d.fromLyrics)
    }

    @Test
    fun introDoesNotTreatTitleAsLyricSynopsis() {
        val lyrics = synced(line(30, "第一句"), line(40, "第二句"))
        val d = LockScreenLyrics.current(lyrics, 5_000L, 0, "歌名", "歌手")
        assertEquals("歌名 · 歌手", d.text)
        assertFalse(d.fromLyrics)
    }

    @Test
    fun interludeKeepsPreviousLyricLine() {
        val lyrics = synced(
            line(10, "上一句"),
            LyricLine(timeMs = 20_000L, text = ""),
            line(30, "下一句"),
        )
        val d = LockScreenLyrics.current(lyrics, 22_000L, 0, "歌名", "歌手")
        assertEquals("上一句", d.text)
        assertTrue(d.fromLyrics)
    }

    @Test
    fun unsyncedFallsBackEvenIfLinesExist() {
        val lyrics = Lyrics(
            lines = listOf(LyricLine(timeMs = -1L, text = "纯文本")),
            synced = false,
        )
        val d = LockScreenLyrics.current(lyrics, 1_000L, 0, "歌名", "歌手")
        assertEquals("歌名 · 歌手", d.text)
        assertFalse(d.fromLyrics)
    }

    @Test
    fun respectsLyricOffset() {
        val lyrics = synced(line(10, "第一句"), line(20, "第二句"))
        // 实际 15s，偏移 +6s → 等效 21s → 第二句
        val d = LockScreenLyrics.current(lyrics, 15_000L, 6_000, "歌名", "歌手")
        assertEquals("第二句", d.text)
        assertTrue(d.fromLyrics)
    }
}
