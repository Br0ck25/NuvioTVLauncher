package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.nuvio.tv.core.audio.SevenBandEqualizerSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Seven peaking filters for decoded PCM. Encoded passthrough data is never rewritten. */
internal class SevenBandEqualizerAudioProcessor : BaseAudioProcessor() {
    private data class Coefficients(
        val b0: Float,
        val b1: Float,
        val b2: Float,
        val a1: Float,
        val a2: Float
    )

    private class State(var x1: Float = 0f, var x2: Float = 0f, var y1: Float = 0f, var y2: Float = 0f)

    @Volatile
    private var gainsDb: List<Int> = List(7) { 0 }
    private var configuredGains: List<Int> = emptyList()
    private var configuredSampleRate = 0
    private var channelCount = 0
    private var coefficients: List<Coefficients> = emptyList()
    private var states: Array<Array<State>> = emptyArray()

    fun setGainsDb(gains: List<Int>) {
        gainsDb = gains.take(7).let { values ->
            if (values.size == 7) values.map { it.coerceIn(-12, 12) } else List(7) { 0 }
        }
    }

    fun isEqualizerEnabled(): Boolean = gainsDb.any { it != 0 }

    override fun isActive(): Boolean = super.isActive() && isEqualizerEnabled()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val format = inputAudioFormat
        val gains = gainsDb
        configureFilters(format.sampleRate, format.channelCount, gains)
        val output = replaceOutputBuffer(inputBuffer.remaining())
        inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())
        val isFloat = format.encoding == C.ENCODING_PCM_FLOAT
        val sampleBytes = if (isFloat) 4 else 2
        val frameBytes = format.channelCount * sampleBytes

        while (frameBytes > 0 && inputBuffer.remaining() >= frameBytes) {
            for (channel in 0 until format.channelCount) {
                val input = if (isFloat) inputBuffer.float else inputBuffer.short.toFloat() / PCM16_SCALE
                var sample = if (input.isFinite()) input else 0f
                for (band in coefficients.indices) {
                    val c = coefficients[band]
                    val state = states[band][channel]
                    val filtered = c.b0 * sample + c.b1 * state.x1 + c.b2 * state.x2 -
                        c.a1 * state.y1 - c.a2 * state.y2
                    state.x2 = state.x1
                    state.x1 = sample
                    state.y2 = state.y1
                    state.y1 = filtered
                    sample = filtered
                }
                // A gentle saturator avoids hard clipping when several bands are boosted.
                val safeSample = softLimit(sample)
                if (isFloat) output.putFloat(safeSample)
                else output.putShort((safeSample * PCM16_SCALE).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        if (inputBuffer.hasRemaining()) output.put(inputBuffer)
        output.flip()
    }

    override fun onFlush() {
        resetFilterState()
    }

    override fun onReset() {
        resetFilterState()
        configuredSampleRate = 0
        configuredGains = emptyList()
    }

    private fun configureFilters(sampleRate: Int, channels: Int, gains: List<Int>) {
        if (sampleRate == configuredSampleRate && channels == channelCount && gains == configuredGains) return
        val rate = sampleRate.takeIf { it > 0 }?.toFloat() ?: DEFAULT_SAMPLE_RATE
        coefficients = SevenBandEqualizerSettings.frequenciesHz.mapIndexed { index, center ->
            peakingCoefficients(center.toFloat().coerceAtMost(rate * 0.45f), rate, gains[index].toFloat())
        }
        if (channels != channelCount || states.size != coefficients.size) {
            states = Array(coefficients.size) { Array(channels.coerceAtLeast(1)) { State() } }
        }
        configuredSampleRate = sampleRate
        channelCount = channels
        configuredGains = gains
    }

    private fun resetFilterState() {
        states.forEach { band -> band.forEach { it.x1 = 0f; it.x2 = 0f; it.y1 = 0f; it.y2 = 0f } }
    }

    private fun peakingCoefficients(frequency: Float, sampleRate: Float, gainDb: Float): Coefficients {
        if (gainDb == 0f || frequency <= 0f) return Coefficients(1f, 0f, 0f, 0f, 0f)
        val omega = (2.0 * Math.PI * frequency / sampleRate).toFloat()
        val alpha = sin(omega) / (2f * QUALITY_FACTOR)
        val amplitude = 10.0.pow(gainDb / 40.0).toFloat()
        val a0 = 1f + alpha / amplitude
        return Coefficients(
            b0 = (1f + alpha * amplitude) / a0,
            b1 = (-2f * cos(omega)) / a0,
            b2 = (1f - alpha * amplitude) / a0,
            a1 = (-2f * cos(omega)) / a0,
            a2 = (1f - alpha / amplitude) / a0
        )
    }

    private fun softLimit(sample: Float): Float {
        val magnitude = kotlin.math.abs(sample)
        if (magnitude <= 0.9f) return sample
        val limited = 0.9f + 0.1f * kotlin.math.tanh((magnitude - 0.9f) * 10f)
        return kotlin.math.sign(sample) * limited
    }

    private companion object {
        const val PCM16_SCALE = 32768f
        const val DEFAULT_SAMPLE_RATE = 48_000f
        const val QUALITY_FACTOR = 1.0f
    }
}
