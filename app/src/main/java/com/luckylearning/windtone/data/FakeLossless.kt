package com.luckylearning.windtone.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 「假无损」识别（竞品借鉴：Salt Player 假无损识别）。
 *
 * 判据只有一条，但足够可靠：**有损编码必然砍掉高频**。
 * MP3 128k 截止约 16 kHz、320k 约 20 kHz，AAC 常见 16~18 kHz；
 * 真无损（FLAC / WAV / ALAC）在 44.1 kHz 采样下高频一直铺到 20 kHz 以上。
 * 所以：声称无损的容器（flac / wav / ape / aiff / dsf / alac）如果能量在
 * 16.5 kHz 上下就断崖，基本可以判定是「有损转无损」（拿 MP3 转出来的 FLAC）。
 *
 * 实现分两块：
 * - [Spectrum] / [judge] 是**纯 Kotlin**（自己写的 radix-2 FFT），JVM 单测可直接
 *   用合成信号断言；
 * - [PcmProbe] 负责用 MediaExtractor + MediaCodec 解出中段 PCM，
 *   只在用户主动打开「音频信息」页时才跑（默认不解码整曲，代价可控）。
 */
object FakeLossless {

    /** 判定结论；UI 负责把枚举映射成文案 */
    enum class Verdict {
        /** 声称无损且高频确实铺满 → 看起来是真的 */
        LIKELY_LOSSLESS,

        /** 声称无损但高频断崖 → 疑似有损转码 */
        SUSPECTED_LOSSY_SOURCE,

        /** 本来就是有损格式，不适用 */
        NOT_APPLICABLE,

        /** 解不出来 / 信息不足 */
        UNKNOWN,
    }

    data class Result(
        val verdict: Verdict,
        /** 检测到的频谱截止频率（Hz）；null = 没测出来 */
        val cutoffHz: Int? = null,
        val sampleRateHz: Int? = null,
    )

    /** 高频截止阈值：低于它就判定为「有损来源」 */
    const val LOSSY_CUTOFF_HZ = 16_500

    private val LOSSLESS_EXTS = setOf("flac", "wav", "wave", "aiff", "aif", "ape", "dsf", "dff", "alac")

    fun isLosslessContainer(ext: String, mime: String?): Boolean {
        if (ext.lowercase() in LOSSLESS_EXTS) return true
        val m = mime?.lowercase().orEmpty()
        return m.contains("flac") || m.contains("alac") || m.contains("pcm") ||
            m.contains("raw") || m.contains("wav")
    }

    fun judge(ext: String, mime: String?, cutoffHz: Int?, sampleRateHz: Int?): Result {
        if (!isLosslessContainer(ext, mime)) {
            return Result(Verdict.NOT_APPLICABLE, cutoffHz, sampleRateHz)
        }
        if (cutoffHz == null || cutoffHz <= 0) {
            return Result(Verdict.UNKNOWN, null, sampleRateHz)
        }
        // 低采样率素材（22.05k / 24k）本身高频上限就低，拿 16.5k 判会全线误报
        if ((sampleRateHz ?: 44100) < 32_000) {
            return Result(Verdict.UNKNOWN, cutoffHz, sampleRateHz)
        }
        return Result(
            if (cutoffHz < LOSSY_CUTOFF_HZ) Verdict.SUSPECTED_LOSSY_SOURCE else Verdict.LIKELY_LOSSLESS,
            cutoffHz,
            sampleRateHz,
        )
    }

    fun inspect(context: Context, uri: Uri, ext: String, mime: String?): Result {
        val pcm = PcmProbe.readMiddle(context, uri) ?: return Result(Verdict.UNKNOWN)
        val cutoff = Spectrum.cutoffHz(pcm.samples, pcm.sampleRate)
        return judge(ext, mime, cutoff, pcm.sampleRate)
    }
}

/** 频谱截止频率估计（纯 Kotlin，可 JVM 单测） */
object Spectrum {

    /** 单帧 FFT 点数；2048 @44.1k → 21.5 Hz 分辨率，足够分辨 16k/20k 断崖 */
    const val FFT_SIZE = 2048

    /** 最多平均几帧（相邻帧 50% 重叠） */
    private const val MAX_FRAMES = 8

