// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import org.json.JSONArray
import org.json.JSONObject

/** Network state belongs to one wave, never to the user's global account settings. */
internal class YandexWaveSource(
    private val api: YandexApi,
    private val blockExplicit: () -> Boolean,
) {
    private var filtered: Boolean? = null
    private var session: String? = null
    private var legacy = false
    private var seeds: List<String>? = null
    private val history = linkedSetOf<String>()
    private val sessions = linkedMapOf<String, String>()

    suspend fun fetch(after: String): Pair<List<Track>, String> {
        val block = blockExplicit()
        if (filtered != block) {
            filtered = block
            session = null
            legacy = false
            history.clear()
            seeds = null
        }
        if (!block || legacy) return api.wave(after)
        if (seeds == null) {
            seeds = try {
                api.waveSeeds()
            } catch (e: ApiError) {
                if (e.code !in setOf(400, 404, 405, 501)) throw e
                null
            }
            if (seeds == null) {
                legacy = true
                return api.wave(after)
            }
        }
        if (after.isNotBlank()) history.add(after)
        val queue = history.toList()
        val currentSession = session
        val path = if (currentSession == null) "/rotor/session/new"
                   else "/rotor/session/${encode(currentSession)}/tracks"
        val body = JSONObject().put("queue", JSONArray(queue))
        if (currentSession == null) {
            body.put("seeds", JSONArray(seeds))
                .put("allowExplicit", false)
                .put("includeTracksInResponse", true)
        }
        val response = try {
            api.rawJson(api.base + path, body)
        } catch (e: ApiError) {
            if (e.code !in setOf(400, 404, 405, 501)) throw e
            // The rejected request consumes this attempt; fallback is the next
            // attempt, so a single search still makes at most twenty requests.
            legacy = true
            session = null
            return emptyList<Track>() to ""
        }
        val result = response.optJSONObject("result") ?: response
        if (result.optBoolean("unknownSession")) {
            session = null
            return emptyList<Track>() to ""
        }
        if (currentSession == null) {
            session = result.optString("radioSessionId").takeIf { it.isNotBlank() }
            if (session == null || result.optString("batchId").isBlank() || result.has("error")) {
                legacy = true
                return emptyList<Track>() to ""
            }
        }
        val batch = result.optString("batchId")
        if (batch.isNotBlank()) {
            sessions[batch] = requireNotNull(session)
            while (sessions.size > 64) sessions.remove(sessions.keys.first())
        }
        val tracks = result.array("sequence").objects()
            .mapNotNull { it.optJSONObject("track") }.map(Track::fromApi)
        history.addAll(tracks.map { it.id })
        while (history.size > 1000) history.remove(history.first())
        if (result.optBoolean("terminated")) session = null
        return tracks to batch
    }

    suspend fun report(type: String, track: Track?, batch: String, seconds: Long) {
        val radioSession = sessions[batch]
            ?: return api.radioFeedback(type, track, batch, seconds)
        val event = JSONObject().put("type", type)
            .put("timestamp", java.time.Instant.now().toString())
        if (track != null) event.put("trackId", track.id)
        if (seconds > 0 || type == "skip" || type == "trackFinished")
            event.put("totalPlayedSeconds", seconds.coerceAtLeast(0))
        api.rawJson(api.base + "/rotor/session/${encode(radioSession)}/feedback",
            JSONObject().put("event", event).put("batchId", batch).put("from", "lad-android"))
    }
}
