// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** C4 track geometry in the 1920 px TV design coordinate space. */
class TvProgress(context: Context, private val accent: Int, private val scale: Float) :
    View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 10000)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val r = 12f * scale
        val left = r
        val right = width - r
        val y = height / 2f
        val x = left + (right - left) * progress / 10000f
        paint.color = Color.rgb(40, 43, 49)
        canvas.drawRoundRect(left, y - 4 * scale, right, y + 4 * scale, 4 * scale, 4 * scale, paint)
        paint.color = accent
        canvas.drawRoundRect(left, y - 4 * scale, x, y + 4 * scale, 4 * scale, 4 * scale, paint)
        canvas.drawCircle(x, y, r, paint)
    }
}
