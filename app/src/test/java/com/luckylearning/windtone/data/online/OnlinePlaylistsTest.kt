package com.luckylearning.windtone.data.online

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.data.model.SongSource
import com.luckylearning.windtone.playback.PlaybackConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 在线播放列表的编排层与队列整合（2026-10-21 补）。
 *
 * 抓取走构造参数注入的假实现（[OnlinePlaylists] 的 `fetcher`），
 * 因此这里**一次网络都不连**，却能把 `add` / `refresh` 的真规则钉住：
 *  · 先抓一遍，抓不到就不入库（避免存一堆打不开的死地址）；
 *  · UNSUPPORTED 不入库、不覆盖旧条目；
 *  · 刷新失败时保留旧条目（弱网下旧数据比没有强）。
 *
 * 队列整合那几条直接打到 `PlaybackConnection.toMediaItem` ——
 * 在线列表「解析完只是普通 Song，交给现有 playQueue」是这套设计的立论之本
 * （见 `docs/架构评审-联网播放可行性.md` B 章），接缝处必须有测试看着。
 */
@RunWith(RobolectricTestRunner::class)
class OnlinePlaylistsTest {

    private lateinit var context: Context

    /** 记录抓取请求，便于断言「规范化后的地址才被抓」 */
    private val requested = mutableListOf<String>()

    private fun repo(
        result: (String) -> ParsedPlaylist,
    ): OnlinePlaylists = OnlinePlaylists(context) { url ->
        requested += url
        result(url)
    }

    private fun repoThrowing(t: Throwable): OnlinePlaylists = OnlinePlaylists(context) { url ->
        requested += url
        throw t
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric 每个用例复用同一份 SharedPreferences，先清干净
        context.getSharedPreferences("online_playlists", Context.MODE_PRIVATE)
            .edit().clear().commit()
        requested.clear()
    }

    // ============================================================ 队列整合

    @Test
    fun `toSongs 把条目转成可直接入队的 Song`() {
        val playlist = OnlinePlaylist(
            id = "p1",
            name = "夜航电台",
            url = "https://cdn.example.com/list.m3u",
            format = PlaylistFormat.M3U,
            kind = PlaylistKind.TRACKS,
            entries = listOf(
                PlaylistEntry("晴天", "周杰伦", "https://cdn.example.com/a/1.mp3", 215_000),
                PlaylistEntry("夜航", null, "https://cdn.example.com/a/2.flac", 0),
                PlaylistEntry("Live", "FIP", "https://cdn.example.com/live.m3u8", 0),
            ),
            updatedAt = 1_700_000_000_000L,
        )

        val songs = OnlinePlaylists(context).toSongs(playlist)

        assertEquals(3, songs.size)
        // id 用负数：曲库里的 id 来自 MediaStore（正数），两者永不冲突
        assertEquals(listOf(-1L, -2L, -3L), songs.map { it.id })
        assertTrue("全部必须是负数 id", songs.all { it.id < 0 })
        // stableKey 是界面查找 / 列表 key / 媒体项 id 的依托，必须等于 uri
        assertEquals(
            listOf(
                "https://cdn.example.com/a/1.mp3",
                "https://cdn.example.com/a/2.flac",
                "https://cdn.example.com/live.m3u8",
            ),
            songs.map { it.stableKey },
        )
        assertTrue("每条 stableKey 必须唯一", songs.map { it.stableKey }.toSet().size == 3)
        // 远程来源：界面上的来源标签、歌词查找都认这个字段
        assertTrue(songs.all { it.source == SongSource.REMOTE })
        // 专辑位放列表名：播放在线列表时「专辑」一栏有东西可看
        assertTrue(songs.all { it.album == "夜航电台" })
        // 字段逐一透传
        assertEquals("晴天", songs[0].title)
        assertEquals("周杰伦", songs[0].artist)
        assertEquals(215_000L, songs[0].durationMs)
        assertEquals("晴天", songs[0].displayName)
        assertNull("清单里没给歌手就应该是 null，别塞占位符", songs[1].artist)
        assertEquals("夜航", songs[1].title)
        // 直播 / 未知时长是 0，进度条那边有兜底
        assertEquals(0L, songs[1].durationMs)
        assertEquals(0L, songs[2].durationMs)
        // dateAdded 用列表更新时间：排序 / 「最近添加」至少有个稳定值
        assertTrue(songs.all { it.dateAdded == 1_700_000_000_000L })
    }

