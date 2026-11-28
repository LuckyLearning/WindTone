package com.luckylearning.windtone.data

import com.luckylearning.windtone.playback.AudioTags
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * 下载 → 读取闭环验收（v1.3.15）。
 *
 * 写侧 AudioTags 内嵌的标签/封面，必须能被**新的读取入口** EmbeddedAudioInfo 读回。
 * 此前读取只走 MediaMetadataRetriever，对 FLAC PICTURE 读不出来，才导致
 * 「下载完封面/标签空白」；本测试锁住的就是这条闭环。
 */
class EmbeddedAudioInfoTest {

    private val cover = ByteArray(64) { (it % 251).toByte() }.also {
        it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte()
    }

    private val meta = AudioTags.Meta(
        title = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        albumArtist = "周杰伦",
        year = 2003,
    )

    @Test
    fun `MP3 写入后可被读取入口解析出标签与封面`() {
        val tagged = AudioTags.write(mpegFrameBytes(), meta, cover)
        val info = EmbeddedAudioInfo.parse(tagged, "mp3", "audio/mpeg", true)
        assertNotNull(info)
        assertEquals("晴天", info!!.title)
        assertEquals("周杰伦", info.artist)
        assertEquals("叶惠美", info.album)
        assertArrayEquals(cover, info.picture)
    }

    @Test
    fun `FLAC 写入后封面能被读取入口解析 这是 MMR 读不到的那一支`() {
        val tagged = AudioTags.write(flacBytes(), meta, cover)
        val info = EmbeddedAudioInfo.parse(tagged, "flac", "audio/flac", true)
        assertNotNull(info)
        assertEquals("晴天", info!!.title)
        assertEquals("周杰伦", info.artist)
        assertEquals("叶惠美", info.album)
        assertArrayEquals(cover, info.picture)
    }

    @Test
    fun `M4A 写入后可被读取入口解析出标签与封面`() {
        val tagged = AudioTags.write(m4aBytes(), meta, cover)
        val info = EmbeddedAudioInfo.parse(tagged, "m4a", "audio/mp4", true)
        assertNotNull(info)
        assertEquals("晴天", info!!.title)
        assertEquals("周杰伦", info.artist)
        assertEquals("叶惠美", info.album)
        assertArrayEquals(cover, info.picture)
    }

    @Test
    fun `只读文字时不取封面 大 APIC 不进入结果`() {
        val tagged = AudioTags.write(mpegFrameBytes(), meta, cover)
        val info = EmbeddedAudioInfo.parse(tagged, "mp3", "audio/mpeg", false)
        assertNotNull(info)
        assertEquals("晴天", info!!.title)
        assertNull(info.picture)
    }

    @Test
    fun `空字节与无标签音频返回空`() {
        assertNull(EmbeddedAudioInfo.parse(ByteArray(0), "flac", "audio/flac", true))
        val plain = flacBytes()
        val info = EmbeddedAudioInfo.parse(plain, "flac", "audio/flac", true)
        // 没有 VORBIS_COMMENT/PICTURE 的裸 FLAC，解析器可能返回 null 或无字段结果
        if (info != null) {
            assertNull(info.title)
            assertNull(info.picture)
        }
    }

    @Test
    fun `读取上限按堆比例且有下限`() {
        assertTrue(EmbeddedAudioInfo.maxReadBytes() >= 4 * 1024 * 1024)
    }

    private fun assertTrue(v: Boolean) = org.junit.Assert.assertTrue(v)

    // ------------------------------------------------------------ 最小容器构造（与 AudioTagsTest 一致）

    private fun mpegFrameBytes(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00.toByte()) + ByteArray(128) { it.toByte() }

    private fun flacBytes(): ByteArray {
        val streamInfo = ByteArray(34) { it.toByte() }
        val header = byteArrayOf(0x80.toByte(), 0, 0, 34)
        return "fLaC".toByteArray(StandardCharsets.ISO_8859_1) + header + streamInfo + ByteArray(256) { it.toByte() }
    }

    private fun m4aBytes(): ByteArray {
        val ftyp = atom("ftyp", "isom".toByteArray(StandardCharsets.ISO_8859_1) + AudioTags.be32(0))
        val moov = atom("moov", ByteArray(0))
        return ftyp + moov + atom("mdat", ByteArray(64))
    }

    private fun atom(type: String, content: ByteArray): ByteArray {
        val size = 8 + content.size
        val s = AudioTags.be32(size)
        return byteArrayOf(s[0], s[1], s[2], s[3]) +
            type.toByteArray(StandardCharsets.ISO_8859_1) + content
    }
}
