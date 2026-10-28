// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.content.Context
import android.graphics.*
import android.renderscript.*
import android.view.View
import android.widget.FrameLayout

/** API 28 glass: sample only this app's content at 1/8 scale, on invalidation. */
@Suppress("DEPRECATION")
class TvGlass(context: Context, private val source: View, private val enabled: () -> Boolean) :
    FrameLayout(context) {
    private var image: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    private val snapshot = Runnable { capture() }

    init {
        setWillNotDraw(false)
    }

    fun refresh() {
        removeCallbacks(snapshot)
        postDelayed(snapshot, 100)
    }

    private fun capture() {
        if (!isAttachedToWindow || width == 0 || height == 0) return
        if (!enabled()) {
            image?.recycle()
            image = null
            invalidate()
            return
        }
        val loc = IntArray(2)
        val src = IntArray(2)
        getLocationInWindow(loc)
        source.getLocationInWindow(src)
        val bitmap =
            Bitmap.createBitmap(
                (width / 8).coerceAtLeast(1),
                (height / 8).coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
        val canvas = Canvas(bitmap)
        canvas.scale(1f / 8, 1f / 8)
        canvas.translate((src[0] - loc[0]).toFloat(), (src[1] - loc[1]).toFloat())
        source.draw(canvas)
        runCatching {
            val rs = RenderScript.create(context)
            try {
                val input = Allocation.createFromBitmap(rs, bitmap)
                val output = Allocation.createTyped(rs, input.type)
                val blur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
                blur.setRadius(2f)
                blur.setInput(input)
                blur.forEach(output)
                output.copyTo(bitmap)
                blur.destroy()
                output.destroy()
                input.destroy()
            } finally {
                rs.destroy()
            }
        }
        image?.recycle()
        image = bitmap
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val radius = height.coerceAtMost(56) / 2f
        clip.reset()
        clip.addRoundRect(r, radius, radius, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)
        image?.let {
            paint.color = Color.WHITE
            paint.alpha = 255
            canvas.drawBitmap(it, null, r, paint)
        }
        paint.color = if (enabled()) Color.argb(191, 23, 25, 29) else Color.rgb(23, 25, 29)
        canvas.drawRect(r, paint)
        canvas.restoreToCount(save)
        paint.color = Color.argb(48, 255, 255, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRoundRect(RectF(1f, 1f, width - 1f, height - 1f), radius, radius, paint)
        paint.style = Paint.Style.FILL
        super.onDraw(canvas)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(snapshot)
        image?.recycle()
        image = null
        super.onDetachedFromWindow()
    }
}