    @Test
    fun `toSongs 按扩展名推 MIME`() {
        val playlist = OnlinePlaylist(
            id = "p", name = "n", url = "https://x/l.m3u",
            format = PlaylistFormat.M3U, kind = PlaylistKind.TRACKS,
            entries = listOf(
                PlaylistEntry("a", null, "https://x/a.mp3"),
                PlaylistEntry("b", null, "https://x/b.m4a"),
                PlaylistEntry("c", null, "https://x/c.flac"),
                PlaylistEntry("d", null, "https://x/live.m3u8"),
                PlaylistEntry("e", null, "https://x/noext"),
            ),
            updatedAt = 0L,
        )
        val songs = OnlinePlaylists(context).toSongs(playlist)
        assertEquals(
            listOf("audio/mpeg", "audio/mp4", "audio/flac", "application/x-mpegURL", null),
            songs.map { it.mimeType },
        )
    }

    @Test
    fun `toSongs 空条目给空队列`() {
        // 界面据此显示「这份列表里没有可播放的条目」，不能返回非空让播放器起播一个空队列
        val empty = OnlinePlaylist(
            id = "p", name = "n", url = "https://x/l.m3u",
            format = PlaylistFormat.M3U, kind = PlaylistKind.TRACKS,
            entries = emptyList(), updatedAt = 0L,
        )
        assertTrue(OnlinePlaylists(context).toSongs(empty).isEmpty())
    }

    @Test
    fun `toSongs 的产出能直接喂给播放器`() {
        // 这是整条链路的接缝：在线条目 → Song → MediaItem。
        // mediaId 用 stableKey、uri 用 http 直链，两项错一个播放器就播不出声。
        val playlist = OnlinePlaylist(
            id = "p1",
            name = "夜航电台",
            url = "https://cdn.example.com/list.m3u",
            format = PlaylistFormat.M3U,
            kind = PlaylistKind.TRACKS,
            entries = listOf(
                PlaylistEntry("晴天", "周杰伦", "https://cdn.example.com/a/1.mp3", 215_000),
                PlaylistEntry("夜航", null, "https://cdn.example.com/a/2.mp3", 180_000),
            ),
            updatedAt = 0L,
        )
        val songs = OnlinePlaylists(context).toSongs(playlist)

        val items: List<MediaItem> = songs.map { PlaybackConnection.toMediaItem(it) }

        assertEquals(songs.map { it.stableKey }, items.map { it.mediaId })
        assertEquals(
            listOf("https://cdn.example.com/a/1.mp3", "https://cdn.example.com/a/2.mp3"),
            items.map { it.localConfiguration?.uri.toString() },
        )
        assertEquals(listOf("晴天", "夜航"), items.map { it.mediaMetadata.title })
        // artistOrUnknown / albumOrUnknown：空值在这里补占位，播放通知不会出现空白
        assertEquals(listOf("周杰伦", "未知歌手"), items.map { it.mediaMetadata.artist })
        assertEquals(listOf("夜航电台", "夜航电台"), items.map { it.mediaMetadata.albumTitle })
        assertTrue(items.all { it.mediaMetadata.isPlayable == true })
    }

    // ============================================================ 存档读写

    @Test
    fun `存档能整份 round-trip`() {
        val playlist = OnlinePlaylist(
            id = "p1",
            name = "夜航电台",
            url = "https://cdn.example.com/list.m3u",
            format = PlaylistFormat.M3U,
            kind = PlaylistKind.TRACKS,
            entries = listOf(
                PlaylistEntry("晴天", "周杰伦", "https://cdn.example.com/a/1.mp3", 215_000),
                PlaylistEntry("夜航", null, "https://cdn.example.com/a/2.mp3", 0),
            ),
            updatedAt = 1_700_000_000_000L,
            note = "另有 2 条非 http 地址未导入",
        )
        OnlinePlaylistStore(context).replace(listOf(playlist))

        // 换个实例读：存的是 JSON 文本，不是内存对象
        assertEquals(listOf(playlist), OnlinePlaylistStore(context).all())
    }

