// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.lad.music.wave.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Compare decoded pulse timing to the player's output clock, without sound or account data. */
@Suppress("DEPRECATION")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class WaveTimingTest : InstrumentationTestCase() {
    fun testDecodedPulsesAgainstOutputClock() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".qa"))
        val rate = 48000
        val size = rate * 7
        val buffer = ByteBuffer.allocate(44 + size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + size * 2).put("WAVEfmt ".toByteArray()).putInt(16)
        buffer.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(size * 2)
        repeat(size) { i ->
            val t = i.toDouble() / rate
            val phase = t % 1
            val pulse = t >= 1 && phase < .12
            buffer.putShort((if (pulse) sin(i * 2 * PI * 125 / rate) * 12000 else 0.0).toInt().toShort())
        }
        val file = File(context.cacheDir, "wave-timing.wav").apply { writeBytes(buffer.array()) }
        val audio = WaveAudio()
        lateinit var player: ExoPlayer
        val samples = ArrayList<Pair<Long, Float>>()
        try {
            instrumentation.runOnMainSync {
                audio.enabled = true
                player = ExoPlayer.Builder(context, audio.renderers(context)).build()
                player.volume = 0f
                player.setAudioAttributes(AudioAttributes.DEFAULT, false)
                player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
                player.prepare()
                player.play()
            }
            val deadline = SystemClock.elapsedRealtime() + 9000
            var position = 0L
            while (position < 6500 && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { position = player.currentPosition }
                if (position >= 500) samples.add(position to audio.sample()[1])
                Thread.sleep(16)
            }
            assertTrue("Real PCM pulses decoded", samples.count { it.second > .02f } > 8)
            val offset = (-500..500 step 5).maxBy { shift ->
                samples.sumOf { (position, value) ->
                    val t = position + shift
                    val active = t >= 1000 && t % 1000 < 120
                    (if (active) 1.0 else -.14) * value
                }
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("waveTiming", "PCM leads output clock by approximately ${offset}ms; samples=${samples.size}")
            })
        } finally {
            instrumentation.runOnMainSync { player.release(); audio.enabled = false }
            file.delete()
        }
    }
}
