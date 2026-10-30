package com.luckylearning.windtone.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音量平衡的标签优先级。
 *
 * 有 ReplayGain / iTunes 标签就用标签，没有才回落 RMS。
 * 增益走播放器音量（衰减制），和均衡器「响度增强」eqLoudness 不是一条链。
 */
class ReplayGainTagsTest {

    @Test
    fun `增益文本`() {
        assertEquals(1.23, ReplayGainTags.parseGainText("+1.23 dB")!!, 1e-9)
        assertEquals(-6.5, ReplayGainTags.parseGainText("-6.50 dB")!!, 1e-9)
        assertEquals(0.0, ReplayGainTags.parseGainText("0.00")!!, 1e-9)
        assertNull(ReplayGainTags.parseGainText("loud"))
        assertNull(ReplayGainTags.parseGainText(""))
    }

    @Test
    fun `iTunes Sound Check 以 1000 为 0 dB`() {
        assertEquals(0.0, ReplayGainTags.parseITunesSoundCheck("000003E8")!!, 1e-6)
        // 4000 → -10 * log10(4) ≈ -6.0206 dB
        assertEquals(-6.0206, ReplayGainTags.parseITunesSoundCheck("00000FA0")!!, 1e-3)
        assertEquals(3.0103, ReplayGainTags.parseITunesSoundCheck("000001F4")!!, 1e-3)
        assertNull(ReplayGainTags.parseITunesSoundCheck("00000000"))
        val line = "00000FA0 00000FA0 00024CA8 00024CA8 00000000 00000000 00007F40 00007F40 0002E248 0002E248"
        assertEquals(-6.0206, ReplayGainTags.parseITunesSoundCheck(line)!!, 1e-3)
    }

    @Test
    fun `同一文件里曲目 ReplayGain 优先于 iTunes 和专辑增益`() {
        val all = ReplayGainTags.Gains(trackDb = -3.0, albumDb = -8.0, itunesDb = -1.0, r128TrackDb = 2.0)
        assertEquals(-3.0, all.preferredDb()!!, 1e-9)
        val noTrack = ReplayGainTags.Gains(albumDb = -8.0, itunesDb = -1.0)
        assertEquals(-1.0, noTrack.preferredDb()!!, 1e-9)
        assertEquals(-8.0, ReplayGainTags.Gains(albumDb = -8.0).preferredDb()!!, 1e-9)
        assertNull(ReplayGainTags.Gains().preferredDb())
    }

    @Test
    fun `曲目模式优先曲目增益，专辑模式优先专辑增益`() {
        val all = ReplayGainTags.Gains(trackDb = -3.0, albumDb = -8.0, itunesDb = -1.0, r128TrackDb = 2.0)
        assertEquals(-3.0, all.preferredDb(ReplayGainMode.TRACK)!!, 1e-9)
        assertEquals(-8.0, all.preferredDb(ReplayGainMode.ALBUM)!!, 1e-9)
        val noTrack = ReplayGainTags.Gains(albumDb = -8.0, itunesDb = -1.0)
        assertEquals(-1.0, noTrack.preferredDb(ReplayGainMode.TRACK)!!, 1e-9)
        assertEquals(-8.0, noTrack.preferredDb(ReplayGainMode.ALBUM)!!, 1e-9)
        assertEquals(-8.0, ReplayGainTags.Gains(albumDb = -8.0).preferredDb(ReplayGainMode.TRACK)!!, 1e-9)
        assertEquals(-4.0, ReplayGainTags.Gains(trackDb = -4.0).preferredDb(ReplayGainMode.ALBUM)!!, 1e-9)
        assertNull(ReplayGainTags.Gains().preferredDb(ReplayGainMode.TRACK))
        assertNull(ReplayGainTags.Gains().preferredDb(ReplayGainMode.ALBUM))
    }

    @Test
    fun `ReplayGainMode 非法存量回落曲目`() {
        assertEquals(ReplayGainMode.TRACK, ReplayGainMode.fromStored(null))
        assertEquals(ReplayGainMode.TRACK, ReplayGainMode.fromStored(""))
        assertEquals(ReplayGainMode.TRACK, ReplayGainMode.fromStored("nope"))
        assertEquals(ReplayGainMode.ALBUM, ReplayGainMode.fromStored("album"))
        assertEquals(ReplayGainMode.TRACK, ReplayGainMode.fromStored("TRACK"))
    }

    @Test
    fun `有标签就不用 RMS，0 dB 也算有标签`() {
        assertEquals(-650, ReplayGainTags.resolveGainMb(-650, -200))
        assertEquals(0, ReplayGainTags.resolveGainMb(0, -500))
        assertEquals(-200, ReplayGainTags.resolveGainMb(null, -200))
        assertNull(ReplayGainTags.resolveGainMb(null, null))
        // 正增益留下来交给衰减层（抬不了就不动），不会拿去填 eqLoudness
        assertEquals(600, ReplayGainTags.resolveGainMb(600, -100))
    }

    @Test
    fun `标签增益走音量因子，不走响度增强的正毫贝`() {
        val cut = ReplayGainTags.toGainMb(-6.0)
        assertTrue("负增益才是音量平衡要压的：$cut", cut < 0)
        assertTrue(cut !in 1..1500)
        assertTrue(LoudnessScan.attenuationFactorMb(cut) < 1f)
        val boost = ReplayGainTags.toGainMb(6.0)
        assertEquals(1f, LoudnessScan.attenuationFactorMb(boost))
        assertEquals(-2000, ReplayGainTags.toGainMb(-30.0))
        assertEquals(1200, ReplayGainTags.toGainMb(40.0))
    }

