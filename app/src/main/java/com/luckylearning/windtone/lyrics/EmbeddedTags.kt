package com.luckylearning.windtone.lyrics

/**
 * 音频文件「内嵌歌词」提取器。
 * 覆盖主流容器：
 *   MP3  -> ID3v2.2/2.3/2.4 的 USLT（非同步歌词）/ SYLT（同步歌词）/ TXXX:LYRICS
 *   FLAC -> VORBIS_COMMENT 中的 LYRICS / UNSYNCEDLYRICS 等字段
 *   OGG/Opus -> Vorbis Comment（\x03vorbis / OpusTags）
 *   M4A/MP4 -> moov.udta.meta.ilst.©lyr
 */
internal object EmbeddedTags {

    private const val MAX_ID3_TAG = 32 * 1024 * 1024
    private const val MAX_SCAN = 1024 * 1024

    private val LYRICS_KEYS = setOf(
        "LYRICS", "LYRIC", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "UNSYNCED_LYRICS",
        "LYRICS_TEXT", "SYNCEDLYRICS", "SONGLYRICS",
    )

    fun parse(r: BinaryReader, extension: String, mime: String?): EmbeddedTagResult? {
        val ext = extension.lowercase()
        val m = mime?.lowercase().orEmpty()
        return when {
            ext == "mp3" || ext == "mp2" || ext == "mpga" || m.contains("mpeg") -> parseId3(r)
            ext == "flac" || m.contains("flac") -> parseFlac(r)
            ext == "m4a" || ext == "m4b" || ext == "mp4" || ext == "aac" || m.contains("mp4") || m.contains("aac") -> parseMp4(r)
            ext == "ogg" || ext == "oga" || ext == "opus" || m.contains("ogg") || m.contains("opus") -> parseOgg(r)
            else -> firstHit(r, ::parseId3, ::parseFlac, ::parseMp4, ::parseOgg)
        }
    }

    /** 未知扩展名时依次探测各容器格式，返回第一个能读出歌词的结果 */
    private fun firstHit(r: BinaryReader, vararg parsers: (BinaryReader) -> EmbeddedTagResult?): EmbeddedTagResult? {
        var fallback: EmbeddedTagResult? = null
        for (p in parsers) {
            val res = runCatching { p(r) }.getOrNull() ?: continue
            if (res.hasLyrics) return res
            if (fallback == null) fallback = res
        }
        return fallback
    }

    // ---------------------------------------------------------------- ID3v2

