package com.luckylearning.windtone.data.online

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.data.db.MusicDb
import com.luckylearning.windtone.data.db.SongEntity
import com.luckylearning.windtone.data.model.SongSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 「最近播放」的场景隔离：
 * 本地只要本地曲，洛雪只要在线曲，两边共用 `song_state` 但不能互相混入。
 */
@RunWith(RobolectricTestRunner::class)
class OnlineRecentTest {

    private lateinit var context: Context
    private lateinit var db: MusicDb

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MusicDb::class.java)
            .allowMainThreadQueries()
            .build()
        MusicDb.testOverride = db
    }

    @After
    fun tearDown() {
        MusicDb.testOverride = null
        db.close()
    }

    @Test
    fun `只取在线曲且按最近播放倒序`() = runBlocking {
        val ids = db.songDao().insertAll(
            listOf(
                SongEntity(uri = "content://local/1", source = "MEDIA_STORE", title = "本地歌"),
                SongEntity(uri = "lx:kw:1", source = SongSource.ONLINE.name, title = "在线一号"),
                SongEntity(uri = "lx:kw:2", source = SongSource.ONLINE.name, title = "在线二号"),
            ),
        )
        val state = db.songStateDao()
        state.ensure(ids[0], 0)
        state.bump(ids[0], 900)   // 本地最近
        state.ensure(ids[1], 0)
        state.bump(ids[1], 100)
        state.ensure(ids[2], 0)
        state.bump(ids[2], 200)

        val recent = OnlineRecent.songs(context)

        assertEquals("只应出现在线曲", listOf("在线二号", "在线一号"), recent.map { it.title })
        assertEquals(
            "本地场景应排除在线曲",
            listOf(ids[0]),
            state.recentIdsExcludingSource(SongSource.ONLINE.name, 100),
        )
    }
}
