package com.luckylearning.windtone.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 曲库数据层语义回归：级联删除、目录移除不丢歌、增量扫描的软删/恢复、统计汇总。
 *
 * 这些是 v4 重构里"最容易悄悄写错"的几条 —— 比如删一首歌忘了清歌单、移除目录把歌一起删了。
 */
@RunWith(RobolectricTestRunner::class)
class LibraryDbTest {

    private lateinit var context: Context
    private lateinit var db: MusicDb

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MusicDb::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun newFolder(docId: String = "primary:Music"): Long =
        db.folderDao().insert(
            FolderEntity(treeUri = "content://tree/$docId", docId = docId, name = "Music", fullPath = "Music", sort = 0, addedAt = 1L),
        )

    private suspend fun newSong(uri: String, folderId: Long? = null, folderPath: String? = null): Long =
        db.songDao().insert(
            SongEntity(uri = uri, source = "FOLDER", title = uri.substringAfterLast('/'), folderId = folderId, folderPath = folderPath),
        )

    // ------------------------------------------------------------------ 级联

    @Test
    fun deletingSongCascadesToStatePlaylistAndQueue() = runBlocking {
        val songId = newSong("u1")
        db.songStateDao().ensure(songId, 10L)
        db.songStateDao().setFavorite(songId, true, 10L)

        val playlistId = db.playlistDao().insert(PlaylistEntity(name = "p", createdAt = 1L))
        db.playlistDao().insertSong(PlaylistSongEntity(playlistId, songId, 0, 1L))

        db.queueDao().replaceAll(listOf(QueueSongEntity(position = 0, songId = songId)))
        db.playerStateDao().put(PlayerStateEntity(id = 1, currentSongId = songId, positionMs = 5L))

        assertEquals(1, db.songStateDao().all().size)
        assertEquals(1, db.playlistDao().allSongs().size)
        assertEquals(1, db.queueDao().all().size)

        db.songDao().deleteById(songId)

        assertEquals("歌删了，状态行必须跟着没", 0, db.songStateDao().all().size)
        assertEquals("歌删了，歌单条目必须跟着没", 0, db.playlistDao().allSongs().size)
        assertEquals("歌删了，队列条目必须跟着没", 0, db.queueDao().all().size)
        assertEquals("歌单本身还在", 1, db.playlistDao().all().size)
        // player_state 是单行、不挂外键：currentSongId 需由调用方负责，这里只确认行还在
        assertNotNull(db.playerStateDao().get())
    }

    @Test
    fun removingFolderKeepsSongsButDetachesThem() = runBlocking {
        val folderId = newFolder()
        val keep = newSong("u1", folderId, "primary:Music")
        val other = newSong("u2")

        db.folderDao().deleteByDocIds(listOf("primary:Music"))

        assertEquals(0, db.folderDao().all().size)
        assertEquals("移除目录不等于删歌", 2, db.songDao().all().size)
        assertNull("被移除目录下的歌要解除关联但保留", db.songDao().byUri("u1")!!.folderId)
        assertNull(db.songDao().byUri("u2")!!.folderId)
        assertTrue(db.songDao().visible().map { it.id }.containsAll(listOf(keep, other)))
    }

    @Test
    fun deletingPlaylistRemovesItsEntriesOnly() = runBlocking {
        val songA = newSong("a")
        val songB = newSong("b")
        val p1 = db.playlistDao().insert(PlaylistEntity(name = "p1", createdAt = 1L))
        val p2 = db.playlistDao().insert(PlaylistEntity(name = "p2", createdAt = 2L))
        db.playlistDao().insertSong(PlaylistSongEntity(p1, songA, 0, 1L))
        db.playlistDao().insertSong(PlaylistSongEntity(p2, songA, 0, 1L))
        db.playlistDao().insertSong(PlaylistSongEntity(p2, songB, 1, 1L))

        db.playlistDao().deletePlaylist(p2)

        assertEquals(1, db.playlistDao().all().size)
        assertEquals(1, db.playlistDao().allSongs().size)
        assertEquals(p1, db.playlistDao().allSongs()[0].playlistId)
        assertEquals("歌不受影响", 2, db.songDao().all().size)
    }

    // ------------------------------------------------------------------ 增量扫描

    @Test
    fun incrementalScanMarksMissingAndRevisitsRevive() = runBlocking {
        val a = newSong("a")
        val b = newSong("b")

        // 本轮扫描（scanStart=50）只见到 a：两者的 lastSeenAt 都是 0（< 50）→ 都被标记
        // 先模拟 a 被重新见到
        db.songDao().update(db.songDao().byUri("a")!!.copy(lastSeenAt = 100L))
        val marked = db.songDao().markMissing(scanStart = 50L, now = 999L)

        assertEquals("只有本轮没出现的才标记", 1, marked)
        assertEquals(listOf("a"), db.songDao().visible().map { it.uri })
        assertEquals(1, db.songDao().countMissing())
        assertEquals(999L, db.songDao().byUri("b")!!.missingSince)

        // 文件回来了：清软删标记 → 重新可见，收藏/歌单都没动过
        db.songDao().update(db.songDao().byUri("b")!!.copy(missingSince = 0L, lastSeenAt = 200L))
        assertEquals(2, db.songDao().visible().size)

        // 再扫一轮（scanStart=150）a 没出现 → a 被标记，b 保持可见
        assertEquals(1, db.songDao().markMissing(scanStart = 150L, now = 1_000L))
        assertEquals(listOf("b"), db.songDao().visible().map { it.uri })

        // 真删：只清软删行
        assertEquals(1, db.songDao().deleteMissing())
        assertEquals(1, db.songDao().all().size)
        assertTrue(db.songDao().byUri("b")!!.missingSince == 0L)
        assertNull(db.songDao().byUri("a"))
    }

