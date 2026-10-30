package com.luckylearning.windtone.data

import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryFormatFilterTest {

    private fun song(fileName: String): Song = Song(
        id = fileName.hashCode().toLong(),
        uri = Uri.parse("file:///music/$fileName"),
        title = fileName.substringBeforeLast('.'),
        artist = "a",
        album = "b",
        durationMs = 1000L,
        sizeBytes = 1L,
        displayName = fileName,
        path = "/music/$fileName",
    )

    @Test
    fun none_passes_all() {
        val songs = listOf(song("a.flac"), song("b.mp3"))
        assertEquals(songs, applyLibraryFormatFilter(songs, LibraryFormatFilter.NONE))
    }

    @Test
    fun lossless_only_keeps_flac_drops_mp3_and_m4a() {
        val songs = listOf(song("a.flac"), song("b.mp3"), song("c.m4a"), song("d.wav"))
        val out = applyLibraryFormatFilter(songs, LibraryFormatFilter(losslessOnly = true))
        assertEquals(listOf("a.flac", "d.wav"), out.map { it.displayName })
    }

    @Test
    fun extension_filter_intersection_with_lossless() {
        val songs = listOf(song("a.flac"), song("b.wav"), song("c.mp3"))
        val filter = LibraryFormatFilter(losslessOnly = true, extensions = setOf("flac", "mp3"))
        val out = applyLibraryFormatFilter(songs, filter)
        assertEquals(listOf("a.flac"), out.map { it.displayName })
    }

    @Test
    fun extension_only_normalizes_case_and_aliases() {
        val songs = listOf(song("a.WAVE"), song("b.Mp3"))
        val filter = LibraryFormatFilter(extensions = setOf("wav"))
        val out = applyLibraryFormatFilter(songs, filter)
        assertEquals(listOf("a.WAVE"), out.map { it.displayName })
    }

    @Test
    fun isActive_and_none() {
        assertFalse(LibraryFormatFilter.NONE.isActive)
        assertTrue(LibraryFormatFilter(losslessOnly = true).isActive)
        assertTrue(LibraryFormatFilter(extensions = setOf("mp3")).isActive)
    }

    @Test
    fun isLosslessAudioExt_matches_stats_set() {
        assertTrue(isLosslessAudioExt("FLAC"))
        assertTrue(isLosslessAudioExt("wav"))
        assertFalse(isLosslessAudioExt("mp3"))
        assertFalse(isLosslessAudioExt("m4a"))
    }
}
