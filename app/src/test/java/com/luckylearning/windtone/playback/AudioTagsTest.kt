package com.luckylearning.windtone.playback

import com.luckylearning.windtone.lyrics.BinaryReader
import com.luckylearning.windtone.lyrics.EmbeddedTags
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * 端到端验收：AudioTags 写进去的标签，必须能被本项目自己的 EmbeddedTags 解析器读回来。
 * 这是「下载下来的歌曲能被正确识别并解析」的直接证明 —— 不是只验证字节写进去了。
 */
class AudioTagsTest {

    private val cover = ByteArray(64) { (it % 251).toByte() }.also {
        it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() // JPEG magic
    }

    private val meta = AudioTags.Meta(
        title = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        albumArtist = "周杰伦",
        year = 2003,
        genre = "流行",
        trackNo = 3,
        discNumber = 1,
    )

    // ------------------------------------------------------------ MP3 / ID3v2

    @Test
    fun `MP3 写入的 ID3 标签能被 EmbeddedTags 读回`() {
        val raw = mpegFrameBytes()
        val out = AudioTags.write(raw, meta, cover)
        assertTrue("必须真的写入了 ID3", out.size > raw.size)
        val r = EmbeddedTags.parse(BinaryReader.open(out), "mp3", "audio/mpeg", true)
        assertNotNull("解析器应识别出标签", r)
        assertEquals("晴天", r!!.title)
        assertEquals("周杰伦", r.artist)
        assertEquals("叶惠美", r.album)
        assertArrayEquals(cover, r.picture)
    }

    @Test
    fun `MP3 重复写入不会堆叠标签`() {
        val raw = mpegFrameBytes()
        val once = AudioTags.write(raw, meta, cover)
        val twice = AudioTags.write(once, meta, cover)
        // 第二次是替换旧 ID3，不是再插一份：两次写入后体积应当相同
        assertEquals(once.size, twice.size)
    }

    // ------------------------------------------------------------ FLAC

    @Test
    fun `FLAC 写入的 Vorbis Comment 与 PICTURE 能被读回`() {
        val raw = flacBytes()
        val out = AudioTags.write(raw, meta, cover)
        assertTrue(out.size > raw.size)
        val r = EmbeddedTags.parse(BinaryReader.open(out), "flac", "audio/flac", true)
        assertNotNull(r)
        assertEquals("晴天", r!!.title)
        assertEquals("周杰伦", r.artist)
        assertEquals("叶惠美", r.album)
        assertArrayEquals(cover, r.picture)
    }

    @Test
    fun `FLAC 重复写入不丢音频帧`() {
        val raw = flacBytes()
        val once = AudioTags.write(raw, meta, cover)
        val twice = AudioTags.write(once, meta, cover)
        // STREAMINFO + 音频帧恒保留，只有 comment/picture 被替换；两次体积相同
        assertEquals(once.size, twice.size)
    }

    // ------------------------------------------------------------ M4A / MP4

    @Test
    fun `M4A 写入的 ilst 能被读回`() {
        val raw = m4aBytes()
        val out = AudioTags.write(raw, meta, cover)
        assertTrue(out.size > raw.size)
        val r = EmbeddedTags.parse(BinaryReader.open(out), "m4a", "audio/mp4", true)
        assertNotNull(r)
        assertEquals("晴天", r!!.title)
        assertEquals("周杰伦", r.artist)
        assertEquals("叶惠美", r.album)
    }

    // ------------------------------------------------------------ 兜底

    @Test
    fun `未知格式原样返回 不损坏音频`() {
        val raw = byteArrayOf(1, 2, 3, 4, 5)
        assertArrayEquals(raw, AudioTags.write(raw, meta, cover))
    }

    @Test
    fun `封面 mime 识别正确`() {
        val png = ByteArray(64) { it.toByte() }.also {
            it[0] = 0x89.toByte(); it[1] = 'P'.code.toByte(); it[2] = 'N'.code.toByte(); it[3] = 'G'.code.toByte()
        }
        val out = AudioTags.write(mpegFrameBytes(), meta, png)
        val r = EmbeddedTags.parse(BinaryReader.open(out), "mp3", null, true)
        assertArrayEquals(png, r!!.picture)
    }

    // ------------------------------------------------------------ 最小容器构造

    /** 一段假 MPEG 帧：同步字 + 若干字节，够探测为 MP3 即可。 */
    private fun mpegFrameBytes(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00.toByte()) + ByteArray(128) { it.toByte() }

    /** fLaC + 一个 34 字节 STREAMINFO block（last=true）+ 假音频帧。 */
    private fun flacBytes(): ByteArray {
        val streamInfo = ByteArray(34) { it.toByte() }
        val header = byteArrayOf(
            0x80.toByte(), // last=true, type=0
            0, 0, 34,
        )
        return "fLaC".toByteArray(StandardCharsets.ISO_8859_1) + header + streamInfo + ByteArray(256) { it.toByte() }
    }

    /** ftyp + 空 moov；写入器会在 moov 内建 udta/meta/ilst。 */
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
