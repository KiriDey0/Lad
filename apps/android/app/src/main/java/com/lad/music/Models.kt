// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import java.net.InetAddress
import java.net.URLEncoder
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONObject.array(name: String) = optJSONArray(name) ?: JSONArray()

fun JSONObject.text(name: String, default: String = "") =
    if (isNull(name)) default else optString(name, default)

fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

fun coverUrl(uri: String): String? =
    uri.takeIf { it.isNotBlank() }
        ?.let {
            "https://" +
                it.removePrefix("https://").removePrefix("http://").replace("%%", "600x600")
        }

data class ArtistRef(val id: String, val name: String)

data class Track(
    val id: String,
    val title: String,
    val artists: List<ArtistRef>,
    val albumId: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val cover: String? = null,
    val available: Boolean = true,
    val explicit: Boolean = false,
    val liked: Boolean = false,
) {
    val artist
        get() = artists.joinToString(", ") { it.name }

    val url
        get() =
            "https://music.yandex.ru/" +
                (if (albumId.isNotBlank()) "album/${encode(albumId)}/" else "") +
                "track/${encode(id)}"

    fun albumCard() = Card(albumId, "album", album.ifBlank { title }, artist, cover = cover)

    fun json() =
        JSONObject()
            .put("id", id)
            .put("title", title)
            .put(
                "artists",
                JSONArray(artists.map { JSONObject().put("id", it.id).put("name", it.name) }),
            )
            .put("albumId", albumId)
            .put("album", album)
            .put("duration", durationMs)
            .put("cover", cover)
            .put("available", available)
            .put("explicit", explicit)
            .put("liked", liked)

    companion object {
        fun fromApi(j: JSONObject): Track {
            val a = j.array("albums").optJSONObject(0)
            return Track(
                j.text("id"),
                j.text("title", "Без названия"),
                j.array("artists").objects().map { ArtistRef(it.text("id"), it.text("name")) },
                a?.text("id").orEmpty(),
                a?.text("title").orEmpty(),
                j.optLong("durationMs"),
                coverUrl(j.text("coverUri")),
                j.optBoolean("available", true),
                j.optBoolean("explicit") || j.text("contentWarning") == "explicit",
            )
        }

        fun fromJson(j: JSONObject) =
            Track(
                j.text("id"),
                j.text("title"),
                j.array("artists").objects().map { ArtistRef(it.text("id"), it.text("name")) },
                j.text("albumId"),
                j.text("album"),
                j.optLong("duration"),
                j.text("cover").takeIf { it.isNotBlank() },
                j.optBoolean("available", true),
                j.optBoolean("explicit"),
                j.optBoolean("liked"),
            )
    }
}

data class Card(
    val id: String,
    val type: String,
    val title: String,
    val subtitle: String = "",
    val owner: String = "",
    val ownerLogin: String = "",
    val cover: String? = null,
) {
    val key
        get() = "$type:$owner:$id"

    val url
        get() =
            when (type) {
                "album" -> "https://music.yandex.ru/album/${encode(id)}"
                "artist" -> "https://music.yandex.ru/artist/${encode(id)}"
                else ->
                    "https://music.yandex.ru/users/${encode(ownerLogin.ifBlank { owner })}/playlists/${encode(id)}"
            }

    companion object {
        fun playlist(j: JSONObject): Card {
            val c = j.optJSONObject("cover")
            val uri =
                c?.text("uri").orEmpty().ifBlank { c?.array("itemsUri")?.optString(0).orEmpty() }
            return Card(
                j.text("kind"),
                "playlist",
                j.text("title"),
                quantityLabel(j.optInt("trackCount"), "трек", "трека", "треков"),
                j.optJSONObject("owner")?.text("uid").orEmpty(),
                j.optJSONObject("owner")?.text("login").orEmpty(),
                coverUrl(uri),
            )
        }

        fun album(j: JSONObject) =
            Card(
                j.text("id"),
                "album",
                j.text("title"),
                j.array("artists").objects().joinToString(", ") { it.text("name") },
                cover = coverUrl(j.text("coverUri")),
            )

        fun artist(j: JSONObject) =
            Card(
                j.text("id"),
                "artist",
                j.text("name"),
                "Исполнитель",
                cover = coverUrl(j.optJSONObject("cover")?.text("uri").orEmpty()),
            )
    }
}

