package com.nuvio.tv.music

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

@UnstableApi
internal class MusicEqualizerRenderersFactory(
    context: Context,
    private val processor: com.nuvio.tv.ui.screens.player.SevenBandEqualizerAudioProcessor
) : DefaultRenderersFactory(context) {
    @Volatile
    private var listener: AudioSink.Listener? = null

    fun notifyEqualizerRequirementChanged() {
        listener?.onAudioCapabilitiesChanged()
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(processor))
            .build()

        return object : ForwardingAudioSink(sink) {
            override fun setListener(listener: AudioSink.Listener) {
                this@MusicEqualizerRenderersFactory.listener = listener
                super.setListener(listener)
            }

            override fun getFormatSupport(format: Format): Int {
                if (processor.isEqualizerEnabled() && isEncodedAudio(format)) {
                    return AudioSink.SINK_FORMAT_UNSUPPORTED
                }
                return super.getFormatSupport(format)
            }

            override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport {
                if (processor.isEqualizerEnabled() && isEncodedAudio(format)) {
                    return AudioOffloadSupport.DEFAULT_UNSUPPORTED
                }
                return super.getFormatOffloadSupport(format)
            }
        }
    }

    private fun isEncodedAudio(format: Format): Boolean =
        format.sampleMimeType?.startsWith("audio/") == true && format.sampleMimeType != "audio/raw"
}
