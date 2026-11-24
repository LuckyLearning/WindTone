package com.luckylearning.windtone.data.online

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.luckylearning.windtone.data.online.source.OnlineTrack
import com.luckylearning.windtone.data.online.source.Quality
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 源内原始信息的存档回归。
 *
 * 重点验证「跨实例读回」：它决定用户重启应用后还能不能从曲库直接点播一首上次搜到的歌。
 * 只测同实例内存命中是没有意义的 —— 内存里本来就有。
 */
@RunWith(RobolectricTestRunner::class)
class OnlineTrackStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun track(songId: String, raw: String = """{"songmid":"$songId"}""") = OnlineTrack(
        sourceId = "kw",
        songId = songId,
        title = "晴天 $songId",
        artist = "周杰伦",
        album = "叶惠美",
        durationMs = 269_000L,
        qualities = listOf(Quality.Q320, Quality.FLAC),
        raw = raw,
        vip = false,
    )

    @Test
    fun `写入后换一个实例仍能读回全部字段`() = runBlocking {
        val original = track("228908")
        OnlineTrackStore(context).put(original)

        // 模拟进程重启：新实例从文件装载
        val restored = OnlineTrackStore(context).get(original.stableKey)

        assertEquals(original, restored)
        assertEquals("""{"songmid":"228908"}""", restored!!.raw)
        assertEquals(listOf(Quality.Q320, Quality.FLAC), restored.qualities)
    }

    @Test
    fun `同一稳定键重复写入是覆盖`() = runBlocking {
        val store = OnlineTrackStore(context)
        store.put(track("1", raw = """{"v":1}"""))
        store.put(track("1", raw = """{"v":2}"""))

        assertEquals("""{"v":2}""", store.get("lx:kw:1")!!.raw)
        assertEquals(1, store.size())
    }

    @Test
    fun `prune 只保留仍在曲库里的条目`() = runBlocking {
        val store = OnlineTrackStore(context)
        store.putAll(listOf(track("1"), track("2"), track("3")))

        store.prune(setOf("lx:kw:1", "lx:kw:3"))

        assertEquals(2, store.size())
        assertNull("被清掉的条目不能再读出来", store.get("lx:kw:2"))
        // 清理结果同样要落盘，否则下次重启又回来了
        assertEquals(2, OnlineTrackStore(context).size())
    }

    @Test
    fun `文件损坏时不抛异常，按空存档处理`() = runBlocking {
        java.io.File(context.filesDir, "online_tracks.json").writeText("{ 这不是 json")

        val store = OnlineTrackStore(context)
        assertEquals(0, store.size())
        // 坏文件之后还能正常写入
        store.put(track("9"))
        assertEquals(1, store.size())
    }
}
