// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.lad.music

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun Progress(s: MusicState, engine: MusicEngine) {
    val duration = s.durationMs.takeIf { it > 0 } ?: s.track?.durationMs ?: 0L
    val position by engine.position.collectAsStateWithLifecycle()
    var draft by remember(s.track?.id, s.output) { mutableStateOf<Float?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (!dragging) draft = null }
    Column {
        C4Slider(
            value = draft ?: position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
            onValueChange = { draft = it },
            onValueChangeFinished = {
                draft?.let { v -> engine.task { engine.seek(v.toLong()) } }
                draft = null
            },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            enabled = s.track != null && duration > 0,
            interactionSource = interaction,
            modifier =
                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = "Позиция трека"
                },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label(timeLabel(draft?.toLong() ?: position), 11, LocalPalette.current.muted)
            Label(timeLabel(duration), 11, LocalPalette.current.muted)
        }
    }
}

@Composable
fun Volume(s: MusicState, engine: MusicEngine) {
    var draft by remember(s.output) { mutableStateOf<Float?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (!dragging) draft = null }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Tool(
            if (s.volume == 0f) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
            "Выключить или включить звук",
            onClick = { engine.task { engine.volume(if (s.volume > 0) 0f else .4f) } },
        )
        C4Slider(
            draft ?: s.volume,
            { draft = it },
            Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                contentDescription = "Громкость"
            },
            onValueChangeFinished = {
                draft?.let { engine.task { engine.volume(it) } }
                draft = null
            },
            interactionSource = interaction,
        )
        Label(
            "${((draft ?: s.volume)*100).toInt()}%",
            11,
            LocalPalette.current.muted,
            modifier = Modifier.width(40.dp),
        )
    }
}

@Composable
fun Transport(s: MusicState, engine: MusicEngine) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tool(
            Icons.Outlined.Shuffle,
            "Перемешать",
            active = s.shuffle,
            onClick = { engine.shuffle() },
        )
        Tool(
            Icons.Outlined.SkipPrevious,
            "Предыдущий трек",
            enabled = s.track != null,
            onClick = { engine.task { engine.advance(-1) } },
        )
        FilledIconButton(
            { engine.task { engine.toggle() } },
            Modifier.size(64.dp),
            enabled = s.track != null && !s.loading,
            shape = RoundedCornerShape(22.dp),
            colors =
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = p.accent,
                    contentColor = p.bg,
                ),
        ) {
            if (s.loading)
                CircularProgressIndicator(Modifier.size(28.dp), color = p.bg, strokeWidth = 2.dp)
            else
                Crossfade(s.playing, animationSpec = tween(120), label = "play pause") { playing ->
                    Icon(
                        if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        if (playing) "Пауза" else "Продолжить",
                        Modifier.size(34.dp),
                    )
                }
        }
        Tool(
            Icons.Outlined.SkipNext,
            "Следующий трек",
            enabled = s.track != null,
            onClick = { engine.task { engine.advance(1) } },
        )
        Tool(
            if (s.repeat == 1) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
            "Повтор: ${s.repeat}",
            active = s.repeat > 0,
            onClick = { engine.repeat() },
        )
    }
}

