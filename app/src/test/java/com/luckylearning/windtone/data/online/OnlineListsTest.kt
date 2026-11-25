package com.luckylearning.windtone.data.online

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.data.db.MusicDb
import com.luckylearning.windtone.data.db.PlaylistEntity
import com.luckylearning.windtone.data.db.PlaylistSongEntity
import com.luckylearning.windtone.data.db.SongEntity
import com.luckylearning.windtone.data.online.source.OnlineTrack
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 在线「我的列表」：收进即幂等、同名合并、以及与本地列表的隔离。
 *
 * 这组行为的起因是一次真机反馈：歌单详情点「收进我的列表」后按钮没有任何变化，
 * 用户以为没生效又点了一次 → 列表里出现两条同名的 59 首。
 * 所以这里锁三件事：
 *  1. 同一歌单重复保存只有一条列表；
 *  2. 历史上已经产生的同名重复会被 [OnlineLists.mergeDuplicates] 合并；
 *  3. 本地自建列表不会被算进在线列表（不然本地模式会凭空少一份列表）。
 */
@RunWith(RobolectricTestRunner::class)
class OnlineListsTest {

    private lateinit var context: Context
    private lateinit var db: MusicDb

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearSavedSheets()
        db = Room.inMemoryDatabaseBuilder(context, MusicDb::class.java)
            .allowMainThreadQueries()
            .build()
        MusicDb.testOverride = db
    }

    @After
    fun tearDown() {
        MusicDb.testOverride = null
        db.close()
        clearSavedSheets()
    }

    private fun clearSavedSheets() {
        context.getSharedPreferences("online_saved_sheets", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun sheet(name: String = "准点下班", id: String = "s1") =
        OnlineSonglists.Sheet(source = "kw", id = id, name = name)

    private fun track(songId: String) = OnlineTrack(
        sourceId = "kw",
        songId = songId,
        title = "歌 $songId",
        artist = "测试歌手",
    )

    // ---------------------------------------------------------------- 幂等

    @Test
    fun `同一个歌单点两次只有一条列表`() = runBlocking {
        val s = sheet()
        val tracks = listOf(track("1"), track("2"), track("3"))

        val first = OnlineLists.saveSheet(context, s, tracks)
        val second = OnlineLists.saveSheet(context, s, tracks)

        assertTrue("第一次应新建", first is OnlineLists.SaveResult.Created)
        assertTrue("第二次应更新同一条", second is OnlineLists.SaveResult.Updated)
        assertEquals(
            "两次保存必须落在同一个列表 id 上",
            (first as OnlineLists.SaveResult.Created).playlistId,
            (second as OnlineLists.SaveResult.Updated).playlistId,
        )

        val lists = OnlineLists.summaries(context)
        assertEquals("只该有一条列表", 1, lists.size)
        assertEquals("曲目不该因为重复保存翻倍", 3, lists[0].count)
    }

    @Test
    fun `歌单更新后再次收进只补新增曲目`() = runBlocking {
        val s = sheet()
        OnlineLists.saveSheet(context, s, listOf(track("1"), track("2")))
        OnlineLists.saveSheet(context, s, listOf(track("1"), track("2"), track("3")))

        val lists = OnlineLists.summaries(context)
        assertEquals(1, lists.size)
        assertEquals("原有曲目不重复，新曲目补进来", 3, lists[0].count)
    }

    // ---------------------------------------------------------------- 历史重复

    @Test
    fun `同名在线列表会被合并成一条`() = runBlocking {
        val s = sheet()
        // 模拟旧版本「点两次」留下的两条同名列表
        val songs = OnlineLibrary(context).upsertAll(listOf(track("1"), track("2")))
        val dao = db.playlistDao()
        val now = System.currentTimeMillis()
        val a = dao.insert(PlaylistEntity(name = "准点下班", createdAt = now - 1000))
        val b = dao.insert(PlaylistEntity(name = "准点下班", createdAt = now))
        dao.insertSong(PlaylistSongEntity(a, songs[0].dbId!!, 0, now))
        dao.insertSong(PlaylistSongEntity(b, songs[0].dbId!!, 0, now))
        dao.insertSong(PlaylistSongEntity(b, songs[1].dbId!!, 1, now))

        val merged = OnlineLists.mergeDuplicates(context)

        assertEquals("应合并掉 1 条", 1, merged)
        val lists = OnlineLists.summaries(context)
        assertEquals(1, lists.size)
        assertEquals("两首重合去重后仍是 2 首", 2, lists[0].count)
        assertEquals("保留最早创建的那条", a, lists[0].id)
        // 映射也指向保留的那条，详情页的「已在我的列表」才认得出来
        assertTrue(OnlineLists.isSaved(context, s))
    }

    @Test
    fun `合并是幂等的`() = runBlocking {
        val songs = OnlineLibrary(context).upsertAll(listOf(track("1")))
        val dao = db.playlistDao()
        val now = System.currentTimeMillis()
        val a = dao.insert(PlaylistEntity(name = "同名", createdAt = now - 1))
        val b = dao.insert(PlaylistEntity(name = "同名", createdAt = now))
        dao.insertSong(PlaylistSongEntity(a, songs[0].dbId!!, 0, now))
        dao.insertSong(PlaylistSongEntity(b, songs[0].dbId!!, 0, now))

        assertEquals(1, OnlineLists.mergeDuplicates(context))
        assertEquals("没有重复时再跑一次不该有任何变化", 0, OnlineLists.mergeDuplicates(context))
    }

    // ---------------------------------------------------------------- 与本地列表隔离

    @Test
    fun `本地自建列表不算在线列表`() = runBlocking {
        val localId = db.songDao().insert(
            SongEntity(uri = "content://media/external/audio/media/9", source = "MEDIA_STORE", title = "本地歌"),
        )
        val dao = db.playlistDao()
        val pid = dao.insert(PlaylistEntity(name = "我的本地列表", createdAt = 1L))
        dao.insertSong(PlaylistSongEntity(pid, localId, 0, 1L))

        val lists = OnlineLists.summaries(context)
        assertEquals("没有在线曲目的列表不该出现在洛雪「我的列表」里", 0, lists.size)
    }

    @Test
    fun `同名但本地自建的列表不参与合并`() = runBlocking {
        val localId = db.songDao().insert(
            SongEntity(uri = "content://media/external/audio/media/7", source = "MEDIA_STORE", title = "本地歌"),
        )
        val dao = db.playlistDao()
        val now = System.currentTimeMillis()
        val local = dao.insert(PlaylistEntity(name = "同名", createdAt = now - 1))
        dao.insertSong(PlaylistSongEntity(local, localId, 0, now))

        val songs = OnlineLibrary(context).upsertAll(listOf(track("1")))
        val online = dao.insert(PlaylistEntity(name = "同名", createdAt = now))
        dao.insertSong(PlaylistSongEntity(online, songs[0].dbId!!, 0, now))

        assertEquals("一本地一在线，不该被合并", 0, OnlineLists.mergeDuplicates(context))
        assertEquals("两条都还在", 2, dao.all().size)
    }

    // ---------------------------------------------------------------- 认领

    @Test
    fun `映射丢失时按同名认领而不是再建一条`() = runBlocking {
        val s = sheet()
        val songs = OnlineLibrary(context).upsertAll(listOf(track("1")))
        val dao = db.playlistDao()
        val existing = dao.insert(PlaylistEntity(name = "准点下班", createdAt = 1L))
        dao.insertSong(PlaylistSongEntity(existing, songs[0].dbId!!, 0, 1L))
        // 此时 SavedSheetStore 是空的（模拟升级后老列表没有映射）

        val result = OnlineLists.saveSheet(context, s, listOf(track("1"), track("2")))

        assertTrue("应认领老列表而不是新建", result is OnlineLists.SaveResult.Updated)
        assertEquals(existing, (result as OnlineLists.SaveResult.Updated).playlistId)
        assertEquals(1, dao.all().size)
        assertEquals(2, OnlineLists.songs(context, existing).size)
    }
}
