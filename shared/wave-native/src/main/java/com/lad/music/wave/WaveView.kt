// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music.wave

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.provider.Settings
import android.view.Choreographer
import android.view.View
import androidx.media3.common.util.UnstableApi
import coil.request.Disposable
import coil.request.ImageRequest
import kotlin.math.*

/** Decorative only: never takes focus, clicks or D-pad input from the player. */
@androidx.annotation.OptIn(UnstableApi::class)
class WaveView(context: Context) : View(context), Choreographer.FrameCallback {
    var playing = false
        set(value) {
            if (field == value) return
            field = value
            last = 0
            refreshAudio()
            schedule()
        }

    var local = true
        set(value) {
            field = value
            refreshAudio()
        }

    var intensity = 100f
        set(value) {
            field = if (value.isFinite()) value.coerceIn(0f, 100f) else 100f
            refreshAudio()
            invalidate()
            schedule()
        }

    var audio: WaveAudio? = null
        set(value) {
            field?.enabled = false
            field = value
            refreshAudio()
        }

    var cover: String? = null
        set(value) {
            if (field == value) return
            field = value
            loadCover(value)
            schedule()
        }

    private val simulation = WaveMotion()
    private val envelope = WaveEnvelope()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val base = arrayOf(floatArrayOf(166f, 182f, 192f), floatArrayOf(190f, 180f, 165f))
    private var target = base.map { it.copyOf() }
    private val colors = base.map { it.copyOf() }
    private var coverRequest: Disposable? = null
    private var last = 0L
    private var pause = 1f
    private var pending = false

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        simulation.step(.016)
    }

    private fun loadCover(url: String?) {
        coverRequest?.dispose()
        target = base.map { it.copyOf() }
        if (url.isNullOrBlank()) return
        coverRequest =
            coil.Coil.imageLoader(context)
                .enqueue(
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(96)
                        .allowHardware(false)
                        .target(
                            onSuccess = { drawable ->
                                if (cover == url) {
                                    val bitmap = (drawable as? BitmapDrawable)?.bitmap
                                    if (bitmap != null) {
                                        val sums = Array(2) { LongArray(4) }
                                        for (y in 0 until bitmap.height step 2) for (x in
                                            0 until bitmap.width step 2) {
                                            if (
                                                x > bitmap.width * .12 &&
                                                    x < bitmap.width * .88 &&
                                                    y > bitmap.height * .12 &&
                                                    y < bitmap.height * .88
                                            )
                                                continue
                                            val pixel = bitmap.getPixel(x, y)
                                            val r = Color.red(pixel)
                                            val g = Color.green(pixel)
                                            val b = Color.blue(pixel)
                                            val light = (r + g + b) / 765f
                                            if (light < .09 || light > .92) continue
                                            val a = sums[if (y < bitmap.height / 2) 0 else 1]
                                            a[0] += r.toLong()
                                            a[1] += g.toLong()
                                            a[2] += b.toLong()
                                            a[3]++
                                        }
                                        target =
                                            sums.mapIndexed { i, a ->
                                                if (a[3] > 0)
                                                    FloatArray(3) { j ->
                                                        (a[j].toFloat() / a[3] * 1.2f).coerceIn(
                                                            42f,
                                                            210f,
                                                        )
                                                    }
                                                else base[i].copyOf()
                                            }
                                        schedule()
                                    }
                                }
                            }
                        )
                        .build()
                )
    }

    private fun reduced() =
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f

    private fun refreshAudio() {
        audio?.enabled =
            playing &&
                local &&
                intensity > 0 &&
                !reduced() &&
                isAttachedToWindow &&
                windowVisibility == VISIBLE &&
                isShown
    }

    private fun schedule() {
        if (!pending && isAttachedToWindow && windowVisibility == VISIBLE && isShown) {
            pending = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        last = 0
        loadCover(cover)
        refreshAudio()
        schedule()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        pending = false
        last = 0
        coverRequest?.dispose()
        audio?.enabled = false
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        refreshAudio()
        if (visibility == VISIBLE) {
            last = 0
            schedule()
        } else {
            Choreographer.getInstance().removeFrameCallback(this)
            pending = false
            last = 0
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        pending = false
        if (windowVisibility != VISIBLE || !isShown) {
            last = 0
            return
        }
        // API 28 / ARMv7: a steady 30 fps, independent of the TV's refresh rate.
        if (last != 0L && frameTimeNanos - last < 30_000_000L) {
            schedule()
            return
        }
        val dt = if (last == 0L) .016 else ((frameTimeNanos - last) / 1e9).coerceAtMost(.05)
        last = frameTimeNanos
        val targetPause = if (playing) 0f else 1f
        pause += ((targetPause - pause) * (1 - exp(-dt * 4))).toFloat()
        val bands =
            envelope.process(
                if (local && playing) audio?.sample() ?: FloatArray(8) else FloatArray(8),
                dt,
            )
        if (!reduced() && pause < .999f) simulation.step(dt * (1 - pause), bands)
        for (i in 0..1) for (j in 0..2) colors[i][j] +=
            ((target[i][j] - colors[i][j]) * (1 - exp(-dt * 2))).toFloat()
        invalidate()
        val paletteMoving =
            colors.indices.any { i -> (0..2).any { j -> abs(target[i][j] - colors[i][j]) > .1f } }
        if (
            (playing && !reduced() && intensity > 0) ||
                abs(targetPause - pause) > .001f ||
                paletteMoving
        )
            schedule()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width.toFloat(), height.toFloat()) * 1.08f
        val cx = width * .5f
        val cy = height * .42f
        val strength = intensity.coerceIn(0f, 100f) / 100 * (1 - pause * .82f)
        fun tint(a: Float) =
            Color.rgb(
                (colors[0][0] * a + colors[1][0] * (1 - a)).toInt(),
                (colors[0][1] * a + colors[1][1] * (1 - a)).toInt(),
                (colors[0][2] * a + colors[1][2] * (1 - a)).toInt(),
            )
        paint.strokeWidth = max(.7f, resources.displayMetrics.density * .65f)
        paint.color = tint(.55f)
        for (i in 0 until simulation.lineCount step 2) {
            val o = i * 3
            paint.alpha =
                (simulation.lines[o + 2] * strength * 1.65f * 255).toInt().coerceIn(0, 255)
            canvas.drawLine(
                cx + simulation.lines[o] * size,
                cy + simulation.lines[o + 1] * size,
                cx + simulation.lines[o + 3] * size,
                cy + simulation.lines[o + 4] * size,
                paint,
            )
        }
        for (i in 0 until simulation.count) {
            val o = i * 4
            val depth = simulation.points[o + 3]
            val r = simulation.points[o + 2] * size * .5f
            val x = cx + simulation.points[o] * size
            val y = cy + simulation.points[o + 1] * size
            paint.color = tint(depth)
            paint.alpha = ((.2f + depth * .48f) * strength * 2 * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, max(.5f, r * .42f), paint)
            paint.alpha = (.07f * strength * 255).toInt()
            canvas.drawCircle(x, y, r * 1.4f, paint)
        }
    }
}