@Composable
fun NowView(
    s: MusicState,
    engine: MusicEngine,
    lyrics: Lyrics,
    loading: Boolean,
    error: String,
    fullLyrics: Boolean,
    dismiss: () -> Unit,
    open: (Card) -> Unit,
    menu: (Track) -> Unit,
    queue: () -> Unit,
    devices: () -> Unit,
    toggleLyrics: () -> Unit,
    retry: () -> Unit,
) {
    val p = LocalPalette.current
    val t = s.track
    val listState = rememberLazyListState()
    var follow by remember(t?.id) { mutableStateOf(true) }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (dragging) follow = false }
    val line =
        remember(engine, lyrics, s.settings.lyricOffsetMs) {
            engine.position
                .map { position ->
                    lyrics.lines.indexOfLast {
                        it.timeMs != null && it.timeMs <= position + s.settings.lyricOffsetMs
                    }
                }
                .distinctUntilChanged()
        }
    val active by line.collectAsStateWithLifecycle(-1)
    LaunchedEffect(active, follow, fullLyrics) {
        if (fullLyrics && follow && active >= 0)
            listState.animateScrollToItem((active - 1).coerceAtLeast(0))
    }
    SwipeDismissLayer(Modifier.fillMaxSize(), dismiss) { handle ->
        Scene(Modifier.fillMaxSize().background(p.bg)) {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                DragHandle(handle, dismiss)
                Row(
                    handle.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Tool(Icons.Outlined.ExpandMore, "Свернуть плеер", onClick = dismiss)
                    Label(
                        if (fullLyrics) "Текст песни" else "Сейчас играет",
                        20,
                        weight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Tool(
                        Icons.Outlined.MoreHoriz,
                        "Действия с текущим треком",
                        enabled = t != null,
                        onClick = { t?.let(menu) },
                    )
                }
                if (t == null) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        Label("У каждой песни свои слова", 26, weight = FontWeight.SemiBold)
                        Label("Включи музыку — обложка и текст появятся здесь.", 14, p.muted)
                    }
                } else if (fullLyrics) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(t.cover, t.title, 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.weight(1f)) {
                            PlayerMetadata(t, s, engine, open, menu, compact = true)
                        }
                    }
                    if (error.isNotBlank()) ErrorBox(error, retry)
                    if (loading) CircularProgressIndicator(Modifier.padding(24.dp))
                    if (!loading && error.isBlank() && lyrics.lines.isEmpty())
                        Label(
                            "У этого трека пока нет текста",
                            16,
                            p.muted,
                            modifier = Modifier.weight(1f).padding(top = 40.dp),
                        )
                    else
                        LazyColumn(
                            Modifier.weight(1f).padding(horizontal = 20.dp).clipToBounds(),
                            state = listState,
                            contentPadding = PaddingValues(vertical = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            itemsIndexed(lyrics.lines) { i, line ->
                                val color by
                                    animateColorAsState(
                                        if (i == active) p.accent else p.muted,
                                        tween(180),
                                        label = "lyric emphasis",
                                    )
                                Label(
                                    line.text,
                                    30,
                                    color,
                                    if (i == active) FontWeight.SemiBold else FontWeight.Medium,
                                    modifier =
                                        Modifier.fillMaxWidth().clickable(
                                            enabled = line.timeMs != null
                                        ) {
                                            engine.task {
                                                engine.seek(
                                                    (line.timeMs!! - s.settings.lyricOffsetMs)
                                                        .coerceAtLeast(0)
                                                )
                                            }
                                        },
                                )
                            }
                        }
                    if (!follow)
                        TextButton(
                            { follow = true },
                            Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Text("К текущей строке")
                        }
                } else {
                    Column(
                        Modifier.weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 16.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                    ) {
                        CoverCarousel(s, engine)
                        Box(Modifier.padding(horizontal = 20.dp)) {
                            PlayerMetadata(t, s, engine, open, menu)
                        }
                        if (s.demo)
                            Label(
                                "Демонстрация · без звука",
                                12,
                                p.muted,
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                    }
                }
                if (t != null) {
                    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
                        Progress(s, engine)
                        Spacer(Modifier.height(8.dp))
                        Transport(s, engine)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                Modifier.weight(1f)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(p.surface)
                                    .clickable(onClick = devices)
                                    .padding(horizontal = 12.dp)
                                    .heightIn(min = 48.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (s.output == "phone") Icons.Outlined.PhoneAndroid
                                    else Icons.Outlined.Speaker,
                                    null,
                                    Modifier.size(20.dp),
                                    tint = p.muted,
                                )
                                Spacer(Modifier.width(8.dp))
                                Label(
                                    s.outputName,
                                    12,
                                    p.muted,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                )
                                Icon(
                                    Icons.Outlined.ChevronRight,
                                    null,
                                    Modifier.size(18.dp),
                                    tint = p.muted,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Tool(
                                if (fullLyrics) Icons.Outlined.Album
                                else Icons.Outlined.Description,
                                if (fullLyrics) "Обложка" else "Полный текст",
                                active = fullLyrics,
                                onClick = toggleLyrics,
                            )
                            Tool(Icons.Outlined.QueueMusic, "Очередь", onClick = queue)
                        }
                        if (!fullLyrics) Volume(s, engine)
                    }
                }
            }
        }
    }
}

