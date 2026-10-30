package com.luckylearning.windtone.playback

import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * 曲目音量标签（ReplayGain / iTunes Sound Check / Opus R128）。
 *
 * 给「音量平衡」用，**不是**均衡器的「响度增强」（`Prefs.eqLoudness` /
 * `LoudnessEnhancer`）。这里算出的是「这首歌该乘多少音量」，正增益在
 * [LoudnessScan.attenuationFactor] 里会被忽略（播放器音量抬不了），
 * 负增益才压下去。没有标签时调用方回落到 RMS 估算。
 *
 * 同一文件里多个标签的优先级：
 *  1. `REPLAYGAIN_TRACK_GAIN`（按曲，和「曲间平衡」同口径）
 *  2. iTunes Sound Check（`iTunNORM`，也是按曲）
 *  3. `REPLAYGAIN_ALBUM_GAIN`（只有专辑增益时的退路）
 *  4. Opus `R128_TRACK_GAIN`（换算到 ReplayGain 的参考响度后再用）
 */
object ReplayGainTags {

    data class Gains(
        val trackDb: Double? = null,
        val albumDb: Double? = null,
        val itunesDb: Double? = null,
        val r128TrackDb: Double? = null,
    ) {
        fun preferredDb(): Double? = trackDb ?: itunesDb ?: albumDb ?: r128TrackDb

        fun isEmpty(): Boolean =
            trackDb == null && albumDb == null && itunesDb == null && r128TrackDb == null
    }

    /**
     * 音量平衡最终采用的增益（mB）。
     *
     * [tagGainMb] 非 null（包括 0）表示文件里有标签，**不再看** RMS。
     * null 才回落到 [rmsGainMb]。两者都没有则返回 null（保持原音量，并排队分析）。
     * 不读取、不写入均衡器响度增强。
     */
    fun resolveGainMb(tagGainMb: Int?, rmsGainMb: Int?): Int? = tagGainMb ?: rmsGainMb

    /** `"+1.23 dB"` / `"-6.50 dB"` / `"0.00"` → dB；认不出返回 null。 */
    fun parseGainText(raw: String): Double? {
        var t = raw.trim()
        if (t.isEmpty()) return null
        val lower = t.lowercase()
        if (lower.endsWith("db")) t = t.dropLast(2).trim()
        if (t.isEmpty()) return null
        return t.toDoubleOrNull()
    }

    /**
     * iTunes Sound Check。
     *
     * `iTunNORM` 是 10 个 8 位十六进制数，第一个是音量。1000 = 0 dB，
     * `gainDb = -10 * log10(value / 1000)`。0 或空当没有标签。
     */
    fun parseITunesSoundCheck(raw: String): Double? {
        val token = raw.trim().split(Regex("\\s+")).firstOrNull { it.isNotBlank() } ?: return null
        val value = token.toLongOrNull(16) ?: return null
        if (value <= 0L) return null
        return -10.0 * log10(value / 1000.0)
    }

    /**
     * Opus `R128_TRACK_GAIN`：Q7.8 整数（256 = 1 dB），参考 -23 LUFS。
     * ReplayGain 参考约 -18 LUFS，所以再加 5 dB，和别的标签同一个量纲。
     */
    fun parseR128(raw: String): Double? {
        val q = raw.trim().toIntOrNull() ?: return null
        return q / 256.0 + 5.0
    }

    /** dB → mB，夹在 [LoudnessScan] 的增益上下限里，和 RMS 路径同一把尺子。 */
    fun toGainMb(db: Double): Int =
        (db.coerceIn(LoudnessScan.MIN_GAIN_DB, LoudnessScan.MAX_GAIN_DB) * 100.0).roundToInt()

    fun merge(a: Gains?, b: Gains?): Gains? {
        if (a == null || a.isEmpty()) return b?.takeUnless { it.isEmpty() }
        if (b == null || b.isEmpty()) return a
        return Gains(
            trackDb = a.trackDb ?: b.trackDb,
            albumDb = a.albumDb ?: b.albumDb,
            itunesDb = a.itunesDb ?: b.itunesDb,
            r128TrackDb = a.r128TrackDb ?: b.r128TrackDb,
        )
    }

    /** 从一个文件头（或文件尾）里把能认的标签都读出来。认不出返回 null。 */
    fun parse(bytes: ByteArray): Gains? = runCatching {
        if (bytes.isEmpty()) return null
        var g: Gains? = null
        if (startsWith(bytes, "ID3")) g = merge(g, parseId3(bytes))
        if (startsWith(bytes, "fLaC")) g = merge(g, parseFlac(bytes))
        if (startsWith(bytes, "OggS")) g = merge(g, parseOgg(bytes))
        g = merge(g, parseApe(bytes))
        if (looksLikeMp4(bytes)) g = merge(g, parseMp4(bytes))
        g?.takeUnless { it.isEmpty() }
    }.getOrNull()

