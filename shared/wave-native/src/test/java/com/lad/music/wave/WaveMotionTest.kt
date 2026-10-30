// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music.wave

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class WaveMotionTest {
    @Test fun smoothingChangesReactionWithoutFreezingOrChangingTheDefault() {
        fun trace(smoothing: Float): Pair<Double, Int> {
            val music = WaveMotion(90)
            val quiet = WaveMotion(90)
            val envelope = WaveEnvelope()
            var previous: FloatArray? = null
            var travel = 0.0
            var firstVisible = Int.MAX_VALUE
            repeat(360) { frame ->
                val t = frame / 60.0
                val pulse = if (t >= 2 && t < 5) (.14 * exp(-(t % .5) / .055)).toFloat() else 0f
                music.step(1.0 / 60, envelope.process(floatArrayOf(pulse, pulse * .8f, 0f, 0f, pulse * .3f, 0f, 0f, 0f), 1.0 / 60, smoothing), smoothing)
                quiet.step(1.0 / 60, smoothing = smoothing)
                val difference = FloatArray(180)
                var visible = 0
                repeat(90) { i ->
                    val x = music.points[i * 4] - quiet.points[i * 4]
                    val y = music.points[i * 4 + 1] - quiet.points[i * 4 + 1]
                    assertTrue(x.isFinite() && y.isFinite())
                    difference[i * 2] = x
                    difference[i * 2 + 1] = y
                    if (hypot(x, y) > .006) visible++
                    previous?.let { travel += hypot((x - it[i * 2]).toDouble(), (y - it[i * 2 + 1]).toDouble()) }
                }
                if (visible >= 18 && firstVisible == Int.MAX_VALUE) firstVisible = frame - 120
                previous = difference
            }
            return travel to firstVisible
        }
        val sharp = trace(0f)
        val normal = trace(50f)
        val calm = trace(100f)
        assertTrue(sharp.second < normal.second)
        assertTrue(sharp.second <= 3)
        assertTrue(calm.first > .005 && calm.first < normal.first * .4)
        val a = WaveMotion(60)
        val b = WaveMotion(60)
        repeat(180) {
            val bands = FloatArray(8) { i -> sin((it + i) / 7.0).pow(2).toFloat() }
            a.step(1.0 / 60, bands)
            b.step(1.0 / 60, bands, 50f)
        }
        assertArrayEquals(a.points, b.points, 0f)
    }

    @Test fun remoteClockCorrectionsStayBoundedAndFollowSeeks() {
        assertEquals(10000L, remoteWaveCorrection(10000, 30000).seekMs)
        assertEquals(30000L, remoteWaveCorrection(30000, 10000).seekMs)
        assertNull(remoteWaveCorrection(30000, 30000).seekMs)
        assertEquals(1f, remoteWaveCorrection(30000, 30000).rate)
        assertTrue(remoteWaveCorrection(30400, 30000).rate > 1f)
        assertTrue(remoteWaveCorrection(29600, 30000).rate < 1f)
        for (error in -650L..650L) assertTrue(remoteWaveCorrection(30000+error, 30000).rate in .96f..1.04f)
    }

    @Test
    fun decodedBassAttackMovesVisiblePointsWithin100ms() {
        val music = WaveMotion(180)
        val quiet = WaveMotion(180)
        val envelope = WaveEnvelope()
        repeat(180) {
            music.step(1.0 / 60)
            quiet.step(1.0 / 60)
            envelope.process(FloatArray(8), 1.0 / 60)
        }
        var largestStep = 0.0
        repeat(6) { frame ->
            val before = music.points.copyOf()
            val raw = FloatArray(8)
            raw[0] = (.14 * exp(-frame / 7.0)).toFloat()
            raw[1] = (.1 * exp(-frame / 7.0)).toFloat()
            music.step(1.0 / 60, envelope.process(raw, 1.0 / 60))
            quiet.step(1.0 / 60)
            for(i in 0 until music.count) largestStep = max(largestStep,
                hypot((music.points[i*4]-before[i*4]).toDouble(), (music.points[i*4+1]-before[i*4+1]).toDouble()))
        }
        val visible = (0 until music.count).count { i ->
            hypot((music.points[i*4]-quiet.points[i*4]).toDouble(), (music.points[i*4+1]-quiet.points[i*4+1]).toDouble()) > .006
        }
        assertTrue("At least 20% of nodes visibly respond within 100ms", visible > music.count * .2)
        assertTrue("No frame jumps", largestStep < .02)
    }

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