data class Profile(val uid: String, val name: String, val plus: Boolean)

data class Library(
    val playlists: List<Card> = emptyList(),
    val albums: List<Card> = emptyList(),
    val ownPlaylists: List<Card> = playlists,
    val likedPlaylists: List<Card> = emptyList(),
    val artists: List<Card> = emptyList(),
    val artistsError: String = "",
)

data class Catalog(
    val title: String = "",
    val tracks: List<Track> = emptyList(),
    val cards: List<Card> = emptyList(),
    val total: Int = 0,
    val more: Boolean = false,
)

data class LyricLine(val timeMs: Long?, val text: String)

data class Lyrics(val lines: List<LyricLine> = emptyList(), val synced: Boolean = false)

data class Device(
    val id: String,
    val name: String,
    val platform: String,
    val certificate: String,
    val host: String = "",
    val port: Int = 1961,
)

data class Settings(
    val oled: Boolean = false,
    val glass: Boolean = true,
    val tinted: Boolean = false,
    val accent: Long = 0xFFE2E6EC,
    val scale: Float = 1f,
    val blockExplicit: Boolean = false,
    val lyricOffsetMs: Long = 0,
    val syncVolume: Boolean = false,
)

data class MusicState(
    val profile: Profile? = null,
    val demo: Boolean = false,
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val source: String = "Очередь Лад",
    val owned: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val externalTrack: Track? = null,
    val output: String = "phone",
    val deviceId: String? = null,
    val devices: List<Device> = emptyList(),
    val connection: String = "disconnected",
    val loading: Boolean = false,
    val repeat: Int = 0,
    val shuffle: Boolean = false,
    val volume: Float = .4f,
    val settings: Settings = Settings(),
    val message: String? = null,
) {
    val track
        get() = externalTrack ?: queue.getOrNull(index)

    val outputName
        get() =
            if (output == "phone") "Этот телефон"
            else devices.find { it.id == deviceId }?.name ?: "Станция"
}

fun parseLyrics(text: String): Lyrics {
    val regex = Regex("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?]")
    val timed =
        text
            .lineSequence()
            .flatMap { line ->
                val matches = regex.findAll(line).toList()
                val words = line.replace(regex, "").trim()
                matches.map { m ->
                    val fraction = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
                    LyricLine(
                        m.groupValues[1].toLong() * 60000 +
                            m.groupValues[2].toLong() * 1000 +
                            fraction,
                        words,
                    )
                }
            }
            .filter { it.text.isNotBlank() }
            .sortedBy { it.timeMs }
            .toList()
    return if (timed.isNotEmpty()) Lyrics(timed, true)
    else
        Lyrics(
            text
                .lines()
                .filter { it.isNotBlank() && !it.startsWith("[") }
                .map { LyricLine(null, it) }
        )
}

fun nextPlayable(
    tracks: List<Track>,
    index: Int,
    direction: Int,
    repeat: Int,
    block: Boolean,
): Int? {
    if (tracks.isEmpty()) return null
    var i = index + direction
    val seen = mutableSetOf<Int>()
    while (true) {
        if (i !in tracks.indices) {
            if (repeat != 2) return null
            i = (i % tracks.size + tracks.size) % tracks.size
        }
        if (!seen.add(i)) return null
        if (tracks[i].available && !(block && tracks[i].explicit)) return i
        i += direction
    }
}

fun moveQueue(tracks: List<Track>, index: Int, from: Int, to: Int): Pair<List<Track>, Int> {
    require(from in tracks.indices && to in tracks.indices)
    val indexed = tracks.mapIndexed { i, t -> i to t }.toMutableList()
    indexed.add(to, indexed.removeAt(from))
    return indexed.map { it.second } to indexed.indexOfFirst { it.first == index }
}