    // ---------------------------------------------------------------- ID3

    private fun parseId3(data: ByteArray): Gains? {
        if (data.size < 10 || !startsWith(data, "ID3")) return null
        val major = data[3].toInt() and 0xFF
        if (major !in 2..4) return null
        val flags = data[5].toInt() and 0xFF
        val size = syncSafe(data, 6)
        if (size <= 0) return null
        val bodyEnd = minOf(data.size, 10 + size)
        var body = data.copyOfRange(10, bodyEnd)
        if (flags and 0x80 != 0) body = deunsync(body)

        var pos = 0
        if (major >= 3 && flags and 0x40 != 0 && body.size >= 4) {
            val skip = if (major >= 4) syncSafe(body, 0) else 4 + beU32(body, 0).toInt()
            if (skip in 0..body.size) pos = skip
        }

        var track: Double? = null
        var album: Double? = null
        val idLen = if (major == 2) 3 else 4
        val headerLen = if (major == 2) 6 else 10
        var guard = 0
        while (pos + headerLen <= body.size && guard++ < 4096) {
            val id = latin1(body, pos, idLen)
            if (id.isEmpty() || id[0] == '\u0000' || id.any { it.code !in 0x20..0x7E }) break
            val fsize = if (major == 2) {
                ((body[pos + 3].toInt() and 0xFF) shl 16) or
                    ((body[pos + 4].toInt() and 0xFF) shl 8) or
                    (body[pos + 5].toInt() and 0xFF)
            } else if (major >= 4) {
                syncSafe(body, pos + 4)
            } else {
                beU32(body, pos + 4).toInt()
            }
            val fflags = if (major >= 3 && pos + 10 <= body.size) {
                ((body[pos + 8].toInt() and 0xFF) shl 8) or (body[pos + 9].toInt() and 0xFF)
            } else 0
            pos += headerLen
            if (fsize <= 0 || pos + fsize > body.size) break
            var frame = body.copyOfRange(pos, pos + fsize)
            pos += fsize
            if (major >= 3) {
                val compressed = if (major >= 4) fflags and 0x0008 != 0 else fflags and 0x0080 != 0
                val encrypted = if (major >= 4) fflags and 0x0004 != 0 else fflags and 0x0040 != 0
                if (compressed || encrypted) continue
                if (major >= 4 && fflags and 0x0002 != 0) frame = deunsync(frame)
            }
            if (id != "TXXX" && id != "TXX") continue
            val (desc, value) = splitTxxx(frame) ?: continue
            val gain = parseGainText(value) ?: continue
            when (desc.trim().uppercase()) {
                "REPLAYGAIN_TRACK_GAIN" -> if (track == null) track = gain
                "REPLAYGAIN_ALBUM_GAIN" -> if (album == null) album = gain
            }
        }
        if (track == null && album == null) return null
        return Gains(trackDb = track, albumDb = album)
    }

    private fun splitTxxx(frame: ByteArray): Pair<String, String>? {
        if (frame.size < 2) return null
        val enc = frame[0].toInt() and 0xFF
        val body = frame.copyOfRange(1, frame.size)
        val term = if (enc == 1 || enc == 2) 2 else 1
        val end = indexOfZeroRun(body, 0, body.size, term)
        if (end < 0) return null
        val desc = decodeText(enc, body.copyOfRange(0, end))
        val start = end + term
        if (start > body.size) return null
        val value = decodeText(enc, body.copyOfRange(start, body.size))
        return desc to value
    }

    // ---------------------------------------------------------------- FLAC / Ogg

    private fun parseFlac(data: ByteArray): Gains? {
        if (!startsWith(data, "fLaC")) return null
        var p = 4
        var last = false
        var guard = 0
        var gains: Gains? = null
        while (!last && guard++ < 64 && p + 4 <= data.size) {
            val b = data[p].toInt() and 0xFF
            last = b and 0x80 != 0
            val type = b and 0x7F
            val len = ((data[p + 1].toInt() and 0xFF) shl 16) or
                ((data[p + 2].toInt() and 0xFF) shl 8) or
                (data[p + 3].toInt() and 0xFF)
            p += 4
            if (p + len > data.size) break
            if (type == 4) gains = merge(gains, parseVorbisComment(data.copyOfRange(p, p + len)))
            p += len
        }
        return gains
    }

