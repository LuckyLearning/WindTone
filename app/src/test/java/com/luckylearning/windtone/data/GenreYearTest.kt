package com.luckylearning.windtone.data

import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreYearTest {
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
        val base = Song(
            uri = android.net.Uri.parse("file://x"),
            displayName = "a.mp3",
            title = "T",
            durationMs = 1L,
            source = SongSource.MEDIA_STORE,
        )
        assertEquals(UNKNOWN_GENRE_LABEL, base.genreOrUnknown())
        assertEquals(UNKNOWN_YEAR_LABEL, base.yearOrUnknown())
        assertEquals("Rock", base.copy(genre = "Rock").genreOrUnknown())
        assertEquals("1998", base.copy(year = 1998).yearOrUnknown())
    }

    @Test
    fun buildGroups_unknownBucketAndSortYearsDesc() {
        val a = Song(
            uri = android.net.Uri.parse("file://a"),
            displayName = "a.mp3",
            title = "A",
            durationMs = 1L,
            source = SongSource.MEDIA_STORE,
            genre = "Jazz",
            year = 2001,
        )
        val b = Song(
            uri = android.net.Uri.parse("file://b"),
            displayName = "b.mp3",
            title = "B",
            durationMs = 1L,
            source = SongSource.MEDIA_STORE,
            genre = null,
            year = null,
        )
        val c = Song(
            uri = android.net.Uri.parse("file://c"),
            displayName = "c.mp3",
            title = "C",
            durationMs = 1L,
            source = SongSource.MEDIA_STORE,
            genre = "Jazz",
            year = 1990,
        )
        val genres = buildGenreGroups(listOf(a, b, c))
        assertEquals(2, genres.size)
        assertTrue(genres.any { it.key == "Jazz" && it.count == 2 })
        assertTrue(genres.any { it.key == UNKNOWN_GENRE_LABEL && it.count == 1 })
        val years = sortYearGroups(buildYearGroups(listOf(a, b, c)))
        assertEquals(listOf("2001", "1990", UNKNOWN_YEAR_LABEL), years.map { it.key })
    }
}
