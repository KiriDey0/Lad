// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.lad.music

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private data class Destination(val label: String, val icon: ImageVector)

private val destinations =
    listOf(
        Destination("Для тебя", Icons.Outlined.GraphicEq),
        Destination("Поиск", Icons.Outlined.Search),
        Destination("Коллекция", Icons.Outlined.LibraryMusic),
        Destination("Настройки", Icons.Outlined.Tune),
    )

@Composable
fun Label(
    text: String,
    size: Int = 14,
    color: Color = LocalPalette.current.text,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val p = LocalPalette.current
    Text(
        text,
        modifier,
        fontFamily = Golos,
        fontSize = (size * p.scale).sp,
        color = color,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Tool(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    IconButton(onClick, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), enabled = enabled) {
        Icon(
            icon,
            label,
            Modifier.size((24 * p.scale).dp),
            tint = if (!enabled) p.muted.copy(alpha = .35f) else if (active) p.accent else p.muted,
        )
    }
}

@Composable
fun Action(
    label: String,
    icon: ImageVector = Icons.Outlined.PlayArrow,
    tonal: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Button(
        onClick,
        modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = if (tonal) p.tonal else p.accent,
                contentColor = if (tonal) p.text else p.bg,
            ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier.size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background((if (tonal) p.text else p.bg).copy(alpha = .08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Label(label, 14, if (tonal) p.text else p.bg, FontWeight.Medium, maxLines = 2)
    }
}

@Composable
fun Artwork(
    cover: String?,
    title: String,
    size: Dp? = null,
    modifier: Modifier = Modifier,
    cornerRadius: Dp? = null,
) {
    val p = LocalPalette.current
    val m = if (size != null) modifier.size(size) else modifier.fillMaxWidth().aspectRatio(1f)
    Box(
        m.clip(
                RoundedCornerShape(
                    cornerRadius ?: if (size != null && size < 80.dp) 10.dp else 24.dp
                )
            )
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFFB6B9AC), Color(0xFF172728), Color(0xFF365761))
                )
            )
    ) {
        if (cover != null)
            AsyncImage(
                cover,
                "Обложка: $title",
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        else
            Icon(
                Icons.Outlined.GraphicEq,
                null,
                Modifier.align(Alignment.BottomStart)
                    .padding(12.dp)
                    .size(if (size != null && size < 80.dp) 20.dp else 40.dp),
                tint = p.text,
            )
    }
}

val LocalExplicitInfo = staticCompositionLocalOf<(Track) -> Unit> { {} }

@Composable
fun Explicit(track: Track, interactive: Boolean = true) {
    if (!track.explicit) return
    val p = LocalPalette.current
    val show = LocalExplicitInfo.current
    val scale = p.scale * LocalDensity.current.fontScale
    Box(
        Modifier.padding(start = 5.dp)
            .size((16 * scale).dp)
            .clip(RoundedCornerShape((3 * scale).dp))
            .background(p.raised)
            .then(if (interactive) Modifier.clickable { show(track) } else Modifier)
            .semantics { contentDescription = "Контент 18+" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "E",
            fontFamily = Golos,
            fontSize = (9 * p.scale).sp,
            lineHeight = (10 * p.scale).sp,
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            color = p.muted,
            maxLines = 1,
        )
    }
}

@Composable
fun TrackRow(
    track: Track,
    state: MusicState,
    play: () -> Unit,
    open: (Card) -> Unit,
    menu: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    val haptic = LocalHapticFeedback.current
    val disabled = !track.available || (state.settings.blockExplicit && track.explicit)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (state.track?.id == track.id && state.owned) p.tonal else Color.Transparent
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The reorder handle is a sibling, so its hold-and-drag never opens the track menu.
        Row(
            Modifier.weight(1f)
                .heightIn(min = 52.dp)
                .combinedClickable(
                    enabled = !disabled,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu()
                    },
                    onClick = play,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box {
                Artwork(track.cover, track.title, 44.dp)
                if (state.track?.id == track.id && state.playing)
                    Icon(
                        Icons.Outlined.GraphicEq,
                        "Играет",
                        Modifier.align(Alignment.Center)
                            .background(p.surface.copy(alpha = .5f), RoundedCornerShape(6.dp))
                            .size(22.dp),
                        tint = p.accent,
                    )
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(
                        track.title,
                        14,
                        if (disabled) p.muted else p.text,
                        FontWeight.Medium,
                        Modifier.weight(1f, false),
                        1,
                    )
                    Explicit(track)
                }
                Spacer(Modifier.height(4.dp))
                MarqueeLabel(track.artist, 12, p.muted, modifier = Modifier.fillMaxWidth())
            }
        }
        if (trailing != null) trailing()
        else Tool(Icons.Outlined.MoreHoriz, "Действия с треком ${track.title}", onClick = menu)
    }
}

@Composable
fun CardRow(cards: List<Card>, open: (Card) -> Unit, menu: (Card) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(cards, key = { it.key }) { card ->
            Column(
                Modifier.width(156.dp)
                    .combinedClickable(onClick = { open(card) }, onLongClick = { menu(card) })
            ) {
                Artwork(card.cover, card.title)
                Spacer(Modifier.height(8.dp))
                Label(card.title, 14, weight = FontWeight.Medium, maxLines = 2)
                Label(card.subtitle, 12, LocalPalette.current.muted, maxLines = 1)
            }
        }
    }
}

