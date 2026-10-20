package com.luckylearning.windtone.playback

import android.net.Uri
import com.luckylearning.windtone.data.db.PlayerStateEntity
import com.luckylearning.windtone.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 断点续播决策的穷举回归。
 *
 * 这些分支以前埋在一个 `suspend` 方法里，只能靠真机复现；抽成 [planRestore] 后
 * 可以在 JVM 里把「曲库空 / 无播放态 / 歌已删 / 队列部分失效 / 正常恢复」全部跑一遍。
 */
@RunWith(RobolectricTestRunner::class)
class RestorePlanTest {

    private fun song(id: Long) = Song(
        id = id,
        uri = Uri.parse("content://test/$id"),
        title = "song$id",
        artist = null,
        album = null,
        durationMs = 180_000L,
        dbId = id,
    )

    private val library = listOf(song(1), song(2), song(3))

    @Test
    fun emptyLibraryWaitsForNextCall() {
        assertEquals(RestorePlan.WaitForLibrary, planRestore(emptyList(), emptyList(), null))
    }

    @Test
    fun noPlayerStateMeansNothingToRestore() {
        assertEquals(RestorePlan.NothingToRestore, planRestore(library, emptyList(), null))
    }

    @Test
    fun currentSongGoneFromLibraryIsStale() {
        val state = PlayerStateEntity(currentSongId = 99L)
        assertEquals(RestorePlan.StaleState, planRestore(library, emptyList(), state))
    }

    @Test
    fun persistedQueueRestoresInOrderAndLocatesCurrent() {
        val state = PlayerStateEntity(currentSongId = 1L, positionMs = 1_234L)

        val plan = planRestore(library, listOf(3L, 1L, 2L), state) as RestorePlan.Restore

        assertEquals(listOf(3L, 1L, 2L), plan.songs.map { it.dbId })
        assertEquals("当前曲应定位到队列里的第 2 项", 1, plan.index)
        assertEquals(1_234L, plan.positionMs)
        assertTrue(plan.fromQueue)
    }

    @Test
    fun unusableQueueFallsBackToSingleSongFromWholeLibrary() {
        val state = PlayerStateEntity(currentSongId = 2L)

        val plan = planRestore(library, listOf(99L), state) as RestorePlan.Restore

        assertEquals("队列全失效时应退化为整库当队列", library.map { it.dbId }, plan.songs.map { it.dbId })
        assertEquals(1, plan.index)
        assertFalse(plan.fromQueue)
    }

    @Test
    fun negativePositionIsClampedToZero() {
        val state = PlayerStateEntity(currentSongId = 1L, positionMs = -50L)

        val plan = planRestore(library, emptyList(), state) as RestorePlan.Restore

        assertEquals(0L, plan.positionMs)
    }
}
