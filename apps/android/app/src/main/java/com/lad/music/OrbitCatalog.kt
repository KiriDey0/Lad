// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class PersonalRelease(val album: Card, val artist: Card, val date: LocalDate, val kind: String)
internal data class PersonalReleases(val items: List<PersonalRelease>, val partial: Boolean)
internal data class ArtistOverview(
    val artist: Card,
    val popularTracks: List<Track>,
    val popularAlbums: List<Card>,
    val listeners: Int?,
    val albumsUnavailable: Boolean,
)

internal suspend fun <T> catalogResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

internal suspend fun YandexApi.favoriteArtists(uid: String): List<Card> =
    arr("/users/${encode(uid)}/likes/artists", mapOf("with-timestamps" to "true"))
        .objects()
        .mapNotNull { item ->
            item.optJSONObject("artist") ?: item.takeIf { it.has("id") && it.has("name") }
        }
        .map(Card::artist)
        .distinctBy(Card::key)

internal suspend fun YandexApi.personalReleases(uid: String): PersonalReleases = coroutineScope {
    val following = async { catalogResult { favoriteArtists(uid) } }
    val history = async { catalogResult { recentTracks(100) } }
    val likes = async { catalogResult { liked(uid, limit = 100).tracks } }
    val followed = following.await()
    val recent = history.await()
    val liked = likes.await()
    val sources = listOf(followed, recent, liked)
    if (sources.all { it.isFailure }) throw IllegalStateException("Не удалось загрузить твоих исполнителей.")

    val artists = linkedMapOf<String, Card>()
    val weights = mutableMapOf<String, Int>()
    fun add(card: Card, weight: Int) {
        if (card.id.isEmpty() || card.id.any { !it.isDigit() }) return
        val previous = artists[card.id]
        if (previous == null || card.cover != null) artists[card.id] = card
        weights[card.id] = (weights[card.id] ?: 0) + weight
    }
    followed.getOrNull().orEmpty().forEach { add(it, 5) }
    recent.getOrNull().orEmpty().forEach { track ->
        track.artists.forEach { add(Card(it.id, "artist", it.name), 2) }
    }
    liked.getOrNull().orEmpty().forEach { track ->
        track.artists.forEach { add(Card(it.id, "artist", it.name), 1) }
    }
    val gate = Semaphore(4)
    val today = LocalDate.now()
    val cutoff = today.minusDays(89)
    val groups = weights.entries.sortedByDescending { it.value }.take(24).map { (id, _) ->
        async {
            gate.withPermit {
                catalogResult {
                    val artist = artists.getValue(id)
                    obj("/artists/${encode(id)}/direct-albums", mapOf("page-size" to "8", "sort-by" to "year"))
                        .array("albums").objects().mapNotNull { item ->
                            val date = runCatching { LocalDate.parse(item.text("releaseDate").take(10)) }.getOrNull()
                                ?: return@mapNotNull null
                            if (date < cutoff || date > today || item.optBoolean("preOrder")) return@mapNotNull null
                            val withCover = item.array("artists").objects()
                                .firstOrNull { it.text("id") == id }?.let(Card::artist)
                            PersonalRelease(
                                Card.album(item),
                                artist.copy(cover = artist.cover ?: withCover?.cover),
                                date,
                                item.text("type", "album"),
                            )
                        }.filter { it.album.id.isNotBlank() }
                }
            }
        }
    }.awaitAll()
    if (groups.isNotEmpty() && groups.all { it.isFailure })
        throw IllegalStateException("Не удалось загрузить новые релизы.")
    PersonalReleases(
        groups.flatMap { it.getOrNull().orEmpty() }.distinctBy { it.album.id }
            .sortedByDescending(PersonalRelease::date).take(36),
        sources.any { it.isFailure } || groups.any { it.isFailure },
    )
}

internal suspend fun YandexApi.artistOverview(card: Card): ArtistOverview = coroutineScope {
    val ranked = async {
        catalogResult {
            obj("/artists/${encode(card.id)}/direct-albums", mapOf("page-size" to "9", "sort-by" to "rating"))
                .array("albums").objects().map(Card::album).take(9)
        }
    }
    val info = obj("/artists/${encode(card.id)}/brief-info")
    val actual = info.optJSONObject("artist")?.let(Card::artist) ?: card
    val albums = ranked.await()
    ArtistOverview(
        actual.copy(cover = actual.cover ?: card.cover),
        info.array("popularTracks").objects().map(Track::fromApi),
        albums.getOrNull().orEmpty(),
        info.optJSONObject("stats")?.optInt("lastMonthListeners")?.takeIf { it > 0 },
        albums.isFailure,
    )
}

internal suspend fun YandexApi.artistDiscography(card: Card, page: Int): Catalog {
    val data = obj(
        "/artists/${encode(card.id)}/direct-albums",
        mapOf("page" to "$page", "page-size" to "24", "sort-by" to "year"),
    )
    val albums = data.array("albums").objects().map(Card::album)
    val total = data.optJSONObject("pager")?.optInt("total") ?: (page * 24 + albums.size)
    return Catalog(card.title, cards = albums, total = total, more = (page + 1) * 24 < total)
}
