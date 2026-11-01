// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

object LocalArtists {
    private val feature = Regex("(?<=\\S)(?:\\s+[\\[(]?\\s*|[\\[(]\\s*)(?:feat(?:uring)?|ft)\\.?\\s+", RegexOption.IGNORE_CASE)
    private val ambiguous = Regex("\\s*(?:,|\\s&\\s|\\s[x×]\\s|\\s/\\s)\\s*", RegexOption.IGNORE_CASE)
    private fun key(name: String) = Normalizer.normalize(name, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
    private fun parts(raw: String): List<String> {
        val marker = feature.find(raw)
        val value = if (marker != null && marker.value.any { it in "([" } && raw.trimEnd().lastOrNull() in listOf(')', ']'))
            raw.trimEnd().dropLast(1) else raw
        return feature.replace(value, ";").split(Regex("[\\u0000;]+"))
            .map { it.trim() }.filter { it.isNotEmpty() }
    }
    private fun ref(name: String): ArtistRef {
        val hash = MessageDigest.getInstance("SHA-256").digest(key(name).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(32)
        return ArtistRef("local-artist-$hash", name)
    }
    fun normalize(tracks: List<Track>): List<Track> {
        val known = tracks.flatMap { it.artists }.flatMap { parts(it.name) }
            .filterNot { ambiguous.containsMatchIn(it) }.mapTo(mutableSetOf(), ::key)
        return tracks.map { track ->
            val names = track.artists.flatMap { parts(it.name) }.flatMap { part ->
                val pieces = ambiguous.split(part).map { it.trim() }.filter { it.isNotEmpty() }
                if (pieces.size > 1 && pieces.any { key(it) in known }) pieces else listOf(part)
            }.distinctBy(::key).ifEmpty { listOf("Неизвестный исполнитель") }
            track.copy(artists = names.map(::ref))
        }
    }
}
