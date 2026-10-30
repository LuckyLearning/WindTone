package com.luckylearning.windtone.playback

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 从曲目文件头尾读 ReplayGain / iTunes 标签，返回增益 mB。
 *
 * 读不到（流媒体、没有标签、文件打不开）返回 null，调用方回落 RMS。
 * 只读头 [HEAD_BYTES] 与尾 [TAIL_BYTES]：ID3 / FLAC / Ogg 在头上，
 * APEv2 和部分 MP4 的 `moov` 在尾上。不整轨解码。
 */
object ReplayGainReader {

    const val HEAD_BYTES = 256 * 1024
    const val TAIL_BYTES = 64 * 1024

    /** 读出文件里全部可识别的增益标签；没有则 null。 */
    fun readGains(context: Context, uri: Uri): ReplayGainTags.Gains? = runCatching {
        val (head, tail) = readParts(context, uri) ?: return null
        ReplayGainTags.merge(
            ReplayGainTags.parse(head),
            tail?.let { ReplayGainTags.parse(it) },
        )?.takeUnless { it.isEmpty() }
    }.getOrNull()

    /**
     * 按 [mode] 解析出最终增益（mB）。
     * 兼容旧调用；新代码应优先 [readGains] + [ReplayGainTags.Gains.preferredDb]，
     * 以便一次读取同时填曲目 / 专辑两份缓存。
     */
    fun read(context: Context, uri: Uri, mode: ReplayGainMode = ReplayGainMode.TRACK): Int? {
        val gains = readGains(context, uri) ?: return null
        val db = gains.preferredDb(mode) ?: return null
        return ReplayGainTags.toGainMb(db)
    }

    private fun readParts(context: Context, uri: Uri): Pair<ByteArray, ByteArray?>? {
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (pfd != null) {
            pfd.use {
                FileInputStream(it.fileDescriptor).use { input ->
                    val channel = input.channel
                    val size = runCatching { channel.size() }.getOrDefault(-1L)
                    if (size > 0L) {
                        val head = readAt(channel, 0L, minOf(size, HEAD_BYTES.toLong()).toInt())
                        if (head.isEmpty()) return null
                        if (size <= HEAD_BYTES) return head to null
                        val tailLen = minOf(TAIL_BYTES.toLong(), size).toInt()
                        val tail = readAt(channel, size - tailLen, tailLen)
                        return head to tail.takeIf { it.isNotEmpty() }
                    }
                }
            }
        }
        val head = readStream(context, uri) ?: return null
        return head to null
    }

    private fun readAt(channel: FileChannel, pos: Long, len: Int): ByteArray {
        if (len <= 0) return ByteArray(0)
        val buf = ByteBuffer.allocate(len)
        channel.position(pos)
        while (buf.hasRemaining()) {
            val n = channel.read(buf)
            if (n < 0) break
        }
        val out = ByteArray(buf.position())
        buf.flip()
        buf.get(out)
        return out
    }

    private fun readStream(context: Context, uri: Uri): ByteArray? {
        val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() ?: return null
        input.use {
            val buf = ByteArray(HEAD_BYTES)
            var off = 0
            while (off < buf.size) {
                val n = it.read(buf, off, buf.size - off)
                if (n < 0) break
                off += n
            }
            return if (off == 0) null else buf.copyOf(off)
        }
    }
}
