// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music.wave

import kotlin.math.*

/** Same bounded pixel histogram and contrast floor as shared/wave/palette.mjs. */
object WavePalette {
    val neutral = listOf(floatArrayOf(213f, 225f, 238f), floatArrayOf(235f, 222f, 204f))
    data class Palette(val colors: List<FloatArray>, val lineGain: Float, val achromatic: Boolean)
    private class Bin { val sum = DoubleArray(3); var count = 0 }
    private data class Candidate(val color: DoubleArray, val score: Double)
    fun luminance(rgb: DoubleArray): Double {
        val c = rgb.map { v -> (v / 255).let { if(it <= .04045) it / 12.92 else ((it + .055) / 1.055).pow(2.4) } }
        return c[0] * .2126 + c[1] * .7152 + c[2] * .0722
    }
    private fun visibleColor(rgb: DoubleArray): FloatArray {
        if (luminance(rgb) >= .48) return FloatArray(3) { floor(rgb[it]+.5).toFloat() }
        var low = 0.0; var high = 1.0
        repeat(12) {
            val amount = (low + high) / 2
            if (luminance(DoubleArray(3) { rgb[it]+(255-rgb[it])*amount }) < .48) low = amount else high = amount
        }
        return FloatArray(3) { floor(rgb[it]+(255-rgb[it])*high+.5).toFloat() }
    }
    fun extract(pixels: IntArray, width: Int, height: Int): Palette {
        fun fallback() = Palette(neutral.map { it.copyOf() }, 1.25f, true)
        if(width <= 0 || height <= 0 || width.toLong()*height > pixels.size) return fallback()
        val bins = linkedMapOf<Int, Bin>()
        var samples = 0
        for(y in 0 until height step 2) for(x in 0 until width step 2) {
            val pixel = pixels[y*width+x]
            if(pixel ushr 24 < 128) continue
            samples++
            val rgb = intArrayOf(pixel ushr 16 and 255, pixel ushr 8 and 255, pixel and 255)
            if((rgb.max()-rgb.min())/255.0 < .12) continue
            val id = (rgb[0] shr 5)*64+(rgb[1] shr 5)*8+(rgb[2] shr 5)
            val bin = bins.getOrPut(id) { Bin() };bin.count++
            repeat(3) { bin.sum[it] += rgb[it] }
        }
        val candidates = bins.values.filter { it.count >= max(3.0, samples*.003) }.map {
            val color = DoubleArray(3) { j -> it.sum[j]/it.count }
            val chroma = (color.max()-color.min())/255
            Candidate(color, it.count*(.2+chroma).pow(2))
        }.sortedByDescending { it.score }
        if(candidates.isEmpty()) return fallback()
        val first = candidates[0]
        val second = candidates.drop(1).firstOrNull { c ->
            c.score >= first.score*.15 && sqrt((0..2).sumOf { (c.color[it]-first.color[it]).pow(2) }) >= 80
        }
        val secondary = second?.color ?: DoubleArray(3) { first.color[it]*.75+neutral[1][it]*.25 }
        return Palette(listOf(visibleColor(first.color), visibleColor(secondary)), 1f, false)
    }
}
