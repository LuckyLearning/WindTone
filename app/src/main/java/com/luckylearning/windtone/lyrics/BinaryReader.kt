package com.luckylearning.windtone.lyrics

import java.io.Closeable
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 带随机访问能力的小型二进制读取器。
 * 基于 FileChannel，可对 content:// 提供的文件描述符做 seek + 顺序读取，
 * 用于解析 ID3v2 / FLAC / Ogg / MP4 的标签块。
 */
internal class BinaryReader(private val channel: FileChannel) : Closeable {

    private val buf1 = ByteBuffer.allocate(1)

    val size: Long get() = channel.size()

    var position: Long
        get() = channel.position()
        set(value) {
            channel.position(value.coerceIn(0L, size))
        }

    fun skip(n: Long) {
        if (n > 0) position = position + n
    }

    fun readByte(): Int {
        buf1.clear()
        val n = channel.read(buf1)
        if (n <= 0) return -1
        buf1.flip()
        return buf1.get().toInt() and 0xFF
    }

    fun readBytes(n: Int): ByteArray? {
        if (n <= 0) return ByteArray(0)
        val bb = ByteBuffer.allocate(n)
        while (bb.hasRemaining()) {
            val r = channel.read(bb)
            if (r < 0) break
        }
        val got = bb.position()
        if (got == 0) return null
        return bb.array().copyOf(got)
    }

    fun readUInt32BE(): Long {
        val b = readBytes(4) ?: return -1
        if (b.size < 4) return -1
        return ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
            ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)
    }

    fun readUInt24BE(): Int {
        val b = readBytes(3) ?: return -1
        if (b.size < 3) return -1
        return ((b[0].toInt() and 0xFF) shl 16) or ((b[1].toInt() and 0xFF) shl 8) or (b[2].toInt() and 0xFF)
    }

    fun readUInt64BE(): Long {
        val b = readBytes(8) ?: return -1
        if (b.size < 8) return -1
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    /** 读取 n 字节并转成 ASCII/ISO-8859-1 字符串（用于 fourcc） */
    fun readTag(n: Int): String {
        val b = readBytes(n) ?: return ""
        return String(b, TextCodec.LATIN1)
    }

    override fun close() {
        runCatching { channel.close() }
    }

    companion object {
        fun open(stream: InputStream): BinaryReader? {
            return if (stream is FileInputStream) {
                runCatching { BinaryReader(stream.channel) }.getOrNull()
            } else {
                null
            }
        }
    }
}

/** ID3v2 / FLAC / MP4 / Ogg 等标签解析结果的统一载体 */
internal data class EmbeddedTagResult(
    val lyricsText: String? = null,
    val syncedLines: List<LyricLine>? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
) {
    val hasLyrics: Boolean get() = !lyricsText.isNullOrBlank() || !syncedLines.isNullOrEmpty()
}