    @Test
    fun `坏数据不炸，降级成 UNKNOWN 与 UNSUPPORTED`() {
        // 升级 / 手改存档都可能留下不认识的枚举值；read 抛异常会让整个列表页打不开
        val raw = """
            [{"id":"p1","name":"x","url":"https://x/l.m3u","format":"WEIRD","kind":"WEIRD",
              "updatedAt":1,"note":"","entries":[]}]
        """.trimIndent()
        context.getSharedPreferences("online_playlists", Context.MODE_PRIVATE)
            .edit().putString("playlists", raw).commit()

        val p = OnlinePlaylistStore(context).all().single()
        assertEquals(PlaylistFormat.UNKNOWN, p.format)
        assertEquals(PlaylistKind.UNSUPPORTED, p.kind)
    }

    @Test
    fun `整段 JSON 坏了返回空列表而不是崩`() {
        context.getSharedPreferences("online_playlists", Context.MODE_PRIVATE)
            .edit().putString("playlists", "这不是 JSON").commit()
        assertTrue(OnlinePlaylistStore(context).all().isEmpty())
    }

    @Test
    fun `空地址条目与字面 null 歌手在读回时被清掉`() {
        val raw = """
            [{"id":"p1","name":"x","url":"https://x/l.m3u","format":"M3U","kind":"TRACKS",
              "updatedAt":1,"entries":[
                {"t":"ok","a":"歌手","u":"https://x/a.mp3","d":1000},
                {"t":"坏条目","a":"歌手","u":"   ","d":1000},
                {"t":"空歌手","a":"null","u":"https://x/b.mp3","d":0},
                {"t":"空歌手2","a":"","u":"https://x/c.mp3","d":0}
              ]}]
        """.trimIndent()
        context.getSharedPreferences("online_playlists", Context.MODE_PRIVATE)
            .edit().putString("playlists", raw).commit()

        val entries = OnlinePlaylistStore(context).all().single().entries
        assertEquals("url 空白的条目必须丢掉，否则队列里会出现播不动的项", 3, entries.size)
        assertNull("JSON 里字面 \"null\" 不是歌手名", entries[1].artist)
        assertNull(entries[2].artist)
        assertEquals("歌手", entries[0].artist)
    }

    // ============================================================ 增删改查

    @Test
    fun `all 按更新时间倒序，最新改的排最前`() {
        val store = OnlinePlaylistStore(context)
        fun pl(id: String, at: Long) = OnlinePlaylist(
            id = id, name = id, url = "https://x/$id.m3u",
            format = PlaylistFormat.M3U, kind = PlaylistKind.TRACKS,
            entries = emptyList(), updatedAt = at,
        )
        store.replace(listOf(pl("old", 100L), pl("new", 300L), pl("mid", 200L)))

        assertEquals(listOf("new", "mid", "old"), OnlinePlaylists(context).all().map { it.id })
    }

    @Test
    fun `find 按 id 找，找不到给 null`() {
        val store = OnlinePlaylistStore(context)
        val p = OnlinePlaylist(
            id = "p1", name = "n", url = "https://x/l.m3u",
            format = PlaylistFormat.M3U, kind = PlaylistKind.TRACKS,
            entries = emptyList(), updatedAt = 1L,
        )
        store.replace(listOf(p))
        assertEquals(p, OnlinePlaylists(context).find("p1"))
        assertNull(OnlinePlaylists(context).find("nope"))
    }

    @Test
    fun `重命名去空白，空名字整体忽略`() {
        val store = OnlinePlaylistStore(context)
        val p = OnlinePlaylist(
            id = "p1", name = "旧名", url = "https://x/l.m3u",
            format = PlaylistFormat.M3U, kind = PlaylistKind.TRACKS,
            entries = emptyList(), updatedAt = 1L,
        )
        store.replace(listOf(p))
        val repo = OnlinePlaylists(context)

        repo.rename("p1", "  新名  ")
        assertEquals("新名", repo.find("p1")?.name)

        repo.rename("p1", "   ")
        assertEquals("空名字不能把列表改成无名", "新名", repo.find("p1")?.name)
    }

