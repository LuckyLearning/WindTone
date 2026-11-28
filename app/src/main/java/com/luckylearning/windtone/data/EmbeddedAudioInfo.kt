package com.luckylearning.windtone.data

import android.content.Context
import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.lyrics.BinaryReader
import com.luckylearning.windtone.lyrics.EmbeddedTags
import java.io.ByteArrayOutputStream

/**
 * 从**音频字节**里直接读内嵌标签与封面。
 *
 * 背景（v1.3.15）：「边播边存」下载时由 [com.luckylearning.windtone.playback.AudioTags]
 * 把歌名/歌手/专辑/封面写进音频本身，但读取侧此前只走
 * [android.media.MediaMetadataRetriever] —— MMR 对 SAF `content://` 文档 uri 支持很弱，
 * 尤其 **FLAC 的 Vorbis PICTURE 块根本不会暴露成 embeddedPicture**，于是出现
 * 「写进去了、播放时封面/标签还是空白」。本模块把**项目自己的字节级解析器**
 * [EmbeddedTags] 接到读取侧，与写入侧用同一套容器格式，形成闭环。
 *
 * 两条硬约束：
 *  1. 纯 JVM 核心 [parse] 不依赖 Android，单测可直接构造字节验证「写入 → 读回」；
 *  2. 一次读取的字节量按堆内存取上限，超大文件直接放弃（宁可没封面也不能 OOM）。
 */
object EmbeddedAudioInfo {

    data class Info(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val picture: ByteArray? = null,
    )

    /**
     * 单次读取上限：堆的 1/8，夹 4~32MB。
     * 不写死 MB 常量，随设备堆大小伸缩（车机低堆设备不会被一首大文件打穿）。
     */
    internal fun maxReadBytes(): Int =
        (Runtime.getRuntime().maxMemory() / 8)
            .coerceIn(4L * 1024 * 1024, 32L * 1024 * 1024)
            .toInt()

    /**
     * 纯 JVM 解析核心。给一段完整音频字节，返回能从内嵌标签读到的字段。
     *
     * @param wantPicture 是否取封面。MP3 的 APIC 是标签里最大的一帧，列表只读文字时
     *   传 false 可整帧跳过，省下一份 1~8MB 的拷贝。
     */
    internal fun parse(
        audio: ByteArray,
        extension: String,
        mime: String?,
        wantPicture: Boolean,
    ): Info? {
        if (audio.isEmpty()) return null
        val result = runCatching {
            BinaryReader.open(audio).use { reader ->
                EmbeddedTags.parse(reader, extension, mime, wantPicture)
            }
        }.getOrNull() ?: return null
        return Info(
            title = result.title?.takeIf { it.isNotBlank() },
            artist = result.artist?.takeIf { it.isNotBlank() },
            album = result.album?.takeIf { it.isNotBlank() },
            picture = if (wantPicture) result.picture?.takeIf { it.isNotEmpty() } else null,
        )
    }

    /** 从 SAF / 本地 content uri 读字节解析；读不到、超上限、解析失败一律返回 null。 */
    fun read(
        context: Context,
        uri: Uri,
        extension: String,
        mime: String?,
        wantPicture: Boolean,
    ): Info? {
        val limit = maxReadBytes()
        val bytes = try {
            val stream = context.contentResolver.openInputStream(uri) ?: return null
            stream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    // 超过预算立刻放弃：半截字节解析出来的标签不可信，且可能误伤解码
                    if (total + n > limit) return null
                    out.write(buf, 0, n)
                    total += n
                }
                out.toByteArray()
            }
        } catch (_: Throwable) {
            return null
        }
        return parse(bytes, extension, mime, wantPicture)
    }

    /** 便捷重载：从 [Song] 取 uri / 扩展名 / MIME。 */
    fun read(context: Context, song: Song, wantPicture: Boolean): Info? {
        val name = song.displayName.ifBlank { song.uri.lastPathSegment.orEmpty() }
        return read(context, song.uri, AudioFormats.extensionOf(name), song.mimeType, wantPicture)
    }
}
