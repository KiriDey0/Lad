// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music.wave

import java.util.Random
import kotlin.math.*

/** Same per-node model as the approved desktop study. Coordinates are cover-relative. */
class WaveMotion(val count: Int = 300, seed: Long = 7193) {
    private val random = Random(seed)

    private fun rnd() = random.nextDouble()

    private class Node(
        val rest: Double,
        var angle: Double,
        val phase: Double,
        val shine: Double,
        val band: Int,
        val blend: Double,
        val polarity: Int,
        val frequency: Double,
        val follow: Double,
    ) {
        var radius = rest
        var shownRadius = rest
        var radial = 0.0
        var angular = 0.0
        var energy = 0.0
        var previous = 0.0
        var x = 0.0
        var y = 0.0
    }

    private val nodes =
        Array(count) {
            Node(
                .355 + ((rnd() + rnd()) / 2 - .5) * .38,
                rnd() * PI * 2,
                rnd() * PI * 2,
                rnd(),
                random.nextInt(8),
                rnd(),
                if (rnd() < .5) -1 else 1,
                .55 + rnd() * .8,
                9 + rnd() * 8,
            )
        }
    val points = FloatArray(count * 4)
    val lines = FloatArray(count * 30 * 6)
    var lineCount = 0
        private set

    private var time = 0.0

    private class Edge(val i: Int, val j: Int, var reach: Double, var alpha: Double = 0.0)

    private data class Candidate(val i: Int, val j: Int, val distance: Double, val reach: Double)

    private val edges = HashMap<Int, Edge>()

    private fun smooth(a: Double, b: Double, t: Double) = a + (b - a) * (1 - exp(-t))

    init {
        updatePoints()
    }

    private fun updatePoints() {
        nodes.forEachIndexed { i, n ->
            val o = i * 4
            points[o] = (cos(n.angle) * n.shownRadius + n.x).toFloat()
            points[o + 1] = (sin(n.angle) * n.shownRadius / 1.06 + n.y).toFloat()
            points[o + 2] = (.0035 + n.shine * .0025 + n.energy * .0007).toFloat()
            points[o + 3] =
                ((.35 + n.shine * .45) *
                        (.84 + n.energy * .32) *
                        (.92 + .12 * sin(time * .42 + n.phase * 3)))
                    .toFloat()
        }
    }

    fun step(seconds: Double, bands: FloatArray = FloatArray(8), smoothing: Float = 50f) {
        if (seconds <= 0) return
        val dt = seconds.coerceAtMost(.05)
        val tune = waveSmoothing(smoothing)
        time += dt * tune.flow
        val mid = (bands[2] + bands[3] + bands[4]) / 3.0
        val high = (bands[5] + bands[6] + bands[7]) / 3.0
        for (n in nodes) {
            val local =
                bands[n.band] * (.8 + n.blend * .15) +
                    bands[(n.band + 3) % 8] * (.2 - n.blend * .15)
            n.energy = smooth(n.energy, local, dt * n.follow * (if (local > n.energy) 2 else 1) / tune.response)
            val attack = max(0.0, n.energy - n.previous)
            n.previous = n.energy
            val flow =
                .045 * sin(n.angle * 2 + time * .21 + n.phase * .35) +
                    .025 * cos(n.angle * 4 - time * .17 + n.phase)
            val individual = .028 * sin(time * n.frequency * .43 + n.phase * 2)
            n.angular =
                smooth(
                    n.angular,
                    flow * (1 + mid * .25) + individual * (.6 + n.energy * .7),
                    dt * 2.4 / tune.response,
                )
            n.angle += n.angular * dt * tune.flow
            n.radial += attack * .18 * n.polarity * tune.travel
            val target =
                n.rest + (n.polarity * n.energy * .075 + .011 * sin(time * n.frequency + n.phase)) * tune.travel
            val physicsDt = dt / max(1.0, tune.response)
            n.radial += ((target - n.radius) * 42 - n.radial * 11) * physicsDt
            n.radius += n.radial * physicsDt
            n.shownRadius = n.radius + (target - n.radius) * tune.direct
            val excursion = (.010 + n.energy * .055) * tune.travel
            val x =
                sin(time * n.frequency + n.phase * 2) +
                    .32 * cos(time * n.frequency * .71 + n.phase * 5)
            val y =
                cos(time * n.frequency * .73 + n.phase * 3) +
                    .30 * sin(time * n.frequency * 1.17 + n.phase)
            n.x = smooth(n.x, x * excursion, dt * n.follow / tune.response)
            n.y = smooth(n.y, y * excursion, dt * n.follow / tune.response)
        }
        updatePoints()
        val grid = HashMap<Int, MutableList<Int>>()
        fun key(x: Int, y: Int) = (x + 128) * 256 + y + 128
        for (i in 0 until count) {
            val x = floor(points[i * 4] / .082).toInt()
            val y = floor(points[i * 4 + 1] / .082).toInt()
            grid.getOrPut(key(x, y)) { ArrayList() }.add(i)
        }
        val candidates = ArrayList<Candidate>()
        for (i in 0 until count) {
            val x = points[i * 4]
            val y = points[i * 4 + 1]
            val cx = floor(x / .082).toInt()
            val cy = floor(y / .082).toInt()
            for (dy in -1..1) for (dx in -1..1) for (j in grid[key(cx + dx, cy + dy)].orEmpty()) {
                if (j <= i) continue
                val d = hypot((x - points[j * 4]).toDouble(), (y - points[j * 4 + 1]).toDouble())
                val reach = .066 + .014 * (nodes[i].energy + nodes[j].energy) * .5
                if (d < reach) candidates.add(Candidate(i, j, d, reach))
            }
        }
        candidates.sortBy { it.distance }
        val degree = IntArray(count)
        val chosen = HashSet<Int>()
        for (c in candidates) {
            if (degree[c.i] >= 4 || degree[c.j] >= 4) continue
            degree[c.i]++
            degree[c.j]++
            val k = c.i * count + c.j
            chosen.add(k)
            edges.getOrPut(k) { Edge(c.i, c.j, c.reach) }.reach = c.reach
        }
        lineCount = 0
        val iterator = edges.iterator()
        while (iterator.hasNext()) {
            val (k, e) = iterator.next()
            val on = chosen.contains(k)
            e.alpha = smooth(e.alpha, if (on) 1.0 else 0.0, dt / ((if (on) .16 else .32) * sqrt(tune.response)))
            if (!on && e.alpha < .008) {
                iterator.remove()
                continue
            }
            val ax = points[e.i * 4]
            val ay = points[e.i * 4 + 1]
            val bx = points[e.j * 4]
            val by = points[e.j * 4 + 1]
            val near = max(0.0, 1 - hypot((ax - bx).toDouble(), (ay - by).toDouble()) / e.reach)
            val v = (e.alpha * (.085 + .16 * near * near) * (1 + mid * .15 + high * .08)).toFloat()
            if (lineCount + 2 > lines.size / 3) continue
            val o = lineCount * 3
            lines[o] = ax
            lines[o + 1] = ay
            lines[o + 2] = v
            lines[o + 3] = bx
            lines[o + 4] = by
            lines[o + 5] = v
            lineCount += 2
        }
    }
}

