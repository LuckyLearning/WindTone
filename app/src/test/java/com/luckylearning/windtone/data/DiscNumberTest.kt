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

    @Test
    fun emptyAlbum_noHeadersAndSortStable() {
        assertFalse(shouldShowDiscHeaders(emptyList()))
        assertTrue(sortAlbumSongs(emptyList()).isEmpty())
        assertTrue(sortArtistAlbumSongs(emptyList()).isEmpty())
    }

    @Test
    fun parseDiscNumber_rejectsZeroAndOver99() {
        assertNull(parseDiscNumber("0"))
        assertNull(parseDiscNumber("Disc 0"))
        assertNull(parseDiscNumber("100"))
        assertNull(normalizeDiscNumber(0))
        assertNull(normalizeDiscNumber(100))
        assertNull(normalizeDiscNumber(-1))
        assertEquals(99, normalizeDiscNumber(99))
    }

    @Test
    fun parseDiscNumber_zhAndCdVariants() {
        assertEquals(3, parseDiscNumber("碟 3"))
        assertEquals(1, parseDiscNumber("CD 1"))
        assertEquals(2, parseDiscNumber("disk 2"))
        assertEquals(4, parseDiscNumber("4/4"))
    }

    @Test
    fun invalidExplicitDiscFallsBackToTrackEncoding() {
        val encoded = song(1, "a", trackNo = (2 shl 16) + 5, discNumber = 0)
        assertEquals(2, encoded.effectiveDiscNumber())
        assertEquals(5, encoded.effectiveTrackNumber())

        val over = song(2, "b", trackNo = (3 shl 16) + 1, discNumber = 100)
        assertEquals(3, over.effectiveDiscNumber())
    }

    @Test
    fun explicitDiscOverridesTrackEncoding() {
        val s = song(1, "a", trackNo = (2 shl 16) + 5, discNumber = 1)
        assertEquals(1, s.effectiveDiscNumber())
        // track 仍从编码低位取
        assertEquals(5, s.effectiveTrackNumber())
    }

    @Test
    fun shouldShowDiscHeaders_allDiscOne_false() {
        val songs = listOf(
            song(1, "a", discNumber = 1, trackNo = 1),
            song(2, "b", discNumber = 1, trackNo = 2),
        )
        assertFalse(shouldShowDiscHeaders(songs))
    }

    @Test
    fun unknownDiscMergesIntoDiscOneWhenMultiDisc() {
        // 文档：无碟号曲目在需要分区的专辑里并入第 1 碟
        val songs = listOf(
            song(1, "no-disc", trackNo = 2, discNumber = null),
            song(2, "d2t1", trackNo = 1, discNumber = 2),
            song(3, "d1t1", trackNo = 1, discNumber = 1),
        )
        assertTrue(shouldShowDiscHeaders(songs))
        assertEquals(1, songs[0].discForGrouping())
        assertEquals(
            listOf("d1t1", "no-disc", "d2t1"),
            sortAlbumSongs(songs).map { it.title },
        )
    }

    @Test
    fun sortArtistAlbumSongs_albumThenDiscThenTrack() {
        val songs = listOf(
            song(1, "b2", album = "B", trackNo = (1 shl 16) + 2),
            song(2, "a1", album = "A", trackNo = (2 shl 16) + 1),
            song(3, "b1", album = "B", trackNo = (1 shl 16) + 1),
            song(4, "a0", album = "A", trackNo = (1 shl 16) + 1),
        )
        assertEquals(
            listOf("a0", "a1", "b1", "b2"),
            sortArtistAlbumSongs(songs).map { it.title },
        )
    }

    @Test
    fun decodeMediaStoreTrack_negativeOrZero() {
        assertEquals(null to 0, decodeMediaStoreTrack(-1))
        assertEquals(null to 0, decodeMediaStoreTrack(0))
    }

}
