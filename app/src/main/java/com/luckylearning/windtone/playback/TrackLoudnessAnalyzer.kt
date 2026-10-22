package com.luckylearning.windtone.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 单曲响度分析：解码音频前 [ANALYZE_MS] 毫秒，混单声道累计 RMS。
 *
 * ## 为什么只分析开头两分钟
 * 整轨解码在长曲目（DJ 混音 / 有声书）上要几十秒；曲目的响度分布
 * 前两分钟基本能代表全曲（主歌副歌都进来了）。省下的电与时间
 * 在车机上（熄火就断电）尤其值钱。
 *
 * ## 失败语义
 * 任何一步失败都返回 null，由调用方按「这首没分析过」处理 ——
 * 不重试、不记崩溃日志（解不动的音频格式本来就不该为响度平衡硬扛）。
 * 本文件是 [LoudnessScan] 纯数学与 Android 解码器之间的**薄壳**：
 * 所有可断言的逻辑（RMS、增益、因子）都在纯函数里，这里只做搬运。
 */
object TrackLoudnessAnalyzer {

    /** 最长分析的音频时长（毫秒） */
    const val ANALYZE_MS = 120_000L

    /** 跳过开头的静音与淡入（毫秒）：曲头空白会把 RMS 拉低、导致压得过狠 */
    private const val SKIP_MS = 500L

    /** 解码的墙钟兜底（毫秒）：个别 ROM 的解码器不出 EOS 也不出数，别把分析线程挂死 */
    private const val WALL_CLOCK_BUDGET_MS = 20_000L

    /**
     * 分析 [uri] 指向的音频。
     * @return 增益（mB，带符号）；解不开 / 没有音频轨 / 没解到样本时为 null
     */
    fun analyze(context: Context, uri: Uri): Int? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return@runCatching null
            extractor.selectTrack(trackIndex)
            extractor.seekTo(SKIP_MS * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                decodeLoop(extractor, codec)
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }.getOrNull()

    /** 解码主循环：喂到 EOS / 时长到点 / 墙钟超时，收口出增益 mB */
    private fun decodeLoop(extractor: MediaExtractor, codec: MediaCodec): Int? {
        val info = MediaCodec.BufferInfo()
        var sumSquares = 0.0
        var sampleCount = 0
        var sampleRate = 0
        var channelCount = 0
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        var sawInputEos = false
        var outputDone = false
        val startedAt = System.nanoTime()
        val targetSamples = Long.MAX_VALUE // sampleRate 未知前先不设限

        fun syncFormat() {
            val f = codec.outputFormat
            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channelCount = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
        }

        while (!outputDone) {
            if (!sawInputEos) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)
                    if (buf == null) {
                        sawInputEos = true
                    } else {
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> syncFormat()
                outIndex >= 0 -> {
                    if (sampleRate == 0) syncFormat()
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0 && channelCount > 0) {
                        val added = accumulate(buffer, info, channelCount, pcmEncoding) { sum, count ->
                            sumSquares += sum
                            sampleCount += count
                        }
                        val limit =
                            if (sampleRate > 0) sampleRate.toLong() * ANALYZE_MS / 1000 else targetSamples
                        if (sampleCount >= limit || !added) outputDone = true
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            if (System.nanoTime() - startedAt > WALL_CLOCK_BUDGET_MS * 1_000_000) break
        }
        return LoudnessScan.gainMbFromAccumulator(sumSquares, sampleCount)
    }

    /**
     * 把一帧输出 PCM 折进累计器（声道平均成单声道）。
     *
     * 16-bit 与 float 两种输出都处理；float 样本先乘回 Short 满幅，
     * 与整型路径共用 [LoudnessScan] 的同一套量纲。
     *
     * @return 是否消费到了样本（连续零样本说明该收口了）
     */
    private fun accumulate(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channels: Int,
        pcmEncoding: Int,
        add: (Double, Int) -> Unit,
    ): Boolean {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        var count = 0
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floatCount = buffer.remaining() / 4
                var i = 0
                while (i + channels <= floatCount) {
                    var acc = 0.0
                    for (c in 0 until channels) acc += buffer.float.toDouble()
                    val mono = acc / channels * 32768.0
                    sum += mono * mono
                    count += channels
                    i += channels
                }
            }
            else -> {
                // 16-bit 整型（绝大多数解码器的输出）
                val shortCount = buffer.remaining() / 2
                var i = 0
                while (i + channels <= shortCount) {
                    var acc = 0.0
                    for (c in 0 until channels) acc += buffer.short.toDouble()
                    val mono = acc / channels
                    sum += mono * mono
                    count += channels
                    i += channels
                }
            }
        }
        if (count > 0) add(sum, count)
        return count > 0
    }
}
