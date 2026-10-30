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
}