    private fun parseOgg(data: ByteArray): Gains? {
        if (!startsWith(data, "OggS")) return null
        val opus = indexOfAscii(data, "OpusTags")
        if (opus >= 0) return parseVorbisComment(data.copyOfRange(opus + 8, data.size))
        val vorbis = indexOfAscii(data, "\u0003vorbis")
        if (vorbis >= 0) return parseVorbisComment(data.copyOfRange(vorbis + 7, data.size))
        return null
    }

    private fun parseVorbisComment(data: ByteArray): Gains? {
        var p = 0
        fun u32le(): Long {
            if (p + 4 > data.size) return -1
            val v = (data[p].toLong() and 0xFF) or
                ((data[p + 1].toLong() and 0xFF) shl 8) or
                ((data[p + 2].toLong() and 0xFF) shl 16) or
                ((data[p + 3].toLong() and 0xFF) shl 24)
            p += 4
            return v
        }
        val vendorLen = u32le()
        if (vendorLen < 0 || p + vendorLen > data.size) return null
        p += vendorLen.toInt()
        val count = u32le()
        if (count < 0 || count > 100_000) return null
        var track: Double? = null
        var album: Double? = null
        var r128: Double? = null
        var i = 0L
        while (i < count && p + 4 <= data.size) {
            val len = u32le()
            if (len < 0 || p + len > data.size) break
            val entry = String(data, p, len.toInt(), Charsets.UTF_8)
            p += len.toInt()
            i++
            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            val key = entry.substring(0, eq).trim().uppercase()
            val value = entry.substring(eq + 1)
            when (key) {
                "REPLAYGAIN_TRACK_GAIN" -> if (track == null) track = parseGainText(value)
                "REPLAYGAIN_ALBUM_GAIN" -> if (album == null) album = parseGainText(value)
                "R128_TRACK_GAIN" -> if (r128 == null) r128 = parseR128(value)
            }
        }
        if (track == null && album == null && r128 == null) return null
        return Gains(trackDb = track, albumDb = album, r128TrackDb = r128)
    }

    // ---------------------------------------------------------------- APEv2

    private fun parseApe(data: ByteArray): Gains? {
        val footerAt = data.size - 32
        if (footerAt < 0 || !asciiAt(data, footerAt, "APETAGEX")) return null
        val size = le32(data, footerAt + 12)
        val count = le32(data, footerAt + 16)
        if (size <= 32 || count < 0 || count > 10_000) return null
        var p = footerAt + 32 - size
        if (p < 0) return null
        val end = footerAt
        var track: Double? = null
        var album: Double? = null
        var n = 0
        while (n < count && p + 8 <= end) {
            val vlen = le32(data, p)
            p += 8
            val keyEnd = indexOfByte(data, p, end, 0)
            if (keyEnd < 0) break
            val key = latin1(data, p, keyEnd - p).uppercase()
            p = keyEnd + 1
            if (vlen < 0 || p + vlen > end) break
            val value = String(data, p, vlen, Charsets.UTF_8)
            p += vlen
            when (key) {
                "REPLAYGAIN_TRACK_GAIN" -> if (track == null) track = parseGainText(value)
                "REPLAYGAIN_ALBUM_GAIN" -> if (album == null) album = parseGainText(value)
            }
            n++
        }
        if (track == null && album == null) return null
        return Gains(trackDb = track, albumDb = album)
    }

    // ---------------------------------------------------------------- MP4 / iTunNORM

    private fun looksLikeMp4(data: ByteArray): Boolean {
        if (data.size < 8) return false
        val type = latin1(data, 4, 4)
        return type == "ftyp" || type == "moov" || type == "wide" || type == "free"
    }

    private class Box(val type: String, val content: Int, val end: Int)

    private fun parseMp4(data: ByteArray): Gains? {
        val moov = findBox(data, 0, data.size, "moov") ?: return null
        val udta = findBox(data, moov.content, moov.end, "udta") ?: return null
        val meta = findBox(data, udta.content, udta.end, "meta") ?: return null
        val ilstStart = meta.content + 4
        if (ilstStart > meta.end) return null
        val ilst = findBox(data, ilstStart, meta.end, "ilst") ?: return null
        var track: Double? = null
        var album: Double? = null
        var itunes: Double? = null
        var p = ilst.content
        var guard = 0
        while (p + 8 <= ilst.end && guard++ < 512) {
            val child = readBox(data, p, ilst.end) ?: break
            if (child.type == "----") {
                val got = parseFreeform(data, child)
                if (got != null) {
                    when (got.first) {
                        "REPLAYGAIN_TRACK_GAIN" -> if (track == null) track = parseGainText(got.second)
                        "REPLAYGAIN_ALBUM_GAIN" -> if (album == null) album = parseGainText(got.second)
                        "ITUNNORM" -> if (itunes == null) itunes = parseITunesSoundCheck(got.second)
                    }
                }
            }
            p = child.end
        }
        if (track == null && album == null && itunes == null) return null
        return Gains(trackDb = track, albumDb = album, itunesDb = itunes)
    }

