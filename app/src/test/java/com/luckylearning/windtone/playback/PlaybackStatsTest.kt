package com.luckylearning.windtone.playback

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.data.SongIndex
import com.luckylearning.windtone.data.db.MusicDb
import com.luckylearning.windtone.data.db.SongEntity
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 播放统计的去重规则回归。
 *
 * 这条规则以前埋在 `PlaybackEngine` 的私有方法里，只能靠人读代码；抽成
 * [PlaybackStats] 后可以直接验证：同一首歌 15 秒内的重复切换不算新播放
 * （`replaceMediaItem` 回填封面也会触发 transition，不判别会把每首歌多计几次）。
 *
 * 也覆盖「索引还没跟上刚入库的在线曲」时，播放次数和最近播放不能写丢。
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackStatsTest {

    private lateinit var db: MusicDb
    private lateinit var scope: CoroutineScope
    private lateinit var index: SongIndex
    private lateinit var stats: PlaybackStats

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDb::class.java)
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        index = SongIndex()
        stats = PlaybackStats(PlaybackStore(db), index, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    @Test
    fun repeatedTransitionWithin15sIsNotCountedAgain() {
        assertTrue("首次切到这首歌应计数", stats.shouldCountPlay("a"))
        assertFalse("15 秒内重复切回同一首不应重复计数", stats.shouldCountPlay("a"))
        assertTrue("换一首应计数", stats.shouldCountPlay("b"))
    }

    @Test
    fun remember后recordPlay会写入播放次数和最近播放() = runBlocking {
        val songId = insertOnline("lx:kw:1", "在线一号")
        index.remember(listOf(onlineSong("lx:kw:1", "在线一号", songId)))
        stats.recordPlay("lx:kw:1")
        drain()
        val state = db.songStateDao().bySongId(songId)!!
        assertEquals(1, state.playCount)
        assertTrue("应记下最近播放时间", state.lastPlayedAt > 0L)
    }

    @Test
    fun 索引未命中但库里有歌时仍写入() = runBlocking {
        val songId = insertOnline("lx:kw:2", "在线二号")
        assertNull("未 remember 时索引应没有这首", index.idOf("lx:kw:2"))
        stats.recordPlay("lx:kw:2")
        drain()
        val state = db.songStateDao().bySongId(songId)!!
        assertEquals(1, state.playCount)
        assertTrue(state.lastPlayedAt > 0L)
        assertEquals(songId, index.idOf("lx:kw:2"))
        assertEquals(SongSource.ONLINE, index.sourceOf(songId))
    }

    @Test
    fun 库里和索引都没有时不写入() = runBlocking {
        stats.recordPlay("lx:kw:missing")
        drain()
        assertTrue(db.songStateDao().all().isEmpty())
    }

    private suspend fun insertOnline(uri: String, title: String): Long =
        db.songDao().insertAll(
            listOf(SongEntity(uri = uri, source = SongSource.ONLINE.name, title = title)),
        ).first()

    private fun onlineSong(uri: String, title: String, dbId: Long) = Song(
        id = 0L,
        uri = Uri.parse(uri),
        title = title,
        artist = null,
        album = null,
        source = SongSource.ONLINE,
        dbId = dbId,
    )

    private suspend fun drain() {
        scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
    }
}
