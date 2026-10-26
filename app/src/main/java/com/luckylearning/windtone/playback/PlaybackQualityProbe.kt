package com.luckylearning.windtone.playback

import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import java.util.concurrent.atomic.AtomicInteger

/**
 * 播放质量可观测（A3）：挂在 ExoPlayer 的 [AnalyticsListener] 上，只记账、不改听感。
 *
 * 诊断包「播放质量」段读取 [report]；拿不到的字段如实写「未知」。
 */
@UnstableApi
object PlaybackQualityProbe : AnalyticsListener {

    @Volatile var decoderName: String? = null
        private set
    @Volatile var sampleMimeType: String? = null
        private set
    @Volatile var sampleRateHz: Int = 0
        private set
    @Volatile var channelCount: Int = 0
        private set
    @Volatile var pcmEncoding: Int = Format.NO_VALUE
        private set
    @Volatile var bitrate: Int = Format.NO_VALUE
        private set
    @Volatile var passthrough: Boolean? = null
        private set
    @Volatile var lastAudioError: String? = null
        private set

    private val underruns = AtomicInteger(0)

    fun underrunCount(): Int = underruns.get()

    fun resetForTest() {
        decoderName = null
        sampleMimeType = null
        sampleRateHz = 0
        channelCount = 0
        pcmEncoding = Format.NO_VALUE
        bitrate = Format.NO_VALUE
        passthrough = null
        lastAudioError = null
        underruns.set(0)
    }

    /** 在 [PlaybackService] 创建 ExoPlayer 后调用一次 */
    fun attach(player: ExoPlayer) {
        player.addAnalyticsListener(this)
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        this.decoderName = decoderName
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
    ) {
        applyFormat(format)
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        underruns.incrementAndGet()
    }

    override fun onAudioSinkError(
        eventTime: AnalyticsListener.EventTime,
        audioSinkError: Exception,
    ) {
        lastAudioError = audioSinkError.javaClass.simpleName + ": " + (audioSinkError.message ?: "")
        if (audioSinkError is AudioSink.UnexpectedDiscontinuityException ||
            audioSinkError is AudioSink.WriteException ||
            audioSinkError is AudioSink.InitializationException
        ) {
            // 不改播放，只记一笔，便于对照卡顿
            lastAudioError = audioSinkError.javaClass.simpleName + ": " + (audioSinkError.message ?: "")
        }
    }

    override fun onAudioCodecError(
        eventTime: AnalyticsListener.EventTime,
        audioCodecError: Exception,
    ) {
        lastAudioError = "codec " + audioCodecError.javaClass.simpleName + ": " +
            (audioCodecError.message ?: "")
    }

    override fun onPlayerError(
        eventTime: AnalyticsListener.EventTime,
        error: PlaybackException,
    ) {
        lastAudioError = "player " + error.errorCodeName + ": " + (error.message ?: "")
    }

    override fun onAudioDisabled(
        eventTime: AnalyticsListener.EventTime,
        decoderCounters: DecoderCounters,
    ) {
        // 曲目结束时保留上一曲快照，方便诊断包在暂停态仍可读
    }

    /** 从当前 ExoPlayer 补采一帧（Analytics 尚未回调时的兜底） */
    fun refreshFromPlayer(player: ExoPlayer?) {
        val p = player ?: return
        val fmt = runCatching { p.audioFormat }.getOrNull() ?: return
        applyFormat(fmt)
    }

    private fun applyFormat(format: Format) {
        sampleMimeType = format.sampleMimeType
        sampleRateHz = format.sampleRate
        channelCount = format.channelCount
        pcmEncoding = format.pcmEncoding
        bitrate = format.bitrate
        // 粗判直通：非 PCM / 非常见 raw 编码时倾向 passthrough 或压缩直出
        val mime = format.sampleMimeType.orEmpty()
        passthrough = when {
            mime.contains("raw", ignoreCase = true) -> false
            mime.contains("pcm", ignoreCase = true) -> false
            mime.startsWith("audio/mp4a") -> false
            mime.startsWith("audio/mpeg") -> false
            mime.startsWith("audio/flac") -> false
            mime.startsWith("audio/vorbis") -> false
            mime.startsWith("audio/opus") -> false
            mime.startsWith("audio/ac3") || mime.startsWith("audio/eac3") ||
                mime.startsWith("audio/ac4") || mime.startsWith("audio/truehd") ||
                mime.startsWith("audio/vnd.dts") -> true
            mime.isBlank() -> null
            else -> null
        }
    }

    fun report(): String = buildString {
        appendLine("[播放质量]")
        appendLine("解码器：${decoderName ?: "未知（尚未起播或未回调）"}")
        val fmtBits = buildString {
            append(sampleMimeType ?: "未知")
            if (sampleRateHz > 0) append("  ").append(sampleRateHz).append(" Hz")
            if (channelCount > 0) append("  ").append(channelCount).append(" ch")
        }
        appendLine("格式：$fmtBits")
        if (bitrate != Format.NO_VALUE && bitrate > 0) {
            appendLine("码率：${bitrate / 1000} kbps")
        }
        appendLine(
            "直通：" + when (passthrough) {
                true -> "疑似是（压缩格式直出）"
                false -> "否（PCM/常见解码路径）"
                null -> "未知"
            },
        )
        appendLine("缓冲欠载次数：${underruns.get()}（本进程累计）")
        appendLine("最近音频错误：${lastAudioError ?: "无"}")
        // gapless 行由 DiagnosticsReport 根据 Prefs 追加，避免本对象依赖 Context
    }.trimEnd()
}
