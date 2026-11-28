package com.luckylearning.windtone.playback

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * 把歌曲元信息与封面**内嵌进音频文件本身**，让「边播边存」的下载目录只需要两个文件：
 * 音频 + 歌词。这样把文件拷到任何播放器，歌手 / 专辑 / 封面都跟着走，不依赖我们的旁车。
 *
 * 只做**写入**，读取完全交给系统 [android.media.MediaMetadataRetriever] 与项目已有的
 * EmbeddedTags 解析器 —— 所以这里严格按各容器标准格式写，能被两者识别。
 *
 * 支持：
 *  - MP3  → ID3v2.3（TIT2 / TPE1 / TALB / TPE2 / TYER / TCON / TRCK / TPOS + APIC 封面）
 *  - FLAC → VORBIS_COMMENT + PICTURE 两个 metadata block
 *  - M4A  → moov.udta.meta.ilst（©nam / ©ART / ©aART / ©alb / ©day / trkn / covr）
 *
 * 三条硬约束：
 *  1. **绝不损坏音频**：任何解析 / 写入异常一律返回原始字节（catch-all），
 *     大不了标签丢了，音频照常能播。
 *  2. **幂等**：替换已有的同类标签块（ID3v2 / VORBIS_COMMENT / PICTURE / ilst item），
 *     不重复堆叠；其它 block（STREAMINFO / SEEKTABLE / moov 里的 trak 等）原样保留。
 *  3. **纯 JVM**：无 Android 依赖，单测直接在 JVM 跑。
 */
object AudioTags {

    data class Meta(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val year: Int? = null,
        val genre: String? = null,
        val trackNo: Int? = null,
        val discNumber: Int? = null,
    )

    /**
     * 把 [meta] 与 [cover] 写进 [audio]。
     * 不能识别的格式、或任何一步出错 → 原样返回 [audio]（音频一定保住）。
     */
    fun write(audio: ByteArray, meta: Meta, cover: ByteArray?): ByteArray {
        if (audio.isEmpty()) return audio
        return runCatching {
            when {
                isMp3(audio) -> writeMp3(audio, meta, cover)
                isFlac(audio) -> writeFlac(audio, meta, cover)
                isMp4(audio) -> writeMp4(audio, meta, cover)
                else -> audio
            }
        }.getOrDefault(audio)
    }

    // ------------------------------------------------------------ 格式探测

    internal fun isMp3(a: ByteArray): Boolean {
        if (a.size >= 3 && a[0] == 'I'.code.toByte() && a[1] == 'D'.code.toByte() && a[2] == '3'.code.toByte()) return true
        // MPEG 帧同步字：0xFF + (111xxxxxx)
        if (a.size >= 2 && a[0] == 0xFF.toByte() && (a[1].toInt() and 0xE0) == 0xE0) return true
        return false
    }

    internal fun isFlac(a: ByteArray): Boolean =
        a.size >= 4 && a[0] == 'f'.code.toByte() && a[1] == 'L'.code.toByte() &&
            a[2] == 'a'.code.toByte() && a[3] == 'C'.code.toByte()

    internal fun isMp4(a: ByteArray): Boolean =
        a.size >= 12 && ascii(a, 4, 4) == "ftyp"

    // ------------------------------------------------------------ MP3 / ID3v2.3

