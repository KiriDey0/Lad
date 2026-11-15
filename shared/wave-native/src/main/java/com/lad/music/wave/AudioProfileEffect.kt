// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music.wave

import android.media.audiofx.Equalizer
import kotlin.math.roundToInt

/** Small native bridge for the shared Android/TV playback path. */
class AudioProfileEffect {
    private var equalizer: Equalizer? = null

    fun attach(audioSessionId: Int) {
        equalizer?.release()
        equalizer = null
        if (audioSessionId <= 0) return
        equalizer = runCatching { Equalizer(0, audioSessionId).apply { enabled = true } }.getOrNull()
    }

    fun apply(enabled: Boolean, preampDb: Float, gains: List<Float>) {
        val effect = equalizer ?: return
        runCatching {
            val range = effect.bandLevelRange
            val count = effect.numberOfBands.toInt().coerceAtLeast(1)
            for (band in 0 until count) {
                val source = gains.getOrElse((band * gains.size / count).coerceIn(0, gains.lastIndex)) { 0f }
                val db = if (enabled) (source + preampDb / 2f).coerceIn(-12f, 12f) else 0f
                val level = (db * 100f).roundToInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort()
                effect.setBandLevel(band.toShort(), level)
            }
            effect.enabled = enabled
        }
    }

    fun release() {
        equalizer?.release()
        equalizer = null
    }
}
