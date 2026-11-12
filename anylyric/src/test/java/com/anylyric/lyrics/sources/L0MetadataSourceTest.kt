package com.anylyric.lyrics.sources

import com.anylyric.lyrics.LyricResult
import com.anylyric.lyrics.TrackContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L0 的解析缓存。
 *
 * 缓存不是「优化得好不好」的问题：真机上元数据回调一秒能来好几次，每次都把几 KB 的
 * 歌词重新过一遍正则，就是第一份报告里 17% CPU 的一部分。这里钉住三条：
 *  · 同一段文本不重复解析；
 *  · 文本变了必须重新解析（不能缓存成「永远是第一首」）；
 *  · 结果与不走缓存时完全一致。
 */
class L0MetadataSourceTest {

    private val lyricKey = "android.media.metadata.LYRIC"

    private fun lrc(vararg lines: String): String = lines.joinToString("\n")

    private fun ctx(text: String): TrackContext = TrackContext(
        packageName = "app.music",
        title = "歌名",
        metadata = mapOf(lyricKey to text)
    )

    @Test
    fun parsesLyricsFromMetadata() {
        val source = L0MetadataSource()
        val result = source.tryResolve(ctx(lrc("[00:01.00]第一句", "[00:05.00]第二句")))

        assertTrue(result is LyricResult.Hit)
        assertEquals("L0", result.sourceId)
        assertEquals(2, (result as LyricResult.Hit).lines.size)
        assertEquals(1, source.parseCount)
    }

    /** 同样的文本连问十次，只该解析一次。 */
    @Test
    fun repeatedSameTextIsParsedOnce() {
        val source = L0MetadataSource()
        val text = lrc("[00:01.00]第一句", "[00:05.00]第二句")
        repeat(10) {
            val result = source.tryResolve(ctx(text))
            assertTrue(result is LyricResult.Hit)
            assertEquals(2, (result as LyricResult.Hit).lines.size)
        }
        assertEquals(1, source.parseCount)
    }

    /** 换歌必然换文本：必须重新解析，不能把上一首的 27 行当成这一首。 */
    @Test
    fun newTextIsParsedAgain() {
        val source = L0MetadataSource()
        source.tryResolve(ctx(lrc("[00:01.00]第一句", "[00:05.00]第二句")))
        val next = source.tryResolve(ctx(lrc("[00:01.00]另一个首", "[00:09.00]第三句")))

        assertEquals(2, source.parseCount)
        assertEquals("另一个首", (next as LyricResult.Hit).lines[0].text)
    }

    /** 只有一行（或根本不像 LRC）的文本仍判 Miss，且键名白名单不因此放宽。 */
    @Test
    fun singleLineTextIsRejected() {
        val source = L0MetadataSource()
        val result = source.tryResolve(ctx("[00:01.00]只有一句"))
        assertTrue(result is LyricResult.Miss)
        assertEquals("no_lyric_key", (result as LyricResult.Miss).reason)
    }

    /** 非歌词键、且内容不像 LRC：不该触发解析。 */
    @Test
    fun unrelatedTextIsNotParsed() {
        val source = L0MetadataSource()
        val result = source.tryResolve(
            TrackContext(metadata = mapOf("android.media.metadata.ALBUM" to "传世情歌"))
        )
        assertTrue(result is LyricResult.Miss)
        assertEquals(0, source.parseCount)
    }

    /** 位图等非字符串值直接跳过：既不该崩，也不该被当作文本解析。 */
    @Test
    fun nonStringValuesAreIgnored() {
        val source = L0MetadataSource()
        val result = source.tryResolve(
            TrackContext(
                metadata = mapOf(
                    "android.media.metadata.ALBUM_ART" to Any(),
                    "android.media.metadata.DURATION" to 253_745L
                )
            )
        )
        assertTrue(result is LyricResult.Miss)
        assertEquals("no_string_value", (result as LyricResult.Miss).reason)
    }
}
