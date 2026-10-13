package com.luckylearning.windtone.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.luckylearning.windtone.data.model.Song
import kotlin.math.roundToInt

/**
 * 音频技术信息读取。
 *
 * 用 [MediaExtractor] 而不是 [android.media.MediaMetadataRetriever]：
 * 前者能给出**轨道级**参数（编码格式、采样率、声道数、标称码率、位深），
 * MediaMetadataRetriever 只给「标称码率 + 时长」，且 Android 11 以下拿不到采样率。
 */
object AudioInspector {

    data class Info(
        val extension: String,
        /** 容器友好名，如 MP3 / MP4 / Ogg */
        val container: String,
        /** 编码友好名，如 MPEG Layer III / FLAC / AAC / Vorbis */
        val codec: String,
        val mime: String?,
        val bitrateKbps: Int?,
        /** 码率是否为按文件大小推算（标称码率缺失时的兜底） */
        val bitrateEstimated: Boolean,
        val sampleRateHz: Int?,
        val channels: Int?,
        val bitsPerSample: Int?,
        val durationMs: Long,
        val sizeBytes: Long,
    ) {
        val sizeText: String get() = formatSize(sizeBytes)
        val durationText: String get() = formatDuration(durationMs)
        val bitrateText: String get() = bitrateKbps?.let { "$it kbps" + if (bitrateEstimated) "（估算）" else "" } ?: "—"
        val sampleRateText: String get() = sampleRateHz?.let { formatHz(it) } ?: "—"
        val channelsText: String get() = channels?.let { formatChannels(it) } ?: "—"
        val bitsText: String get() = bitsPerSample?.let { "$it bit" } ?: "—"
    }

    fun inspect(context: Context, song: Song): Info? {
        val ext = song.displayName.substringAfterLast('.', "").lowercase()
        val container = containerName(ext)
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, song.uri, null)
            var mime: String? = null
            var sampleRate: Int? = null
            var channels: Int? = null
            var bitrate: Int? = null
            var bits: Int? = null
            var durationUs = 0L

            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val m = fmt.stringOrNull(MediaFormat.KEY_MIME) ?: continue
                if (!m.startsWith("audio/")) continue
                mime = m
                sampleRate = fmt.intOrNull(MediaFormat.KEY_SAMPLE_RATE)
                channels = fmt.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)
                bitrate = fmt.intOrNull(MediaFormat.KEY_BIT_RATE)
                // "bits-per-sample" 无稳定常量，用字面 key，缺失时返回 null
                bits = fmt.intOrNull("bits-per-sample")
                durationUs = fmt.longOrNull(MediaFormat.KEY_DURATION) ?: 0L
                break
            }

            val durationMs = when {
                durationUs > 0 -> durationUs / 1000
                song.durationMs > 0 -> song.durationMs
                else -> 0L
            }

            var estimated = false
            var finalBitrate = bitrate?.takeIf { it > 0 }
            if (finalBitrate == null && durationMs > 0 && song.sizeBytes > 0) {
                finalBitrate = (song.sizeBytes * 8.0 / (durationMs / 1000.0)).roundToInt()
                estimated = true
            }

            Info(
                extension = ext.ifBlank { "未知" },
                container = container,
                codec = codecName(mime, ext),
                mime = mime,
                bitrateKbps = finalBitrate?.let { (it / 1000.0).roundToInt() },
                bitrateEstimated = estimated,
                sampleRateHz = sampleRate,
                channels = channels,
                bitsPerSample = bits,
                durationMs = durationMs,
                sizeBytes = song.sizeBytes,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "读取音频信息失败: ${song.uri}", t)
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    // ------------------------------------------------------------ 名称映射

    fun codecName(mime: String?, ext: String): String = when {
        mime == null -> ext.uppercase().ifBlank { "未知" }
        mime.contains("mpeg", true) && mime.contains("layer", true) -> "MPEG Layer III"
        mime == "audio/mpeg" || mime == "audio/mp3" -> "MPEG Layer III"
        mime.contains("aac", true) || mime.contains("mp4a", true) -> "AAC"
        mime.contains("flac", true) -> "FLAC（无损）"
        mime.contains("alac", true) -> "ALAC（无损）"
        mime.contains("vorbis", true) -> "Vorbis"
        mime.contains("opus", true) -> "Opus"
        mime.contains("amr", true) -> "AMR"
        mime.contains("raw", true) || mime.contains("pcm", true) -> "PCM"
        mime.contains("midi", true) -> "MIDI"
        else -> mime.substringAfter('/').uppercase()
    }

    fun containerName(ext: String): String = when (ext) {
        "mp3", "mp2", "mpga" -> "MP3"
        "flac" -> "FLAC"
        "wav", "wave" -> "WAV / RIFF"
        "m4a", "m4b", "mp4", "aac" -> "MP4 / ISO-BMFF"
        "ogg", "oga" -> "Ogg"
        "opus" -> "Ogg Opus"
        "amr" -> "AMR"
        else -> ext.uppercase().ifBlank { "未知" }
    }

    fun formatHz(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000.0)

    fun formatChannels(ch: Int): String = when (ch) {
        1 -> "单声道"
        2 -> "立体声"
        6 -> "5.1 声道"
        8 -> "7.1 声道"
        else -> "$ch 声道"
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "—"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "—"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1) "%.2f MB".format(mb) else "%.0f KB".format(bytes / 1024.0)
    }

    // ------------------------------------------------------------ 内部

    private fun MediaFormat.stringOrNull(key: String): String? =
        runCatching { if (containsKey(key)) getString(key) else null }.getOrNull()

    private fun MediaFormat.intOrNull(key: String): Int? =
        runCatching { if (containsKey(key)) getInteger(key) else null }.getOrNull()

    private fun MediaFormat.longOrNull(key: String): Long? =
        runCatching { if (containsKey(key)) getLong(key) else null }.getOrNull()

    private const val TAG = "AudioInspector"
}
