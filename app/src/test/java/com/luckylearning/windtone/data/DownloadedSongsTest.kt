package com.luckylearning.windtone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 下载目录列表的纯规则：文件名剥离身份哈希、音频后缀识别。 */
class DownloadedSongsTest {

    @Test
    fun `展示名去掉扩展名与身份哈希`() {
        assertEquals("晴天", DownloadedSongs.displayTitle("晴天-4e5a647a.mp3"))
        assertEquals("Hello - Adele", DownloadedSongs.displayTitle("Hello - Adele-0A1B2C3D.flac"))
        assertEquals("本地文件", DownloadedSongs.displayTitle("本地文件.lrc"))
    }

    @Test
    fun `普通文件名不被误删`() {
        assertEquals("abc-def", DownloadedSongs.displayTitle("abc-def.mp3"))
        assertEquals("abc-1234567", DownloadedSongs.displayTitle("abc-1234567.mp3"))
    }

    @Test
    fun `音频按 mime 或后缀识别`() {
        assertTrue(DownloadedSongs.isAudio("a.MP3", "application/octet-stream"))
        assertTrue(DownloadedSongs.isAudio("a.unknown", "audio/flac"))
        assertTrue(DownloadedSongs.isAudio("a.opus", ""))
        assertFalse(DownloadedSongs.isAudio("cover.jpg", "image/jpeg"))
        assertFalse(DownloadedSongs.isAudio("lyrics.lrc", "text/plain"))
    }
}
