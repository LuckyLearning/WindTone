package com.luckylearning.windtone.state

import android.net.Uri
import com.luckylearning.windtone.data.db.SongStateEntity
import com.luckylearning.windtone.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 智能歌单纯函数：[neverPlayedSongs] / [dormantSongs]。
 *
 * 钉住口径：
 * - 从未播放 = 曲库存在、playCount=0（或无 song_state 行）；dbId=0（扫描中未落库）不算；
 * - 很久没听 = 播过、lastPlayedAt 距今 ≥ DORMANT_DAYS 天；从未播的不混进来（归「从未播放」）；
 * - 很久没听按 lastPlayedAt 升序（最久没听的排前面）。
 */
@RunWith(RobolectricTestRunner::class)
class SmartPlaylistTest {

    private fun song(id: Long, dbId: Long = id) = Song(
        id = id,
        uri = Uri.parse("content://song/$id"),
        title = "g$id",
        artist = "a",
        album = "b",
        dbId = dbId,
    )

    private fun state(songId: Long, playCount: Int = 0, lastPlayedAt: Long = 0L) =
        SongStateEntity(songId = songId).copy(playCount = playCount, lastPlayedAt = lastPlayedAt)

    private val now = 1_800_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    @Test
    fun neverPlayedListsSongsWithoutPlayCount() {
        val songs = listOf(song(1), song(2), song(3))
        val states = listOf(
            state(1, playCount = 5, lastPlayedAt = now - day),
            state(2), // 有行但没播过
            // 3 完全没有 song_state 行
        )
        assertEquals(listOf("g2", "g3"), neverPlayedSongs(songs, states).map { it.title })
    }

    @Test
    fun neverPlayedSkipsUnsavedRows() {
        val unsaved = song(9, dbId = 0L)
        assertTrue(neverPlayedSongs(listOf(unsaved), emptyList()).isEmpty())
    }

    @Test
    fun dormantListsLongUnplayedSortedByOldestFirst() {
        val fresh = song(1)      // 1 天前听过 → 不算
        val mid = song(2)        // 40 天前 → 算
        val oldest = song(3)     // 90 天前 → 算，排最前
        val never = song(4)      // 没播过 → 不算（归「从未播放」）
        val states = listOf(
            state(1, playCount = 1, lastPlayedAt = now - day),
            state(2, playCount = 3, lastPlayedAt = now - 40 * day),
            state(3, playCount = 2, lastPlayedAt = now - 90 * day),
            state(4), // playCount=0
        )
        assertEquals(
            listOf("g3", "g2"),
            dormantSongs(listOf(fresh, mid, oldest, never), states, now).map { it.title },
        )
    }

    @Test
    fun dormantBoundaryIsInclusiveAtCutoff() {
        val edge = song(1)
        val states = listOf(state(1, playCount = 1, lastPlayedAt = now - VirtualPlaylist.DORMANT_DAYS * day))
        // 恰好等于天数边界（lastPlayedAt == cutoff）→ in 1..cutoff 成立，应入选
        assertEquals(listOf("g1"), dormantSongs(listOf(edge), states, now).map { it.title })
    }

    @Test
    fun emptyOrNonPositiveArgs() {
        assertTrue(neverPlayedSongs(emptyList(), emptyList()).isEmpty())
        assertTrue(neverPlayedSongs(listOf(song(1)), emptyList(), limit = 0).isEmpty())
        assertTrue(dormantSongs(emptyList(), emptyList(), now).isEmpty())
        assertTrue(dormantSongs(listOf(song(1)), emptyList(), now, days = 0).isEmpty())
    }

    @Test
    fun respectsLimit() {
        val songs = (1L..5L).map { song(it) }
        val states = (1L..5L).map { state(it, playCount = 1, lastPlayedAt = now - it * 10 * day) }
        assertEquals(listOf("g5", "g4", "g3"), dormantSongs(songs, states, now, limit = 3).map { it.title })
    }
}