@Composable
fun NavBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(
        modifier.padding(6.dp).height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        destinations.forEachIndexed { i, d ->
            val selection by
                animateColorAsState(
                    if (i == selected) p.tonal else Color.Transparent,
                    tween(160),
                    label = "navigation selection",
                )
            Box(
                Modifier.weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(18.dp))
                    .background(selection)
                    .clickable { onSelect(i) }
                    .semantics {
                        contentDescription = d.label
                        this.selected = i == selected
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    d.icon,
                    null,
                    Modifier.size(24.dp),
                    tint = if (i == selected) p.accent else Color(0xFFD2D8E0),
                )
            }
        }
    }
}

@Composable
private fun WaveHome(
    s: MusicState,
    engine: MusicEngine,
    lyrics: Lyrics,
    onLyrics: () -> Unit,
    onSettings: () -> Unit,
    onPlay: () -> Unit,
) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().height(602.dp).clip(RoundedCornerShape(24.dp))) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { context ->
                com.lad.music.wave.WaveView(context).apply { audio = engine.waveAudio; remote = engine.remoteWave }
            },
            update = { v ->
                v.playing = s.playing
                v.local = s.output == "phone"
                v.intensity = s.settings.waveIntensity
                v.cover = s.track?.cover
            },
            modifier = Modifier.fillMaxWidth().height(430.dp),
        )
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Label("Моя волна", 24, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Artwork(s.track?.cover, "Моя волна", 180.dp)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Label(
                        s.track?.title ?: "В твоём настроении",
                        17,
                        weight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false).basicMarquee(),
                        maxLines = 1,
                    )
                    s.track?.let { Explicit(it) }
                }
                Label(
                    s.track?.artist ?: "Знакомое и новое",
                    13,
                    p.muted,
                    modifier = Modifier.basicMarquee(),
                    maxLines = 1,
                )
            }
            Box(Modifier.height(92.dp), contentAlignment = Alignment.Center) {
                if (s.track != null) HomeLyric(engine, lyrics, s.settings.lyricOffsetMs, onLyrics)
                else Label("Музыка, которая подойдёт тебе сейчас.", 13, p.muted, maxLines = 2)
            }
            Action(
                if (s.source.startsWith("Моя волна") && s.owned)
                    if (s.playing) "Пауза" else "Продолжить волну"
                else "Включить Мою волну",
                if (s.source.startsWith("Моя волна") && s.owned && s.playing) Icons.Outlined.Pause
                else Icons.Outlined.PlayArrow,
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = onPlay,
            )
            TextButton(onClick = onSettings) {
                Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Настроить", color = p.muted)
            }
        }
    }
}

