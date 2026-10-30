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

    @Test
    fun buildAlbumArtistGroups_emptyLibrary_isEmpty() {
        assertTrue(buildAlbumArtistGroups(emptyList()).isEmpty())
    }

    @Test
    fun effectiveAlbumArtist_bothPlaceholdersBecomeUnknown() {
        val s = song(1L, "T", artist = "<unknown>", albumArtist = "未知歌手")
        assertNull(s.effectiveAlbumArtist())
        assertEquals(UNKNOWN_ALBUM_ARTIST_LABEL, s.albumArtistOrUnknown())
    }

    @Test
    fun effectiveAlbumArtist_blankAlbumArtistFallsBackToArtist() {
        val s = song(1L, "T", artist = "Jay", albumArtist = "   ")
        assertEquals("Jay", s.effectiveAlbumArtist())
    }

    @Test
    fun buildAlbumArtistGroups_keepsCompilationLabelsDistinct() {
        // 文档口径：Various Artists / 合辑 / 群星 保留原标签，不做别名合并
        val songs = listOf(
            song(1L, "a", artist = "G1", albumArtist = "Various Artists"),
            song(2L, "b", artist = "G2", albumArtist = "合辑"),
            song(3L, "c", artist = "G3", albumArtist = "群星"),
        )
        val groups = buildAlbumArtistGroups(songs)
        assertEquals(3, groups.size)
        assertTrue(groups.any { it.key == "Various Artists" && it.count == 1 })
        assertTrue(groups.any { it.key == "合辑" && it.count == 1 })
        assertTrue(groups.any { it.key == "群星" && it.count == 1 })
    }

    @Test
    fun buildAlbumArtistGroups_compilationMergesGuestsUnderSameAlbumArtist() {
        val songs = listOf(
            song(1L, "track1", artist = "Guest A", albumArtist = "Various Artists"),
            song(2L, "track2", artist = "Guest B", albumArtist = "Various Artists"),
            song(3L, "track3", artist = "Guest C", albumArtist = "Various Artists"),
            song(4L, "solo", artist = "Solo Act", albumArtist = null),
        )
        val groups = buildAlbumArtistGroups(songs)
        assertEquals(2, groups.size)
        val va = groups.single { it.key == "Various Artists" }
        assertEquals(3, va.count)
        assertTrue(groups.any { it.key == "Solo Act" && it.count == 1 })
    }

    @Test
    fun buildAlbumArtistGroups_artistAsVariousWithoutAlbumArtistStillGroups() {
        // 无 albumArtist 时回退 artist：歌手字段本身写 Various Artists 也归合辑组
        val songs = listOf(
            song(1L, "a", artist = "Various Artists", albumArtist = null),
            song(2L, "b", artist = "Various Artists", albumArtist = ""),
        )
        val groups = buildAlbumArtistGroups(songs)
        assertEquals(1, groups.size)
        assertEquals("Various Artists", groups.single().key)
        assertEquals(2, groups.single().count)
    }

}
