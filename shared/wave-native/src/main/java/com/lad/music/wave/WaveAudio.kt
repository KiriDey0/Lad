// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music.wave

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

@androidx.annotation.OptIn(UnstableApi::class)
class WaveAudio : TeeAudioProcessor.AudioBufferSink {
    @Volatile var enabled = false
    private var rate = 48000
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var fft = WaveSpectrum()

    private data class Sample(val bands: FloatArray, val at: Long)

    @Volatile private var latest = Sample(FloatArray(8), 0)

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        rate = sampleRateHz
        channels = channelCount
        this.encoding = encoding
        fft = WaveSpectrum()
        latest = Sample(FloatArray(8), 0)
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!enabled) return
        val bytes =
            when (encoding) {
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_FLOAT -> 4
                else -> return
            }
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        while (b.remaining() >= bytes * channels) {
            var sample = 0.0
            repeat(channels) { sample += if (bytes == 2) b.short / 32768.0 else b.float.toDouble() }
            if (fft.add(sample / channels, rate))
                latest = Sample(fft.latest, SystemClock.elapsedRealtime())
        }
    }

    fun clear() { latest = Sample(FloatArray(8), 0) }

    fun sample(): FloatArray {
        val value = latest
        return if (SystemClock.elapsedRealtime() - value.at < 300) value.bands else FloatArray(8)
    }

    fun renderers(context: Context) =
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(TeeAudioProcessor(this@WaveAudio)))
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }
}
