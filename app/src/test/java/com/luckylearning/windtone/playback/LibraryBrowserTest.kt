package com.luckylearning.windtone.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.data.db.MusicDb
import com.luckylearning.windtone.data.db.PlaylistEntity
import com.luckylearning.windtone.data.db.PlaylistSongEntity
import com.luckylearning.windtone.data.db.SongEntity
import com.luckylearning.windtone.data.model.Song
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
 * 车机浏览树的回归（批次 9.1 遗留：车机媒体面板看不到歌单层级）。
 *
 * 为什么值得单独测：这份树**没有任何界面能看见**。手机端的曲库列表、
 * 抽屉、搜索全部走另一条路，车机面板的树写错了在手机上完全无感 ——
 * 要么车机上少一个歌单，要么点进去顺序乱了，要么点了「播放」不出声。
 * 这三类都发生在用户车里，开发者自己不一定第一时间碰到，所以钉死在下测。
 *
 * 覆盖三层：
 *  ① id 方案（`wt:` 分类节点 vs 音频 URI）——树能不能被正确下钻全靠它；
 *  ② 节点形状（可下钻 / 可播放 / 计数 / 时长）；
 *  ③ 真实 Room 上的取数（顺序、分页、过滤、展开）。
 */
@RunWith(RobolectricTestRunner::class)
class LibraryBrowserTest {