    private fun parseId3(r: BinaryReader): EmbeddedTagResult? {
        r.position = 0
        val head = r.readBytes(10) ?: return null
        if (head.size < 10) return null
        if (head[0] != 0x49.toByte() || head[1] != 0x44.toByte() || head[2] != 0x33.toByte()) return null

        val major = head[3].toInt() and 0xFF
        val flags = head[5].toInt() and 0xFF
        val size = syncSafe(head, 6)
        if (major < 2 || major > 4 || size <= 0 || size > MAX_ID3_TAG) return null

        var body = r.readBytes(size) ?: return null
        try {
            if (flags and 0x80 != 0) body = deUnsynchronise(body)

            var off = 0
            if (major >= 3 && flags and 0x40 != 0) {
                off = if (major >= 4) syncSafe(body, 0) else 4 + beInt(body, 0)
            }
            if (off < 0 || off >= body.size) off = 0

            var lyricsText: String? = null
            var synced: List<LyricLine>? = null
            var title: String? = null
            var artist: String? = null
            var album: String? = null

            val idLen = if (major == 2) 3 else 4
            val headerLen = if (major == 2) 6 else 10
            var pos = off
            var guard = 0

            while (pos + headerLen <= body.size && guard++ < 8192) {
                val id = String(body, pos, idLen, TextCodec.LATIN1)
                if (id[0] == '\u0000') break
                if (id.any { it.code < 0x20 || it.code > 0x7E }) break

                val fsize: Int
                val fflags: Int
                if (major == 2) {
                    fsize = ((body[pos + 3].toInt() and 0xFF) shl 16) or
                        ((body[pos + 4].toInt() and 0xFF) shl 8) or (body[pos + 5].toInt() and 0xFF)
                    fflags = 0
                } else {
                    fsize = if (major >= 4) syncSafe(body, pos + 4) else beInt(body, pos + 4)
                    fflags = ((body[pos + 8].toInt() and 0xFF) shl 8) or (body[pos + 9].toInt() and 0xFF)
                }
                pos += headerLen
                if (fsize <= 0 || pos + fsize > body.size) break

                var data = body.copyOfRange(pos, pos + fsize)
                pos += fsize
                if (data.isEmpty()) continue

                if (major >= 3) {
                    val compressed = if (major >= 4) fflags and 0x0008 != 0 else fflags and 0x0080 != 0
                    val encrypted = if (major >= 4) fflags and 0x0004 != 0 else fflags and 0x0040 != 0
                    if (compressed || encrypted) continue
                    if (major >= 4 && fflags and 0x0001 != 0 && data.size > 4) {
                        data = data.copyOfRange(4, data.size)
                    }
                    if (major >= 4 && fflags and 0x0002 != 0) data = deUnsynchronise(data)
                    if (data.isEmpty()) continue
                }

                when (id) {
                    "USLT", "ULT" -> if (lyricsText.isNullOrBlank()) lyricsText = extractUslt(data)
                    "SYLT", "SLT" -> if (synced.isNullOrEmpty()) synced = extractSylt(data)
                    "TXXX", "TXX" -> if (lyricsText.isNullOrBlank()) {
                        lyricsText = extractTxxxLyrics(data)
                    }
                    "TIT2", "TT2" -> if (title.isNullOrBlank()) title = textFrame(data)
                    "TPE1", "TP1" -> if (artist.isNullOrBlank()) artist = textFrame(data)
                    "TALB", "TAL" -> if (album.isNullOrBlank()) album = textFrame(data)
                }
            }

            val res = EmbeddedTagResult(lyricsText, synced, title, artist, album)
            return if (res.hasLyrics || title != null) res else null
        } catch (t: Throwable) {
            return null
        }
    }

    private fun extractUslt(data: ByteArray): String? {
        if (data.size < 4) return null
        val enc = data[0].toInt() and 0xFF
        val body = data.copyOfRange(4, data.size)
        val term = TextCodec.terminatorLength(enc)
        val descEnd = indexOfZeroRun(body, 0, term)
        val start = if (descEnd < 0) 0 else descEnd + term
        if (start >= body.size) return null
        return TextCodec.decodeId3(enc, body.copyOfRange(start, body.size))
            .trim('\u0000', '\r', '\n')
            .takeIf { it.isNotBlank() }
    }

    private fun extractTxxxLyrics(data: ByteArray): String? {
        if (data.size < 2) return null
        val enc = data[0].toInt() and 0xFF
        val body = data.copyOfRange(1, data.size)
        val term = TextCodec.terminatorLength(enc)
        val dEnd = indexOfZeroRun(body, 0, term)
        if (dEnd < 0) return null
        val desc = TextCodec.decodeId3(enc, body.copyOfRange(0, dEnd)).lowercase()
        if (!desc.contains("lyric") && !desc.contains("歌词")) return null
        val start = dEnd + term
        if (start >= body.size) return null
        return TextCodec.decodeId3(enc, body.copyOfRange(start, body.size)).trim('\u0000').takeIf { it.isNotBlank() }
    }

