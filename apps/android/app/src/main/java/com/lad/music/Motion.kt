// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import kotlin.math.roundToInt

/** A single mounted scene: outgoing and incoming blur sources never overlap. */
@Stable
class LayerTransition<T>(initial: T) {
    var displayed by mutableStateOf(initial)
    var leaving by mutableStateOf(false)
    val progress = Animatable(0f)
}

val LocalLayerMotion = staticCompositionLocalOf<LayerTransition<*>?> { null }

@Composable
fun <T : Any> AnimatedPane(target: T?, content: @Composable (T) -> Unit) {
    val motion = rememberLayerTransition(target)
    motion.displayed?.let { value ->
        CompositionLocalProvider(LocalLayerMotion provides motion) { content(value) }
    }
}

@Composable
fun <T> rememberLayerTransition(
    target: T,
    enterMs: Int = 240,
    exitMs: Int = 140,
): LayerTransition<T> {
    val transition = remember { LayerTransition(target) }
    LaunchedEffect(target) {
        if (transition.displayed != target) {
            transition.leaving = true
            if (transition.displayed != null)
                transition.progress.animateTo(0f, tween(exitMs, easing = FastOutLinearInEasing))
            transition.displayed = target
            transition.progress.snapTo(0f)
        }
        transition.leaving = false
        if (target != null)
            transition.progress.animateTo(1f, tween(enterMs, easing = LinearOutSlowInEasing))
    }
    return transition
}

@Composable
fun Modifier.layerMotion(transition: LayerTransition<*>?, distance: Dp = 48.dp): Modifier {
    if (transition == null) return this
    val pixels = with(LocalDensity.current) { distance.toPx() }
    return offset { IntOffset(0, ((1f - transition.progress.value) * pixels).roundToInt()) }
        .graphicsLayer { alpha = transition.progress.value }
        .pointerInput(transition.leaving) {
            if (transition.leaving)
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach {
                        it.consume()
                    }
                }
        }
}

/** Full-height opaque slide; the player beneath remains mounted and stationary. */
@Composable
fun Modifier.lyricsScreenMotion(transition: LayerTransition<*>, height: Dp): Modifier {
    val pixels = with(LocalDensity.current) { height.toPx() }
    return offset { IntOffset(0, ((1f - transition.progress.value) * pixels).roundToInt()) }
        .pointerInput(transition.leaving) {
            if (transition.leaving)
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach {
                        it.consume()
                    }
                }
        }
}

@Composable
fun pageEntrance(key: Any): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(key) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(180, easing = LinearOutSlowInEasing))
    }
    return Modifier.graphicsLayer { alpha = progress.value }
}

@Composable
fun TrackTransition(
    track: Track,
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable (Track) -> Unit,
) {
    AnimatedContent(
        targetState = track to index,
        modifier = modifier,
        contentKey = { it.first.id },
        transitionSpec = {
            fadeIn(tween(180)).togetherWith(fadeOut(tween(100))).using(SizeTransform(clip = true))
        },
        label = "track identity",
    ) { value ->
        content(value.first)
    }
}
