package com.luckylearning.windtone.playback.softEq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** Soft-EQ Media3 processor (E1 via SoftEqPath into PlaybackService; default off). See SoftEqPath / docs/功能-软EQ.md */
@UnstableApi
class SoftEqAudioProcessor : BaseAudioProcessor() {
    private val engine: SoftEqEngine = SoftEqEngine()
    @Volatile private var enabled: Boolean = false
    @Volatile private var centersHz: IntArray = SoftEqEngine.DEFAULT_CENTERS_HZ.copyOf()
    @Volatile private var gainsMb: IntArray = IntArray(SoftEqEngine.DEFAULT_CENTERS_HZ.size)
    private var configuredSampleRate = 0
    private var configuredChannels = 0
    private var floatScratch = FloatArray(0)

    fun setEnabled(on: Boolean) { enabled = on; if (!on) engine.reset() }
    fun setCurve(centersHz: IntArray, gainsMb: IntArray) {
        require(centersHz.size == gainsMb.size)
        this.centersHz = centersHz.copyOf(); this.gainsMb = gainsMb.copyOf()
        if (configuredSampleRate > 0) engine.configure(configuredSampleRate, configuredChannels, this.centersHz, this.gainsMb)
    }
    fun setGainsMb(gainsMb: IntArray) {
        if (gainsMb.size != centersHz.size) return
        this.gainsMb = gainsMb.copyOf()
        if (configuredSampleRate > 0) engine.setGainsMb(this.gainsMb)
    }
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        configuredSampleRate = inputAudioFormat.sampleRate
        configuredChannels = inputAudioFormat.channelCount
        engine.configure(configuredSampleRate, configuredChannels, centersHz, gainsMb)
        return inputAudioFormat
    }
    override fun isActive(): Boolean = super.isActive() && enabled && engine.isActive
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!isActive) {
            val out = replaceOutputBuffer(inputBuffer.remaining()); out.put(inputBuffer); out.flip(); return
        }
        when (outputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> queuePcm16(inputBuffer, outputAudioFormat.channelCount)
            C.ENCODING_PCM_FLOAT -> queuePcmFloat(inputBuffer, outputAudioFormat.channelCount)
            else -> { val out = replaceOutputBuffer(inputBuffer.remaining()); out.put(inputBuffer); out.flip() }
        }
    }
    override fun onFlush() { engine.reset() }
    override fun onReset() { engine.reset(); configuredSampleRate = 0; configuredChannels = 0 }
    private fun queuePcm16(inputBuffer: ByteBuffer, channels: Int) {
        val bytes = inputBuffer.remaining(); val frames = bytes / (2 * channels); ensureFloat(frames * channels)
        var i = 0; val limit = inputBuffer.limit(); var pos = inputBuffer.position()
        while (pos < limit) { floatScratch[i++] = inputBuffer.getShort(pos) / 32768f; pos += 2 }
        engine.processInterleaved(floatScratch, frames)
        val out = replaceOutputBuffer(bytes)
        for (s in 0 until frames * channels) {
            val v = (floatScratch[s] * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out.putShort(v.toShort())
        }
        inputBuffer.position(limit); out.flip()
    }
    private fun queuePcmFloat(inputBuffer: ByteBuffer, channels: Int) {
        val bytes = inputBuffer.remaining(); val frames = bytes / (4 * channels); ensureFloat(frames * channels)
        var i = 0; val limit = inputBuffer.limit(); var pos = inputBuffer.position()
        while (pos < limit) { floatScratch[i++] = inputBuffer.getFloat(pos); pos += 4 }
        engine.processInterleaved(floatScratch, frames)
        val out = replaceOutputBuffer(bytes)
        for (s in 0 until frames * channels) out.putFloat(floatScratch[s])
        inputBuffer.position(limit); out.flip()
    }
    private fun ensureFloat(n: Int) { if (floatScratch.size < n) floatScratch = FloatArray(n) }
}
