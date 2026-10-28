// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import org.junit.Assert.*
import org.junit.Test

class CarouselTest {
    private fun t(id: String, explicit: Boolean = false, available: Boolean = true) =
        Track(
            id,
            "Track $id",
            listOf(ArtistRef("a", "Artist")),
            explicit = explicit,
            available = available,
        )

    private fun s(vararg tracks: Track, index: Int = 0) =
        MusicState(queue = tracks.toList(), index = index, owned = true)

    @Test
    fun realEndsHaveNoFabricatedCover() {
        val first = coverWindow(s(t("a"), t("b")))
        assertEquals(listOf(0, 1), first.entries.map { it.index })
        assertEquals(0, first.center)
        val last = coverWindow(s(t("a"), t("b"), index = 1))
        assertEquals(1, last.center)
        assertEquals(listOf(0, 1), last.entries.map { it.index })
    }

    @Test
    fun neighborsSkipBlockedAndUnavailableButKeepCurrentOccurrence() {
        val state =
            s(t("a"), t("x", explicit = true), t("b"), t("z", available = false), t("c"), index = 2)
                .copy(settings = Settings(blockExplicit = true))
        assertEquals(listOf(0, 2, 4), coverWindow(state).entries.map { it.index })
        assertEquals(listOf(0, 1, 2), coverWindow(state.copy(index = 1)).entries.map { it.index })
    }

    @Test
    fun duplicateTracksRetainQueueOccurrence() {
        val state = s(t("a"), t("b"), t("a"), index = 1)
        val window = coverWindow(state)
        assertTrue(acceptsCoverSelection(state, window, 0))
        assertTrue(acceptsCoverSelection(state, window, 2))
        assertEquals(2, window.entries[2].index)
        assertFalse(acceptsCoverSelection(state, window, window.center))
    }

    @Test
    fun externalStationNeverUsesSavedQueueAsNeighbors() {
        val state =
            s(t("a"), t("b")).copy(output = "station", owned = false, externalTrack = t("outside"))
        val window = coverWindow(state)
        assertEquals(listOf("outside"), window.entries.map { it.track.id })
        assertFalse(acceptsCoverSelection(state, window, 0))
    }

    @Test
    fun savedPhoneQueueStillHasNeighborsBeforeFirstPlay() {
        val state = s(t("a"), t("b")).copy(owned = false)
        assertEquals(2, coverWindow(state).entries.size)
        assertTrue(acceptsCoverSelection(state, coverWindow(state), 1))
    }

    @Test
    fun repeatAllUsesActualWrappedNeighbors() {
        val state = s(t("a"), t("b"), t("c")).copy(repeat = 2)
        val window = coverWindow(state)
        assertEquals(listOf(2, 0, 1), window.entries.map { it.index })
        assertTrue(acceptsCoverSelection(state, window, 0))
    }

    @Test
    fun queueFilterOutputOrCurrentChangeInvalidatesGesture() {
        val state = s(t("a"), t("b"), t("c"), index = 1)
        val window = coverWindow(state)
        assertFalse(acceptsCoverSelection(state.copy(index = 2), window, 2))
        assertFalse(acceptsCoverSelection(state.copy(output = "station"), window, 2))
        assertFalse(acceptsCoverSelection(state.copy(queue = state.queue.reversed()), window, 2))
        assertFalse(
            acceptsCoverSelection(state.copy(settings = Settings(blockExplicit = true)), window, 2)
        )
        assertTrue(acceptsCoverSelection(state.copy(positionMs = 8000, playing = true), window, 2))
    }
}
