// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pulling unconsumed space at the top of a scrollable sheet dismisses it. */
@Composable
fun SwipeDismissLayer(
    modifier: Modifier = Modifier,
    dismiss: () -> Unit,
    content: @Composable (handle: Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val screenHeight = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val threshold = with(density) { 88.dp.toPx() }
    val flingThreshold = with(density) { 900.dp.toPx() }
    var offset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val revealBackdrop = LocalBackdropReveal.current
    val revealKey = remember { Any() }
    val revealing by remember { derivedStateOf { offset > 0f } }
    LaunchedEffect(revealing) { revealBackdrop?.invoke(revealKey, revealing) }
    DisposableEffect(revealBackdrop) { onDispose { revealBackdrop?.invoke(revealKey, false) } }

    val onDismiss by rememberUpdatedState(dismiss)
    val animated by
        animateFloatAsState(
            targetValue = offset,
            animationSpec = if (dragging) snap() else spring(dampingRatio = 1f, stiffness = 500f),
            label = "sheet drag",
            finishedListener = { if (closing) onDismiss() },
        )
    val settle by
        rememberUpdatedState<(Float) -> Unit>({ velocity ->
            if (!closing) {
                dragging = false
                closing =
                    offset >= threshold || (offset > threshold / 8 && velocity > flingThreshold)
                offset = if (closing) screenHeight else 0f
            }
        })
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (
                    source != NestedScrollSource.UserInput ||
                        closing ||
                        offset <= 0 ||
                        available.y >= 0
                )
                    return Offset.Zero
                dragging = true
                val consumed = available.y.coerceAtLeast(-offset)
                offset += consumed
                return Offset(0f, consumed)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput || closing || available.y <= 0)
                    return Offset.Zero
                dragging = true
                offset = (offset + available.y).coerceAtMost(screenHeight)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset <= 0 || closing) return Velocity.Zero
                settle(available.y)
                return Velocity(0f, available.y)
            }
        }
    }
    val handle =
        Modifier.draggable(
            state =
                rememberDraggableState { dy ->
                    if (!closing) offset = (offset + dy).coerceIn(0f, screenHeight)
                },
            orientation = Orientation.Vertical,
            enabled = !closing,
            onDragStarted = { dragging = true },
            onDragStopped = { settle(it) },
        )
    Box(modifier.offset { IntOffset(0, animated.roundToInt()) }.nestedScroll(connection)) {
        content(handle)
    }
}

@Composable
fun DragHandle(modifier: Modifier, dismiss: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(24.dp).semantics {
            contentDescription = "Потяни вниз, чтобы закрыть"
            customActions =
                listOf(
                    CustomAccessibilityAction("Закрыть") {
                        dismiss()
                        true
                    }
                )
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(32.dp, 4.dp)
                .background(LocalPalette.current.muted.copy(alpha = .5f), RoundedCornerShape(2.dp))
        )
    }
}

@Composable
fun rememberTrackSwipe(s: MusicState, engine: MusicEngine): Modifier {
    val density = LocalDensity.current
    val threshold = with(density) { 56.dp.toPx() }
    val velocityThreshold = with(density) { 700.dp.toPx() }
    val haptic = LocalHapticFeedback.current
    var offset by remember(s.track?.id) { mutableFloatStateOf(0f) }
    fun skip(direction: Int): Boolean {
        engine.task { engine.advance(direction) }
        return true
    }
    return Modifier.draggable(
            state = rememberDraggableState { offset += it },
            orientation = Orientation.Horizontal,
            enabled = !s.loading,
            onDragStarted = { offset = 0f },
            onDragStopped = { velocity ->
                if (
                    abs(offset) >= threshold ||
                        (abs(offset) > threshold / 8 && abs(velocity) >= velocityThreshold)
                ) {
                    val delta = if (abs(offset) >= threshold) offset else velocity
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    skip(if (delta < 0) 1 else -1)
                }
                offset = 0f
            },
        )
        .semantics {
            customActions =
                listOf(
                    CustomAccessibilityAction("Следующий трек") { skip(1) },
                    CustomAccessibilityAction("Предыдущий трек") { skip(-1) },
                )
        }
}
