package com.luckylearning.windtone.state

import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaylistCoverTest {

    private fun song(id: Long) = Song(
        id = id,
        uri = Uri.parse("content://song/$id"),
        title = "t$id",
        artist = "a",
        album = "b",
        durationMs = 1000L,
    )

    @Test
    fun takeAtMostFourAndPreserveOrder() {
        val songs = (1L..5L).map { song(it) }
        assertEquals(songs.take(4), playlistCoverSongs(songs))
        assertTrue(playlistCoverSongs(emptyList()).isEmpty())
        assertEquals(emptyList<Song>(), playlistCoverSongs(songs, limit = 0))
    }

    @Test
    fun layoutModeDegradesByCount() {
        assertEquals(PlaylistCoverMode.ICON, playlistCoverMode(0))
        assertEquals(PlaylistCoverMode.ICON, playlistCoverMode(-1))
        assertEquals(PlaylistCoverMode.SINGLE, playlistCoverMode(1))
        assertEquals(PlaylistCoverMode.GRID, playlistCoverMode(2))
        assertEquals(PlaylistCoverMode.GRID, playlistCoverMode(3))
        assertEquals(PlaylistCoverMode.GRID, playlistCoverMode(4))
    }
}
