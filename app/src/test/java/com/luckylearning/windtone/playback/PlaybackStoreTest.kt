package com.luckylearning.windtone.playback

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.data.db.MusicDb
import com.luckylearning.windtone.data.db.PlayerStateEntity
import com.luckylearning.windtone.data.db.QueueSongEntity
import com.luckylearning.windtone.data.db.SongEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 播放持久化门面 [PlaybackStore] 的语义回归。
 *
 * 这些用例在 `PlaybackEngine` 直连 DAO 的时代根本写不了（引擎依赖 Application /
 * MediaController，无法在 JVM 里拼出来）。抽出仓库后，播放域的落库语义可以在这里
 * 用真实 Room（Robolectric + 内存库）独立验证：
 *  - 记播放次数、结算收听时长（含小时桶）；
 *  - 队列 / 播放态 / 进度的往返；
 *  - 曲库快照只含可见歌、歌词偏移落库。
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackStoreTest {

    private lateinit var context: Context
    private lateinit var db: MusicDb
    private lateinit var store: PlaybackStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MusicDb::class.java).build()
        store = PlaybackStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun newSong(uri: String): Long =
        db.songDao().insert(SongEntity(uri = uri, source = "FOLDER", title = uri.substringAfterLast('/')))

    @Test
    fun recordPlayCreatesRowAndBumpsCount() = runBlocking {
        val id = newSong("u1")
        store.recordPlay(id, 100L)
        store.recordPlay(id, 200L)

        val state = db.songStateDao().bySongId(id)
            ?: error("recordPlay 必须先 ensure 再 bump，否则未收藏的歌写不进统计表")
        assertEquals("两次播放应累计为 2", 2, state.playCount)
        assertEquals("最后播放时间取最后一次", 200L, state.lastPlayedAt)
    }

    @Test
    fun addListenTimeAccumulatesAndWritesHourBucket() = runBlocking {
        val id = newSong("u1")
        val hour = 5_000L
        store.addListenTime(id, 3_000L, hour, 1L)
        store.addListenTime(id, 2_000L, hour, 2L)

        assertEquals("每曲累计收听时长", 5_000L, db.songStateDao().bySongId(id)!!.playedMs)
        val bucket = db.listenLogDao().since(hour).single()
        assertEquals("小时桶序号", hour, bucket.hourEpoch)
        assertEquals("小时桶累计", 5_000L, bucket.ms)
    }

    @Test
    fun queueAndStateRoundTrip() = runBlocking {
        val a = newSong("u1")
        val b = newSong("u2")
        store.saveQueueAndState(
            listOf(QueueSongEntity(position = 0, songId = b), QueueSongEntity(position = 1, songId = a)),
            PlayerStateEntity(
                id = 1,
                currentSongId = b,
                positionMs = 1_234L,
                shuffle = true,
                repeatMode = 2,
                updatedAt = 9L,
            ),
        )

        assertEquals("队列顺序必须按 position 还原", listOf(b, a), store.loadQueueSongIds())
        val state = store.loadPlayerState() ?: error("播放态应已落库")
        assertEquals(b, state.currentSongId)
        assertEquals(1_234L, state.positionMs)
        assertTrue(state.shuffle)
        assertEquals(2, state.repeatMode)
    }

    @Test
    fun updatePositionOnlyTouchesProgress() = runBlocking {
        val id = newSong("u1")
        store.saveState(PlayerStateEntity(id = 1, currentSongId = id, positionMs = 1L))

        store.updatePosition(999L, 42L)

        val state = store.loadPlayerState()!!
        assertEquals("只推进进度", 999L, state.positionMs)
        assertEquals("当前曲不受影响", id, state.currentSongId)
    }

    @Test
    fun loadVisibleSongsSkipsMissing() = runBlocking {
        newSong("u1")
        db.songDao().insert(SongEntity(uri = "u2", source = "FOLDER", title = "u2", missingSince = 5L))

        val songs = store.loadVisibleSongs()

        assertEquals("软删的歌不应进入曲库快照", 1, songs.size)
        assertEquals("u1", songs.single().uri.toString())
    }

    @Test
    fun setLyricOffsetPersists() = runBlocking {
        val id = newSong("u1")
        store.setLyricOffset(id, -350, 7L)

        assertEquals("歌词偏移应能以负数落库", -350, db.songStateDao().lyricOffsetOf(id))
    }
}
