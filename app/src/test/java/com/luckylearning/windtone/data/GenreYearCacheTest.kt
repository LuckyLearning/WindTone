package com.luckylearning.windtone.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GenreYearCacheTest {

    private lateinit var cache: GenreYearCache

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences(GenreYearCache.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(GenreYearCache.KEY).commit()
        cache = GenreYearCache(ctx)
    }

    private fun song(id: Long, genre: String? = null, year: Int? = null) = Song(
        id = id,
        uri = Uri.parse("content://media/external/audio/media/$id"),
        title = "t$id",
        artist = "a",
        album = "b",
        displayName = "t$id.mp3",
        durationMs = 1L,
        source = SongSource.MEDIA_STORE,
        genre = genre,
        year = year,
    )

    @Test
    fun syncFromLibrary_writesAndPrunesStaleKeys() {
        cache.putFromSongs(listOf(song(1, genre = "Rock", year = 2000), song(2, genre = "Jazz")))
        assertEquals("Rock", cache.overlay(song(1)).genre)
        assertEquals("Jazz", cache.overlay(song(2)).genre)

        // 曲库只剩 #1：应丢掉 #2
        cache.syncFromLibrary(listOf(song(1, genre = "Rock", year = 2001)))
        assertEquals(2001, cache.overlay(song(1)).year)
        assertNull(cache.overlay(song(2)).genre)
    }

    @Test
    fun overlay_listUsesMemoWithoutLosingValues() {
        val lib = listOf(song(3, genre = "Pop", year = 1999))
        cache.syncFromLibrary(lib)
        val once = cache.overlay(lib)
        val twice = cache.overlay(lib)
        assertEquals("Pop", once.single().genre)
        assertEquals(1999, twice.single().year)
    }
}