    @Test
    fun `ID3 曲目增益优先于同标签里的专辑增益`() {
        val bytes = id3v23(
            txxx("REPLAYGAIN_ALBUM_GAIN", "-8.00 dB"),
            txxx("REPLAYGAIN_TRACK_GAIN", "-6.50 dB"),
        )
        val gains = ReplayGainTags.parse(bytes)!!
        assertEquals(-6.5, gains.trackDb!!, 1e-9)
        assertEquals(-8.0, gains.albumDb!!, 1e-9)
        assertEquals(-6.5, gains.preferredDb()!!, 1e-9)
        assertEquals(
            -650,
            ReplayGainTags.resolveGainMb(ReplayGainTags.toGainMb(gains.preferredDb()!!), -100),
        )
    }

    @Test
    fun `FLAC Vorbis 注释与 Opus R128`() {
        val flac = flacComment("REPLAYGAIN_TRACK_GAIN=-3.25 dB")
        assertEquals(-3.25, ReplayGainTags.parse(flac)!!.preferredDb()!!, 1e-9)

        val opus = flacComment("R128_TRACK_GAIN=0")
        // 0 Q7.8 = 相对 -23 LUFS 的 0 dB，换到 ReplayGain 参考是 +5 dB
        assertEquals(5.0, ReplayGainTags.parse(opus)!!.preferredDb()!!, 1e-9)
        val both = vorbisCommentBytes(
            listOf("REPLAYGAIN_TRACK_GAIN=-2.00 dB", "R128_TRACK_GAIN=0"),
        )
        val wrapped = flacWithComment(both)
        assertEquals(-2.0, ReplayGainTags.parse(wrapped)!!.preferredDb()!!, 1e-9)
    }

    @Test
    fun `MP4 iTunNORM`() {
        val mp4 = mp4ITunNorm("000003E8 000003E8 00000000 00000000 00000000 00000000 00000000 00000000 00000000 00000000")
        val db = ReplayGainTags.parse(mp4)!!.preferredDb()!!
        assertEquals(0.0, db, 1e-6)
        assertEquals(0, ReplayGainTags.resolveGainMb(ReplayGainTags.toGainMb(db), -400))
    }

    @Test
    fun `没有标签的字节返回 null，好让调用方去算 RMS`() {
        assertNull(ReplayGainTags.parse(ByteArray(0)))
        assertNull(ReplayGainTags.parse("not a tag".toByteArray()))
        assertNull(ReplayGainTags.resolveGainMb(null, null))
    }

    // ---------------------------------------------------------------- fixtures

    private fun txxx(desc: String, value: String): ByteArray {
        val payload = byteArrayOf(0) + desc.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
            value.toByteArray(Charsets.ISO_8859_1)
        return frame("TXXX", payload)
    }

    private fun frame(id: String, payload: ByteArray): ByteArray {
        val size = payload.size
        val header = byteArrayOf(
            id[0].code.toByte(), id[1].code.toByte(), id[2].code.toByte(), id[3].code.toByte(),
            (size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte(),
            0, 0,
        )
        return header + payload
    }

    private fun id3v23(vararg frames: ByteArray): ByteArray {
        val body = frames.reduce { a, b -> a + b }
        val size = body.size
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            ((size shr 21) and 0x7F).toByte(),
            ((size shr 14) and 0x7F).toByte(),
            ((size shr 7) and 0x7F).toByte(),
            (size and 0x7F).toByte(),
        )
        return header + body
    }

    private fun flacComment(entry: String): ByteArray = flacWithComment(vorbisCommentBytes(listOf(entry)))

    private fun flacWithComment(comment: ByteArray): ByteArray {
        val header = byteArrayOf(
            0x84.toByte(),
            ((comment.size shr 16) and 0xFF).toByte(),
            ((comment.size shr 8) and 0xFF).toByte(),
            (comment.size and 0xFF).toByte(),
        )
        return "fLaC".toByteArray(Charsets.US_ASCII) + header + comment
    }

    private fun vorbisCommentBytes(entries: List<String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u32(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        u32(0)
        u32(entries.size)
        for (e in entries) {
            val b = e.toByteArray(Charsets.UTF_8)
            u32(b.size)
            out.write(b)
        }
        return out.toByteArray()
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        val out = ByteArray(size)
        out[0] = (size shr 24).toByte()
        out[1] = (size shr 16).toByte()
        out[2] = (size shr 8).toByte()
        out[3] = size.toByte()
        type.toByteArray(Charsets.US_ASCII).copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    private fun mp4ITunNorm(value: String): ByteArray {
        val mean = box("mean", byteArrayOf(0, 0, 0, 0) + "com.apple.iTunes".toByteArray(Charsets.US_ASCII))
        val name = box("name", byteArrayOf(0, 0, 0, 0) + "iTunNORM".toByteArray(Charsets.US_ASCII))
        val data = box(
            "data",
            byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) + value.toByteArray(Charsets.US_ASCII),
        )
        val freeform = box("----", mean + name + data)
        val ilst = box("ilst", freeform)
        val meta = box("meta", byteArrayOf(0, 0, 0, 0) + ilst)
        val udta = box("udta", meta)
        return box("moov", udta)
    }
}
