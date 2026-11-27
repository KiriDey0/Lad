// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable
internal fun OrbitDislikes(state: MusicState, engine: MusicEngine, modifier: Modifier, bottom: Dp) {
    val uid = state.profile?.uid ?: return
    val p = LocalPalette.current
    var ids by remember(uid) { mutableStateOf<List<String>?>(null) }
    var tracks by remember(uid) { mutableStateOf(emptyList<Track>()) }
    var page by remember(uid) { mutableIntStateOf(1) }
    var revision by remember(uid) { mutableIntStateOf(0) }
    var error by remember(uid) { mutableStateOf("") }
    var loading by remember(uid) { mutableStateOf(true) }
    val removing = remember(uid) { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(uid, page, revision) {
        loading = true
        error = ""
        try {
            val known = ids ?: (if (state.demo) state.queue.filter { it.disliked }.map { it.id }
                else engine.api.ratingIds(uid, true)).also { ids = it }
            val selected = known.take(page * 50)
            val missing = selected - tracks.map(Track::id).toSet()
            val models = if (state.demo) state.queue else engine.api.tracks(missing)
            val resolved = models.associateBy(Track::id)
            tracks = (tracks + missing.map { id ->
                resolved[id]?.copy(disliked = true)
                    ?: Track(id, "Недоступный трек", emptyList(), available = false, disliked = true)
            }).distinctBy(Track::id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Не удалось загрузить дизлайки"
        } finally {
            loading = false
        }
    }
    LazyColumn(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (error.isNotEmpty()) item { ErrorBox(error) { revision++ } }
        if (!loading && error.isEmpty() && tracks.isEmpty())
            item { Label("Дизлайков пока нет", 18, weight = FontWeight.SemiBold) }
        items(tracks, key = Track::id) { track ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(track.cover, track.title, 44.dp)
                Column(Modifier.weight(1f)) {
                    Label(track.title, 15, weight = FontWeight.SemiBold)
                    if (track.artist.isNotBlank()) Label(track.artist, 12, p.muted)
                }
                Tool(Icons.Outlined.Undo, "Снять дизлайк: ${track.title}", enabled = removing[track.id] != true) {
                    removing[track.id] = true
                    engine.task {
                        try {
                            engine.dislike(track, false)
                            ids = ids?.filterNot { it == track.id }
                            tracks = tracks.filterNot { it.id == track.id }
                            if ((ids?.size ?: 0) > tracks.size) revision++
                        } finally {
                            removing.remove(track.id)
                        }
                    }
                }
            }
        }
        if (!loading && error.isEmpty() && tracks.size < (ids?.size ?: 0))
            item {
                Action("Показать ещё", Icons.Outlined.Refresh, tonal = true) { page++ }
            }
    }
}
