// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.lad.music

import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlinx.coroutines.isActive

/** The gesture and floating row live outside LazyColumn so recycling cannot cancel a drag. */
@Composable
fun QueueTrackList(
    state: MusicState,
    engine: MusicEngine,
    open: (Card) -> Unit,
    menu: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val list = rememberLazyListState()
    val handles = remember { mutableMapOf<String, Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var height by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf<Track?>(null) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var grab by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(0) }
    var target by remember { mutableIntStateOf(0) }
    val tracks by rememberUpdatedState(state.queue)
    val onMenu by rememberUpdatedState(menu)
    val onOpen by rememberUpdatedState(open)
    var initialPositioned by remember { mutableStateOf(false) }
    val entryId = active?.queueEntryId
    val preview = remember(state.queue, entryId, target) {
        val from = entryId?.let { queueEntryIndex(state.queue, it) } ?: -1
        if (from < 0) state.queue else state.queue.toMutableList().apply {
            add(target.coerceIn(0, lastIndex), removeAt(from))
        }
    }
    // Position only on opening, never follow playback or pull a manually scrolled list back.
    LaunchedEffect(state.queue.isNotEmpty(), height) {
        if (!initialPositioned && state.queue.isNotEmpty() && height > 0) {
            initialPositioned = true
            val index = state.index.coerceIn(state.queue.indices)
            list.scrollToItem(index, -(height * 2 / 3))
        }
    }
    // External queue changes invalidate the preview, not a metadata/position update.
    val order = state.queue.map { it.queueEntryId }
    LaunchedEffect(order) { active = null }

    fun updateTarget() {
        val center = (pointerY - grab + rowHeight / 2f).coerceIn(0f, height.coerceAtLeast(1) - 1f)
        val visible = list.layoutInfo.visibleItemsInfo
        val candidate = visible.firstOrNull { center >= it.offset && center < it.offset + it.size }
            ?: visible.minByOrNull { abs(it.offset + it.size / 2f - center) }
        if (candidate != null && candidate.key != active?.queueEntryId) target = candidate.index
    }
    // A single frame loop runs only during a drag, even if the finger is held still at an edge.
    val edge = with(density) { 64.dp.toPx() }
    val maxSpeed = with(density) { 800.dp.toPx() }
    LaunchedEffect(entryId) {
        if (entryId == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (isActive && active != null) {
            val now = withFrameNanos { it }
            val dt = ((now - previous) / 1_000_000_000f).coerceIn(0f, .05f)
            previous = now
            val speed = queueEdgeSpeed(pointerY, height.toFloat(), edge, maxSpeed)
            if (speed != 0f) list.scrollBy(speed * dt)
            updateTarget()
        }
    }
    fun finish(commit: Boolean) {
        val moving = active ?: return
        if (commit) {
            val rest = tracks.filter { it.queueEntryId != moving.queueEntryId }
            val before = rest.getOrNull(target.coerceIn(0, rest.size))?.queueEntryId
            engine.moveEntry(moving.queueEntryId, before)
        }
        active = null
    }
    Box(modifier.fillMaxWidth().clipToBounds()
        .onGloballyPositioned { origin = it.positionInRoot(); height = it.size.height }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val hit = handles.entries.firstOrNull { (id, rect) ->
                    queueEntryIndex(tracks, id) >= 0 && rect.contains(down.position + origin)
                } ?: return@awaitEachGesture
                val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == hit.key }
                    ?: return@awaitEachGesture
                val track = tracks.firstOrNull { it.queueEntryId == hit.key } ?: return@awaitEachGesture
                pointerY = press.position.y
                grab = pointerY - item.offset
                rowHeight = item.size
                target = item.index
                active = track
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                try {
                    // Consume at Initial before LazyColumn can start its own scroll gesture.
                    // The handler stays attached to the list viewport throughout recycling.
                    var released = false
                    while (active != null) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == press.id } ?: break
                        if (change.isConsumed) break
                        pointerY = change.position.y
                        change.consume()
                        updateTarget()
                        if (!change.pressed) { released = true; break }
                    }
                    finish(released)
                } finally { finish(false) }
            }
        }) {
        LazyColumn(Modifier.fillMaxSize(), state = list,
            userScrollEnabled = active == null, contentPadding = PaddingValues(bottom = 20.dp)) {
            items(preview, key = { it.queueEntryId }) { track ->
                val id = track.queueEntryId
                val isCurrent = state.owned && state.queue.getOrNull(state.index)?.queueEntryId == id
                Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                    placementSpec = spring(stiffness = 600f))
                    .semantics { contentDescription = "Строка очереди: ${track.title}"; selected = isCurrent }) {
                    if (id == entryId) Spacer(Modifier.fillMaxWidth().height(with(density) { rowHeight.toDp() }))
                    else TrackRow(track, state,
                        { engine.task { engine.jumpEntry(id) } },
                        onOpen, { onMenu(track) }, trailing = {
                            Tool(Icons.Outlined.MoreHoriz, "Действия в очереди: ${track.title}", onClick = { onMenu(track) })
                            DisposableEffect(id) { onDispose { handles.remove(id) } }
                            Icon(Icons.Outlined.DragIndicator, "Переставить ${track.title}",
                                Modifier.size(48.dp).onGloballyPositioned { handles[id] = it.boundsInRoot() }.padding(12.dp), tint = p.muted)
                        })
                }
            }
        }
        active?.let { track ->
            TrackRow(track, state, {}, {}, {},
                Modifier.offset { IntOffset(0, (pointerY - grab).roundToInt()) }
                    .height(with(density) { rowHeight.toDp() }).zIndex(2f)
                    .shadow(12.dp, ladPanelShape(16.dp))
                    .background(p.raised, ladPanelShape(16.dp))
                    .semantics { contentDescription = "Переносимый трек: ${track.title}" },
                trailing = { Icon(Icons.Outlined.DragIndicator, null, Modifier.size(48.dp).padding(12.dp), tint = p.muted) })
        }
    }
}