    /** SYLT：返回毫秒级同步歌词；帧计时（MPEG frame）因需帧率换算而忽略 */
    private fun extractSylt(data: ByteArray): List<LyricLine>? {
        if (data.size < 7) return null
        val enc = data[0].toInt() and 0xFF
        val timeFormat = data[4].toInt() and 0xFF
        if (timeFormat != 2) return null
        val body = data.copyOfRange(6, data.size)
        val term = TextCodec.terminatorLength(enc)
        val descEnd = indexOfZeroRun(body, 0, term)
        var p = if (descEnd < 0) 0 else descEnd + term

        val out = ArrayList<LyricLine>()
        while (p < body.size && out.size < 20000) {
            val tEnd = indexOfZeroRun(body, p, term)
            if (tEnd < 0) break
            val text = TextCodec.decodeId3(enc, body.copyOfRange(p, tEnd))
            p = tEnd + term
            if (p + 4 > body.size) break
            val ts = ((body[p].toLong() and 0xFF) shl 24) or ((body[p + 1].toLong() and 0xFF) shl 16) or
                ((body[p + 2].toLong() and 0xFF) shl 8) or (body[p + 3].toLong() and 0xFF)
            p += 4
            out.add(LyricLine(ts, text.trim('\u0000')))
        }
        return out
            .filter { it.text.isNotBlank() || out.size == 1 }
            .takeIf { it.isNotEmpty() }
    }