    @Test
    fun `删除只删指定那一个`() {
        val store = OnlinePlaylistStore(context)
        fun pl(id: String) = OnlinePlaylist(
            id = id, name = id, url = "https://x/$id.m3u",
            format = PlaylistFormat.M3U, kind = PlaylistKind.TRACKS,
            entries = emptyList(), updatedAt = 1L,
        )
        store.replace(listOf(pl("a"), pl("b")))
        OnlinePlaylists(context).remove("a")
        assertEquals(listOf("b"), OnlinePlaylists(context).all().map { it.id })
    }

    // ============================================================ add

    private val tracks = ParsedPlaylist(
        format = PlaylistFormat.M3U,
        kind = PlaylistKind.TRACKS,
        entries = listOf(PlaylistEntry("晴天", "周杰伦", "https://cdn.example.com/a/1.mp3", 215_000)),
        note = "另有 1 条非 http 地址未导入",
    )

    @Test
    fun `add 成功后人库，且抓的是规范化后的地址`() = runBlocking {
        val repo = repo { tracks }

        val r = repo.add("  我的电台  ", "cdn.example.com/list.m3u")

        assertTrue("失败原因：${r.exceptionOrNull()?.message}", r.isSuccess)
        val saved = r.getOrThrow()
        assertEquals("我的电台", saved.name)
        assertEquals("https://cdn.example.com/list.m3u", saved.url)
        assertEquals(PlaylistKind.TRACKS, saved.kind)
        assertEquals(1, saved.entries.size)
        assertEquals("另有 1 条非 http 地址未导入", saved.note)
        assertEquals(listOf(saved.id), repo.all().map { it.id })
        // 补协议头发生在抓取之前：抓的不能是用户粘的原始串
        assertEquals(listOf("https://cdn.example.com/list.m3u"), requested)
    }

    @Test
    fun `add 不填名字时用默认名`() = runBlocking {
        val repo = repo { tracks }
        // list.m3u 是通用名，退化成域名才分得清是哪个站
        val r = repo.add("   ", "https://cdn.example.com/list.m3u")
        assertEquals("cdn.example.com", r.getOrThrow().name)
    }

    @Test
    fun `add 抓到不可用内容就不入库`() = runBlocking {
        val repo = repo {
            ParsedPlaylist(PlaylistFormat.UNKNOWN, PlaylistKind.UNSUPPORTED, emptyList(), note = "返回的是网页")
        }

        val r = repo.add("x", "https://cdn.example.com/page")

        assertTrue(r.isFailure)
        assertEquals("返回的是网页", r.exceptionOrNull()?.message)
        assertTrue("存一堆打不开的死地址比不存更糟", repo.all().isEmpty())
    }

    @Test
    fun `add 抓取失败时连错误带空库一起交回界面`() = runBlocking {
        val repo = repoThrowing(IllegalStateException("连接超时：对方没有响应，或当前网络太慢"))

        val r = repo.add("x", "https://cdn.example.com/list.m3u")

        assertTrue(r.isFailure)
        assertEquals("连接超时：对方没有响应，或当前网络太慢", r.exceptionOrNull()?.message)
        assertTrue(repo.all().isEmpty())
    }

    @Test
    fun `add 地址不合法时根本不发起抓取`() = runBlocking {
        val repo = repo { tracks }

        assertTrue(repo.add("x", "").isFailure)
        assertTrue(repo.add("x", "   ").isFailure)
        assertTrue(repo.add("x", "https://").isFailure)

        assertTrue("三次都该在抓取前就被拦下", requested.isEmpty())
        assertTrue(repo.all().isEmpty())
    }

