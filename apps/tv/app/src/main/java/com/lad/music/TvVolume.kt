// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.roundToInt

internal class TvVolume(
    context: Context,
    private val accent: Int,
    private val scale: Float,
    font: Typeface,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font
        textSize = 26f * scale
    }
    private val speaker = context.getDrawable(R.drawable.ic_volume_2)?.mutate()
    private val mutedSpeaker = context.getDrawable(R.drawable.ic_volume_x)?.mutate()
    private val onAccent = Color.rgb(17, 23, 40)

    var value = 0f
        set(next) {
            if (field != next) {
                field = next.coerceIn(0f, 1f)
                contentDescription = "Громкость ${(field * 100).roundToInt()}%"
                invalidate()
            }
        }

    init {
        contentDescription = "Громкость 0%"
        speaker?.setTint(onAccent)
        mutedSpeaker?.setTint(onAccent)
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        val fill = (width * value).coerceAtLeast(96f * scale).coerceAtMost(width.toFloat())
        paint.color = Color.rgb(32, 34, 39)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
        paint.color = accent
        canvas.drawRoundRect(0f, 0f, fill, height.toFloat(), radius, radius, paint)
        paint.color = onAccent
        canvas.drawRoundRect(
            fill - 22 * scale, radius - 15 * scale, fill - 16 * scale, radius + 15 * scale,
            3 * scale, 3 * scale, paint,
        )
        val percent = (value * 100).roundToInt()
        val iconSize = (28 * scale).roundToInt()
        val iconLeft = (24 * scale).roundToInt()
        val iconTop = (height - iconSize) / 2
        val icon = if (percent == 0) mutedSpeaker else speaker
        icon?.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
        icon?.draw(canvas)

        val label = "$percent%"
        val labelWidth = paint.measureText(label)
        val inside = fill >= labelWidth + 96 * scale
        val labelX = if (inside) fill - labelWidth - 34 * scale else fill + 14 * scale
        val baseline = radius - (paint.ascent() + paint.descent()) / 2
        paint.color = if (inside) onAccent else Color.rgb(241, 243, 245)
        canvas.drawText(label, labelX, baseline, paint)
        val endX = width - paint.measureText("100%") - 24 * scale
        if (percent < 100 && endX - 12 * scale > maxOf(fill, labelX + labelWidth)) {
            paint.color = Color.rgb(170, 175, 185)
            canvas.drawText("100%", endX, baseline, paint)
        }
    }
}