    @Test
    fun countsByFolderIgnoresMissingSongs() = runBlocking {
        val folderId = newFolder()
        newSong("a", folderId, "primary:Music")
        newSong("b", folderId, "primary:Music")
        val gone = newSong("c", folderId, "primary:Music")
        db.songDao().update(db.songDao().byUri("c")!!.copy(missingSince = 123L))

        val counts = db.songDao().countsByFolder()
        assertEquals(1, counts.size)
        assertEquals(folderId, counts[0].folderId)
        assertEquals("软删的歌不计入目录歌曲数", 2, counts[0].songCount)
        assertEquals(gone, db.songDao().byUri("c")!!.id)
    }

    // ------------------------------------------------------------------ 每曲状态与统计

    @Test
    fun stateMergesOnceAndStatsResetKeepsFavorite() = runBlocking {
        val songId = newSong("a")

        // 连续 ensure 不会插出第二行（历史 bug：只有 UPDATE 没有 INSERT）
        db.songStateDao().ensure(songId, 1L)
        db.songStateDao().ensure(songId, 2L)
        assertEquals(1, db.songStateDao().all().size)

        db.songStateDao().bump(songId, 10L)
        db.songStateDao().addPlayedMs(songId, 500L, 10L)
        db.songStateDao().setFavorite(songId, true, 10L)
        db.songStateDao().setLyricOffset(songId, -1500, 10L)
        db.songStateDao().setOverride(songId, "新标题", "新歌手", null, 10L)

        var row = db.songStateDao().bySongId(songId)!!
        assertEquals(1, row.playCount)
        assertEquals(500L, row.playedMs)
        assertTrue(row.favorite)
        assertEquals(-1500, row.lyricOffsetMs)

        var s = db.songStateDao().summary()
        assertEquals(1, s.listenedSongs)
        assertEquals(1L, s.totalPlays)
        assertEquals(500L, s.totalListenedMs)
        assertEquals(1, s.favorites)

        // 清空统计：次数/时长归零，收藏与标签编辑必须留着
        db.songStateDao().resetStats()
        row = db.songStateDao().bySongId(songId)!!
        assertEquals(0, row.playCount)
        assertEquals(0L, row.playedMs)
        assertTrue("收藏不能被清空统计带走", row.favorite)
        assertEquals("新标题", row.overrideTitle)

        s = db.songStateDao().summary()
        assertEquals(0, s.listenedSongs)
        assertEquals(0L, s.totalPlays)
        assertEquals(1, s.favorites)

        // 清标签覆盖：三列一起回 null，其它不动
        db.songStateDao().clearOverride(songId)
        row = db.songStateDao().bySongId(songId)!!
        assertNull(row.overrideTitle)
        assertNull(row.overrideArtist)
        assertNull(row.overrideAlbum)

        assertEquals(listOf(songId), db.songStateDao().favoriteIds())
    }

    // ------------------------------------------------------------------ 队列与播放态

    @Test
    fun queueReplaceAllKeepsOrderAndPositionRoundTrips() = runBlocking {
        val ids = (1..3).map { newSong("u$it") }
        db.queueDao().replaceAll(ids.mapIndexed { i, id -> QueueSongEntity(position = i, songId = id) })
        assertEquals(ids, db.queueDao().songIds())

        // 整体重写：以新列表为准
        db.queueDao().replaceAll(listOf(QueueSongEntity(position = 0, songId = ids[2])))
        assertEquals(listOf(ids[2]), db.queueDao().songIds())

        db.playerStateDao().put(PlayerStateEntity(id = 1, currentSongId = ids[2], positionMs = 12_345L, shuffle = true, repeatMode = 2, updatedAt = 1L))
        db.playerStateDao().updatePosition(20_000L, 2L)

        val saved = db.playerStateDao().get()!!
        assertEquals(ids[2], saved.currentSongId)
        assertEquals("断点续播位置要写进去", 20_000L, saved.positionMs)
        assertTrue(saved.shuffle)
        assertEquals(2, saved.repeatMode)
    }

    // ------------------------------------------------------------------ 搜索历史

    @Test
    fun searchHistoryBumpsAndTrims() = runBlocking {
        db.searchHistoryDao().add("周杰伦", 1L)
        db.searchHistoryDao().add("陈奕迅", 2L)
        db.searchHistoryDao().add("周杰伦", 3L)

        assertEquals(listOf("周杰伦", "陈奕迅"), db.searchHistoryDao().recent(10))

        (1..15).forEach { db.searchHistoryDao().add("query$it", 100L + it) }
        db.searchHistoryDao().trim(keep = 10)
        assertEquals(10, db.searchHistoryDao().recent(100).size)
        assertEquals("query15", db.searchHistoryDao().recent(10).first())

        db.searchHistoryDao().clear()
        assertEquals(0, db.searchHistoryDao().recent(10).size)
    }
}
