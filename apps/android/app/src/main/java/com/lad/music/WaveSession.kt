// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

/** A radio session outlives the finite, currently downloaded queue. */
class WaveSession(
    private val fetch: suspend (String) -> Pair<List<Track>, String>,
    private val feedback: suspend (String, Track?, String, Long) -> Unit,
) {
    var batch: String = ""
        private set

    var cursor: String = ""
        private set

    suspend fun report(type: String, track: Track?, seconds: Long = 0) {
        val capturedBatch = batch
        feedback(type, track, capturedBatch, seconds)
    }

    suspend fun replenish(
        remaining: List<Track>,
        after: String,
        blockExplicit: Boolean,
        maxBatches: Int = 4,
        isCurrent: () -> Boolean = { true },
    ): List<Track> {
        repeat(maxBatches) {
            val (tracks, newBatch) = fetch(cursor.ifBlank { after })
            if (!isCurrent()) return emptyList()
            batch = newBatch
            tracks.lastOrNull()?.let { cursor = it.id }
            val existing = remaining.mapTo(mutableSetOf()) { it.id.substringBefore(':') }
            val additions = mutableListOf<Track>()
            for (track in tracks) {
                if (blockExplicit && track.explicit) {
                    runCatching { feedback("skip", track, newBatch, 0) }
                } else if (track.available && existing.add(track.id.substringBefore(':'))) {
                    additions += track
                }
            }
            if (!isCurrent()) return emptyList()
            if (additions.isNotEmpty()) return additions
        }
        return emptyList()
    }
}

enum class StationReport {
    EXPECTED,
    STARTING,
    CONTINUATION_START,
    CONTINUATION_WAIT,
    EXTERNAL,
}

/** Distinguishes repeated native continuation reports from a separate user change. */
class StationContinuity {
    var pending: String? = null
        private set

    fun reset() {
        pending = null
    }

    fun classify(
        id: String,
        expected: String,
        owned: Boolean,
        starting: Boolean,
        nearEnd: Boolean,
    ): StationReport {
        if (!owned || id.isBlank() || expected.isBlank() || id == expected) {
            reset()
            return StationReport.EXPECTED
        }
        if (id == pending) return StationReport.CONTINUATION_WAIT
        if (pending != null) {
            reset()
            return StationReport.EXTERNAL
        }
        if (starting) return StationReport.STARTING
        if (nearEnd) {
            pending = id
            return StationReport.CONTINUATION_START
        }
        return StationReport.EXTERNAL
    }
}
