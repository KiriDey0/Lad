// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.lad.music

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlin.math.floor

fun collectionTitle(section: String?) =
    when (section) {
        "playlists" -> "Плейлисты"
        "albums" -> "Альбомы"
        "artists" -> "Исполнители"
        else -> "Коллекция"
    }

@Composable
private fun FavoriteCover(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(p.tonal),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Favorite, null, Modifier.size(34.dp), tint = p.accent)
    }
}

@Composable
private fun CollectionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier,
    click: () -> Unit,
) {
    val p = LocalPalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(p.surface)
            .clickable(onClick = click)
            .padding(12.dp)
            .heightIn(min = 80.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = p.accent)
        Label(title, 15, weight = FontWeight.Medium, maxLines = 1)
        Label(subtitle, 11, p.muted, maxLines = 1)
    }
}

@Composable
fun CollectionView(
    library: Library,
    liked: Catalog,
    section: String?,
    likedGroup: Boolean,
    loading: Boolean,
    error: String,
    bottom: Dp,
    scrollState: LazyListState,
    navigate: (String) -> Unit,
    group: (Boolean) -> Unit,
    favorite: () -> Unit,
    open: (Card) -> Unit,
    menu: (Card) -> Unit,
    retry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val favorites =
        remember(liked.total) {
            Card(
                "__liked__",
                "liked",
                "Мне нравится",
                quantityLabel(liked.total, "трек", "трека", "треков"),
            )
        }
    if (section == "playlists") {
        LazyRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item {
                FilterChip(
                    !likedGroup,
                    { group(false) },
                    label = {
                        Label("Вы собрали · ${library.ownPlaylists.size + 1}", 12, maxLines = 1)
                    },
                )
            }
            item {
                FilterChip(
                    likedGroup,
                    { group(true) },
                    label = {
                        Label("Вам понравились · ${library.likedPlaylists.size}", 12, maxLines = 1)
                    },
                )
            }
        }
    }
    val cards =
        remember(library, section, likedGroup, favorites) {
            when (section) {
                "playlists" ->
                    if (likedGroup) library.likedPlaylists
                    else listOf(favorites) + library.ownPlaylists
                "albums" -> library.albums
                "artists" -> library.artists
                else -> emptyList()
            }
        }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale * p.scale
        val columns =
            floor(((maxWidth + 12.dp) / (104.dp * fontScale.coerceAtLeast(1f) + 12.dp)).toDouble())
                .toInt()
                .coerceIn(2, 5)
        key(section, likedGroup, columns) {
            LazyColumn(
                Modifier.fillMaxSize().clipToBounds(),
                state = scrollState,
                contentPadding = PaddingValues(top = 8.dp, bottom = bottom),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (loading)
                    item {
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth(),
                            color = p.accent,
                            trackColor = p.raised,
                        )
                    }
                if (error.isNotBlank()) item { ErrorBox(error, retry) }
                if (section == null) {
                    item {
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(p.surface)
                                .clickable(onClick = favorite)
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(Modifier.size(48.dp)) { FavoriteCover(Modifier.fillMaxWidth()) }
                            Column(Modifier.weight(1f)) {
                                Label("Мне нравится", 18, weight = FontWeight.SemiBold)
                                Label(
                                    quantityLabel(liked.total, "трек", "трека", "треков"),
                                    12,
                                    p.muted,
                                )
                            }
                            Icon(Icons.Outlined.ChevronRight, null, tint = p.muted)
                        }
                    }
                    item {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                                navigate("artists")
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Label(
                                "Любимые исполнители",
                                20,
                                weight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Icon(Icons.Outlined.ChevronRight, null, tint = p.muted)
                        }
                        if (library.artistsError.isNotBlank()) ErrorBox(library.artistsError, retry)
                        else if (library.artists.isNotEmpty())
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(library.artists.take(12), key = { it.key }) { artist ->
                                    Column(
                                        Modifier.width(100.dp)
                                            .combinedClickable(
                                                onClick = { open(artist) },
                                                onLongClick = { menu(artist) },
                                            )
                                    ) {
                                        Artwork(
                                            artist.cover,
                                            artist.title,
                                            modifier = Modifier.fillMaxWidth().clip(CircleShape),
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Label(artist.title, 12, maxLines = 1)
                                    }
                                }
                            }
                        else if (!loading && error.isBlank())
                            Label("Сохранённые исполнители появятся здесь", 13, p.muted)
                    }
                    item {
                        Section("В коллекции")
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CollectionTile(
                                "Плейлисты",
                                quantityLabel(
                                    library.playlists.size,
                                    "плейлист",
                                    "плейлиста",
                                    "плейлистов",
                                ),
                                Icons.Outlined.QueueMusic,
                                Modifier.weight(1f),
                            ) {
                                navigate("playlists")
                            }
                            CollectionTile(
                                "Альбомы",
                                quantityLabel(library.albums.size, "альбом", "альбома", "альбомов"),
                                Icons.Outlined.Album,
                                Modifier.weight(1f),
                            ) {
                                navigate("albums")
                            }
                        }
                    }
                } else {
                    if (section == "artists" && library.artistsError.isNotBlank())
                        item { ErrorBox(library.artistsError, retry) }
                    if (
                        cards.isEmpty() &&
                            !loading &&
                            error.isBlank() &&
                            (section != "artists" || library.artistsError.isBlank())
                    )
                        item {
                            Column(
                                Modifier.padding(top = 32.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Label(
                                    if (section == "playlists") "Здесь пока нет плейлистов"
                                    else if (section == "albums") "Пока нет любимых альбомов"
                                    else "Пока нет любимых исполнителей",
                                    20,
                                    weight = FontWeight.SemiBold,
                                )
                                Label("Сохранённая музыка из Яндекса появится здесь.", 14, p.muted)
                            }
                        }
                    items(
                        cards.chunked(columns),
                        key = { row -> row.joinToString("|") { it.key } },
                    ) { row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            row.forEach { card ->
                                Column(
                                    Modifier.weight(1f)
                                        .combinedClickable(
                                            onClickLabel = "Открыть ${card.title}",
                                            onClick = {
                                                if (card.type == "liked") favorite() else open(card)
                                            },
                                            onLongClick =
                                                if (card.type == "liked") null else ({ menu(card) }),
                                        )
                                ) {
                                    if (card.type == "liked") FavoriteCover(Modifier.fillMaxWidth())
                                    else
                                        Artwork(
                                            card.cover,
                                            card.title,
                                            cornerRadius = 18.dp,
                                            modifier =
                                                Modifier.fillMaxWidth()
                                                    .then(
                                                        if (card.type == "artist")
                                                            Modifier.clip(CircleShape)
                                                        else Modifier
                                                    ),
                                        )
                                    Spacer(Modifier.height(4.dp))
                                    Label(card.title, 13, weight = FontWeight.Medium, maxLines = 1)
                                    Label(card.subtitle, 11, p.muted, maxLines = 1)
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}
