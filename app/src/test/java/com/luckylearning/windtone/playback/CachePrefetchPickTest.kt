package com.luckylearning.windtone.playback

import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 预取选曲的回归测试（v1.3.12）。
 *
 * 洛雪在线曲的 Song.id 恒为 0（`MusicSource.toSong(id=0L)` → `toEntity.mediaStoreId=0`
 * → `SongEntity.toSong().id=mediaStoreId=0`）。若按 id 去重，当前曲 + 两首后续
 * 会全部撞成一首，且切歌后批次 id 仍是 [0]，ticker 误判「同一批」不再规划。
 */
@RunWith(RobolectricTestRunner::class)
class CachePrefetchPickTest {

    /**
     * 播放队列里的在线曲是补链后的 http(s) 地址，身份放在 fragment（lx:kw:1）。
     * Song.id 仍为 0（mediaStoreId 落库就是 0，读回也是 0）。
     */
    private fun online(key: String) = Song(
        id = 0L,
        uri = Uri.parse("https://example.com/song.flac#$key"),
        title = key,
        artist = null,
        album = null,
        source = SongSource.ONLINE,
    )

    @Test
    fun `在线曲 id 全为零时仍按 stableKey 选出当前曲与后续`() {
        val current = online("lx:kw:1")
        val upcoming = listOf(online("lx:kw:2"), online("lx:kw:3"), online("lx:kw:4"))

        val picked = CachePrefetcher.pickSongs(current, upcoming, paused = true)

        assertEquals(
            listOf("lx:kw:1", "lx:kw:2"),
            picked.map { it.stableKey },
        )
        // 换一首后续时批次身份必须变化，否则 ticker 会误判「同一批」不再规划
        val next = CachePrefetcher.pickSongs(current, upcoming.drop(1), paused = true)
        assertEquals(listOf("lx:kw:1", "lx:kw:3"), next.map { it.stableKey })
    }
}
