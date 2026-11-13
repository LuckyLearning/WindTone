package com.anylyric.lyrics.sources

import com.anylyric.lyrics.LyricResult
import com.anylyric.lyrics.TrackContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PlaybackState.extras` 取词。
 *
 * 这条通道是真机上「明明有歌词却显示暂无」的主要漏点：BYD 把整段歌词放在
 * `lyrics`、把滚动窗口放在 `lrc_line_1~3`，而旧实现只读 MediaMetadata。
 */
class ExtrasSourceTest {

    private val fullLrc = "[00:01.00]第一句\n[00:05.00]第二句"

    private fun ctx(vararg pairs: Pair<String, String>): TrackContext =
        TrackContext(packageName = "byd.music", playbackExtras = pairs.toMap())

    @Test
    fun `full lyrics hit with timestamps`() {
        val source = ExtrasSource()
        val result = source.tryResolve(ctx("lyrics" to fullLrc))

        assertTrue(result is LyricResult.Hit)
        assertEquals("L0b", result.sourceId)
        assertEquals(2, (result as LyricResult.Hit).lines.size)
        assertEquals(1, source.parseCount)
    }

    /** 同一段整段歌词反复出现时只解析一次。 */
    @Test
    fun `repeated full lyrics parsed once`() {
        val source = ExtrasSource()
        repeat(5) { source.tryResolve(ctx("lyrics" to fullLrc)) }
        assertEquals(1, source.parseCount)
    }

    /** 滚动三行：第一行是当前句，锚点必须是 0，否则会把第二句当当前句画。 */
    @Test
    fun `rolling window anchors on first line`() {
        val source = ExtrasSource()
        val result = source.tryResolve(
            ctx(
                "lrc_line_1" to "当前句",
                "lrc_line_2" to "下一句",
                "lrc_line_3" to "再下一句"
            )
        )

        assertTrue(result is LyricResult.Hit)
        val hit = result as LyricResult.Hit
        assertEquals(3, hit.lines.size)
        assertEquals(0, hit.currentLineIndex)
        assertEquals("当前句", hit.lines[0].text)
        assertEquals(null, hit.lines[0].timeMs)
    }

    /** 只有一条滚动行也要用（配合副行回退取前行）。 */
    @Test
    fun `single rolling line still hits`() {
        val source = ExtrasSource()
        val result = source.tryResolve(ctx("lrc_line_1" to "只有当前句"))
        assertTrue(result is LyricResult.Hit)
        assertEquals("只有当前句", (result as LyricResult.Hit).lines[0].text)
    }

    /** 整段歌词被 JSON 包起来时先解包再解析。 */
    @Test
    fun `json wrapped full lyrics is unwrapped`() {
        val source = ExtrasSource()
        // JSON 字符串里不能直接塞真实换行，用单行示例证明解包即可。
        val result = source.tryResolve(ctx("lyrics" to """{"lyrics":"[00:01.00]只有一句"}"""))
        assertTrue(result is LyricResult.Hit)
        val hit = result as LyricResult.Hit
        assertEquals(1, hit.lines.size)
        // 时间戳被 LrcParser 剥进 timeMs，text 里只剩正文。
        assertEquals("只有一句", hit.lines[0].text)
        assertEquals(1_000L, hit.lines[0].timeMs)
    }

    @Test
    fun `no extras means miss`() {
        val result = ExtrasSource().tryResolve(TrackContext())
        assertTrue(result is LyricResult.Miss)
        assertEquals("no_extras", (result as LyricResult.Miss).reason)
    }

    @Test
    fun `unrelated extras mean miss`() {
        val result = ExtrasSource().tryResolve(ctx("something" to "value"))
        assertTrue(result is LyricResult.Miss)
        assertEquals("no_lyric_field", (result as LyricResult.Miss).reason)
    }
}