    private fun textFrame(data: ByteArray): String? {
        if (data.isEmpty()) return null
        val enc = data[0].toInt() and 0xFF
        return TextCodec.decodeId3(enc, data.copyOfRange(1, data.size)).trim('\u0000').takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------------ FLAC

    private fun parseFlac(r: BinaryReader): EmbeddedTagResult? {
        r.position = 0
        val magic = r.readBytes(4) ?: return null
        if (String(magic, TextCodec.LATIN1) != "fLaC") return null

        var last = false
        var guard = 0
        while (!last && guard++ < 256) {
            val b = r.readByte()
            if (b < 0) break
            last = (b and 0x80) != 0
            val type = b and 0x7F
            val len = r.readUInt24BE()
            if (len < 0) break
            if (type == 4) {
                val block = r.readBytes(len) ?: break
                return parseVorbisComment(block)
            }
            r.skip(len.toLong())
        }
        return null
    }

    // ------------------------------------------------------------- OGG / Opus

    private fun parseOgg(r: BinaryReader): EmbeddedTagResult? {
        r.position = 0
        val n = minOf(r.size, MAX_SCAN.toLong()).toInt()
        val data = r.readBytes(n) ?: return null

        val opus = indexOfAscii(data, "OpusTags")
        if (opus >= 0) return parseVorbisComment(data.copyOfRange(opus + 8, data.size))

        val vorbis = indexOfAscii(data, "\u0003vorbis")
        if (vorbis >= 0) return parseVorbisComment(data.copyOfRange(vorbis + 7, data.size))

        val flacMarker = indexOfAscii(data, "\u0084flac")
        if (flacMarker >= 0) {
            val idx = indexOfAscii(data, "fLaC", flacMarker)
            if (idx >= 0) return parseVorbisComment(data.copyOfRange(idx + 4, data.size))
        }
        return null
    }

    private fun parseVorbisComment(data: ByteArray): EmbeddedTagResult? {
        var p = 0

        fun u32le(): Long {
            if (p + 4 > data.size) return -1
            val v = ((data[p + 3].toLong() and 0xFF) shl 24) or ((data[p + 2].toLong() and 0xFF) shl 16) or
                ((data[p + 1].toLong() and 0xFF) shl 8) or (data[p].toLong() and 0xFF)
            p += 4
            return v
        }

        val vendorLen = u32le()
        if (vendorLen < 0 || p + vendorLen > data.size) return null
        p += vendorLen.toInt()

        val count = u32le()
        if (count < 0 || count > 100000) return null

        var lyrics: String? = null
        var title: String? = null
        var artist: String? = null
        var album: String? = null

        var i = 0L
        while (i < count && p + 4 <= data.size) {
            val len = u32le()
            if (len < 0 || p + len > data.size) break
            val entry = String(data, p, len.toInt(), TextCodec.UTF8)
            p += len.toInt()
            i++

            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            val key = entry.substring(0, eq).trim().uppercase()
            val value = entry.substring(eq + 1)
            when {
                key in LYRICS_KEYS -> if (lyrics.isNullOrBlank()) lyrics = value
                key == "TITLE" -> if (title.isNullOrBlank()) title = value
                key == "ARTIST" -> if (artist.isNullOrBlank()) artist = value
                key == "ALBUM" -> if (album.isNullOrBlank()) album = value
            }
        }

        val res = EmbeddedTagResult(lyrics, null, title, artist, album)
        return if (res.hasLyrics || title != null) res else null
    }

    // --------------------------------------------------------------- MP4 / M4A

    private fun parseMp4(r: BinaryReader): EmbeddedTagResult? {
        val moov = findAtom(r, 0L, r.size, "moov") ?: return null
        val udta = findAtom(r, moov.first, moov.second, "udta") ?: return null
        val meta = findAtom(r, udta.first, udta.second, "meta") ?: return null
        val ilst = findAtom(r, meta.first + 4, meta.second, "ilst") ?: return null

        var lyricsText: String? = null
        var title: String? = null
        var artist: String? = null
        var album: String? = null

        val lyr = findAtom(r, ilst.first, ilst.second, "\u00A9lyr")
        if (lyr != null) {
            val dataAtom = findAtom(r, lyr.first, lyr.second, "data")
            if (dataAtom != null) {
                val payloadStart = dataAtom.first + 4
                val len = (dataAtom.second - payloadStart).toInt()
                if (len > 0 && len < MAX_SCAN) {
                    r.position = payloadStart
                    val bytes = r.readBytes(len)
                    if (bytes != null && bytes.isNotEmpty()) {
                        lyricsText = TextCodec.decode(bytes).trim('\u0000', '\r', '\n', ' ')
                    }
                }
            }
        }

        fun readTextAtom(name: String): String? {
            val atom = findAtom(r, ilst.first, ilst.second, name) ?: return null
            val d = findAtom(r, atom.first, atom.second, "data") ?: return null
            val start = d.first + 4
            val len = (d.second - start).toInt()
            if (len <= 0 || len > 4096) return null
            r.position = start
            val bytes = r.readBytes(len) ?: return null
            return TextCodec.decode(bytes).trim('\u0000').takeIf { it.isNotBlank() }
        }

        title = readTextAtom("\u00A9nam")
        artist = readTextAtom("\u00A9ART")
        album = readTextAtom("\u00A9alb")

        val res = EmbeddedTagResult(lyricsText, null, title, artist, album)
        return if (res.hasLyrics || title != null) res else null
    }

    /** 在 [start, end) 范围内查找指定 fourcc 的 atom，返回 (内容起点, atom 结束位置) */
    private fun findAtom(r: BinaryReader, start: Long, end: Long, type: String): Pair<Long, Long>? {
        var p = start
        var guard = 0
        while (p + 8 <= end && guard++ < 4096) {
            r.position = p
            var sz = r.readUInt32BE()
            if (sz < 0) return null
            val t = r.readTag(4)
            var headerSize = 8L
            if (sz == 1L) {
                sz = r.readUInt64BE()
                headerSize = 16L
            }
            if (sz == 0L) sz = end - p
            if (sz < headerSize) return null
            if (t == type) return Pair(p + headerSize, p + sz)
            p += sz
        }
        return null
    }

    // ------------------------------------------------------------------ utils

    private fun syncSafe(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return -1
        return ((b[off].toInt() and 0x7F) shl 21) or ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or (b[off + 3].toInt() and 0x7F)
    }

    /** ID3v2 去同步化：FF 00 还原成 FF（把写入时插入的 0x00 去掉） */
    private fun deUnsynchronise(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            val cur = b[i]
            out.write(cur.toInt())
            if (cur == 0xFF.toByte() && i + 1 < b.size && b[i + 1] == 0x00.toByte()) {
                i += 2
            } else {
                i += 1
            }
        }
        return out.toByteArray()
    }

    private fun beInt(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return -1
        return ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
    }

    private fun indexOfZeroRun(b: ByteArray, from: Int, run: Int): Int {
        var i = from
        while (i + run <= b.size) {
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

    private fun indexOfAscii(b: ByteArray, s: String, from: Int = 0): Int {
        val needle = s.toByteArray(TextCodec.LATIN1)
        if (needle.isEmpty()) return -1
        var i = from
        outer@ while (i + needle.size <= b.size) {
            for (k in needle.indices) {
                if (b[i + k] != needle[k]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return -1
    }
}
