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

/** G1 genre/year pure functions; Robolectric for android.net.Uri on Song. */
@RunWith(RobolectricTestRunner::class)
class GenreYearTest {
    private fun song(
        id: Long,
        name: String,
        genre: String? = null,
        year: Int? = null,
    ) = Song(
        id = id,
        uri = Uri.parse("content://song/$id"),
        title = name,
        artist = null,
        album = null,
        displayName = "$name.mp3",
        durationMs = 1L,
        source = SongSource.MEDIA_STORE,
        genre = genre,
        year = year,
    )

    @Test
    fun normalizeGenre_trimsAndDropsPlaceholders() {
        assertNull(normalizeGenre(null))
        assertNull(normalizeGenre("  "))
        assertNull(normalizeGenre("<unknown>"))
        assertNull(normalizeGenre("unknown"))
        assertNull(normalizeGenre("UNKNOWN"))
        assertNull(normalizeGenre("未知"))
        assertNull(normalizeGenre("未知流派"))
        assertEquals("Jazz", normalizeGenre("  Jazz  "))
    }

    @Test
    fun parseYear_acceptsFourDigitInRange() {
        assertEquals(1999, parseYearInt(1999))
        assertNull(parseYearInt(999))
        assertNull(parseYearInt(3001))
        assertEquals(2005, parseYearLabel("2005"))
        assertEquals(2005, parseYearLabel(" © 2005 Universal"))
        assertNull(parseYearLabel("no-year"))
    }

    @Test
    fun genreOrUnknown_and_yearOrUnknown() {
        val base = song(1L, "T")
        assertEquals(UNKNOWN_GENRE_LABEL, base.genreOrUnknown())
        assertEquals(UNKNOWN_YEAR_LABEL, base.yearOrUnknown())
        assertEquals("Rock", base.copy(genre = "Rock").genreOrUnknown())
        assertEquals("1998", base.copy(year = 1998).yearOrUnknown())
    }

    @Test
    fun buildGroups_unknownBucketAndSortYearsDesc() {
        val a = song(1L, "A", genre = "Jazz", year = 2001)
        val b = song(2L, "B", genre = null, year = null)
        val c = song(3L, "C", genre = "Jazz", year = 1990)
        val genres = buildGenreGroups(listOf(a, b, c))
        assertEquals(2, genres.size)
        assertTrue(genres.any { it.key == "Jazz" && it.count == 2 })
        assertTrue(genres.any { it.key == UNKNOWN_GENRE_LABEL && it.count == 1 })
        val years = sortYearGroups(buildYearGroups(listOf(a, b, c)))
        assertEquals(listOf("2001", "1990", UNKNOWN_YEAR_LABEL), years.map { it.key })
    }

    @Test
    fun buildGroups_emptyLibrary_isEmpty() {
        assertTrue(buildGenreGroups(emptyList()).isEmpty())
        assertTrue(buildYearGroups(emptyList()).isEmpty())
        assertTrue(sortYearGroups(emptyList()).isEmpty())
    }

    @Test
    fun buildGenreGroups_placeholderTagsLandInUnknownBucket() {
        val songs = listOf(
            song(1L, "a", genre = "<unknown>"),
            song(2L, "b", genre = "unknown"),
            song(3L, "c", genre = "未知"),
            song(4L, "d", genre = "未知流派"),
            song(5L, "e", genre = "  "),
            song(6L, "f", genre = null),
        )
        val groups = buildGenreGroups(songs)
        assertEquals(1, groups.size)
        assertEquals(UNKNOWN_GENRE_LABEL, groups.single().key)
        assertEquals(6, groups.single().count)
    }

    @Test
    fun buildGenreGroups_trimmedSameLabelMerges() {
        val songs = listOf(
            song(1L, "a", genre = "  Jazz  "),
            song(2L, "b", genre = "Jazz"),
        )
        val groups = buildGenreGroups(songs)
        assertEquals(1, groups.size)
        assertEquals("Jazz", groups.single().key)
        assertEquals(2, groups.single().count)
    }

    @Test
    fun yearOrUnknown_outOfRangeBecomesUnknown() {
        val base = song(1L, "T")
        assertEquals(UNKNOWN_YEAR_LABEL, base.copy(year = 999).yearOrUnknown())
        assertEquals(UNKNOWN_YEAR_LABEL, base.copy(year = 2101).yearOrUnknown())
        assertEquals("1000", base.copy(year = 1000).yearOrUnknown())
        assertEquals("2100", base.copy(year = 2100).yearOrUnknown())
    }

    @Test
    fun parseYearInt_boundaries() {
        assertEquals(1000, parseYearInt(1000))
        assertEquals(2100, parseYearInt(2100))
        assertNull(parseYearInt(0))
        assertNull(parseYearInt(-1))
    }

    @Test
    fun sortYearGroups_onlyUnknownStays() {
        val years = sortYearGroups(buildYearGroups(listOf(song(1L, "a"), song(2L, "b"))))
        assertEquals(listOf(UNKNOWN_YEAR_LABEL), years.map { it.key })
        assertEquals(2, years.single().count)
    }

    @Test
    fun sortYearGroups_unknownAlwaysLastAmongNumeric() {
        val songs = listOf(
            song(1L, "a", year = 1980),
            song(2L, "b", year = null),
            song(3L, "c", year = 2020),
            song(4L, "d", year = 999), // out of range → unknown
        )
        val years = sortYearGroups(buildYearGroups(songs))
        assertEquals(listOf("2020", "1980", UNKNOWN_YEAR_LABEL), years.map { it.key })
        assertEquals(2, years.last().count)
    }

}
