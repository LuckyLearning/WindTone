package com.luckylearning.windtone.lyrics

import java.io.Closeable
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

/**
 * 带随机访问能力的小型二进制读取器。
 * 基于 [SeekableByteChannel]：本地文件走 FileChannel（content:// 的文件描述符也算），
 * 远程音频则用内存里的一段字节（[open] 的 ByteArray 重载），
 * 用于解析 ID3v2 / FLAC / Ogg / MP4 的标签块。
 */
internal class BinaryReader(private val channel: SeekableByteChannel) : Closeable {

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

        /**
         * 用一段内存字节构造（v1.1.43）。
         *
         * 远程音频（网盘 / 在线歌单）拿不到 fd，只能按 Range 取前若干字节回来解析标签。
         * 取不到的部分读作 -1，解析器据此判断「标签不在这一段里」——所以调用方必须
         * 只对「标签一定在文件开头」的容器走这条路（flac / mp3 / ogg），m4a 的 moov
         * 可能在尾部，不在此列。
         */
        fun open(bytes: ByteArray): BinaryReader = BinaryReader(ByteArrayChannel(bytes))
    }
}

/**
 * 把 ByteArray 包成只读的随机访问通道（够 [BinaryReader] 用）。
 *
 * 注意 [size] 返回的是**这段字节的长度**，不是原文件大小：解析器若 seek 到「文件尾」
 * 会看到这一段数据的末尾。对标签在头部的容器（flac / mp3 / ogg）这正是我们要的语义。
 */
private class ByteArrayChannel(private val data: ByteArray) : SeekableByteChannel {

    private var pos = 0L
    private var open = true

    override fun read(dst: ByteBuffer): Int {
        if (!open) throw ClosedChannelException()
        if (pos >= data.size) return -1
        val count = minOf(dst.remaining(), (data.size - pos).toInt())
        dst.put(data, pos.toInt(), count)
        pos += count
        return count
    }

    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()

    override fun position(): Long = pos

    override fun position(newPosition: Long): SeekableByteChannel {
        pos = newPosition.coerceIn(0L, data.size.toLong())
        return this
    }

    override fun size(): Long = data.size.toLong()

    override fun truncate(size: Long): SeekableByteChannel = this

    override fun isOpen(): Boolean = open

    override fun close() {
        open = false
    }
}

/** ID3v2 / FLAC / MP4 / Ogg 等标签解析结果的统一载体 */
internal data class EmbeddedTagResult(
    val lyricsText: String? = null,
    val syncedLines: List<LyricLine>? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val picture: ByteArray? = null,
) {
    val hasLyrics: Boolean get() = !lyricsText.isNullOrBlank() || !syncedLines.isNullOrEmpty()
}
