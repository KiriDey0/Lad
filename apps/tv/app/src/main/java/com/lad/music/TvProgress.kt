// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/** Orbit scrubber in the TV design coordinate space. */
class TvProgress(context: Context, private val accent: Int, private val scale: Float) :
    View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wave = Path()
    var progress: Int = 0
        set(value) {
            val next = value.coerceIn(0, 10000)
            if (field != next) {
                field = next
                invalidate()
            }
        }
    var playing: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        val inset = 4f * scale
        val right = width - inset
        val y = height / 2f
        val head = inset + (right - inset) * progress / 10000f
        val gap = 6f * scale
        val played = head - gap
        val unplayed = head + gap
        paint.strokeWidth = 4f * scale
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(40, 43, 49)
        if (unplayed < right) canvas.drawLine(unplayed, y, right, y, paint)
        paint.color = accent
        if (played > inset) {
            if (playing && ValueAnimator.areAnimatorsEnabled()) {
                val period = 40f * scale
                val stride = 2f * scale
                val phase = (SystemClock.uptimeMillis() % 1600L) / 1600.0
                fun waveY(x: Float) = y + 3f * scale * sin((x / period - phase) * 2 * PI).toFloat()
                wave.rewind()
                wave.moveTo(inset, waveY(inset))
                var x = inset + stride
                while (x < played) {
                    wave.lineTo(x, waveY(x))
                    x += stride
                }
                wave.lineTo(played, waveY(played))
                paint.style = Paint.Style.STROKE
                canvas.drawPath(wave, paint)
                paint.style = Paint.Style.FILL
                if (isShown && windowVisibility == VISIBLE) postInvalidateOnAnimation()
            } else {
                canvas.drawLine(inset, y, played, y, paint)
            }
        }
        paint.color = Color.rgb(241, 243, 245)
        canvas.drawRoundRect(
            head - 2 * scale, y - 9 * scale, head + 2 * scale, y + 9 * scale,
            2 * scale, 2 * scale, paint,
        )
    }
}