    private fun parseFreeform(data: ByteArray, box: Box): Pair<String, String>? {
        val nameBox = findBox(data, box.content, box.end, "name") ?: return null
        val dataBox = findBox(data, box.content, box.end, "data") ?: return null
        val name = boxText(data, nameBox, 4)?.uppercase() ?: return null
        val value = boxText(data, dataBox, 8) ?: return null
        return name to value
    }

    private fun findBox(data: ByteArray, start: Int, end: Int, type: String): Box? {
        var p = start
        var guard = 0
        while (p + 8 <= end && guard++ < 4096) {
            val box = readBox(data, p, end) ?: return null
            if (box.type == type) return box
            p = box.end
        }
        return null
    }

    private fun readBox(data: ByteArray, offset: Int, limit: Int): Box? {
        if (offset < 0 || offset + 8 > limit || offset + 8 > data.size) return null
        var size = beU32(data, offset)
        val type = latin1(data, offset + 4, 4)
        var header = 8
        if (size == 1L) {
            if (offset + 16 > data.size) return null
            size = beU64(data, offset + 8)
            header = 16
        }
        if (size == 0L) size = (limit - offset).toLong()
        if (size < header || size > Int.MAX_VALUE) return null
        val end = offset + size.toInt()
        if (end > limit || end > data.size) return null
        return Box(type, offset + header, end)
    }

    private fun boxText(data: ByteArray, box: Box, skip: Int): String? {
        val start = box.content + skip
        if (start < 0 || start >= box.end || start > data.size) return null
        val len = box.end - start
        if (len <= 0) return null
        return String(data, start, len, Charsets.UTF_8).trim('\u0000', ' ').takeIf { it.isNotBlank() }
    }

    // ---------------------------------------------------------------- binary

    private fun startsWith(data: ByteArray, ascii: String): Boolean = asciiAt(data, 0, ascii)

    private fun asciiAt(data: ByteArray, offset: Int, ascii: String): Boolean {
        if (offset < 0 || offset + ascii.length > data.size) return false
        for (i in ascii.indices) {
            if (data[offset + i] != ascii[i].code.toByte()) return false
        }
        return true
    }

    private fun indexOfAscii(data: ByteArray, ascii: String): Int {
        if (ascii.isEmpty() || data.size < ascii.length) return -1
        val last = data.size - ascii.length
        var i = 0
        while (i <= last) {
            if (asciiAt(data, i, ascii)) return i
            i++
        }
        return -1
    }

    private fun latin1(data: ByteArray, offset: Int, len: Int): String {
        if (len <= 0 || offset < 0 || offset + len > data.size) return ""
        return String(data, offset, len, Charsets.ISO_8859_1)
    }

    private fun syncSafe(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return -1
        return ((b[off].toInt() and 0x7F) shl 21) or
            ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or
            (b[off + 3].toInt() and 0x7F)
    }

    private fun beU32(b: ByteArray, off: Int): Long {
        if (off + 4 > b.size) return -1
        return ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)
    }

    private fun beU64(b: ByteArray, off: Int): Long {
        if (off + 8 > b.size) return -1
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun le32(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return -1
        return (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
    }

    private fun deunsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt() and 0xFF)
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i += 2 else i++
        }
        return out.toByteArray()
    }

    private fun indexOfByte(b: ByteArray, from: Int, to: Int, value: Int): Int {
        var i = from
        val end = minOf(to, b.size)
        while (i < end) {
            if ((b[i].toInt() and 0xFF) == value) return i
            i++
        }
        return -1
    }

    private fun indexOfZeroRun(b: ByteArray, from: Int, to: Int, run: Int): Int {
        if (run <= 0) return -1
        var i = from
        val end = minOf(to, b.size)
        while (i + run <= end) {
            var ok = true
            for (k in 0 until run) {
                if (b[i + k] != 0.toByte()) {
                    ok = false
                    break
                }
            }
            if (ok) return i
            i++
        }
        return -1
    }

    private fun decodeText(enc: Int, bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val text = when (enc) {
            0 -> String(bytes, Charsets.ISO_8859_1)
            1 -> {
                val be = bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()
                val le = bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()
                val start = if (be || le) 2 else 0
                val cs = if (be) Charsets.UTF_16BE else Charsets.UTF_16LE
                String(bytes, start, bytes.size - start, cs)
            }
            2 -> String(bytes, Charsets.UTF_16BE)
            else -> String(bytes, Charsets.UTF_8)
        }
        return text.trim('\u0000', '\uFEFF', ' ', '\r', '\n')
    }
}
