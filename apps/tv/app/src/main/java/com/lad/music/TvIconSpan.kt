// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.style.ReplacementSpan

/** Baseline-independent Lucide icon, using the text's current foreground colour. */
class TvIconSpan(private val icon: Drawable, private val size: Int) : ReplacementSpan() {
    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ) = size

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val center = y + (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2
        val save = canvas.save()
        canvas.translate(x, center - size / 2)
        icon.setBounds(0, 0, size, size)
        icon.setTint(paint.color)
        icon.draw(canvas)
        canvas.restoreToCount(save)
    }
}