@Composable
private fun WavePreferencesPane(s: MusicState, engine: MusicEngine, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf<com.lad.music.wave.WavePreferences?>(null) }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (s.demo) error = "Настройки Яндекс Музыки появятся после входа."
        else
            try {
                value = engine.api.waveSettings()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Не удалось загрузить настройки"
            }
    }
    // Uses the same C4 glass surface and native Back as the other application panels.
    Pane(
        "Настроить волну",
        s.settings,
        dismiss,
        footer = {
            Action(
                if (saving) "Применяем…" else "Со следующего трека",
                Icons.Outlined.PlayArrow,
                enabled = value != null && !saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                scope.launch {
                    saving = true
                    try {
                        engine.applyWavePreferences(value!!)
                        dismiss()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "Не удалось сохранить настройки"
                    } finally {
                        saving = false
                    }
                }
            }
        },
    ) {
        if (error.isNotBlank()) Label(error, 13, LocalPalette.current.muted)
        else if (value == null) Label("Загружаю настройки…", 14, LocalPalette.current.muted)
        value?.groups?.forEach { g ->
            Label(g.name, 15, weight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                g.values.forEach { v ->
                    FilterChip(
                        selected = value?.selected?.get(g.key) == v.value,
                        onClick = {
                            value = value!!.copy(selected = value!!.selected + (g.key to v.value))
                        },
                        label = { Text(v.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeLyric(engine: MusicEngine, lyrics: Lyrics, offset: Long, onClick: () -> Unit) {
    val line =
        remember(engine, lyrics, offset) {
            engine.position
                .map { position ->
                    lyrics.lines.indexOfLast { it.timeMs != null && it.timeMs <= position + offset }
                }
                .distinctUntilChanged()
        }
    val active by line.collectAsStateWithLifecycle(-1)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        val first = active.coerceAtLeast(0)
        repeat(2) { i ->
            Label(
                lyrics.lines.getOrNull(first + i)?.text ?: if (i == 0) "" else " ",
                15,
                if (i == 0) LocalPalette.current.text else LocalPalette.current.muted,
                modifier = Modifier.basicMarquee(),
                maxLines = 1,
            )
        }
        TextButton(onClick) {
            Text("Открыть полный текст ↗", color = LocalPalette.current.muted, fontSize = 12.sp)
        }
    }
}

@Composable
fun MiniPlayer(s: MusicState, engine: MusicEngine, openNow: () -> Unit, menu: (Track) -> Unit) {
    val t = s.track ?: return
    val swipe = rememberTrackSwipe(s, engine)
    Glass(
        Modifier.fillMaxWidth()
            .heightIn(min = 68.dp)
            .then(swipe)
            .combinedClickable(
                onClickLabel = "Развернуть плеер",
                onClick = openNow,
                onLongClick = { menu(t) },
            )
            .semantics {
                contentDescription = "Мини-плеер"
                stateDescription = "${t.title} · ${t.artist}"
            },
        s.settings.glass,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TrackTransition(t, s.index, Modifier.weight(1f)) { display ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Artwork(display.cover, display.title, 44.dp)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MarqueeLabel(
                                display.title,
                                13,
                                weight = FontWeight.Medium,
                                modifier = Modifier.weight(1f, false),
                            )
                            Explicit(display, interactive = false)
                        }
                        MarqueeLabel(
                            display.artist,
                            11,
                            LocalPalette.current.muted,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (s.loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else
                Tool(
                    if (s.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    if (s.playing) "Пауза" else "Продолжить",
                    onClick = { engine.task { engine.toggle() } },
                )
        }
    }
}

@Composable
fun Pane(
    title: String,
    settings: Settings,
    dismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    val motion = LocalLayerMotion.current
    Box(
        Modifier.fillMaxSize()
            .drawBehind {
                drawRect(Color.Black.copy(alpha = .55f * (motion?.progress?.value ?: 1f)))
            }
            .clickable(onClick = dismiss)
            .semantics { paneTitle = title },
        contentAlignment = Alignment.BottomCenter,
    ) {
        SwipeDismissLayer(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * .86f).dp),
            dismiss,
        ) { handle ->
            Glass(Modifier.fillMaxWidth().layerMotion(motion, 84.dp), settings.glass, true) {
                Column(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 20.dp)
                        .clickable(
                            interactionSource =
                                remember {
                                    androidx.compose.foundation.interaction
                                        .MutableInteractionSource()
                                },
                            indication = null,
                        ) {}
                ) {
                    DragHandle(handle, dismiss)
                    Row(handle.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Label(
                            title,
                            22,
                            weight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Tool(Icons.Outlined.Close, "Закрыть", onClick = dismiss)
                    }
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = content,
                    )
                    if (footer != null) {
                        HorizontalDivider(
                            Modifier.padding(vertical = 12.dp),
                            color = p.muted.copy(alpha = .2f),
                        )
                        footer()
                    }
                }
            }
        }
    }
}

@Composable
fun MenuItem(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(10.dp)
            .alpha(if (enabled) 1f else .35f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = p.muted)
        Label(label, 14)
    }
}

@Composable
fun LadApp(engine: MusicEngine) {
    val s by engine.uiState.collectAsStateWithLifecycle()
    val auth by engine.auth.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<Card?>(null) }
    val history = remember { mutableStateListOf<Card?>() }
    var overlay by rememberSaveable { mutableStateOf<String?>(null) }
    var overlayParent by rememberSaveable { mutableStateOf<String?>(null) }
    fun showOverlay(target: String, retain: Boolean = false) {
        overlayParent = if (retain) overlay else null
        overlay = target
    }
    fun dismissOverlay() {
        overlay = overlayParent
        overlayParent = null
    }
    fun closeLyrics() {
        overlay = "now"
        overlayParent = null
    }
    var trackMenu by remember { mutableStateOf<Track?>(null) }
    var cardMenu by remember { mutableStateOf<Card?>(null) }
    var explicitInfo by remember { mutableStateOf<Track?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchType by rememberSaveable { mutableStateOf("all") }
    var catalog by remember { mutableStateOf(Catalog()) }
    var library by remember { mutableStateOf(Library()) }
    var liked by remember { mutableStateOf(Catalog()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var likedPage by remember { mutableStateOf(false) }
    var collectionSection by rememberSaveable { mutableStateOf<String?>(null) }
    var likedPlaylistGroup by rememberSaveable { mutableStateOf(false) }
    val collectionScrollStates = remember {
        listOf("overview", "own", "liked", "albums", "artists").associateWith { LazyListState() }
    }
    var libraryLoading by remember { mutableStateOf(false) }
    var libraryError by remember { mutableStateOf("") }
    var libraryUid by remember { mutableStateOf<String?>(null) }
    var lyrics by remember { mutableStateOf(Lyrics()) }
    var lyricRetry by remember { mutableIntStateOf(0) }
    var lyricsLoading by remember { mutableStateOf(false) }
    var lyricsError by remember { mutableStateOf("") }
    val ui = rememberCoroutineScope()
    val uid = s.profile?.uid
    fun open(card: Card) {
        overlay = null
        overlayParent = null
        trackMenu = null
        cardMenu = null
        history.add(detail)
        detail = card
        likedPage = false
        page = 0
    }
    fun run(action: suspend () -> Unit) {
        engine.task(action)
    }
    fun share(url: String) {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url),
                "Поделиться",
            )
        )
    }
    fun browser(url: String) {
        val parsed = Uri.parse(url)
        if (parsed.scheme == "https")
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, parsed)) }
                .onFailure { engine.notice("Не найден браузер") }
    }
    suspend fun collection(card: Card) =
        if (s.demo) Catalog(card.title, MusicEngine.demoTracks) else engine.api.collection(card)
    fun batch(card: Card, next: Boolean?, start: Boolean = false) {
        run {
            val data = collection(card)
            val tracks =
                data.tracks.filter {
                    it.available && !(engine.state.value.settings.blockExplicit && it.explicit)
                }
            if (start) {
                check(tracks.isNotEmpty()) { "Нет доступных треков с учётом фильтра" }
                engine.play(tracks, 0, data.title)
            } else engine.add(tracks, next == true)
        }
    }
    LaunchedEffect(uid, s.demo, refresh) {
        if (libraryUid != uid) {
            library = Library()
            liked = Catalog()
            libraryUid = uid
        }
        libraryLoading = uid != null
        libraryError = ""
        if (uid != null) {
            try {
                if (s.demo) {
                    library = Library(MusicEngine.demoCards)
                    liked =
                        Catalog(
                            "Мне нравится",
                            MusicEngine.demoTracks.filter { it.liked },
                            total = 3,
                        )
                } else
                    coroutineScope {
                        val l = async { engine.api.library(uid) }
                        val t = async { engine.api.liked(uid, limit = 8) }
                        library = l.await()
                        liked = t.await()
                    }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                libraryError = e.message ?: "Не удалось загрузить коллекцию"
            } finally {
                libraryLoading = false
            }
        }
    }
    LaunchedEffect(uid, s.demo, tab, detail, likedPage, query, searchType, page, refresh) {
        if (uid == null || tab == 3) return@LaunchedEffect
        loading = true
        error = ""
        try {
            if (tab == 1 && detail == null && !likedPage && query.isBlank()) {
                catalog = Catalog()
                return@LaunchedEffect
            }
            if (tab == 1 && detail == null) delay(300)
            val data =
                when {
                    s.demo ->
                        if (likedPage)
                            Catalog(
                                "Мне нравится",
                                MusicEngine.demoTracks.filter { it.liked },
                                total = 3,
                            )
                        else if (detail != null) Catalog(detail!!.title, MusicEngine.demoTracks)
                        else if (tab == 1)
                            Catalog(
                                "Поиск",
                                MusicEngine.demoTracks.filter {
                                    (it.title + it.artist).contains(query, true)
                                },
                                MusicEngine.demoCards.filter { it.title.contains(query, true) },
                            )
                        else
                            Catalog(
                                "Продолжить слушать",
                                MusicEngine.demoTracks,
                                MusicEngine.demoCards,
                            )
                    likedPage -> engine.api.liked(uid, page * 100)
                    detail != null -> engine.api.collection(detail!!, page)
                    tab == 1 -> engine.api.search(query, searchType, page)
                    tab == 0 -> engine.api.home()
                    else -> Catalog()
                }
            catalog =
                if (page == 0) data
                else
                    data.copy(
                        tracks = catalog.tracks + data.tracks,
                        cards = catalog.cards + data.cards,
                    )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Не удалось загрузить музыку"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(s.track?.id, s.demo, refresh, lyricRetry) {
        lyrics = Lyrics()
        lyricsError = ""
        val t = s.track ?: return@LaunchedEffect
        lyricsLoading = true
        try {
            lyrics =
                if (s.demo)
                    Lyrics(
                        listOf(
                                "За окном засыпает город",
                                "И стихают его голоса",
                                "Мы останемся на своей орбите",
                                "Пусть играет ещё одна",
                                "Свет проходит сквозь наши окна",
                            )
                            .mapIndexed { i, text -> LyricLine(i * 30000L, text) },
                        true,
                    )
                else engine.api.lyrics(t.id)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            lyricsError = e.message ?: "Не удалось загрузить текст"
        } finally {
            lyricsLoading = false
        }
    }
    LaunchedEffect(s.message) {
        if (s.message != null) {
            delay(5000)
            engine.clearNotice()
        }
    }
    LaunchedEffect(uid) {
        detail = null
        history.clear()
        overlay = null
        overlayParent = null
        trackMenu = null
        cardMenu = null
        likedPage = false
        collectionSection = null
        likedPlaylistGroup = false
    }
    val goBack: () -> Unit = {
        when {
            explicitInfo != null -> explicitInfo = null
            trackMenu != null -> trackMenu = null
            cardMenu != null -> cardMenu = null
            overlay == "lyrics" -> closeLyrics()
            overlay != null -> dismissOverlay()
            likedPage -> {
                likedPage = false
                page = 0
            }
            detail != null -> {
                detail = if (history.isNotEmpty()) history.removeAt(history.lastIndex) else null
                page = 0
            }
            tab == 2 && collectionSection != null -> collectionSection = null
            else -> tab = 0
        }
    }
    BackHandler(
        explicitInfo != null ||
            trackMenu != null ||
            cardMenu != null ||
            overlay != null ||
            detail != null ||
            likedPage ||
            tab != 0,
        onBack = goBack,
    )
    LadTheme(s.settings) {
        val p = LocalPalette.current
        val config = LocalConfiguration.current
        val density = LocalDensity.current
        val landscape = config.screenWidthDp > config.screenHeightDp && config.screenHeightDp < 500
        val ime = WindowInsets.ime.getBottom(density) > 0
        val backdrop = remember { HazeState() }
        val playerBackdrop = remember { HazeState() }
        val queueBackdrop = remember { HazeState() }
        val lyricsBackdrop = remember { HazeState() }
        val primaryTarget = if (overlay == "devices") overlayParent else overlay
        val lyricsMotion =
            rememberLayerTransition(
                if (primaryTarget == "lyrics") Unit else null,
                enterMs = 320,
                exitMs = 260,
            )
        val baseTarget =
            if (
                primaryTarget == "lyrics" ||
                    (primaryTarget == null && lyricsMotion.displayed != null)
            )
                "now"
            else primaryTarget
        val primaryMotion = rememberLayerTransition(baseTarget, enterMs = 220, exitMs = 100)
        val primaryOverlay = primaryMotion.displayed
        val backdropReveals = remember { mutableStateMapOf<Any, Boolean>() }
        val revealBackdrop: (Any, Boolean) -> Unit = remember {
            { key, visible ->
                if (visible) backdropReveals[key] = true else backdropReveals.remove(key)
            }
        }

        val mainOccluded by remember {
            derivedStateOf {
                primaryMotion.displayed != null &&
                    !primaryMotion.leaving &&
                    primaryMotion.progress.value >= .999f &&
                    backdropReveals.isEmpty()
            }
        }
        val playerOccluded by remember {
            derivedStateOf {
                lyricsMotion.displayed != null &&
                    !lyricsMotion.leaving &&
                    lyricsMotion.progress.value >= .999f &&
                    backdropReveals.isEmpty()
            }
        }

        val baseBackdrop =
            when (primaryOverlay) {
                "now" -> playerBackdrop
                "queue" -> queueBackdrop
                else -> backdrop
            }
        val activeBackdrop = if (lyricsMotion.displayed != null) lyricsBackdrop else baseBackdrop
        CompositionLocalProvider(
            LocalBackdrop provides backdrop,
            LocalBackdropReveal provides revealBackdrop,
            LocalExplicitInfo provides { explicitInfo = it },
        ) {
            Box(
                Modifier.fillMaxSize()
                    .background(p.bg)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                        )
                    )
            ) {
                Box(
                    Modifier.fillMaxSize()
                        .drawWithContent { if (!mainOccluded) drawContent() }
                        .then(if (mainOccluded) Modifier.clearAndSetSemantics {} else Modifier)
                ) {
                    val bottom =
                        if (ime) 16.dp
                        else if (landscape) if (s.track != null) 98.dp else 16.dp
                        else
                            60.dp +
                                (if (s.track != null) 84.dp else 0.dp) +
                                28.dp +
                                WindowInsets.navigationBars
                                    .asPaddingValues()
                                    .calculateBottomPadding()
                    Scene(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.fillMaxSize()
                                .then(
                                    pageEntrance(
                                        "$tab/${detail?.type}/${detail?.id}/$likedPage/$collectionSection"
                                    )
                                )
                                .padding(start = if (landscape) 92.dp else 16.dp, end = 16.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (
                                    detail != null ||
                                        likedPage ||
                                        (tab == 2 && collectionSection != null)
                                )
                                    Tool(Icons.Outlined.ChevronLeft, "Назад", onClick = goBack)
                                Label(
                                    if (likedPage) "Мне нравится"
                                    else
                                        detail?.title
                                            ?: if (tab == 0) "лад"
                                            else if (tab == 2) collectionTitle(collectionSection)
                                            else destinations[tab].label,
                                    if (tab == 2 && detail == null && !likedPage) 24
                                    else if (detail == null) 28 else 22,
                                    weight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                )
                                Tool(
                                    if (s.output == "phone") Icons.Outlined.PhoneAndroid
                                    else Icons.Outlined.Speaker,
                                    "Выбрать устройство",
                                    onClick = {
                                        showOverlay("devices")
                                        run { engine.refreshDevices() }
                                    },
                                )
                            }
                            if (s.profile == null)
                                LoginView(engine, auth, ::browser, clipboard, bottom)
                            else if (tab == 3 && detail == null && !likedPage)
                                SettingsView(s, engine, Modifier.weight(1f), bottom)
                            else if (tab == 2 && detail == null && !likedPage) {
                                CollectionView(
                                    library,
                                    liked,
                                    collectionSection,
                                    likedPlaylistGroup,
                                    libraryLoading,
                                    libraryError,
                                    bottom,
                                    collectionScrollStates[
                                        when (collectionSection) {
                                            "playlists" ->
                                                if (likedPlaylistGroup) "liked" else "own"
                                            "albums" -> "albums"
                                            "artists" -> "artists"
                                            else -> "overview"
                                        }]!!,
                                    navigate = {
                                        collectionSection = it
                                        likedPlaylistGroup = false
                                    },
                                    group = { likedPlaylistGroup = it },
                                    favorite = {
                                        likedPage = true
                                        page = 0
                                    },
                                    open = ::open,
                                    menu = { cardMenu = it },
                                    retry = { refresh++ },
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                if (tab == 1 && detail == null && !likedPage) {
                                    OutlinedTextField(
                                        query,
                                        {
                                            query = it
                                            page = 0
                                        },
                                        Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = { Text("Поиск музыки") },
                                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                        trailingIcon = {
                                            if (query.isNotEmpty())
                                                Tool(
                                                    Icons.Outlined.Close,
                                                    "Очистить поиск",
                                                    onClick = { query = "" },
                                                )
                                        },
                                        shape = RoundedCornerShape(16.dp),
                                    )
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = PaddingValues(vertical = 8.dp),
                                    ) {
                                        items(
                                            listOf(
                                                "all" to "Всё",
                                                "track" to "Треки",
                                                "album" to "Альбомы",
                                                "artist" to "Исполнители",
                                                "playlist" to "Плейлисты",
                                            )
                                        ) { (key, label) ->
                                            FilterChip(
                                                searchType == key,
                                                {
                                                    searchType = key
                                                    page = 0
                                                },
                                                label = { Text(label) },
                                            )
                                        }
                                    }
                                }
                                LazyColumn(
                                    Modifier.weight(1f),
                                    contentPadding = PaddingValues(bottom = bottom),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    if (s.demo)
                                        item { Label("Демонстрация · без звука", 12, p.muted) }
                                    if (detail != null)
                                        item {
                                            val c = detail!!
                                            Row(
                                                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Artwork(
                                                    c.cover,
                                                    c.title,
                                                    112.dp,
                                                    Modifier.combinedClickable(
                                                        onClick = {},
                                                        onLongClick = { cardMenu = c },
                                                    ),
                                                )
                                                Column(Modifier.weight(1f)) {
                                                    Label(
                                                        if (c.type == "album") "АЛЬБОМ"
                                                        else if (c.type == "artist") "ИСПОЛНИТЕЛЬ"
                                                        else "ПЛЕЙЛИСТ",
                                                        11,
                                                        p.muted,
                                                    )
                                                    MarqueeLabel(
                                                        catalog.title.ifBlank { c.title },
                                                        24,
                                                        weight = FontWeight.SemiBold,
                                                    )
                                                    MarqueeLabel(
                                                        c.subtitle,
                                                        12,
                                                        p.muted,
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                    Row {
                                                        if (c.type == "album")
                                                            Tool(
                                                                Icons.Outlined.FavoriteBorder,
                                                                if (
                                                                    library.albums.any {
                                                                        it.id == c.id
                                                                    }
                                                                )
                                                                    "Убрать альбом из коллекции"
                                                                else "Добавить альбом в коллекцию",
                                                                active =
                                                                    library.albums.any {
                                                                        it.id == c.id
                                                                    },
                                                                onClick = {
                                                                    run {
                                                                        if (!s.demo)
                                                                            engine.api.like(
                                                                                uid!!,
                                                                                c.id,
                                                                                "album",
                                                                                library.albums
                                                                                    .none {
                                                                                        it.id ==
                                                                                            c.id
                                                                                    },
                                                                            )
                                                                        refresh++
                                                                    }
                                                                },
                                                            )
                                                        Tool(
                                                            Icons.Outlined.MoreHoriz,
                                                            "Действия с подборкой",
                                                            onClick = { cardMenu = c },
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    if (detail == null && !likedPage && tab == 0)
                                        item {
                                            WaveHome(
                                                s,
                                                engine,
                                                lyrics,
                                                { showOverlay("lyrics") },
                                                { showOverlay("wave-settings") },
                                            ) {
                                                run {
                                                    if (s.source.startsWith("Моя волна") && s.owned)
                                                        engine.toggle()
                                                    else engine.wave()
                                                }
                                            }
                                        }
                                    run {
                                        if (loading && page == 0)
                                            item {
                                                Column(
                                                    Modifier.fillMaxWidth().padding(40.dp),
                                                    horizontalAlignment =
                                                        Alignment.CenterHorizontally,
                                                ) {
                                                    CircularProgressIndicator()
                                                    Label("Загружаю музыку…", 13, p.muted)
                                                }
                                            }
                                        if (error.isNotBlank())
                                            item { ErrorBox(error) { refresh++ } }
                                        if (!loading || page > 0) {
                                            if (catalog.cards.isNotEmpty())
                                                item {
                                                    Section(
                                                        if (detail?.type == "artist") "Альбомы"
                                                        else if (tab == 1) "Подборки и исполнители"
                                                        else "Собрано для тебя"
                                                    )
                                                    CardRow(
                                                        catalog.cards,
                                                        ::open,
                                                        { cardMenu = it },
                                                    )
                                                }
                                            if (catalog.tracks.isNotEmpty())
                                                item {
                                                    Row(
                                                        verticalAlignment =
                                                            Alignment.CenterVertically
                                                    ) {
                                                        Label(
                                                            if (detail != null) "Треки"
                                                            else if (likedPage) "Любимые треки"
                                                            else catalog.title,
                                                            20,
                                                            weight = FontWeight.SemiBold,
                                                            modifier = Modifier.weight(1f),
                                                        )
                                                        Tool(
                                                            Icons.Outlined.PlayArrow,
                                                            "Слушать подборку",
                                                            onClick = {
                                                                run {
                                                                    val index =
                                                                        catalog.tracks
                                                                            .indexOfFirst {
                                                                                it.available &&
                                                                                    !(s.settings
                                                                                        .blockExplicit &&
                                                                                        it.explicit)
                                                                            }
                                                                    engine.play(
                                                                        catalog.tracks,
                                                                        index,
                                                                        catalog.title,
                                                                    )
                                                                }
                                                            },
                                                        )
                                                    }
                                                }
                                            itemsIndexed(
                                                catalog.tracks,
                                                key = { i, t -> "${t.id}:$i" },
                                            ) { i, t ->
                                                TrackRow(
                                                    t,
                                                    s,
                                                    {
                                                        run {
                                                            engine.play(
                                                                catalog.tracks,
                                                                i,
                                                                catalog.title,
                                                            )
                                                        }
                                                    },
                                                    ::open,
                                                    { trackMenu = t },
                                                )
                                            }
                                            if (catalog.more)
                                                item {
                                                    Action(
                                                        "Показать ещё",
                                                        Icons.Outlined.ExpandMore,
                                                        true,
                                                        modifier = Modifier.fillMaxWidth(),
                                                    ) {
                                                        page++
                                                    }
                                                }
                                            if (
                                                catalog.tracks.isEmpty() &&
                                                    catalog.cards.isEmpty() &&
                                                    error.isBlank()
                                            )
                                                item {
                                                    Label(
                                                        if (tab == 1 && query.isBlank())
                                                            "Найди трек, альбом или исполнителя"
                                                        else "Здесь пока нет музыки",
                                                        16,
                                                        p.muted,
                                                        modifier =
                                                            Modifier.padding(vertical = 40.dp),
                                                    )
                                                }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!ime && s.profile != null) {
                        if (landscape)
                            Glass(
                                Modifier.align(Alignment.CenterStart)
                                    .padding(start = 12.dp)
                                    .width(64.dp),
                                s.settings.glass,
                            ) {
                                Column(Modifier.padding(6.dp)) {
                                    destinations.forEachIndexed { i, d ->
                                        Tool(
                                            d.icon,
                                            d.label,
                                            active = i == tab,
                                            onClick = {
                                                tab = i
                                                collectionSection = null
                                                detail = null
                                                history.clear()
                                                likedPage = false
                                                page = 0
                                            },
                                        )
                                    }
                                }
                            }
                        Column(
                            Modifier.align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(
                                    start = if (landscape) 92.dp else 16.dp,
                                    end = 16.dp,
                                    bottom = 12.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            MiniPlayer(s, engine, { showOverlay("now") }, { trackMenu = it })
                            if (!landscape)
                                Glass(Modifier.fillMaxWidth(), s.settings.glass) {
                                    NavBar(
                                        tab,
                                        {
                                            tab = it
                                            collectionSection = null
                                            detail = null
                                            history.clear()
                                            likedPage = false
                                            page = 0
                                        },
                                    )
                                }
                        }
                    }
                }
                CompositionLocalProvider(LocalBackdrop provides activeBackdrop) {
                    if (primaryOverlay != null)
                        CompositionLocalProvider(LocalBackdrop provides baseBackdrop) {
                            Box(
                                Modifier.fillMaxSize()
                                    .layerMotion(primaryMotion, 48.dp)
                                    .drawWithContent { if (!playerOccluded) drawContent() }
                                    .then(
                                        if (playerOccluded) Modifier.clearAndSetSemantics {}
                                        else Modifier
                                    )
                            ) {
                                when (primaryOverlay) {
                                    "now" ->
                                        NowView(
                                            s,
                                            engine,
                                            lyrics,
                                            lyricsLoading,
                                            lyricsError,
                                            false,
                                            { dismissOverlay() },
                                            ::open,
                                            { trackMenu = it },
                                            { showOverlay("queue", retain = true) },
                                            {
                                                showOverlay("devices", retain = true)
                                                run { engine.refreshDevices() }
                                            },
                                            { showOverlay("lyrics", retain = true) },
                                            { lyricRetry++ },
                                        )
                                    "queue" ->
                                        QueueView(
                                            s,
                                            engine,
                                            { dismissOverlay() },
                                            ::open,
                                            { trackMenu = it },
                                        )
                                }
                            }
                        }
                    if (lyricsMotion.displayed != null)
                        CompositionLocalProvider(LocalBackdrop provides lyricsBackdrop) {
                            Box(
                                Modifier.fillMaxSize()
                                    .lyricsScreenMotion(lyricsMotion, config.screenHeightDp.dp)
                            ) {
                                NowView(
                                    s,
                                    engine,
                                    lyrics,
                                    lyricsLoading,
                                    lyricsError,
                                    true,
                                    { closeLyrics() },
                                    ::open,
                                    { trackMenu = it },
                                    { showOverlay("queue", retain = true) },
                                    {
                                        showOverlay("devices", retain = true)
                                        run { engine.refreshDevices() }
                                    },
                                    { closeLyrics() },
                                    { lyricRetry++ },
                                )
                            }
                        }
                    AnimatedPane(if (overlay == "wave-settings") Unit else null) {
                        WavePreferencesPane(s, engine, { dismissOverlay() })
                    }
                    AnimatedPane(if (overlay == "devices") Unit else null) {
                        DevicePane(s, engine, { dismissOverlay() })
                    }
                    AnimatedPane(explicitInfo) { t ->
                        Pane(
                            "Контент 18+",
                            s.settings,
                            { explicitInfo = null },
                            footer = {
                                Action(
                                    "Понятно",
                                    Icons.Outlined.Check,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    explicitInfo = null
                                }
                            },
                        ) {
                            MenuSummary(t.cover, t.title, t.artist)
                            Label(
                                "Яндекс отметил этот трек как контент для взрослых. Можно временно пропускать такие треки: Настройки → Не воспроизводить 18+.",
                                14,
                                p.muted,
                            )
                        }
                    }
                    AnimatedPane(trackMenu) { t ->
                        Pane("Действия с треком", s.settings, { trackMenu = null }) {
                            MenuSummary(t.cover, t.title, t.artist)
                            val blocked = !t.available || (s.settings.blockExplicit && t.explicit)
                            MenuPlaybackActions(
                                !blocked,
                                play = {
                                    trackMenu = null
                                    run { engine.play(listOf(t), 0, "Выбранный трек") }
                                },
                                next = {
                                    trackMenu = null
                                    run { engine.add(listOf(t), true) }
                                },
                                append = {
                                    trackMenu = null
                                    run { engine.add(listOf(t)) }
                                },
                            )
                            MenuItem(
                                if (t.liked) "Убрать из любимых" else "Нравится",
                                Icons.Outlined.FavoriteBorder,
                            ) {
                                trackMenu = null
                                run {
                                    engine.like(t)
                                    catalog =
                                        catalog.copy(
                                            tracks =
                                                catalog.tracks.map {
                                                    if (it.id == t.id) it.copy(liked = !t.liked)
                                                    else it
                                                }
                                        )
                                    refresh++
                                }
                            }
                            if (t.albumId.isNotBlank())
                                MenuItem("Перейти к альбому", Icons.Outlined.Album) {
                                    open(t.albumCard())
                                }
                            t.artists
                                .filter { it.id.isNotBlank() }
                                .forEach { a ->
                                    MenuItem(a.name, Icons.Outlined.Person) {
                                        open(Card(a.id, "artist", a.name))
                                    }
                                }
                            MenuItem("Поделиться", Icons.Outlined.Share, !s.demo) {
                                trackMenu = null
                                share(t.url)
                            }
                            MenuItem("Скопировать ссылку", Icons.Outlined.Link, !s.demo) {
                                clipboard.setText(AnnotatedString(t.url))
                                trackMenu = null
                                engine.notice("Ссылка скопирована")
                            }
                            val qi = s.queue.indexOfFirst { it === t }
                            if (overlay == "queue" && qi >= 0) {
                                MenuItem("Поднять в очереди", Icons.Outlined.ArrowUpward, qi > 0) {
                                    trackMenu = null
                                    engine.move(qi, qi - 1)
                                }
                                MenuItem(
                                    "Опустить в очереди",
                                    Icons.Outlined.ArrowDownward,
                                    qi < s.queue.lastIndex,
                                ) {
                                    trackMenu = null
                                    engine.move(qi, qi + 1)
                                }
                                MenuItem(
                                    "Удалить из очереди",
                                    Icons.Outlined.DeleteOutline,
                                    !(s.owned && qi == s.index),
                                ) {
                                    trackMenu = null
                                    run { engine.remove(qi) }
                                }
                            }
                        }
                    }
                    AnimatedPane(cardMenu) { c ->
                        Pane("Действия с подборкой", s.settings, { cardMenu = null }) {
                            MenuSummary(
                                c.cover,
                                c.title,
                                if (c.type == "album") "Альбом"
                                else if (c.type == "playlist") "Плейлист" else "Исполнитель",
                            )
                            if (c.type != "artist") {
                                MenuPlaybackActions(
                                    play = {
                                        cardMenu = null
                                        batch(c, null, true)
                                    },
                                    next = {
                                        cardMenu = null
                                        batch(c, true)
                                    },
                                    append = {
                                        cardMenu = null
                                        batch(c, false)
                                    },
                                )
                            }
                            MenuItem("Открыть", Icons.Outlined.ArrowOutward) { open(c) }
                            if (c.type != "artist") {
                                if (c.type == "album")
                                    MenuItem(
                                        if (library.albums.any { it.id == c.id })
                                            "Убрать альбом из коллекции"
                                        else "Нравится",
                                        Icons.Outlined.FavoriteBorder,
                                    ) {
                                        cardMenu = null
                                        run {
                                            if (!s.demo)
                                                engine.api.like(
                                                    uid!!,
                                                    c.id,
                                                    "album",
                                                    library.albums.none { it.id == c.id },
                                                )
                                            refresh++
                                        }
                                    }
                            }
                            MenuItem("Поделиться", Icons.Outlined.Share, !s.demo) {
                                cardMenu = null
                                share(c.url)
                            }
                            MenuItem("Скопировать ссылку", Icons.Outlined.Link, !s.demo) {
                                cardMenu = null
                                clipboard.setText(AnnotatedString(c.url))
                                engine.notice("Ссылка скопирована")
                            }
                        }
                    }
                    if (s.message != null)
                        Glass(
                            Modifier.align(Alignment.TopCenter)
                                .padding(horizontal = 24.dp)
                                .padding(top = 72.dp)
                                .fillMaxWidth()
                                .clickable { engine.clearNotice() },
                            s.settings.glass,
                            true,
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Label(s.message!!, 13, modifier = Modifier.padding(16.dp))
                        }
                }
            }
        }
    }
}

@Composable
fun Section(title: String) {
    Label(
        title,
        20,
        weight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
    )
}

@Composable
fun CardGrid(cards: List<Card>, open: (Card) -> Unit, menu: (Card) -> Unit) {
    if (cards.isEmpty()) Label("Здесь пока пусто", 14, LocalPalette.current.muted)
    else
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            cards.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { c ->
                        Column(
                            Modifier.weight(1f)
                                .combinedClickable(onClick = { open(c) }, onLongClick = { menu(c) })
                        ) {
                            Artwork(c.cover, c.title)
                            Label(
                                c.title,
                                14,
                                weight = FontWeight.Medium,
                                modifier = Modifier.padding(top = 8.dp),
                                maxLines = 2,
                            )
                            Label(c.subtitle, 12, LocalPalette.current.muted, maxLines = 1)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
}

@Composable
fun ErrorBox(message: String, retry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Label(message, 14, LocalPalette.current.muted)
        Action("Повторить", Icons.Outlined.Refresh, true, onClick = retry)
    }
}