    private fun writeMp3(a: ByteArray, meta: Meta, cover: ByteArray?): ByteArray {
        var pos = 0
        if (a.size >= 10 && ascii(a, 0, 3) == "ID3") {
            val ver = a[3].toInt() and 0xFF
            val flags = a[5].toInt() and 0xFF
            val tagSize = syncSafeRead(a, 6)
            pos = 10 + tagSize + if (ver >= 4 && (flags and 0x10) != 0) 10 else 0
            if (pos > a.size) pos = 0
        }
        val frames = ArrayList<ByteArray>()
        meta.title?.takeIf { it.isNotBlank() }?.let { frames += textFrame("TIT2", it) }
        meta.artist?.takeIf { it.isNotBlank() }?.let { frames += textFrame("TPE1", it) }
        meta.album?.takeIf { it.isNotBlank() }?.let { frames += textFrame("TALB", it) }
        meta.albumArtist?.takeIf { it.isNotBlank() }?.let { frames += textFrame("TPE2", it) }
        meta.year?.takeIf { it > 0 }?.let { frames += textFrame("TYER", it.toString()) }
        meta.genre?.takeIf { it.isNotBlank() }?.let { frames += textFrame("TCON", it) }
        meta.trackNo?.takeIf { it > 0 }?.let { frames += textFrame("TRCK", it.toString()) }
        meta.discNumber?.takeIf { it > 0 }?.let { frames += textFrame("TPOS", it.toString()) }
        cover?.takeIf { it.isNotEmpty() }?.let { frames += apicFrame(it) }
        if (frames.isEmpty()) return a
        val body = concat(*frames.toTypedArray())
        // ID3v2.3 头：ID3 + 版本(3,0) + flags(0) + syncsafe(帧区长度，含 footer 时另算)
        val header = concat("ID3".toBytes(), byteArrayOf(3, 0, 0), syncSafe(body.size))
        return concat(header, body, a.copyOfRange(pos, a.size))
    }

    /** 文本帧：encoding=3(UTF-8) + 文本；v2.3 帧长度用普通 BE32（不是 syncsafe）。 */
    private fun textFrame(id: String, text: String): ByteArray {
        val payload = concat(byteArrayOf(3), text.toByteArray(StandardCharsets.UTF_8))
        return concat(id.toBytes(), be32(payload.size), byteArrayOf(0, 0), payload)
    }

    /** APIC：encoding + MIME(0 结尾) + 图片类型(3=正面封面) + 描述(0 结尾) + 图片字节。 */
    private fun apicFrame(cover: ByteArray): ByteArray {
        val mime = mimeOf(cover)
        val payload = concat(
            byteArrayOf(0),
            concat(mime.toByteArray(StandardCharsets.ISO_8859_1), byteArrayOf(0)),
            byteArrayOf(3, 0),
            cover,
        )
        return concat("APIC".toBytes(), be32(payload.size), byteArrayOf(0, 0), payload)
    }

    // ------------------------------------------------------------ FLAC

    private fun writeFlac(a: ByteArray, meta: Meta, cover: ByteArray?): ByteArray {
        if (a.size < 8) return a
        val out = ByteArrayOutputStream()
        out.write("fLaC".toBytes())
        var p = 4
        var last = false
        var sawStreamInfo = false
        // STREAMINFO(type 0) 必须是第一个 block，先原样写出；其余 block 收集起来重建。
        val keep = ArrayList<Pair<Int, ByteArray>>()
        while (!last && p + 4 <= a.size) {
            val h = a[p].toInt() and 0xFF
            last = (h and 0x80) != 0
            val type = h and 0x7F
            val len = be24(a, p + 1)
            p += 4
            if (p + len > a.size) return a
            val data = a.copyOfRange(p, p + len)
            p += len
            if (type == 0) {
                // 先写 STREAMINFO，last 恒为 false —— 后面一定还会跟 VORBIS_COMMENT
                out.write(blockHeader(len, 0, false))
                out.write(data)
                sawStreamInfo = true
            } else if (type != 4 && type != 6 && type != 5) {
                // 丢掉旧的 VORBIS_COMMENT/PICTURE/PADDING，保留 SEEKTABLE / CUESHEET / APPLICATION
                keep += type to data
            }
        }
        if (!sawStreamInfo) return a // 没有 STREAMINFO 的不是合法 FLAC，别动
        val vc: ByteArray = vorbisComment(meta)
        val pic: ByteArray? = cover?.takeIf { it.isNotEmpty() }?.let { flacPicture(it) }
        val all = ArrayList<Pair<Int, ByteArray>>(keep)
        all += 4 to vc
        if (pic != null) all += 6 to pic
        all.forEachIndexed { i, (type, data) ->
            out.write(blockHeader(data.size, type, i == all.size - 1))
            out.write(data)
        }
        // 音频帧原样接在 metadata 之后
        out.write(a, p, a.size - p)
        return out.toByteArray()
    }

