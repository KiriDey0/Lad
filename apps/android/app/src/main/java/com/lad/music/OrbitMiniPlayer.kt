// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lad.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
internal fun OrbitMiniPlayer(
    state: MusicState,
    engine: MusicEngine,
    openNow: () -> Unit,
    menu: (Track) -> Unit,
) {
    val current = state.track ?: return
    val p = LocalPalette.current
    CoverCarousel(state, engine, compact = true) { visible, center ->
        val track = if (center && visible.id == current.id) current else visible
        val repository = (LocalContext.current.applicationContext as LadApplication).lockArtwork
        val artwork by produceState(repository.cached(track.cover), track.cover) {
            value = repository.load(track.cover)
        }
        val tint = artwork?.waveColor?.let { Color(it) }
        val surface = tint?.let { lerp(p.raised, it, .10f) } ?: p.raised
        val fill = lerp(surface, tint ?: p.text, .12f)
        val position = if (center) engine.position.collectAsStateWithLifecycle() else null
        val shape = CircleShape
        val content = Modifier.fillMaxSize()
            .clip(shape)
            .background(surface)
            .drawBehind {
                if (center && state.durationMs > 0) {
                    val fraction = ((position?.value ?: 0L).toDouble() / state.durationMs)
                        .coerceIn(0.0, 1.0).toFloat()
                    drawRect(fill, size = Size(size.width * fraction, size.height))
                }
            }
            .border(1.dp, p.text.copy(alpha = .14f), shape)
        val interaction = if (center) Modifier
            .combinedClickable(
                onClickLabel = "Развернуть плеер",
                onClick = openNow,
                onLongClick = { menu(track) },
            )
            .semantics {
                contentDescription = "Мини-плеер"
                stateDescription = "${track.title} · ${track.artist}"
            }
        else Modifier.clearAndSetSemantics {}
        Row(
            content.then(interaction).padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(track.cover, track.title, 40.dp, cornerRadius = 9.dp)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                MarqueeLabel(track.title, 14, weight = FontWeight.SemiBold)
                MarqueeLabel(track.artist, 11, p.muted)
            }
            if (center) {
                OrbitMiniLike(track, engine)
                if (state.loading) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else {
                    Tool(
                        if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        if (state.playing) "Пауза" else "Продолжить",
                        onClick = { engine.task { engine.toggle() } },
                    )
                }
            }
        }
    }
}

@Composable
private fun OrbitMiniLike(track: Track, engine: MusicEngine) {
    val beat = remember(track.id) { Animatable(1f) }
    val shake = remember(track.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var pending by remember(track.id) { mutableStateOf(false) }
    Box(Modifier.graphicsLayer {
        scaleX = beat.value
        scaleY = beat.value
        translationX = shake.value.dp.toPx()
    }) {
        Tool(
            if (track.liked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
            if (track.liked) "Убрать из любимых" else "Мне нравится",
            enabled = !pending,
            onClick = {
                pending = true
                val operation = engine.task { engine.like(track) }
                scope.launch {
                    operation.join()
                    pending = false
                    val actual = engine.state.value.track
                    if (actual?.id == track.id && actual.liked != track.liked) {
                        if (actual.liked)
                            beat.animateTo(1f, keyframes {
                                durationMillis = 320
                                1.18f at 105
                                .96f at 200
                            })
                        else
                            shake.animateTo(0f, keyframes {
                                durationMillis = 240
                                -3f at 55
                                3f at 115
                                -1.5f at 175
                            })
                    }
                }
            },
        )
    }
}
