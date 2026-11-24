package com.luckylearning.windtone.data.online

import com.luckylearning.windtone.data.online.source.Capability
import com.luckylearning.windtone.data.online.source.MusicSource
import com.luckylearning.windtone.data.online.source.OnlineSourceRegistry
import com.luckylearning.windtone.data.online.source.OnlineTrack
import com.luckylearning.windtone.data.online.source.Quality
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 取址链的回归：音质降级、备忘录短路、非法地址拒绝。
 *
 * 这三条决定了「点一首在线歌，多久出声、失败了会不会自己救回来」，
 * 也是唯一能在没有真机、没有真音源的情况下验证的播放侧逻辑。
 */
class OnlineResolverTest {

    /** 只会用 320k 出地址的假音源；FLAC 一律失败，用来验证降级 */
    private class FakeSource(
        override val id: String = "kw",
        override val name: String = "假源",
        override val qualities: List<Quality> = listOf(Quality.FLAC, Quality.Q320),
        private val available: Set<Quality> = setOf(Quality.Q320),
        private val urlFactory: (OnlineTrack, Quality) -> String? = { t, q ->
            "https://cdn.example.com/${t.songId}-${q.id}.mp3"
        },
        /** 同一平台可能有多份脚本，ownerId 区分它们 */
        override val ownerId: String = "script-a",
    ) : MusicSource {
        override val capabilities: Set<Capability> = setOf(Capability.SEARCH)
        val calls = mutableListOf<Quality>()

        override suspend fun search(keyword: String, limit: Int): List<OnlineTrack> = emptyList()

        override suspend fun resolveUrl(track: OnlineTrack, quality: Quality): String? {
            calls += quality
            return if (quality in available) urlFactory(track, quality) else null
        }
    }

    private fun track(
        sourceId: String = "kw",
        songId: String = "1",
        qualities: List<Quality> = emptyList(),
    ) = OnlineTrack(sourceId = sourceId, songId = songId, title = "晴天", artist = "周杰伦", qualities = qualities)

    @Before
    fun reset() {
        OnlineSourceRegistry.clear()
        OnlineSourceRegistry.setPreferred(emptyMap())
        OnlineUrlMemo.clear()
    }

    @Test
    fun `高档位取不到时逐级降级到能用的档位`() = runBlocking {
        val source = FakeSource()
        OnlineSourceRegistry.register(source)

        val resolved = OnlineResolver.resolve(track(), preferred = Quality.FLAC)

        assertEquals("必须落到 320k", Quality.Q320, resolved?.quality)
        assertEquals("先试 flac 再试 320k", listOf(Quality.FLAC, Quality.Q320), source.calls)
    }

    @Test
    fun `命中备忘录不再打第二次网络`() = runBlocking {
        val source = FakeSource(available = setOf(Quality.Q320))
        OnlineSourceRegistry.register(source)

        val first = OnlineResolver.resolve(track(), preferred = Quality.Q320)
        val second = OnlineResolver.resolve(track(), preferred = Quality.Q320)

        assertEquals(first, second)
        assertEquals("第二次必须命中 memo，只有一次真实调用", 1, source.calls.size)
    }

    @Test
    fun `没注册音源时返回 null 而不是抛异常`() = runBlocking {
        assertNull(OnlineResolver.resolve(track(sourceId = "不存在"), preferred = null))
    }

    @Test
    fun `返回非 http 地址视为失败继续降级`() = runBlocking {
        // 脚本偶尔会吐出空串 / 本地路径 / data URI，这些都不能交给播放器
        val source = FakeSource(
            available = setOf(Quality.FLAC, Quality.Q320),
            urlFactory = { _, q -> if (q == Quality.FLAC) "file:///sdcard/a.flac" else "https://cdn/a.mp3" },
        )
        OnlineSourceRegistry.register(source)

        val resolved = OnlineResolver.resolve(track(), preferred = Quality.FLAC)

        assertEquals("非法地址不能算成功", Quality.Q320, resolved?.quality)
        assertTrue(resolved!!.url.startsWith("https://"))
    }

    @Test
    fun `全部档位失败时返回 null`() = runBlocking {
        OnlineSourceRegistry.register(FakeSource(available = emptySet()))
        assertNull(OnlineResolver.resolve(track(), preferred = Quality.FLAC))
    }