    /** VORBIS_COMMENT：vendor + 注释列表 + framing byte。 */
    private fun vorbisComment(meta: Meta): ByteArray {
        val vendor = "WindTone".toByteArray(StandardCharsets.UTF_8)
        val entries = ArrayList<String>()
        meta.title?.takeIf { it.isNotBlank() }?.let { entries += "TITLE=$it" }
        meta.artist?.takeIf { it.isNotBlank() }?.let { entries += "ARTIST=$it" }
        meta.album?.takeIf { it.isNotBlank() }?.let { entries += "ALBUM=$it" }
        meta.albumArtist?.takeIf { it.isNotBlank() }?.let { entries += "ALBUMARTIST=$it" }
        meta.year?.takeIf { it > 0 }?.let { entries += "DATE=$it" }
        meta.genre?.takeIf { it.isNotBlank() }?.let { entries += "GENRE=$it" }
        meta.trackNo?.takeIf { it > 0 }?.let { entries += "TRACKNUMBER=$it" }
        meta.discNumber?.takeIf { it > 0 }?.let { entries += "DISCNUMBER=$it" }
        val out = ByteArrayOutputStream()
        out.write(le32(vendor.size)); out.write(vendor)
        out.write(le32(entries.size))
        for (e in entries) {
            val b = e.toByteArray(StandardCharsets.UTF_8)
            out.write(le32(b.size)); out.write(b)
        }
        out.write(1) // framing bit
        return out.toByteArray()
    }

    /** FLAC PICTURE block 的 data：type=3 + MIME + 描述 + 尺寸 + 图片字节。 */
    private fun flacPicture(cover: ByteArray): ByteArray {
        val mime = mimeOf(cover).toByteArray(StandardCharsets.ISO_8859_1)
        val out = ByteArrayOutputStream()
        out.write(be32(3)) // 3 = front cover
        out.write(be32(mime.size)); out.write(mime)
        out.write(be32(0)) // 描述长度
        out.write(be32(0)); out.write(be32(0)); out.write(be32(0)); out.write(be32(0)) // w/h/depth/colors
        out.write(be32(cover.size)); out.write(cover)
        return out.toByteArray()
    }

    // ------------------------------------------------------------ M4A / MP4 ilst

    private class Atom(val type: String, val start: Int, val header: Int, val size: Int) {
        val contentStart: Int get() = start + header
        val end: Int get() = start + size
    }

    private fun writeMp4(a: ByteArray, meta: Meta, cover: ByteArray?): ByteArray {
        val top = atoms(a, 0, a.size)
        val moov = top.firstOrNull { it.type == "moov" } ?: return a
        val newMoov = rebuildMoov(a, moov, meta, cover) ?: return a
        val out = ByteArrayOutputStream()
        var p = 0
        for (atom in top) {
            if (atom.type == "moov") {
                if (atom.start > p) out.write(a, p, atom.start - p)
                out.write(newMoov)
                p = atom.end
            }
        }
        if (p < a.size) out.write(a, p, a.size - p)
        return out.toByteArray()
    }

    private fun rebuildMoov(a: ByteArray, moov: Atom, meta: Meta, cover: ByteArray?): ByteArray? {
        val children = atoms(a, moov.contentStart, moov.end)
        var udta: Atom? = null
        var found = false
        val parts = ArrayList<ByteArray>()
        var p = moov.contentStart
        for (c in children) {
            if (c.type == "udta") {
                udta = c
                rebuildUdta(a, c, meta, cover)?.let { parts += it } ?: return null
                found = true
            } else {
                parts += a.copyOfRange(c.start, c.end)
            }
            p = c.end
        }
        if (!found) {
            udta = null
            val newUdta = makeUdta(meta, cover)
            parts += newUdta
        }
        val content = concat(*parts.toTypedArray())
        return atom("moov", content)
    }

    private fun rebuildUdta(a: ByteArray, udta: Atom, meta: Meta, cover: ByteArray?): ByteArray? {
        val children = atoms(a, udta.contentStart, udta.end)
        val parts = ArrayList<ByteArray>()
        var found = false
        for (c in children) {
            if (c.type == "meta") {
                rebuildMeta(a, c, meta, cover)?.let { parts += it } ?: return null
                found = true
            } else {
                parts += a.copyOfRange(c.start, c.end)
            }
        }
        if (!found) parts += makeMeta(meta, cover)
        return atom("udta", concat(*parts.toTypedArray()))
    }

