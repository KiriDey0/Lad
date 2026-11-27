// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LockArtwork(val cover: Bitmap, val backdrop: Bitmap, val waveColor: Int? = null)

/** Small, pre-blurred texture; drawing this never invokes a live blur or an FFT. */
internal fun lockBackdrop(source: Bitmap): Bitmap {
    val small = Bitmap.createScaledBitmap(source, 64, 64, true)
    var pixels = IntArray(64 * 64).also { small.getPixels(it, 0, 64, 0, 0, 64, 64) }
    if (small !== source) small.recycle()
    repeat(3) {
        for (horizontal in listOf(true, false)) {
            val out = IntArray(pixels.size)
            val radius = 10
            for (line in 0 until 64) {
                fun at(p: Int) = if (horizontal) line * 64 + p.coerceIn(0, 63) else p.coerceIn(0, 63) * 64 + line
                var r = 0; var g = 0; var b = 0
                for (p in -radius..radius) { val c = pixels[at(p)]; r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255 }
                for (p in 0 until 64) {
                    out[at(p)] = (255 shl 24) or ((r / 21) shl 16) or ((g / 21) shl 8) or (b / 21)
                    val a = pixels[at(p - radius)]; val z = pixels[at(p + radius + 1)]
                    r += (z shr 16 and 255) - (a shr 16 and 255)
                    g += (z shr 8 and 255) - (a shr 8 and 255)
                    b += (z and 255) - (a and 255)
                }
            }
            pixels = out
        }
    }
    return Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888)
}

class LockArtworkRepository(private val context: Context) {
    private val cache = object : LruCache<String, LockArtwork>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: LockArtwork) = value.cover.allocationByteCount + value.backdrop.allocationByteCount
    }
    private val mutex = Mutex()
    fun cached(url: String?) = url?.let { cache.get(it) }
    suspend fun load(url: String?): LockArtwork? {
        if (url.isNullOrBlank()) return null
        cached(url)?.let { return it }
        return mutex.withLock {
            cached(url)?.let { return@withLock it }
            try {
                withTimeout(8000) {
                    val drawable = context.imageLoader.execute(ImageRequest.Builder(context).data(url)
                        .size(640).precision(Precision.EXACT).allowHardware(false).build()).drawable
                        ?: return@withTimeout null
                    withContext(Dispatchers.Default) {
                        val scale = minOf(1f, 640f / maxOf(drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1)))
                        val cover = drawable.toBitmap((drawable.intrinsicWidth * scale).toInt().coerceAtLeast(1),
                            (drawable.intrinsicHeight * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        ensureActive()
                        val side = minOf(cover.width, cover.height)
                        val crop = Bitmap.createBitmap(cover, (cover.width - side) / 2, (cover.height - side) / 2, side, side)
                        val sample = Bitmap.createScaledBitmap(crop, 96, 96, true)
                        val pixels = IntArray(96 * 96)
                        sample.getPixels(pixels, 0, 96, 0, 0, 96, 96)
                        val palette = com.lad.music.wave.WavePalette.extract(pixels, 96, 96)
                        val rgb = palette.colors.firstOrNull()
                        val color = rgb?.let { android.graphics.Color.rgb(it[0].toInt(), it[1].toInt(), it[2].toInt()) }
                        if (sample !== crop && sample !== cover) sample.recycle()
                        if (crop !== cover) crop.recycle()
                        LockArtwork(cover, lockBackdrop(cover), color).also { cache.put(url, it) }
                    }
                }
            } catch (_: TimeoutCancellationException) { null }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
    }
}