    @Test
    fun `add 两次得到两条互不覆盖的记录`() = runBlocking {
        val repo = repo { tracks }
        repo.add("第一个", "https://cdn.example.com/list.m3u")
        repo.add("第二个", "https://other.example.com/list.m3u")
        assertEquals(listOf("第二个", "第一个"), repo.all().map { it.name })
    }

    // ============================================================ refresh

    /**
     * 预置一条记录。
     *
     * [result] 必须显式给出：`seeded()` 若用默认抓取器，`refresh` 会**真连网络**——
     * 写这条测试时就踩过（跑出「HTTPS 握手失败」才想起来注入）。
     */
    private fun seeded(result: (String) -> ParsedPlaylist): OnlinePlaylists {
        val store = OnlinePlaylistStore(context)
        store.replace(
            listOf(
                OnlinePlaylist(
                    id = "p1",
                    name = "夜航电台",
                    url = "https://cdn.example.com/list.m3u",
                    format = PlaylistFormat.M3U,
                    kind = PlaylistKind.TRACKS,
                    entries = listOf(PlaylistEntry("旧歌", "旧歌手", "https://cdn.example.com/old.mp3", 100)),
                    updatedAt = 1_000L,
                    note = "旧备注",
                ),
            ),
        )
        return repo(result)
    }

    @Test
    fun `refresh 换条目但保留 id 名字与地址`() = runBlocking {
        val repo = seeded { tracks }

        val r = repo.refresh("p1")

        assertTrue("失败原因：${r.exceptionOrNull()?.message}", r.isSuccess)
        val updated = r.getOrThrow()
        assertEquals("p1", updated.id)
        assertEquals("夜航电台", updated.name)
        assertEquals("https://cdn.example.com/list.m3u", updated.url)
        assertEquals(listOf(PlaylistEntry("晴天", "周杰伦", "https://cdn.example.com/a/1.mp3", 215_000)), updated.entries)
        assertEquals("另有 1 条非 http 地址未导入", updated.note)
        assertTrue("更新时间必须往前走", updated.updatedAt >= 1_000L)
        // 落盘的也得是新的
        assertEquals(updated, repo.find("p1"))
        assertEquals(listOf("https://cdn.example.com/list.m3u"), requested)
    }

    @Test
    fun `refresh 抓到不可用内容时保留旧条目`() = runBlocking {
        // 对方站点改版 / 需要登录：抓回来是网页
        val repo = seeded {
            ParsedPlaylist(PlaylistFormat.UNKNOWN, PlaylistKind.UNSUPPORTED, emptyList(), note = "网站挂了")
        }
        val before = repo.find("p1")

        val r = repo.refresh("p1")

        assertTrue(r.isFailure)
        assertEquals("网站挂了", r.exceptionOrNull()?.message)
        assertEquals("弱网下旧数据比没有强", before, repo.find("p1"))
    }

    @Test
    fun `refresh 不存在的 id 直接失败`() = runBlocking {
        val repo = seeded { tracks }
        val r = repo.refresh("nope")
        assertTrue(r.isFailure)
        assertEquals("列表不存在", r.exceptionOrNull()?.message)
        assertTrue(requested.isEmpty())
    }

    @Test
    fun `HLS 直播流整条算一项`() = runBlocking {
        val repo = repo {
            ParsedPlaylist(
                format = PlaylistFormat.M3U,
                kind = PlaylistKind.HLS,
                entries = listOf(PlaylistEntry("FIP", "直播流", "https://live.example.com/a.m3u8")),
                note = "整条流作为一项播放",
            )
        }
        val r = repo.add("FIP", "https://live.example.com/a.m3u8")
        val saved = r.getOrThrow()
        assertTrue("HLS 必须是流", saved.isStream)
        val songs = repo.toSongs(saved)
        assertEquals(1, songs.size)
        assertEquals("https://live.example.com/a.m3u8", songs.single().stableKey)
        assertEquals("application/x-mpegURL", songs.single().mimeType)
        // 播的是清单本身的地址，交给 ExoPlayer 的 HLS 解析器取子清单
        assertEquals(
            "https://live.example.com/a.m3u8",
            PlaybackConnection.toMediaItem(songs.single()).localConfiguration?.uri.toString(),
        )
    }
}