    private fun rebuildMeta(a: ByteArray, metaAtom: Atom, meta: Meta, cover: ByteArray?): ByteArray? {
        // meta 的内容前 4 字节是 version/flags，之后才是子 atom
        val cs = metaAtom.contentStart
        if (cs + 4 > metaAtom.end) return null
        val versionFlags = a.copyOfRange(cs, cs + 4)
        val children = atoms(a, cs + 4, metaAtom.end)
        val parts = ArrayList<ByteArray>()
        var found = false
        for (c in children) {
            if (c.type == "ilst") {
                rebuildIlst(a, c, meta, cover)?.let { parts += it } ?: return null
                found = true
            } else {
                parts += a.copyOfRange(c.start, c.end)
            }
        }
        if (!found) parts += makeIlst(meta, cover)
        return atom("meta", concat(versionFlags, *parts.toTypedArray()))
    }

    private fun rebuildIlst(a: ByteArray, ilst: Atom, meta: Meta, cover: ByteArray?): ByteArray? {
        val items = atoms(a, ilst.contentStart, ilst.end).toMutableList()
        val wanted = wantedItems(meta, cover)
        val seen = HashSet<String>()
        val result = ArrayList<ByteArray>()
        for (item in items) {
            val payload = a.copyOfRange(item.start, item.end)
            if (item.type in wanted) {
                // 同名 item 用新值替换（wantedItems 里已构造好），避免重复堆叠
                if (seen.add(item.type)) result += wanted.getValue(item.type)
            } else {
                result += payload
            }
        }
        for ((type, bytes) in wanted) if (seen.add(type)) result += bytes
        return atom("ilst", concat(*result.toTypedArray()))
    }

    private fun makeUdta(meta: Meta, cover: ByteArray?): ByteArray = atom("udta", makeMeta(meta, cover))
    private fun makeMeta(meta: Meta, cover: ByteArray?): ByteArray =
        atom("meta", concat(be32(0), makeIlst(meta, cover)))
    private fun makeIlst(meta: Meta, cover: ByteArray?): ByteArray =
        atom("ilst", concat(*wantedItems(meta, cover).values.toTypedArray()))

    private fun wantedItems(meta: Meta, cover: ByteArray?): LinkedHashMap<String, ByteArray> {
        val m = LinkedHashMap<String, ByteArray>()
        meta.title?.takeIf { it.isNotBlank() }?.let { m["\u00A9nam"] = textItem("\u00A9nam", it) }
        meta.artist?.takeIf { it.isNotBlank() }?.let { m["\u00A9ART"] = textItem("\u00A9ART", it) }
        meta.album?.takeIf { it.isNotBlank() }?.let { m["\u00A9alb"] = textItem("\u00A9alb", it) }
        meta.albumArtist?.takeIf { it.isNotBlank() }?.let { m["aART"] = textItem("aART", it) }
        meta.year?.takeIf { it > 0 }?.let { m["\u00A9day"] = textItem("\u00A9day", it.toString()) }
        meta.genre?.takeIf { it.isNotBlank() }?.let { m["\u00A9gen"] = textItem("\u00A9gen", it) }
        meta.trackNo?.takeIf { it > 0 }?.let { m["trkn"] = trackItem(it) }
        cover?.takeIf { it.isNotEmpty() }?.let { m["covr"] = coverItem(it) }
        return m
    }

    /** data atom：version=1(UTF-8) + locale=0 + 文本字节。 */
    private fun textItem(type: String, text: String): ByteArray {
        val payload = text.toByteArray(StandardCharsets.UTF_8)
        val data = atom("data", concat(byteArrayOf(0, 0, 0, 1), be32(0), payload))
        return atom(type, data)
    }

    /** trkn：data(version=0) + 8 字节 [保留, 曲目号, 总数, 保留]（各 BE16）。 */
    private fun trackItem(track: Int): ByteArray {
        val body = concat(be16(0), be16(track.coerceAtMost(0xFFFF)), be16(0), be16(0))
        val data = atom("data", concat(be32(0), be32(0), body))
        return atom("trkn", data)
    }

