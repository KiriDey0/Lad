// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun OrbitSectionLink(title: String, open: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = open),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label(title, 23, weight = FontWeight.SemiBold)
        Icon(Icons.Outlined.ChevronRight, null, tint = p.muted)
    }
}

@Composable
internal fun ReleaseShelf(
    releases: List<PersonalRelease>,
    state: LazyListState,
    openAll: () -> Unit,
    open: (Card) -> Unit,
    play: (Card) -> Unit,
) {
    OrbitSectionLink("Новые релизы", openAll)
    Spacer(Modifier.height(10.dp))
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(releases.take(6), key = { it.album.id }) { release ->
            ReleaseTile(release, open, play, Modifier.width(226.dp))
        }
    }
}

private val releaseDateFormat = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru-RU"))

@Composable
internal fun ReleaseTile(
    release: PersonalRelease,
    open: (Card) -> Unit,
    play: (Card) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    Box(modifier.height(220.dp), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier.size(174.dp)
                .clip(RoundedCornerShape(50))
                .clickable { open(release.artist) },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Artwork(release.artist.cover, release.artist.title, 174.dp, cornerRadius = 87.dp)
            Box(Modifier.matchParentSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .65f)), startY = 80f),
            ))
            Label(
                release.artist.title,
                17,
                Color.White,
                FontWeight.SemiBold,
                Modifier.padding(start = 18.dp, end = 18.dp, bottom = 29.dp),
                maxLines = 1,
            )
        }
        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).height(64.dp)
                .clip(RoundedCornerShape(18.dp)).background(p.surface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).fillMaxHeight().clickable { open(release.album) }.padding(start = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(release.album.cover, release.album.title, 42.dp)
                Column(Modifier.weight(1f)) {
                    Label(release.album.title, 14, weight = FontWeight.SemiBold, maxLines = 1)
                    val kind = when (release.kind) {
                        "single" -> "Сингл"
                        "ep" -> "EP"
                        else -> "Альбом"
                    }
                    Label("$kind · ${release.date.format(releaseDateFormat)}", 11, p.muted, maxLines = 1)
                }
            }
            Tool(Icons.Outlined.PlayArrow, "Слушать ${release.album.title}") { play(release.album) }
        }
    }
}