@Composable
fun QueueView(
    s: MusicState,
    engine: MusicEngine,
    dismiss: () -> Unit,
    open: (Card) -> Unit,
    menu: (Track) -> Unit,
) {
    val p = LocalPalette.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    var from by remember { mutableStateOf<Int?>(null) }
    var target by remember { mutableStateOf<Int?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var listBounds by remember { mutableStateOf(Rect.Zero) }
    SwipeDismissLayer(Modifier.fillMaxSize(), dismiss) { handle ->
        Column(
            Modifier.fillMaxSize()
                .background(p.bg)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Scene(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize()) {
                    DragHandle(handle, dismiss)
                    Row(handle, verticalAlignment = Alignment.CenterVertically) {
                        Tool(Icons.Outlined.ChevronLeft, "Назад", onClick = dismiss)
                        Label(
                            "Очередь",
                            26,
                            weight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Tool(
                            Icons.Outlined.DeleteSweep,
                            "Очистить следующие треки",
                            enabled = s.queue.isNotEmpty(),
                            onClick = { engine.clearQueue() },
                        )
                    }
                    Label(
                        if (s.owned) s.source else "Сохранённая очередь Лад",
                        13,
                        p.muted,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    if (!s.owned && s.output == "station")
                        Label(
                            "Станция не передаёт очередь, запущенную вне Лада. Твою очередь можно включить снова.",
                            13,
                            p.muted,
                        )
                    if (s.queue.isEmpty())
                        Label(
                            "Добавь музыку в очередь",
                            16,
                            p.muted,
                            modifier = Modifier.weight(1f).padding(top = 48.dp),
                        )
                    else
                        LazyColumn(
                            Modifier.weight(1f).onGloballyPositioned {
                                listBounds = it.boundsInRoot()
                            },
                            state = listState,
                            contentPadding = PaddingValues(bottom = 20.dp),
                        ) {
                            itemsIndexed(s.queue, key = { i, t -> "$i:${t.id}" }) { i, t ->
                                Column {
                                    if (target == i && from != null && i < from!!)
                                        HorizontalDivider(color = Color.White, thickness = 2.dp)
                                    TrackRow(
                                        t,
                                        s,
                                        { engine.task { engine.jump(i) } },
                                        open,
                                        { menu(t) },
                                        Modifier.onGloballyPositioned {
                                                bounds[i] = it.boundsInRoot()
                                            }
                                            .alpha(if (from == i) .45f else 1f),
                                        trailing = {
                                            Tool(
                                                Icons.Outlined.MoreHoriz,
                                                "Действия в очереди: ${t.title}",
                                                onClick = { menu(t) },
                                            )
                                            Icon(
                                                Icons.Outlined.DragIndicator,
                                                "Переставить ${t.title}",
                                                Modifier.size(48.dp).padding(12.dp).pointerInput(
                                                    i,
                                                    s.queue.size,
                                                ) {
                                                    detectDragGesturesAfterLongPress(
                                                        onDragStart = {
                                                            from = i
                                                            target = i
                                                            dragY = bounds[i]?.center?.y ?: 0f
                                                            haptic.performHapticFeedback(
                                                                HapticFeedbackType.LongPress
                                                            )
                                                        },
                                                        onDrag = { change, delta ->
                                                            change.consume()
                                                            dragY += delta.y
                                                            target =
                                                                bounds.entries
                                                                    .filter {
                                                                        it.key in s.queue.indices
                                                                    }
                                                                    .minByOrNull {
                                                                        abs(
                                                                            it.value.center.y -
                                                                                dragY
                                                                        )
                                                                    }
                                                                    ?.key ?: i
                                                            if (dragY < listBounds.top + 64)
                                                                scope.launch {
                                                                    listState.scrollBy(-24f)
                                                                }
                                                            else if (dragY > listBounds.bottom - 64)
                                                                scope.launch {
                                                                    listState.scrollBy(24f)
                                                                }
                                                        },
                                                        onDragEnd = {
                                                            val a = from
                                                            val b = target
                                                            if (a != null && b != null && a != b)
                                                                engine.move(a, b)
                                                            from = null
                                                            target = null
                                                        },
                                                        onDragCancel = {
                                                            from = null
                                                            target = null
                                                        },
                                                    )
                                                },
                                                tint = p.muted,
                                            )
                                        },
                                    )
                                    if (target == i && from != null && i > from!!)
                                        HorizontalDivider(color = Color.White, thickness = 2.dp)
                                }
                            }
                        }
                    Label(
                        "Удерживай ручку справа и перетаскивай треки",
                        12,
                        p.muted,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            MiniPlayer(s, engine, dismiss, menu)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun DevicePane(s: MusicState, engine: MusicEngine, dismiss: () -> Unit) {
    val p = LocalPalette.current
    var selected by remember { mutableStateOf(if (s.output == "phone") "phone" else s.deviceId ?: "phone") }
    var host by
        remember(selected) { mutableStateOf(s.devices.find { it.id == selected }?.host.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var transferError by remember { mutableStateOf("") }
    val selectedDevice = s.devices.find { it.id == selected }
    val ambiguous = selectedDevice?.let { deviceIdentityAmbiguous(it, s.devices) } == true
    val alreadyActive =
        (selected == "phone" && s.output == "phone") ||
            (selected != "phone" && selected == s.deviceId && s.output == "station" && s.connection == "connected")
    Pane(
        "Где будем слушать?",
        s.settings,
        dismiss,
        footer = {
            Action(
                if (alreadyActive) "Готово"
                else if (selected == "phone") "Продолжить на телефоне" else "Продолжить: ${selectedDevice?.selectionLabel.orEmpty()}",
                if (selected == "phone") Icons.Outlined.PhoneAndroid else Icons.Outlined.Speaker,
                enabled = !busy && (!s.devicesLoading || selected == "phone") && (alreadyActive || (!ambiguous && (selected == "phone" || selectedDevice != null))),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (alreadyActive) {
                    dismiss()
                    return@Action
                }
                busy = true
                transferError = ""
                engine.task {
                    try {
                        if (selected == "phone") engine.transferPhone()
                        else {
                            val d =
                                engine.state.value.devices.find { it.id == selected }
                                    ?: error("Обнови список Станций")
                            engine.transfer(d, host)
                        }
                        dismiss()
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        transferError =
                            e.message ?: "Не удалось передать воспроизведение. Попробуй ещё раз."
                    } finally {
                        busy = false
                    }
                }
            }
        },
    ) {
        Label("Тот же трек с текущей позиции", 14, p.muted)
        if (transferError.isNotBlank())
            Label(transferError, 14, p.accent, modifier = Modifier.padding(vertical = 12.dp))
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (selected == "phone") p.tonal else p.surface)
                .clickable { selected = "phone" }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Outlined.PhoneAndroid, null, tint = p.muted)
            Column(Modifier.weight(1f)) {
                Label("Этот телефон", 16, weight = FontWeight.Medium)
                Label("Динамик и наушники", 12, p.muted)
            }
            if (selected == "phone") Icon(Icons.Outlined.Check, null, tint = p.accent)
        }
        s.devices.forEach { device ->
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected == device.id) p.tonal else p.surface)
                    .clickable { selected = device.id }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.Outlined.Speaker, null, tint = p.muted)
                Column(Modifier.weight(1f)) {
                    Label(device.displayTitle, 16, weight = FontWeight.Medium)
                    Label(device.locationDescription, 12, p.muted)
                    if (deviceIdentityAmbiguous(device, s.devices))
                        Label("Нельзя различить · ID ${device.id.takeLast(8)}", 12, p.muted)
                    Label(
                        if (device.host.isNotBlank()) "Найдена в локальной сети"
                        else "Укажи IP или обнови поиск",
                        12,
                        p.muted,
                    )
                }
                if (selected == device.id) Icon(Icons.Outlined.Check, null, tint = p.accent)
            }
        }
        if (s.devicesLoading) Label("Обновляем имена и комнаты…", 13, p.muted)
        if (ambiguous) Label(AMBIGUOUS_DEVICE, 13, p.accent)
        if (s.devices.isEmpty())
            Label(
                "Станции из аккаунта появятся здесь. Телефон и Станция должны быть в одной сети Wi-Fi.",
                13,
                p.muted,
            )
        Action(
            "Обновить устройства",
            Icons.Outlined.Refresh,
            true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            busy = true
            engine.task {
                try {
                    engine.refreshDevices()
                } finally {
                    busy = false
                }
            }
        }
        if (selected != "phone") {
            Section("Локальный IP Станции")
            OutlinedTextField(
                host,
                { host = it },
                Modifier.fillMaxWidth(),
                label = { Text("Например, 192.168.1.25") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions =
                    androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii
                    ),
            )
            Label(
                "Адрес используется только для выбранной Станции из твоего аккаунта.",
                12,
                p.muted,
            )
        }
        if (s.settings.blockExplicit) Label("Фильтр 18+ включён в Ладе", 13, p.accent)
        if (busy) CircularProgressIndicator(Modifier.padding(16.dp))
    }
}