    /** covr：data version/flags = 0x0000000D(JPEG) 或 0x0000000E(PNG)。 */
    private fun coverItem(cover: ByteArray): ByteArray {
        val flag = if (isPng(cover)) byteArrayOf(0, 0, 0, 14) else byteArrayOf(0, 0, 0, 13)
        val data = atom("data", concat(flag, be32(0), cover))
        return atom("covr", data)
    }

    private fun atom(type: String, content: ByteArray): ByteArray =
        concat(be32(8 + content.size), type.toBytes(), content)

    /** 解析 [from, to) 范围内的一层 atom；只认 32 位 size（大于 4GB 的 m4a 不现实）。 */
    private fun atoms(a: ByteArray, from: Int, to: Int): List<Atom> {
        val out = ArrayList<Atom>()
        var p = from
        while (p + 8 <= to) {
            val size = be32read(a, p)
            if (size < 8 || p + size > to) break
            out += Atom(ascii(a, p + 4, 4), p, 8, size)
            p += size
        }
        return out
    }

    // ------------------------------------------------------------ 二进制工具

    internal fun be32(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun be16(v: Int): ByteArray = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun le32(v: Int): ByteArray = byteArrayOf(
        v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte(),
    )

    private fun be32read(a: ByteArray, at: Int): Int =
        ((a[at].toInt() and 0xFF) shl 24) or ((a[at + 1].toInt() and 0xFF) shl 16) or
            ((a[at + 2].toInt() and 0xFF) shl 8) or (a[at + 3].toInt() and 0xFF)

    private fun be24(a: ByteArray, at: Int): Int =
        ((a[at].toInt() and 0xFF) shl 16) or ((a[at + 1].toInt() and 0xFF) shl 8) or (a[at + 2].toInt() and 0xFF)

    private fun syncSafeRead(a: ByteArray, at: Int): Int =
        ((a[at].toInt() and 0x7F) shl 21) or ((a[at + 1].toInt() and 0x7F) shl 14) or
            ((a[at + 2].toInt() and 0x7F) shl 7) or (a[at + 3].toInt() and 0x7F)

    /** 把长度编码成 ID3 syncsafe（每字节低 7 位），超过 256MB 截断（ID3 上限）。 */
    private fun syncSafe(v: Int): ByteArray {
        val n = v.coerceAtMost(0x0FFFFFFF)
        return byteArrayOf(
            ((n ushr 21) and 0x7F).toByte(),
            ((n ushr 14) and 0x7F).toByte(),
            ((n ushr 7) and 0x7F).toByte(),
            (n and 0x7F).toByte(),
        )
    }

    private fun blockHeader(len: Int, type: Int, last: Boolean): ByteArray = byteArrayOf(
        ((if (last) 0x80 else 0) or type).toByte(),
        ((len ushr 16) and 0xFF).toByte(),
        ((len ushr 8) and 0xFF).toByte(),
        (len and 0xFF).toByte(),
    )

    private fun ascii(a: ByteArray, at: Int, len: Int): String =
        String(a, at, len, StandardCharsets.ISO_8859_1)

    private fun String.toBytes(): ByteArray = toByteArray(StandardCharsets.ISO_8859_1)

    private fun concat(vararg parts: ByteArray): ByteArray {
        var total = 0
        for (p in parts) total += p.size
        val out = ByteArray(total)
        var off = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, off, p.size)
            off += p.size
        }
        return out
    }

    private fun mimeOf(cover: ByteArray): String = when {
        cover.size >= 8 && cover[0] == 0x89.toByte() && ascii(cover, 1, 3) == "PNG" -> "image/png"
        cover.size >= 3 && cover[0] == 0xFF.toByte() && cover[1] == 0xD8.toByte() -> "image/jpeg"
        cover.size >= 12 && ascii(cover, 0, 4) == "RIFF" && ascii(cover, 8, 4) == "WEBP" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun isPng(cover: ByteArray): Boolean =
        cover.size >= 8 && cover[0] == 0x89.toByte() && ascii(cover, 1, 3) == "PNG"
}
