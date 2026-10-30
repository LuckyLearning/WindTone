package com.luckylearning.windtone.state

import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A5「最近添加」纯函数：[recentlyAddedSongs]。
 *
 * 钉住：按 dateAdded 倒序、丢掉 0、尊重 limit、空库不崩。
 */
@RunWith(RobolectricTestRunner::class)
class RecentlyAddedSongsTest {

    private fun song(id: Long, title: String, dateAdded: Long) = Song(
        id = id,
        uri = Uri.parse("content://song/$id"),
        title = title,
        artist = "a",
        album = "b",
        durationMs = 1000L,
        dateAdded = dateAdded,
    )

    @Test
    fun sortsByDateAddedDescendingAndDropsZero() {
        val old = song(1, "旧", 100L)
        val mid = song(2, "中", 200L)
        val fresh = song(3, "新", 300L)
        val unknown = song(4, "未知", 0L)
        assertEquals(
            listOf(fresh, mid, old),
            recentlyAddedSongs(listOf(old, unknown, fresh, mid)),
        )
    }

    @Test
    fun respectsLimit() {
        val songs = (1L..5L).map { song(it, "g$it", it * 10) }
        assertEquals(
            listOf("g5", "g4", "g3"),
            recentlyAddedSongs(songs, limit = 3).map { it.title },
        )
    }

    @Test
    fun emptyOrNonPositiveLimit() {
        assertTrue(recentlyAddedSongs(emptyList()).isEmpty())
        assertTrue(recentlyAddedSongs(listOf(song(1, "x", 9L)), limit = 0).isEmpty())
    }
}
