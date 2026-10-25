package com.luckylearning.windtone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsMatcherPickBestTest {

    @Test
    fun exactLrcBeatsTxt() {
        val best = LyricsMatcher.pickBest(
            listOf("Song.txt", "Song.lrc", "other.lrc"),
            "Song",
        )
        assertEquals("Song.lrc", best)
    }

    @Test
    fun exactTxtBeatsArtistSuffixLrc() {
        // 全同名 stem 分更高；「歌名 - 歌手.lrc」是后缀匹配，不应压过「歌名.txt」
        val best = LyricsMatcher.pickBest(
            listOf("Song - Artist.lrc", "Song.txt"),
            "Song",
        )
        assertEquals("Song.txt", best)
    }

    @Test
    fun suffixLrcWhenNoExact() {
        val best = LyricsMatcher.pickBest(
            listOf("Song - Artist.lrc", "other.txt"),
            "Song",
        )
        assertEquals("Song - Artist.lrc", best)
    }

    @Test
    fun noMatchReturnsNull() {
        assertNull(LyricsMatcher.pickBest(listOf("a.mp3", "b.flac"), "Song"))
    }
}