fun localIp(value: String): String {
    val host = value.trim().removePrefix("[").removeSuffix("]")
    require(host.any { it == '.' || it == ':' } && host.matches(Regex("[0-9a-fA-F:.]+"))) {
        "Укажи локальный IP Станции"
    }
    val address = InetAddress.getByName(host)
    val b = address.address
    val ula = b.size == 16 && (b[0].toInt() and 0xfe) == 0xfc
    require(
        (address.isSiteLocalAddress || address.isLinkLocalAddress || ula) &&
            !address.isLoopbackAddress &&
            !address.isAnyLocalAddress &&
            !address.isMulticastAddress
    ) {
        "Нужен локальный адрес Станции"
    }
    return host
}

fun timeLabel(ms: Long): String {
    val s = max(0, ms) / 1000
    return "${s/60}:${(s%60).toString().padStart(2,'0')}"
}

/** Queue occurrence, not just track ID: duplicates remain distinct. */
data class CoverEntry(val index: Int, val track: Track)

data class CoverWindow(
    val entries: List<CoverEntry>,
    val center: Int,
    val origin: Int,
    val queueStamp: List<String>,
    val output: String,
    val blockExplicit: Boolean,
)

fun queueStamp(s: MusicState): List<String> =
    s.queue.map { "${it.id}:${it.available}:${it.explicit}" }

fun coverWindow(s: MusicState): CoverWindow {
    val current = s.track
    val unknown = s.output == "station" && !s.owned
    if (current == null || unknown)
        return CoverWindow(
            current?.let { listOf(CoverEntry(-1, it)) }.orEmpty(),
            0,
            s.index,
            queueStamp(s),
            s.output,
            s.settings.blockExplicit,
        )
    val before =
        nextPlayable(s.queue, s.index, -1, s.repeat, s.settings.blockExplicit)
            ?.takeIf { it != s.index }
            ?.let { CoverEntry(it, s.queue[it]) }
    val after =
        nextPlayable(s.queue, s.index, 1, s.repeat, s.settings.blockExplicit)
            ?.takeIf { it != s.index }
            ?.let { CoverEntry(it, s.queue[it]) }
    return CoverWindow(
        listOfNotNull(before, CoverEntry(s.index, current), after),
        if (before == null) 0 else 1,
        s.index,
        queueStamp(s),
        s.output,
        s.settings.blockExplicit,
    )
}

/** Reject a gesture captured before queue/filter/output changed. */
fun acceptsCoverSelection(s: MusicState, window: CoverWindow, target: Int): Boolean {
    if (s.output == "station" && !s.owned) return false
    if (
        s.index != window.origin ||
            s.output != window.output ||
            s.settings.blockExplicit != window.blockExplicit ||
            queueStamp(s) != window.queueStamp
    )
        return false
    val selected = window.entries.getOrNull(target) ?: return false
    if (selected.index == window.origin || selected.index !in s.queue.indices) return false
    val direction = if (target > window.center) 1 else -1
    return nextPlayable(s.queue, s.index, direction, s.repeat, s.settings.blockExplicit) ==
        selected.index
}

fun quantityLabel(count: Int, one: String, few: String, many: String): String {
    val word =
        when {
            count % 100 in 11..14 -> many
            count % 10 == 1 -> one
            count % 10 in 2..4 -> few
            else -> many
        }
    return "$count $word"
}

// Keep the played occurrences and current index; restore only the unplayed tail.
fun restoreUnshuffledQueue(current: List<Track>, index: Int, original: List<Track>): List<Track> {
    val prefix = current.take((index + 1).coerceIn(0, current.size))
    val remaining = original.toMutableList()
    prefix.forEach { played ->
        val i = remaining.indexOfFirst { it.id == played.id }
        if (i >= 0) remaining.removeAt(i)
    }
    val live = current.drop(prefix.size).groupBy { it.id }.mapValues { it.value.toMutableList() }
    return prefix +
        remaining.map { originalTrack ->
            live[originalTrack.id]?.let { if (it.isNotEmpty()) it.removeAt(0) else null }
                ?: originalTrack
        }
}