    /** 相对最大幅度的能量门槛（约 -50 dB）：低于它的频点算「没有内容」 */
    private const val REL_THRESHOLD = 0.0032

    /**
     * 估计高频截止：平均功率谱从最高频往下找，第一个超过门槛的频点。
     *
     * 之所以从高往低找而不是「找最大下降沿」：有损编码的高频不是渐降，是
     * **直接归零**，从顶往下第一个非空频点就是截止点，简单且不依赖曲线形状。
     *
     * @return 截止频率（Hz）；样本太少 / 全静音返回 null
     */
    fun cutoffHz(samples: FloatArray, sampleRate: Int): Int? {
        if (samples.size < FFT_SIZE || sampleRate <= 0) return null
        val frames = minOf(MAX_FRAMES, samples.size / (FFT_SIZE / 2) - 1).coerceAtLeast(1)
        val avg = DoubleArray(FFT_SIZE / 2 + 1)
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)

        for (f in 0 until frames) {
            val offset = f * (FFT_SIZE / 2)
            var energy = 0.0
            for (i in 0 until FFT_SIZE) {
                val s = samples[offset + i].toDouble()
                energy += s * s
                // Hann 窗：抑制帧边界的频谱泄漏，否则截止点会被抬高
                re[i] = s * (0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1)))
                im[i] = 0.0
            }
            if (energy < 1e-6) continue // 静音帧不参与平均
            fft(re, im)
            for (b in avg.indices) {
                avg[b] += hypot(re[b], im[b])
            }
        }

        var peak = 0.0
        for (v in avg) if (v > peak) peak = v
        if (peak <= 0.0) return null
        val threshold = peak * REL_THRESHOLD

        for (b in avg.indices.reversed()) {
            if (avg[b] >= threshold) {
                val hz = (b.toDouble() * sampleRate / FFT_SIZE).roundToInt()
                return hz.coerceAtMost(sampleRate / 2)
            }
        }
        return null
    }

    /** 迭代式 radix-2 FFT（原地） */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** 供单测构造正弦信号用：静音段的能量检查依赖它 */
    internal fun sine(freqHz: Int, sampleRate: Int, size: Int, amplitude: Float = 0.5f): FloatArray =
        FloatArray(size) { i -> amplitude * kotlin.math.sin(2.0 * PI * freqHz * i / sampleRate).toFloat() }

    /** 供单测构造白噪声（确定性 LCG，避免测试随机不稳定） */
    internal fun noise(size: Int, amplitude: Float = 0.3f, seed: Long = 42L): FloatArray {
        var s = seed
        return FloatArray(size) {
            s = s * 6364136223846793005L + 1442695040888963407L
            val v = ((s ushr 40) and 0xFFFF).toFloat() / 32768f - 1f
            v * amplitude
        }
    }
}

/**
 * 中段 PCM 采样：解约 3 秒、单声道混缩。
 *
 * 为什么不从头解：很多文件开头是淡入 / 静音，判不准；中段最能代表整曲频谱。
 * 为什么只解 3 秒：解码整曲是秒级到分钟级的开销，而频谱特征 3 秒足够。
 */
object PcmProbe {

    data class Pcm(val samples: FloatArray, val sampleRate: Int)

    private const val TARGET_SECONDS = 3

    fun readMiddle(context: Context, uri: Uri): Pcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
            if (durationUs > 2_000_000L) {
                // 跳过前 1/3，落在中段；SEEK_TO_CLOSEST_SYNC 对音频帧总是可用的
                extractor.seekTo(durationUs / 3, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val want = sampleRate * TARGET_SECONDS
            val out = FloatArray(want)
            var filled = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var guard = 0

            while (!outputDone && filled < want && guard++ < 5000) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val shorts = buf.asShortBuffer()
                        val frames = shorts.remaining() / channels
                        var k = 0
                        while (k < frames && filled < want) {
                            // 多声道只取第一声道：判截止频率不需要下混，也避免相位抵消
                            out[filled++] = shorts.get(k * channels) / 32768f
                            k++
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            if (filled < Spectrum.FFT_SIZE) return null
            Pcm(out.copyOf(filled), sampleRate)
        } catch (t: Throwable) {
            Log.w("PcmProbe", "PCM 采样失败: $uri", t)
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
