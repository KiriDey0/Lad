// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.lad.music

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.launch

/** The clickable region stays outside marquee: its coordinates never move with the glyphs. */
@Composable
fun MarqueeLabel(
    text: String,
    size: Int = 14,
    color: Color = LocalPalette.current.text,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    key(text) {
        Text(
            text,
            modifier
                .clipToBounds()
                .basicMarquee(
                    iterations = Int.MAX_VALUE,
                    initialDelayMillis = 1600,
                    repeatDelayMillis = 1600,
                    spacing = MarqueeSpacing(32.dp),
                    velocity = 28.dp,
                ),
            fontFamily = Golos,
            fontSize = (size * p.scale).sp,
            color = color,
            fontWeight = weight,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
fun PlayerMetadata(
    t: Track,
    s: MusicState,
    engine: MusicEngine,
    open: (Card) -> Unit,
    menu: (Track) -> Unit,
    compact: Boolean = false,
) {
    val p = LocalPalette.current
    val fontScale = LocalDensity.current.fontScale * p.scale
    val height = (56f * fontScale.coerceAtLeast(1f)).dp
    Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
        TrackTransition(t, s.index, Modifier.weight(1f).fillMaxHeight()) { display ->
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).clickable(enabled = display.albumId.isNotBlank()) {
                            open(display.albumCard())
                        }
                    ) {
                        MarqueeLabel(
                            display.title,
                            if (compact) 16 else 22,
                            weight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Explicit(display)
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.fillMaxWidth().clickable(
                        enabled = display.artists.any { it.id.isNotBlank() }
                    ) {
                        val artists = display.artists.filter { it.id.isNotBlank() }
                        if (artists.size == 1) open(Card(artists[0].id, "artist", artists[0].name))
                        else menu(display)
                    }
                ) {
                    MarqueeLabel(
                        display.artist,
                        if (compact) 12 else 14,
                        p.muted,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Tool(
            if (t.liked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
            "Нравится",
            active = t.liked,
            onClick = { engine.task { engine.like(t) } },
        )
    }
}

/** Three real queue occurrences, frozen during a drag and its playback commit. */
@Composable
fun CoverCarousel(s: MusicState, engine: MusicEngine, modifier: Modifier = Modifier) {
    var window by remember { mutableStateOf(coverWindow(s)) }
    var engaged by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    val ui = rememberCoroutineScope()
    LaunchedEffect(
        s.queue,
        s.index,
        s.externalTrack,
        s.output,
        s.owned,
        s.repeat,
        s.settings.blockExplicit,
        engaged,
        pending,
    ) {
        if (!engaged && !pending) window = coverWindow(s)
    }
    if (window.entries.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val viewport = maxWidth.coerceAtMost(390.dp)
        val edge = (viewport * .7846f).coerceAtMost(306.dp)
        val inset = (viewport - edge) / 2
        key(window) {
            val pager =
                rememberPagerState(initialPage = window.center, pageCount = { window.entries.size })
            var armed by remember { mutableStateOf(false) }
            LaunchedEffect(pager) {
                pager.interactionSource.interactions.collect { event ->
                    if (event is DragInteraction.Start) {
                        armed = true
                        engaged = true
                    }
                }
            }
            LaunchedEffect(pager) {
                snapshotFlow { pager.isScrollInProgress to pager.settledPage }
                    .collect { (moving, page) ->
                        if (!moving && armed) {
                            armed = false
                            if (
                                page != window.center &&
                                    acceptsCoverSelection(engine.state.value, window, page)
                            ) {
                                pending = true
                                val selection = window to page
                                val direction = if (page > window.center) 1 else -1
                                // Engine owns this job; closing the player must not cancel a
                                // selected track.
                                val job =
                                    engine.task {
                                        engine.advance(direction, coverSelection = selection)
                                    }
                                ui.launch {
                                    job.join()
                                    pending = false
                                }
                            }
                            engaged = false
                            if (page != window.center && !pending)
                                pager.animateScrollToPage(window.center)
                        }
                    }
            }
            HorizontalPager(
                state = pager,
                modifier = Modifier.width(viewport).height(edge),
                contentPadding = PaddingValues(horizontal = inset),
                pageSpacing = 12.dp,
                pageSize = PageSize.Fixed(edge),
                beyondViewportPageCount = 1,
                userScrollEnabled = !pending && !s.loading && window.entries.size > 1,
                flingBehavior =
                    PagerDefaults.flingBehavior(
                        pager,
                        pagerSnapDistance = PagerSnapDistance.atMost(1),
                    ),
                key = { "$it:${window.entries[it].index}:${window.entries[it].track.id}" },
            ) { page ->
                val track = window.entries[page].track
                Artwork(track.cover, track.title, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** C4 visuals with Material's native drag, keyboard, accessibility and touch target handling. */
@Composable
fun C4Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val p = LocalPalette.current
    Slider(
        value,
        onValueChange,
        modifier.heightIn(min = 48.dp),
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        thumb = {
            Box(Modifier.size(16.dp).background(if (enabled) p.accent else p.muted, CircleShape))
        },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val f =
                    ((state.value - valueRange.start) /
                            (valueRange.endInclusive - valueRange.start))
                        .coerceIn(0f, 1f)
                drawLine(
                    p.raised,
                    Offset(0f, size.height / 2),
                    Offset(size.width, size.height / 2),
                    size.height,
                    StrokeCap.Round,
                )
                drawLine(
                    if (enabled) p.accent else p.muted,
                    Offset(0f, size.height / 2),
                    Offset(size.width * f, size.height / 2),
                    size.height,
                    StrokeCap.Round,
                )
            }
        },
    )
}

@Composable
fun MenuSummary(cover: String?, title: String, subtitle: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(cover, title, 44.dp)
        Column(Modifier.weight(1f)) {
            MarqueeLabel(
                title,
                16,
                weight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            MarqueeLabel(
                subtitle,
                12,
                LocalPalette.current.muted,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun MenuPlaybackActions(
    enabled: Boolean = true,
    play: () -> Unit,
    next: () -> Unit,
    append: () -> Unit,
) {
    val p = LocalPalette.current
    val labels = listOf("Включить", "Следующим", "В очередь")
    val icons =
        listOf(Icons.Outlined.PlayArrow, Icons.Outlined.PlaylistPlay, Icons.Outlined.QueueMusic)
    val actions = listOf(play, next, append)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { i, label ->
            Column(
                Modifier.weight(1f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                    .background(p.raised)
                    .clickable(enabled = enabled, onClick = actions[i])
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    icons[i],
                    null,
                    Modifier.size(24.dp),
                    tint = if (enabled) p.accent else p.muted,
                )
                Spacer(Modifier.height(8.dp))
                Label(label, 11, if (enabled) p.text else p.muted, maxLines = 1)
            }
        }
    }
}
