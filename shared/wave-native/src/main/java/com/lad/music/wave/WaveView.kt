// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music.wave

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import android.content.Context
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

    var smoothing = 50f
        set(value) {
            field = wavePercent(value, 50f)
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
    private val silence = FloatArray(8)

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
    private var frameInterval = 33_333_333L
    private var onBattery = false
    private var nextFrame = 0L
    private var reduceMotion = false
    private val powerEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                onBattery = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0
            }
            val saving = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
            frameInterval = if (onBattery || saving) 50_000_000L else 33_333_333L
            nextFrame = 0
            schedule()
        }
    }
    private val motionChanges = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            reduceMotion = readReduced()
            refreshAudio()
            schedule()
        }
    }

    companion object {
        private val worker =
            Executors.newSingleThreadExecutor { task ->
                Thread(task, "LadWave").apply { isDaemon = true }
            }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val base = WavePalette.neutral
    private var lineGain = 1.25f
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
        if (url.isNullOrBlank()) {
            target = base.map { it.copyOf() }; lineGain = 1.25f
            return
        }
        coverRequest =
            coil.Coil.imageLoader(context)
                .enqueue(
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(96)
                        .allowHardware(false)
                        .target(
                            onError = {
                                if (cover == url) {
                                    target = base.map { it.copyOf() }; lineGain = 1.25f
                                    schedule()
                                }
                            },
                            onSuccess = { drawable ->
                                if (cover == url) {
                                    val bitmap = (drawable as? BitmapDrawable)?.bitmap
                                    if (bitmap != null) {
                                        val side = min(bitmap.width, bitmap.height)
                                        val crop = Bitmap.createBitmap(bitmap, (bitmap.width-side)/2, (bitmap.height-side)/2, side, side)
                                        val sample = Bitmap.createScaledBitmap(crop, 96, 96, true)
                                        val pixels = IntArray(96*96)
                                        sample.getPixels(pixels, 0, 96, 0, 0, 96, 96)
                                        val extracted = WavePalette.extract(pixels, 96, 96)
                                        target = extracted.colors
                                        lineGain = extracted.lineGain
                                        if(sample !== bitmap && sample !== crop) sample.recycle()
                                        if(crop !== bitmap) crop.recycle()
                                        schedule()
                                    }
                                }
                            }
                        )
                        .build()
                )
    }

    private fun readReduced() =
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f

    private fun refreshAudio() {
        val active = playing &&
                intensity > 0 &&
                !reduceMotion &&
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
        reduceMotion = readReduced()
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, motionChanges)
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply { addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) }
        ContextCompat.registerReceiver(context, powerEvents, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        viewTreeObserver.addOnScrollChangedListener(scrollVisibility)
        viewTreeObserver.addOnGlobalLayoutListener(layoutVisibility)
        last = 0
        loadCover(cover)
        refreshAudio()
        schedule()
    }

    override fun onDetachedFromWindow() {
        context.contentResolver.unregisterContentObserver(motionChanges)
        context.unregisterReceiver(powerEvents)
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
            nextFrame = 0
            return
        }
        if (intensity <= 0) { invalidate(); last = 0; nextFrame = 0; return }
        // Decorative background: 30 fps, or 20 fps on battery/power saver.
        if (nextFrame != 0L && frameTimeNanos + 250_000L < nextFrame) {
            schedule()
            return
        }
        nextFrame = frameTimeNanos + frameInterval -
            (if (nextFrame == 0L) 0L else (frameTimeNanos - nextFrame).coerceAtLeast(0L) % frameInterval)
        val dt = if (last == 0L) .016 else ((frameTimeNanos - last) / 1e9).coerceAtMost(.05)
        last = frameTimeNanos
        val targetPause = if (playing) 0f else 1f
        pause += ((targetPause - pause) * (1 - exp(-dt * 4))).toFloat()
        if (!computing) {
            computing = true
            val raw = if (playing) (if (local) audio else remote?.audio)?.sample() ?: silence else silence
            val movement = if (reduceMotion || pause >= .999f) 0.0 else dt * (1 - pause)
            val destination = back
            val smooth = smoothing
            worker.execute {
                val bands = envelope.process(raw, dt, smooth)
                if (movement > 0) simulation.step(movement, bands, smooth)
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
        if (computing.not()) invalidate()
        val paletteMoving =
            colors.indices.any { i -> (0..2).any { j -> abs(target[i][j] - colors[i][j]) > .1f } }
        if (
            (playing && !reduceMotion && intensity > 0) ||
                abs(targetPause - pause) > .001f ||
                paletteMoving
        )
            schedule()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (intensity <= 0) return
        val size = min(width.toFloat(), height.toFloat()) * 1.08f
        val cx = width * .5f
        val cy = height * .42f
        val strength = waveBrightness(intensity) * (1 - pause * .82f)
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
            val bucket = (frame.lines[o + 2] * (1 - pause * .82f) * 1.65f * lineGain * 40).toInt().coerceIn(0, 11)
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
            paint.alpha = (i / 40f * waveBrightness(intensity) * 255).toInt().coerceIn(0, 255)
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
            paint.alpha = ((.2f + depth * .48f).times(2).coerceAtMost(1f) * strength * 255).toInt().coerceIn(0, 255)
            paint.strokeWidth = max(1f, (.0035f + depth * .0025f) * size * .42f)
            canvas.drawPoints(pointBuckets[i], 0, pointSizes[i], paint)
            paint.alpha = (.07f * strength * 255).toInt().coerceIn(0, 255)
            paint.strokeWidth *= 3.2f
            canvas.drawPoints(pointBuckets[i], 0, pointSizes[i], paint)
        }
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
    }
}
