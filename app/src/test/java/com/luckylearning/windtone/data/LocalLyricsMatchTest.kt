package com.luckylearning.windtone.data

import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalLyricsMatchTest {

    private fun song(
        id: Long,
        title: String,
        artist: String?,
        source: SongSource,
        uri: String,
        lrcUri: String? = null,
    ) = Song(
        id = id,
        uri = Uri.parse(uri),
        title = title,
        artist = artist,
        album = "",
        displayName = "$title.mp3",
        source = source,
        lrcUri = lrcUri?.let { Uri.parse(it) },
    )

    @Test
    fun prefersSameArtistLocal() {
        val remote = song(1, "Hello", "A", SongSource.REMOTE, "https://example.com/a.mp3")
        val a = song(2, "Hello", "A", SongSource.MEDIA_STORE, "content://media/1", lrcUri = "content://media/1.lrc")
        val b = song(3, "Hello", "B", SongSource.MEDIA_STORE, "content://media/2")
        assertEquals(a, LocalLyricsMatch.find(listOf(a, b), remote))
    }

    @Test
    fun prefersSidecarWhenArtistBlank() {
        val remote = song(1, "Hello", null, SongSource.REMOTE, "https://example.com/a.mp3")
        val plain = song(2, "Hello", "X", SongSource.MEDIA_STORE, "content://media/1")
        val withLrc = song(3, "Hello", "Y", SongSource.MEDIA_STORE, "content://media/2", lrcUri = "content://media/2.lrc")
        assertEquals(withLrc, LocalLyricsMatch.find(listOf(plain, withLrc), remote))
    }

    @Test
    fun ignoresWhenTitleBlank() {
        val remote = song(1, "  ", "A", SongSource.REMOTE, "https://example.com/a.mp3")
        val local = song(2, "Hello", "A", SongSource.MEDIA_STORE, "content://media/1")
        assertNull(LocalLyricsMatch.find(listOf(local), remote))
    }
}
