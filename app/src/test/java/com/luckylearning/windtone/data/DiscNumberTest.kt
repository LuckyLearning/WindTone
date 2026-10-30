package com.luckylearning.windtone.data

import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** G3 discNumber pure functions; Robolectric for android.net.Uri on Song. */
@RunWith(RobolectricTestRunner::class)
class DiscNumberTest {
    private fun song(
        id: Long,
        name: String,
        trackNo: Int = 0,
        discNumber: Int? = null,
        album: String? = "A",
    ) = Song(
        id = id,
        uri = Uri.parse("content://song/$id"),
        title = name,
        artist = null,
        album = album,
        displayName = "$name.mp3",
        durationMs = 1L,
        source = SongSource.MEDIA_STORE,
        trackNo = trackNo,
        discNumber = discNumber,
    )

    @Test
    fun decodeMediaStoreTrack_highBitsAreDisc() {
        assertEquals(null to 0, decodeMediaStoreTrack(0))
        assertEquals(null to 5, decodeMediaStoreTrack(5))
        // disc 2 track 3 = (2 << 16) + 3
        assertEquals(2 to 3, decodeMediaStoreTrack((2 shl 16) + 3))
        assertEquals(1 to 1, decodeMediaStoreTrack((1 shl 16) + 1))
    }

    @Test
    fun parseDiscNumber_variants() {
        assertNull(parseDiscNumber(null))
        assertNull(parseDiscNumber("  "))
        assertEquals(2, parseDiscNumber("2"))
        assertEquals(2, parseDiscNumber("2/3"))
        assertEquals(2, parseDiscNumber("Disc 2"))
        assertEquals(1, parseDiscNumber("碟 1"))
        assertNull(parseDiscNumber("nope"))
    }

    @Test
    fun effectiveDisc_prefersExplicitThenTrackEncoding() {
        val plain = song(1, "a", trackNo = 5)
        assertNull(plain.effectiveDiscNumber())
        assertEquals(5, plain.effectiveTrackNumber())

        val encoded = song(2, "b", trackNo = (2 shl 16) + 7)
        assertEquals(2, encoded.effectiveDiscNumber())
        assertEquals(7, encoded.effectiveTrackNumber())

        val explicit = song(3, "c", trackNo = 4, discNumber = 3)
        assertEquals(3, explicit.effectiveDiscNumber())
    }

    @Test
    fun shouldShowDiscHeaders_onlyWhenMulti() {
        val single = listOf(song(1, "a", trackNo = 1), song(2, "b", trackNo = 2))
        assertFalse(shouldShowDiscHeaders(single))

        val multi = listOf(
            song(1, "a", trackNo = (1 shl 16) + 1),
            song(2, "b", trackNo = (2 shl 16) + 1),
        )
        assertTrue(shouldShowDiscHeaders(multi))

        val disc2Only = listOf(song(1, "a", discNumber = 2, trackNo = 1))
        assertTrue(shouldShowDiscHeaders(disc2Only))
    }

    @Test
    fun sortAlbumSongs_discThenTrack() {
        val songs = listOf(
            song(1, "d2t2", trackNo = (2 shl 16) + 2),
            song(2, "d1t2", trackNo = (1 shl 16) + 2),
            song(3, "d2t1", trackNo = (2 shl 16) + 1),
            song(4, "d1t1", trackNo = (1 shl 16) + 1),
        )
        assertEquals(
            listOf("d1t1", "d1t2", "d2t1", "d2t2"),
            sortAlbumSongs(songs).map { it.title },
        )
    }

    @Test
    fun sortAlbumSongs_noDiscKeepsTrackNoOrder() {
        val songs = listOf(
            song(1, "c", trackNo = 3),
            song(2, "a", trackNo = 1),
            song(3, "b", trackNo = 2),
        )
        assertEquals(listOf("a", "b", "c"), sortAlbumSongs(songs).map { it.title })
    }
}