    @Test
    fun `降级链从用户偏好档位开始且不越级上升`() {
        val source = FakeSource(qualities = listOf(Quality.FLAC24, Quality.FLAC, Quality.Q320, Quality.Q128))

        // 用户选 320k：只能是 320k / 128k，不能偷偷升到 FLAC（白烧流量）
        assertEquals(
            listOf(Quality.Q320, Quality.Q128),
            OnlineResolver.qualityChain(source, track(), Quality.Q320),
        )
        // 什么都没选：从最高档开始
        assertEquals(
            listOf(Quality.FLAC24, Quality.FLAC, Quality.Q320, Quality.Q128),
            OnlineResolver.qualityChain(source, track(), null),
        )
        // 曲目自带档位优先于音源全集
        assertEquals(
            listOf(Quality.FLAC),
            OnlineResolver.qualityChain(source, track(qualities = listOf(Quality.FLAC)), null),
        )
        // 偏好档位源不支持：从它支持的最高档开始，而不是直接失败
        assertEquals(
            listOf(Quality.FLAC, Quality.Q320, Quality.Q128),
            OnlineResolver.qualityChain(
                FakeSource(qualities = listOf(Quality.FLAC, Quality.Q320, Quality.Q128)),
                track(),
                Quality.FLAC24,
            ),
        )
    }

    // ------------------------------------------------------------ 音质口径（v1.2.62）

    @Test
    fun `曲目标 FLAC 但音源只声明 128k 时不向它要 FLAC`() {
        // 酷我歌单元数据里几乎都写着 FLAC，而免费脚本一般只声明 128k。
        // 以前这里会先试 flac、再试 320k，两次必然失败的请求之后才落到 128k。
        val source = FakeSource(qualities = listOf(Quality.Q128), available = setOf(Quality.Q128))
        assertEquals(
            listOf(Quality.Q128),
            OnlineResolver.qualityChain(
                source,
                track(qualities = listOf(Quality.FLAC, Quality.Q320, Quality.Q128)),
                null,
            ),
        )
    }

    @Test
    fun `列表标签取音源能给到的最高档而不是平台标称`() {
        OnlineSourceRegistry.register(
            FakeSource(qualities = listOf(Quality.Q128), available = setOf(Quality.Q128)),
        )
        val t = track(qualities = listOf(Quality.FLAC, Quality.Q320, Quality.Q128))

        // 未取址：按「音源声明 ∩ 曲目」给，列表不再标一个播不出来的 FLAC
        assertEquals(Quality.Q128, OnlineResolver.displayQuality(t))

        // 取址后：用实际落地的档位（这里与上面一致）
        runBlocking {
            assertEquals(Quality.Q128, OnlineResolver.resolve(t, null)?.quality)
        }
        assertEquals(Quality.Q128, OnlineResolver.displayQuality(t))
    }

    @Test
    fun `同一平台两份脚本 第一份取不到时自动换第二份`() = runBlocking {
        val broken = FakeSource(available = emptySet(), ownerId = "script-a")
        val working = FakeSource(available = setOf(Quality.Q320), ownerId = "script-b")
        OnlineSourceRegistry.register(broken)
        OnlineSourceRegistry.register(working)

        val resolved = OnlineResolver.resolve(track(), preferred = null)

        assertEquals("第二份脚本必须被用上", "script-b", resolved?.ownerId)
        assertEquals(Quality.Q320, resolved?.quality)
        assertTrue(resolved!!.url.startsWith("https://"))
    }

    @Test
    fun `用户手选的脚本排第一`() = runBlocking {
        val first = FakeSource(
            qualities = listOf(Quality.Q128),
            available = setOf(Quality.Q128),
            ownerId = "script-a",
        )
        val second = FakeSource(
            qualities = listOf(Quality.FLAC),
            available = setOf(Quality.FLAC),
            ownerId = "script-b",
        )
        OnlineSourceRegistry.register(first)
        OnlineSourceRegistry.register(second)

        // 自动：清单顺序第一份（128k）先胜出
        assertEquals("script-a", OnlineResolver.resolve(track(), null)?.ownerId)

        // 用户指定第二份：重取必须换人，且不能命中上一条备忘录
        OnlineUrlMemo.clear()
        OnlineSourceRegistry.setPreferred(mapOf("kw" to "script-b"))
        assertEquals("script-b", OnlineResolver.resolve(track(), null)?.ownerId)
    }
}
