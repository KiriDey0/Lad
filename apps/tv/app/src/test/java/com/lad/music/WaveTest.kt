// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WaveTest {
    private fun t(id: String, explicit: Boolean = false, available: Boolean = true) =
        Track(id, "Track $id", emptyList(), explicit = explicit, available = available)

    @Test
    fun explicitOnlyBatchesAdvanceCursorUntilAllowedTrack() = runBlocking {
        val calls = mutableListOf<String>()
        val skips = mutableListOf<String>()
        val session =
            WaveSession(
                { after ->
                    calls += after
                    val n = calls.size
                    listOf(t("${n*2}", n < 3), t("${n*2+1}", n < 3)) to "b$n"
                },
                { kind, track, _, _ -> if (kind == "skip") skips += track!!.id },
            )
        val tracks = session.replenish(emptyList(), "1", true)
        assertEquals(listOf("1", "3", "5"), calls)
        assertEquals(listOf("6", "7"), tracks.map { it.id })
        assertEquals(listOf("2", "3", "4", "5"), skips)
    }

    @Test
    fun boundedSearchPreservesSessionAndRetryCursor() = runBlocking {
        var calls = 0
        val cursors = mutableListOf<String>()
        val session =
            WaveSession(
                { after ->
                    cursors += after
                    calls++
                    listOf(t("$calls", calls <= 4)) to "b$calls"
                },
                { _, _, _, _ -> },
            )
        assertTrue(session.replenish(emptyList(), "", true).isEmpty())
        assertEquals(4, calls)
        assertEquals(listOf("5"), session.replenish(emptyList(), "", true).map { it.id })
        assertEquals("4", cursors.last())
    }

    @Test
    fun batchesExcludeUnavailableAndCanonicalDuplicatesButAllowPlayedHistory() = runBlocking {
        val session =
            WaveSession(
                { listOf(t("2:4"), t("3"), t("3:5"), t("4", available = false), t("1")) to "new" },
                { _, _, _, _ -> },
            )
        assertEquals(listOf("3", "1"), session.replenish(listOf(t("2")), "2", false).map { it.id })
    }

    @Test
    fun externalChangeWhileFetchingDoesNotAppendLateBatch() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var owned = true
        val session =
            WaveSession(
                {
                    ready.complete(Unit)
                    release.await()
                    listOf(t("3")) to "new"
                },
                { _, _, _, _ -> },
            )
        val job = async { session.replenish(emptyList(), "2", false, isCurrent = { owned }) }
        ready.await()
        owned = false
        release.complete(Unit)
        assertTrue(job.await().isEmpty())
    }

    @Test
    fun feedbackUsesBatchFromBeforeReplenishment() = runBlocking {
        var n = 0
        val reports = mutableListOf<String>()
        val session =
            WaveSession(
                {
                    n++
                    listOf(t("$n")) to "b$n"
                },
                { type, track, batch, seconds -> reports += "$type:${track?.id}:$batch:$seconds" },
            )
        session.replenish(emptyList(), "", false)
        session.report("skip", t("1"), 90)
        session.replenish(emptyList(), "1", false)
        session.report("trackStarted", t("2"))
        assertEquals(listOf("skip:1:b1:90", "trackStarted:2:b2:0"), reports)
    }

    @Test
    fun disabledFilterRetainsExplicitSongs() = runBlocking {
        val session = WaveSession({ listOf(t("1", true)) to "new" }, { _, _, _, _ -> })
        assertEquals("1", session.replenish(emptyList(), "", false).single().id)
    }

    @Test
    fun repeatedNativeContinuationDoesNotReleaseManagedQueue() {
        val continuity = StationContinuity()
        assertEquals(
            StationReport.CONTINUATION_START,
            continuity.classify("native", "2", true, false, true),
        )
        assertEquals(
            StationReport.CONTINUATION_WAIT,
            continuity.classify("native", "2", true, true, false),
        )
        assertEquals(
            StationReport.CONTINUATION_WAIT,
            continuity.classify("native", "2", true, false, false),
        )
        assertEquals("native", continuity.pending)
    }

    @Test
    fun anotherUserChangeWhileWaitingIsExternal() {
        val continuity = StationContinuity()
        continuity.classify("native", "2", true, false, true)
        assertEquals(StationReport.EXTERNAL, continuity.classify("other", "2", true, true, true))
        assertNull(continuity.pending)
    }

    @Test
    fun expectedTrackAfterRetryClearsNativeContinuation() {
        val continuity = StationContinuity()
        continuity.classify("native", "2", true, false, true)
        assertEquals(StationReport.EXPECTED, continuity.classify("3", "3", true, true, false))
        assertNull(continuity.pending)
    }

    @Test
    fun lateOldReportDuringPlayCommandIsNotAnExternalChange() {
        val continuity = StationContinuity()
        assertEquals(StationReport.STARTING, continuity.classify("old", "3", true, true, false))
        assertEquals(StationReport.EXTERNAL, continuity.classify("other", "3", true, false, false))
    }
}
