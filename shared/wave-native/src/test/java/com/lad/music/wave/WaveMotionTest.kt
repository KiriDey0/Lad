// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music.wave

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class WaveMotionTest {
    @Test
    fun fftSeparatesBassFromTrebleAndSilence() {
        fun fft(hz: Double): FloatArray {
            val f = WaveSpectrum()
            for (i in 0 until 2048) f.add(
                if (hz == 0.0) 0.0 else sin(i * 2 * PI * hz / 48000) * .4,
                48000,
            )
            return f.latest
        }
        assertTrue(fft(0.0).all { it == 0f })
        val bass = fft(100.0)
        val high = fft(6000.0)
        assertTrue(bass[1] > .1f)
        assertTrue(bass[1] > bass[7] * 10)
        assertTrue(high[7] > .1f)
        assertTrue(high[7] > high[1] * 10)
    }

    @Test
    fun pausedSimulationIsExactlyFrozenAndLongRunsStayFinite() {
        val w = WaveMotion()
        assertEquals(300, w.count)
        w.step(.02)
        val before = w.points.copyOf()
        w.step(0.0, FloatArray(8) { 1f })
        assertArrayEquals(before, w.points, 0f)
        repeat(1000) { w.step(1.0 / 30, FloatArray(8) { .5f }) }
        assertTrue(w.points.all { it.isFinite() })
        assertTrue(w.lineCount > 0)
        assertTrue(w.lines.take(w.lineCount * 3).all { it.isFinite() })
    }

    @Test
    fun beatDoesNotExpandAllPointsTogether() {
        val beat = WaveMotion()
        val quiet = WaveMotion()
        repeat(180) {
            beat.step(1.0 / 60)
            quiet.step(1.0 / 60)
        }
        repeat(12) {
            beat.step(1.0 / 60, FloatArray(8) { 1f })
            quiet.step(1.0 / 60)
        }
        var inward = 0
        var outward = 0
        for (i in 0 until beat.count) {
            val o = i * 4
            val d =
                (beat.points[o] - quiet.points[o]) * quiet.points[o] +
                    (beat.points[o + 1] - quiet.points[o + 1]) * quiet.points[o + 1]
            if (d < -.00001) inward++
            if (d > .00001) outward++
        }
        assertTrue(inward > 50)
        assertTrue(outward > 50)
    }

    @Test
    fun motionIsConsistentAcrossRefreshRatesAndSilenceDoesNotPump() {
        val a = WaveMotion(60)
        val b = WaveMotion(60)
        repeat(180) { a.step(1.0 / 30, FloatArray(8) { .4f }) }
        repeat(720) { b.step(1.0 / 120, FloatArray(8) { .4f }) }
        val error =
            (0 until 60).sumOf { i ->
                hypot(
                    (a.points[i * 4] - b.points[i * 4]).toDouble(),
                    (a.points[i * 4 + 1] - b.points[i * 4 + 1]).toDouble(),
                )
            } / 60
        assertTrue(error < .008)
        val e = WaveEnvelope()
        repeat(200) { assertTrue(e.process(FloatArray(8), .016).all { it == 0f }) }
    }
}
