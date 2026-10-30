// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music.wave

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.provider.Settings
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import androidx.media3.common.util.UnstableApi
import coil.request.Disposable
import coil.request.ImageRequest
import java.util.concurrent.Executors
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

    var remote: RemoteWaveAudio? = null
        set(value) {
            if (field === value) return
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

    private class Frame(count: Int) {
        val points = FloatArray(count * 4)
        val lines = FloatArray(count * 30 * 6)
        var lineCount = 0
    }

    private var front = Frame(simulation.count)
    private var back = Frame(simulation.count)
    private var computing = false
    private val lineBuckets = Array(12) { FloatArray(simulation.count * 30 * 4) }
    private val lineSizes = IntArray(12)
    private val pointBuckets = Array(8) { FloatArray(simulation.count * 2) }
    private val pointSizes = IntArray(8)
    private val frameInterval =
        if (
            (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
                Configuration.UI_MODE_TYPE_TELEVISION
        )
            30_000_000L
        else 15_000_000L

    companion object {
        private val worker =
            Executors.newSingleThreadExecutor { task ->
                Thread(task, "LadWave").apply { isDaemon = true }
            }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val base = arrayOf(floatArrayOf(166f, 182f, 192f), floatArrayOf(190f, 180f, 165f))
    private var target = base.map { it.copyOf() }
    private val colors = base.map { it.copyOf() }
    private var coverRequest: Disposable? = null
    private var last = 0L
    private var pause = 1f
    private var pending = false
    private val visibleRect = Rect()
    private val scrollVisibility = ViewTreeObserver.OnScrollChangedListener { refreshAudio(); schedule() }
    private val layoutVisibility = ViewTreeObserver.OnGlobalLayoutListener { refreshAudio(); schedule() }

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        simulation.step(.016)
        simulation.points.copyInto(front.points)
        simulation.lines.copyInto(front.lines, endIndex = simulation.lineCount * 3)
        front.lineCount = simulation.lineCount
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
        val active = playing &&
                intensity > 0 &&
                !reduced() &&
                isAttachedToWindow &&
                windowVisibility == VISIBLE &&
                isShown && getGlobalVisibleRect(visibleRect)
        audio?.enabled = active && local
        remote?.enabled = active && !local
    }

    private fun schedule() {
        if (!pending && isAttachedToWindow && windowVisibility == VISIBLE && isShown) {
            pending = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(scrollVisibility)
        viewTreeObserver.addOnGlobalLayoutListener(layoutVisibility)
        last = 0
        loadCover(cover)
        refreshAudio()
        schedule()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnScrollChangedListener(scrollVisibility)
        viewTreeObserver.removeOnGlobalLayoutListener(layoutVisibility)
        Choreographer.getInstance().removeFrameCallback(this)
        pending = false
        last = 0
        coverRequest?.dispose()
        audio?.enabled = false
        remote?.enabled = false
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
        if (windowVisibility != VISIBLE || !isShown || !getGlobalVisibleRect(visibleRect)) {
            refreshAudio()
            last = 0
            return
        }
        // Keep TV at 30 fps; touch devices use 60 fps without blocking input.
        if (last != 0L && frameTimeNanos - last < frameInterval) {
            schedule()
            return
        }
        val dt = if (last == 0L) .016 else ((frameTimeNanos - last) / 1e9).coerceAtMost(.05)
        last = frameTimeNanos
        val targetPause = if (playing) 0f else 1f
        pause += ((targetPause - pause) * (1 - exp(-dt * 4))).toFloat()
        if (!computing) {
            computing = true
            val raw = if (playing) (if (local) audio else remote?.audio)?.sample() ?: FloatArray(8) else FloatArray(8)
            val movement = if (reduced()) 0.0 else dt * (1 - pause)
            val destination = back
            worker.execute {
                val bands = envelope.process(raw, dt)
                if (movement > 0) simulation.step(movement, bands)
                simulation.points.copyInto(destination.points)
                simulation.lines.copyInto(destination.lines, endIndex = simulation.lineCount * 3)
                destination.lineCount = simulation.lineCount
                post {
                    back = front
                    front = destination
                    computing = false
                    if (isAttachedToWindow) invalidate()
                }
            }
        }
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
        val frame = front
        lineSizes.fill(0)
        pointSizes.fill(0)
        for (i in 0 until frame.lineCount step 2) {
            val o = i * 3
            val bucket = (frame.lines[o + 2] * strength * 1.65f * 40).toInt().coerceIn(0, 11)
            if (bucket == 0) continue
            val b = lineBuckets[bucket]
            val n = lineSizes[bucket]
            b[n] = cx + frame.lines[o] * size
            b[n + 1] = cy + frame.lines[o + 1] * size
            b[n + 2] = cx + frame.lines[o + 3] * size
            b[n + 3] = cy + frame.lines[o + 4] * size
            lineSizes[bucket] += 4
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(.7f, resources.displayMetrics.density * .65f)
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = tint(.55f)
        for (i in 1..11) if (lineSizes[i] > 0) {
            paint.alpha = (i / 40f * 255).toInt()
            canvas.drawLines(lineBuckets[i], 0, lineSizes[i], paint)
        }
        for (i in 0 until simulation.count) {
            val o = i * 4
            val bucket = (frame.points[o + 3] * 8).toInt().coerceIn(0, 7)
            val b = pointBuckets[bucket]
            val n = pointSizes[bucket]
            b[n] = cx + frame.points[o] * size
            b[n + 1] = cy + frame.points[o + 1] * size
            pointSizes[bucket] += 2
        }
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 0..7) if (pointSizes[i] > 0) {
            val depth = (i + .5f) / 8
            paint.color = tint(depth)
            paint.alpha = ((.2f + depth * .48f) * strength * 2 * 255).toInt().coerceIn(0, 255)
            paint.strokeWidth = max(1f, (.0035f + depth * .0025f) * size * .42f)
            canvas.drawPoints(pointBuckets[i], 0, pointSizes[i], paint)
            paint.alpha = (.07f * strength * 255).toInt()
            paint.strokeWidth *= 3.2f
            canvas.drawPoints(pointBuckets[i], 0, pointSizes[i], paint)
        }
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
    }
}