/** Log compression and independent band envelopes. Silence stays silent. */
class WaveEnvelope {
    private val average = FloatArray(8)
    private val values = FloatArray(8)
    private val motion = FloatArray(8)
    private var age = 0.0

    private fun follow(v: Float, t: Float, dt: Double, attack: Double, release: Double) =
        (v + (t - v) * (1 - exp(-dt / if (t > v) attack else release))).toFloat()

    fun process(raw: FloatArray, seconds: Double, smoothing: Float = 50f): FloatArray {
        val dt = seconds.coerceIn(.001, .05)
        age += dt
        val response = waveSmoothing(smoothing).response
        for (i in 0..7) {
            val v = raw[i].coerceAtLeast(0f)
            val prior = average[i]
            average[i] = follow(prior, v, dt, if (age < .5) .2 else 2.5, 3.5)
            val absolute = ((20 * log10(max(v.toDouble(), .00001)) + 65) / 48).coerceIn(0.0, 1.0)
            val dynamic = ((v / max(.003f, prior) - .7) / 1.3).coerceIn(0.0, 1.0)
            values[i] =
                follow(values[i], (absolute * (.3 + .7 * dynamic)).toFloat(), dt, .012, .11)
        }
        for (i in 0..7) {
            val target = values[i] * .85f + values[(i + 7) % 8] * .075f + values[(i + 1) % 8] * .075f
            val delta = follow(motion[i], target, dt, .03 * .75 * response, .16 * .75 * response) - motion[i]
            motion[i] += (delta.toDouble().coerceIn(-8.0 / (.75 * response) * dt, 8.0 / (.75 * response) * dt)).toFloat()
        }
        return motion
    }
}

/** Reusable Hann-window FFT; called by the decoder, never records the microphone. */
class WaveSpectrum(private val size: Int = 2048) {
    private val input = DoubleArray(size)
    private val real = DoubleArray(size)
    private val imag = DoubleArray(size)
    private val window = DoubleArray(size) { .5 - .5 * cos(2 * PI * it / (size - 1)) }
    private var used = 0
    var latest = FloatArray(8)
        private set

    fun add(sample: Double, rate: Int): Boolean {
        input[used++] = sample
        if (used < size) return false
        used = 0
        for (i in input.indices) {
            real[i] = input[i] * window[i]
            imag[i] = 0.0
        }
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = real[i]
                real[i] = real[j]
                real[j] = t
                t = imag[i]
                imag[i] = imag[j]
                imag[j] = t
            }
        }
        var length = 2
        while (length <= size) {
            val angle = -2 * PI / length
            val wr = cos(angle)
            val wi = sin(angle)
            for (start in 0 until size step length) {
                var ur = 1.0
                var ui = 0.0
                for (k in 0 until length / 2) {
                    val a = start + k
                    val b = a + length / 2
                    val vr = real[b] * ur - imag[b] * ui
                    val vi = real[b] * ui + imag[b] * ur
                    real[b] = real[a] - vr
                    imag[b] = imag[a] - vi
                    real[a] += vr
                    imag[a] += vi
                    val t = ur * wr - ui * wi
                    ui = ur * wi + ui * wr
                    ur = t
                }
            }
            length = length shl 1
        }
        val hz = intArrayOf(35, 80, 160, 320, 640, 1280, 2560, 5120, 12000)
        val step = rate.toDouble() / size
        val result = FloatArray(8)
        for (n in 0..7) {
            var power = 0.0
            for (k in
                max(1, ceil(hz[n] / step).toInt()) until
                    min(size / 2, ceil(hz[n + 1] / step).toInt())) power +=
                real[k] * real[k] + imag[k] * imag[k]
            result[n] = (sqrt(power) * 2.4 / (size * .5)).toFloat()
        }
        latest = result
        return true
    }
}
