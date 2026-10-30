// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music.wave

import kotlin.math.*

fun wavePercent(value: Float, fallback: Float) = if (value.isFinite()) value.coerceIn(0f, 100f) else fallback
fun waveBrightness(value: Float) = wavePercent(value, 100f) / 50f

data class WaveSmoothing(val response: Double, val direct: Double, val travel: Double, val flow: Double)
fun waveSmoothing(value: Float = 50f): WaveSmoothing {
    val p = wavePercent(value, 50f).toDouble()
    val calm = max(0.0, (p - 50) / 50)
    return WaveSmoothing(10.0.pow((p - 50) / 50), max(0.0, (50 - p) / 50), 1 - calm * .7, 1 - calm * .75)
}
