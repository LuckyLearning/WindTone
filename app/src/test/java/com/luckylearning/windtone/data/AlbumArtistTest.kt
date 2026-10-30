package com.luckylearning.windtone.data

import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** G2 albumArtist pure functions; Robolectric for android.net.Uri on Song. */
@RunWith(RobolectricTestRunner::class)
class AlbumArtistTest {
    private fun song(
        id: Long,
        name: String,
        artist: String? = null,
        albumArtist: String? = null,
    ) = Song(
        id = id,
        uri = Uri.parse("content://song/$id"),
        title = name,
        artist = artist,
        album = null,
        displayName = "$name.mp3",
        durationMs = 1L,
        source = SongSource.MEDIA_STORE,
        albumArtist = albumArtist,
    )

    @Test
    fun normalizeAlbumArtist_trimsAndDropsPlaceholders_keepsVarious() {
        assertNull(normalizeAlbumArtist(null))
        assertNull(normalizeAlbumArtist("  "))
        assertNull(normalizeAlbumArtist("<unknown>"))
        assertNull(normalizeAlbumArtist("unknown"))
        assertNull(normalizeAlbumArtist("UNKNOWN"))
        assertNull(normalizeAlbumArtist("未知"))
        assertNull(normalizeAlbumArtist("未知歌手"))
        assertNull(normalizeAlbumArtist("未知合辑艺人"))
        assertEquals("Various Artists", normalizeAlbumArtist("  Various Artists  "))
        assertEquals("合辑", normalizeAlbumArtist("合辑"))
        assertEquals("群星", normalizeAlbumArtist("群星"))
    }

    @Test
    fun effectiveAlbumArtist_prefersAlbumArtist_thenArtist() {
        val base = song(1L, "T")
        assertNull(base.effectiveAlbumArtist())
        assertEquals(UNKNOWN_ALBUM_ARTIST_LABEL, base.albumArtistOrUnknown())

        val byAa = base.copy(albumArtist = "Various Artists", artist = "A / B")
        assertEquals("Various Artists", byAa.effectiveAlbumArtist())

        val byArtist = base.copy(artist = "Jay Chou")
        assertEquals("Jay Chou", byArtist.effectiveAlbumArtist())

        // albumArtist 占位无效时回退 artist
        val fallback = base.copy(albumArtist = "<unknown>", artist = "Solo")
        assertEquals("Solo", fallback.effectiveAlbumArtist())
    }

    @Test
    fun buildAlbumArtistGroups_compilesTogether() {
        val a = song(1L, "A", artist = "Guest A", albumArtist = "Various Artists")
        val b = song(2L, "B", artist = "Guest B", albumArtist = "Various Artists")
        val c = song(3L, "C", artist = "Solo", albumArtist = null)
        val d = song(4L, "D", artist = null, albumArtist = null)
        val groups = buildAlbumArtistGroups(listOf(a, b, c, d))
        assertEquals(3, groups.size)
        assertTrue(groups.any { it.key == "Various Artists" && it.count == 2 })
        assertTrue(groups.any { it.key == "Solo" && it.count == 1 })
        assertTrue(groups.any { it.key == UNKNOWN_ALBUM_ARTIST_LABEL && it.count == 1 })
    }
}
