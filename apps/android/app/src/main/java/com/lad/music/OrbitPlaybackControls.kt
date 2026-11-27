// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lad.music

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
internal fun OrbitSeekTrack(fraction: Float, playing: Boolean, enabled: Boolean) {
    val p = LocalPalette.current
    val animated = playing && ValueAnimator.areAnimatorsEnabled()
    val phase = if (animated) {
        rememberInfiniteTransition(label = "Ход волны").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
            label = "Фаза",
        )
    } else null
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        val y = size.height / 2
        val head = size.width * fraction
        val stroke = 3.dp.toPx()
        val gap = 5.dp.toPx()
        val played = (head - gap).coerceAtLeast(0f)
        val unplayed = (head + gap).coerceAtMost(size.width)
        val ink = if (enabled) p.accent else p.muted
        if (unplayed < size.width) {
            drawLine(p.raised, Offset(unplayed, y), Offset(size.width, y), stroke, StrokeCap.Round)
        }
        if (played <= 0f) return@Canvas
        if (phase == null) {
            drawLine(ink, Offset(0f, y), Offset(played, y), stroke, StrokeCap.Round)
            return@Canvas
        }
        val period = 32.dp.toPx()
        val amplitude = 2.4.dp.toPx()
        val stride = 2.dp.toPx()
        val angle = phase.value * 2 * PI
        fun wave(x: Float): Float = y + amplitude * sin(x / period * 2 * PI - angle).toFloat()
        val line = Path().apply {
            moveTo(0f, wave(0f))
            var x = stride
            while (x < played) {
                lineTo(x, wave(x))
                x += stride
            }
            lineTo(played, wave(played))
        }
        drawPath(line, ink, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
internal fun OrbitVolumeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    interaction: MutableInteractionSource,
    onMute: () -> Unit,
) {
    val p = LocalPalette.current
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val percent = (value * 100).roundToInt()
    val fontScale = LocalDensity.current.fontScale
    val iconWidth = 48.dp
    BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
        val fill = (iconWidth + (maxWidth - iconWidth) * value).coerceAtLeast(60.dp).coerceAtMost(maxWidth)
        val inside = fill >= iconWidth + (22 + 40 * fontScale).dp
        val showMax = maxWidth - fill >= (if (inside) 45 else 89).dp * fontScale
        Box(Modifier.fillMaxWidth().height(44.dp).background(p.raised, CircleShape))
        Box(Modifier.width(fill).height(44.dp).background(p.accent, CircleShape)) {
            Box(
                Modifier.align(Alignment.CenterEnd).padding(end = 10.dp)
                    .size(4.dp, 20.dp).background(onAccent, CircleShape),
            )
            if (inside) {
                Label(
                    "$percent%", 12, onAccent,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 22.dp).clearAndSetSemantics {},
                )
            }
        }
        if (!inside) {
            Label(
                "$percent%", 12, p.text,
                modifier = Modifier.padding(start = fill + 8.dp).clearAndSetSemantics {},
            )
        }
        if (showMax) {
            Label(
                "100%", 12, p.muted,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).clearAndSetSemantics {},
            )
        }
        Slider(
            value,
            onValueChange,
            Modifier.matchParentSize().padding(start = iconWidth).semantics {
                contentDescription = "Громкость"
                stateDescription = "$percent%"
            },
            onValueChangeFinished = onValueChangeFinished,
            interactionSource = interaction,
            thumb = { Spacer(Modifier.size(4.dp)) },
            track = { Spacer(Modifier.fillMaxWidth().height(44.dp)) },
        )
        IconButton(onMute, Modifier.size(iconWidth)) {
            Icon(
                if (percent == 0) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                if (percent == 0) "Включить звук" else "Выключить звук",
                tint = onAccent,
            )
        }
    }
}