    private lateinit var context: Context
    private lateinit var db: MusicDb
    private lateinit var browser: LibraryBrowser

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MusicDb::class.java).build()
        browser = LibraryBrowser(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------ ① id 方案

    /**
     * 分类节点与歌曲节点必须能被区分开：
     * 歌曲节点的 mediaId 就是音频 URI，所以**任何**音频 URI 都不能落进 `wt:` 前缀。
     */
    @Test
    fun idSchemeDistinguishesFoldersFromSongs() {
        assertEquals("wt:pl:7", BrowseIds.playlist(7))
        assertEquals(7L, BrowseIds.playlistIdOf("wt:pl:7"))

        // 三个虚拟歌单不是「用户歌单」，不能被解析出主键
        assertNull(BrowseIds.playlistIdOf(BrowseIds.FAVORITES))
        assertNull(BrowseIds.playlistIdOf(BrowseIds.RECENT))
        assertNull(BrowseIds.playlistIdOf(BrowseIds.MOST_PLAYED))
        // 歌曲节点（URI）解析不出歌单主键
        assertNull(BrowseIds.playlistIdOf("content://media/external/audio/media/9"))

        assertTrue(BrowseIds.isLibraryId(BrowseIds.ROOT))
        assertTrue(BrowseIds.isLibraryId(BrowseIds.playlist(1)))
        assertFalse(BrowseIds.isLibraryId("content://media/external/audio/media/9"))
        assertFalse(BrowseIds.isLibraryId("/storage/emulated/0/Music/a.flac"))
        assertFalse(BrowseIds.isLibraryId(null))
    }

    // ------------------------------------------------------------ ② 节点形状

    @Test
    fun rootNodeIsBrowsableButNotPlayable() {
        val root = browseRootItem()
        assertEquals(BrowseIds.ROOT, root.mediaId)
        assertEquals(true, root.mediaMetadata.isBrowsable)
        assertEquals(false, root.mediaMetadata.isPlayable)
    }

    @Test
    fun folderNodeCarriesCountAndIsPlayableAsWhole() {
        val node = browseFolderItem(BrowseIds.playlist(3), "通勤", 12)
        assertEquals("通勤", node.mediaMetadata.title)
        assertEquals("12 首", node.mediaMetadata.subtitle)
        assertEquals(true, node.mediaMetadata.isPlayable)
    }

    @Test
    fun songNodeKeepsUriAsMediaIdAndCarriesDuration() {
        val song = Song(
            id = 0L,
            uri = Uri.parse("content://media/external/audio/media/42"),
            title = "夜航",
            artist = "潮汐",
            album = "潮汐",
            durationMs = 214_000L,
        )
        val item = browseSongItem(song)

        // mediaId 必须是 URI 本身：车机把这一项交回来时，全靠它把播放地址补回来
        assertEquals("content://media/external/audio/media/42", item.mediaId)
        assertEquals("夜航", item.mediaMetadata.title)
        assertEquals("潮汐", item.mediaMetadata.artist)
        assertEquals(214_000L, item.mediaMetadata.durationMs ?: -1L)
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, item.mediaMetadata.mediaType)
        assertEquals(true, item.mediaMetadata.isPlayable)
        assertEquals(false, item.mediaMetadata.isBrowsable)
    }

    // ------------------------------------------------------------ ③ 真实取数

    @Test
    fun rootListsThreeVirtualPlaylistsBeforeUserPlaylists() = runBlocking {
        val a = newSong("u/a", "a")
        val b = newSong("u/b", "b")
        val c = newSong("u/c", "c")
        favorite(a, 100L)

        val p1 = db.playlistDao().insert(PlaylistEntity(name = "通勤", createdAt = 1L))
        db.playlistDao().insertSong(PlaylistSongEntity(p1, a, 0, 1L))
        db.playlistDao().insertSong(PlaylistSongEntity(p1, b, 1, 1L))
        val p2 = db.playlistDao().insert(PlaylistEntity(name = "睡前", createdAt = 2L))
        db.playlistDao().insertSong(PlaylistSongEntity(p2, c, 0, 1L))

        val nodes = browser.childrenOf(BrowseIds.ROOT, 0, Int.MAX_VALUE)
        // 三个虚拟歌单固定在最前，用户歌单按 createdAt 倒序（与 App 内歌单列表一致）
        assertEquals(
            listOf("我的收藏", "最近播放", "最常播放", "睡前", "通勤"),
            nodes.map { it.mediaMetadata.title },
        )
        // 虚拟歌单要显示真实数量，用户歌单显示关联行数
        assertEquals(
            listOf("1 首", "0 首", "0 首", "1 首", "2 首"),
            nodes.map { it.mediaMetadata.subtitle },
        )
    }

    @Test
    fun playlistChildrenFollowStoredPosition() = runBlocking {
        val a = newSong("u/a", "a")
        val b = newSong("u/b", "b")
        val c = newSong("u/c", "c")
        val p = db.playlistDao().insert(PlaylistEntity(name = "顺序", createdAt = 1L))
        // 故意按「c → a → b」落库，验证取回来的是 position 顺序而不是插入顺序
        db.playlistDao().insertSong(PlaylistSongEntity(p, c, 0, 1L))
        db.playlistDao().insertSong(PlaylistSongEntity(p, a, 1, 1L))
        db.playlistDao().insertSong(PlaylistSongEntity(p, b, 2, 1L))

        val titles = browser.childrenOf(BrowseIds.playlist(p), 0, Int.MAX_VALUE)
            .map { it.mediaMetadata.title }
        assertEquals(listOf("c", "a", "b"), titles)
    }

    @Test
    fun favoriteAndRecentAndMostUseTheirOwnOrdering() = runBlocking {
        val old = newSong("u/old", "老歌")
        val mid = newSong("u/mid", "中间")
        val fresh = newSong("u/fresh", "新歌")
        favorite(old, 100L)
        favorite(mid, 200L)
        favorite(fresh, 300L)

        // 最近播放：按 lastPlayedAt 倒序
        bump(old, 10L)
        bump(fresh, 30L)
        bump(mid, 20L)
        assertEquals(
            listOf("新歌", "中间", "老歌"),
            browser.childrenOf(BrowseIds.RECENT, 0, Int.MAX_VALUE).map { it.mediaMetadata.title },
        )

        // 最常播放：fresh 播 3 次、mid 2 次、old 1 次
        bump(fresh, 31L)
        bump(fresh, 32L)
        bump(mid, 21L)
        assertEquals(
            listOf("新歌", "中间", "老歌"),
            browser.childrenOf(BrowseIds.MOST_PLAYED, 0, Int.MAX_VALUE).map { it.mediaMetadata.title },
        )

        // 收藏：同样按 lastPlayedAt 倒序（与 App 内虚拟歌单口径一致）
        assertEquals(
            listOf("新歌", "中间", "老歌"),
            browser.childrenOf(BrowseIds.FAVORITES, 0, Int.MAX_VALUE).map { it.mediaMetadata.title },
        )
    }

    @Test
    fun missingAndExcludedSongsStayOutOfTheTree() = runBlocking {
        val ok = newSong("u/ok", "正常")
        val gone = newSong("u/gone", "文件没了")
        val out = newSong("u/out", "已移出曲库")
        val p = db.playlistDao().insert(PlaylistEntity(name = "混合", createdAt = 1L))
        db.playlistDao().insertSong(PlaylistSongEntity(p, ok, 0, 1L))
        db.playlistDao().insertSong(PlaylistSongEntity(p, gone, 1, 1L))
        db.playlistDao().insertSong(PlaylistSongEntity(p, out, 2, 1L))

        // ok 是本轮扫到的（先把自己标记成「本轮见过」），不该被软删
        db.songDao().update(db.songDao().byIds(listOf(ok)).single().copy(lastSeenAt = 150L))
        db.songDao().markMissing(scanStart = 100L, now = 200L)
        db.songDao().setExcluded(listOf(out), true)

        val titles = browser.childrenOf(BrowseIds.playlist(p), 0, Int.MAX_VALUE)
            .map { it.mediaMetadata.title }
        assertEquals(listOf("正常"), titles)
    }

    /**
     * 分页的语义是 Media3 定的：`page` 是**页码**（从 0 开始），起点 = page × pageSize。
     * 这条一旦理解错，只有上千首的歌单才会露馅（第二页开始整体错位），所以在这里钉死。
     */
    @Test
    fun pagingFollowsMedia3PageNumberSemantics() = runBlocking {
        val p = db.playlistDao().insert(PlaylistEntity(name = "长单", createdAt = 1L))
        repeat(5) { i ->
            val id = newSong("u/$i", "第${i}首")
            db.playlistDao().insertSong(PlaylistSongEntity(p, id, i, 1L))
        }
        val node = BrowseIds.playlist(p)

        assertEquals(
            listOf("第0首", "第1首"),
            browser.childrenOf(node, page = 0, pageSize = 2).map { it.mediaMetadata.title },
        )
        // 第二页从第 2 首开始（不是第 1 首）
        assertEquals(
            listOf("第2首", "第3首"),
            browser.childrenOf(node, page = 1, pageSize = 2).map { it.mediaMetadata.title },
        )
        // 末页只返回剩余部分
        assertEquals(
            listOf("第4首"),
            browser.childrenOf(node, page = 2, pageSize = 2).map { it.mediaMetadata.title },
        )
        // 越界返回空，不抛异常
        assertEquals(emptyList<MediaItem>(), browser.childrenOf(node, page = 99, pageSize = 2))
        // 防御：非法 pageSize 不回全部（否则「翻到最后一页」会变成「一次给全」）
        assertEquals(emptyList<MediaItem>(), browser.childrenOf(node, page = 0, pageSize = 0))
        // 不分页的约定值
        assertEquals(5, browser.childrenOf(node, page = 0, pageSize = Int.MAX_VALUE).size)
    }

    @Test
    fun unknownPlaylistIsGoneNotAnError() = runBlocking {
        // 车机拿着上一次的树点了进来，而歌单已经被删——正常时序，不该崩也不该报错
        assertNull(browser.itemOf(BrowseIds.playlist(9999)))
        assertEquals(emptyList<MediaItem>(), browser.childrenOf(BrowseIds.playlist(9999), 0, 10))
    }

    @Test
    fun itemOfRebuildsSingleNodes() = runBlocking {
        assertEquals(BrowseIds.ROOT, browser.itemOf(BrowseIds.ROOT)?.mediaId)
        assertEquals("我的收藏", browser.itemOf(BrowseIds.FAVORITES)?.mediaMetadata?.title)
        val p = db.playlistDao().insert(PlaylistEntity(name = "通勤", createdAt = 1L))
        newSong("u/x", "x").let { db.playlistDao().insertSong(PlaylistSongEntity(p, it, 0, 1L)) }
        val node = browser.itemOf(BrowseIds.playlist(p))
        assertEquals("通勤", node?.mediaMetadata?.title)
        assertEquals("1 首", node?.mediaMetadata?.subtitle)
    }

    @Test
    fun resolveExpandsFolderAndRestoresUri() = runBlocking {
        val a = newSong("content://media/external/audio/media/1", "甲")
        val b = newSong("content://media/external/audio/media/2", "乙")
        val p = db.playlistDao().insert(PlaylistEntity(name = "两首", createdAt = 1L))
        db.playlistDao().insertSong(PlaylistSongEntity(p, a, 0, 1L))
        db.playlistDao().insertSong(PlaylistSongEntity(p, b, 1, 1L))

        // ① 分类节点 → 展开成歌曲
        val expanded = browser.resolve(listOf(browseFolderItem(BrowseIds.playlist(p), "两首", 2)))
        assertEquals(listOf("甲", "乙"), expanded.map { it.mediaMetadata.title })
        assertTrue(expanded.all { it.localConfiguration != null })

        // ② 歌曲节点 → 跨 Binder 丢掉的 URI 要补回来（mediaId 就是 URI）
        val stripped = MediaItem.Builder()
            .setMediaId("content://media/external/audio/media/1")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("甲").build())
            .build()
        assertNull(stripped.localConfiguration)
        val restored = browser.resolve(listOf(stripped)).single()
        assertEquals("content://media/external/audio/media/1", restored.localConfiguration?.uri.toString())

        // ③ 既不是分类节点、也不像 uri 的项原样返回（交给 Media3 自己报错，这里不猜）
        val weird = MediaItem.Builder().setMediaId("怪东西").build()
        assertEquals("怪东西", browser.resolve(listOf(weird)).single().mediaId)
    }

    // ------------------------------------------------------------ 造数

    private suspend fun newSong(uri: String, title: String, artist: String? = null, durationMs: Long = 0L): Long =
        db.songDao().insert(
            SongEntity(
                uri = uri,
                source = "FOLDER",
                title = title,
                artist = artist,
                durationMs = durationMs,
                displayName = "$title.mp3",
            ),
        )

    private suspend fun favorite(songId: Long, now: Long) {
        db.songStateDao().ensure(songId, now)
        db.songStateDao().setFavorite(songId, true, now)
    }

    private suspend fun bump(songId: Long, now: Long) {
        db.songStateDao().ensure(songId, now)
        db.songStateDao().bump(songId, now)
    }
}
