// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LogicTest {
    private fun track(id: String, explicit: Boolean = false) =
        Track(
            id,
            "Track $id",
            listOf(ArtistRef("a", "Artist")),
            durationMs = 120000,
            explicit = explicit,
        )

    @Test
    fun timedLyricsKeepFractionsAndMultipleTimestamps() {
        val result = parseLyrics("[00:01.2][00:02.345]Строка\n[01:03]Другая")
        assertTrue(result.synced)
        assertEquals(listOf(1200L, 2345L, 63000L), result.lines.map { it.timeMs })
        assertEquals("Строка", result.lines[1].text)
    }

    @Test
    fun plainLyricsNeverInventTimings() {
        val lyrics = parseLyrics("Обычный текст\nВторая строка")
        assertFalse(lyrics.synced)
        assertTrue(lyrics.lines.all { it.timeMs == null })
    }

    @Test
    fun repeatAllWithOnlyExplicitTracksTerminates() {
        assertNull(nextPlayable(listOf(track("a", true), track("b", true)), 0, 1, 2, true))
    }

    @Test
    fun explicitFilterIsIndependentAndBounded() {
        val queue = listOf(track("a"), track("b", true), track("c"))
        assertEquals(2, nextPlayable(queue, 0, 1, 0, true))
        assertEquals(1, nextPlayable(queue, 0, 1, 0, false))
        assertEquals(0, nextPlayable(queue, 2, 1, 2, true))
        assertNull(nextPlayable(queue, 2, 1, 0, false))
    }

    @Test
    fun queueMoveKeepsExactPlayingOccurrenceEvenWithDuplicateIds() {
        val queue = listOf(track("a"), track("b"), track("a"))
        val moved = moveQueue(queue, 2, 0, 2)
        assertEquals(listOf("b", "a", "a"), moved.first.map { it.id })
        assertEquals(1, moved.second)
    }

    @Test
    fun yandexExplicitAndArtistIdsArePreserved() {
        val t =
            Track.fromApi(
                JSONObject(
                    """{"id":12,"title":"Test","durationMs":8000,"contentWarning":"explicit","artists":[{"id":1,"name":"One"},{"id":2,"name":"Two"}],"albums":[{"id":5,"title":"Album"}]}"""
                )
            )
        assertTrue(t.explicit)
        assertEquals("2", t.artists[1].id)
        assertEquals("5", t.albumId)
        assertEquals(t, Track.fromJson(t.json()))
    }

    @Test
    fun stationAddressRequiresPrivateNumericIp() {
        assertEquals("192.168.50.25", localIp("192.168.50.25"))
        assertEquals("fd00::1", localIp("fd00::1"))
        for (value in
            listOf("127.0.0.1", "8.8.8.8", "0.0.0.0", "224.0.0.1", "example.com", "face")) {
            assertTrue(value, runCatching { localIp(value) }.isFailure)
        }
    }
}
