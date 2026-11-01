package com.luckylearning.windtone.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * 左右声道平衡 PCM 处理器（B3）。
 *
 * [balance]==0（居中）时 [isActive] 为 false，Media3 音频链跳过本节点，
 * 等价于从处理链卸下，避免恒等 [ChannelMixingAudioProcessor] 仍逐帧混音。
 * 非居中时对角缩放 L/R；单声道直通。
 *
 * 不重建 DefaultAudioSink / ExoPlayer，开↔关只靠 isActive 切换，降低爆音风险。
 */
@UnstableApi
class StereoBalanceAudioProcessor : BaseAudioProcessor() {

    @Volatile private var leftGain: Float = 1f
    @Volatile private var rightGain: Float = 1f
    @Volatile private var centered: Boolean = true

    val isCentered: Boolean get() = centered

    fun setBalance(balance: Int) {
        val b = StereoBalance.coerce(balance)
        centered = StereoBalance.isCentered(b)
        leftGain = StereoBalance.leftGain(b)
        rightGain = StereoBalance.rightGain(b)
    }

    override fun isActive(): Boolean = super.isActive() && !centered

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!isActive || outputAudioFormat.channelCount != 2) {
            val out = replaceOutputBuffer(inputBuffer.remaining())
            out.put(inputBuffer)
            out.flip()
            return
        }
        when (outputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> queuePcm16(inputBuffer)
            C.ENCODING_PCM_FLOAT -> queuePcmFloat(inputBuffer)
            else -> {
                val out = replaceOutputBuffer(inputBuffer.remaining())
                out.put(inputBuffer)
                out.flip()
            }
        }
    }

    private fun queuePcm16(inputBuffer: ByteBuffer) {
        val out = replaceOutputBuffer(inputBuffer.remaining())
        val l = leftGain
        val r = rightGain
        while (inputBuffer.hasRemaining()) {
            val left = inputBuffer.short
            val right = inputBuffer.short
            out.putShort((left * l).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            out.putShort((right * r).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        out.flip()
    }

    private fun queuePcmFloat(inputBuffer: ByteBuffer) {
        val out = replaceOutputBuffer(inputBuffer.remaining())
        val l = leftGain
        val r = rightGain
        while (inputBuffer.hasRemaining()) {
            out.putFloat(inputBuffer.float * l)
            out.putFloat(inputBuffer.float * r)
        }
        out.flip()
    }
}
