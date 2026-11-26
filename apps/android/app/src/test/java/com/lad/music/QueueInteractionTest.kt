// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import org.junit.Assert.*
import org.junit.Test

class QueueInteractionTest {
    private fun track(id: String) = Track(id, id, emptyList())
    @Test fun duplicateSongObjectsHaveDistinctOccurrenceIdentities() {
        val d = track("D")
        val queue = queueEntries(listOf(track("A"), d, track("F"), track("A"), d))
        assertEquals(5, queue.map { it.queueEntryId }.toSet().size)
        val last = queue.last().queueEntryId
        val moved = moveQueue(queue, 0, 1, 2)
        val selected = queueEntryIndex(moved.first, last)
        assertEquals(4, selected)
        val removed = moved.first.filterIndexed { i, _ -> i != selected }
        assertEquals(listOf("A", "F", "D", "A"), removed.map { it.id })
        assertEquals(queue[1].queueEntryId, removed[2].queueEntryId)
        assertEquals("", d.queueEntryId)
    }
    @Test fun identitySurvivesMetadataRefreshPersistenceAndShuffle() {
        val q = queueEntries(listOf(track("A"), track("D"), track("D")))
        val refreshed = q.map { it.copy(liked = true) }
        assertEquals(q.map { it.queueEntryId }, queueEntries(refreshed).map { it.queueEntryId })
        val restored = refreshed.map { Track.fromJson(it.json()) }
        assertEquals(refreshed, queueEntries(restored))
        val moved = moveQueue(restored, 2, 0, 2)
        assertEquals(q[2].queueEntryId, moved.first[moved.second].queueEntryId)
        assertEquals(-1, queueEntryIndex(q, "removed"))
    }
    @Test fun addingTheSameOccurrenceAgainGetsAFreshId() {
        val q = queueEntries(listOf(track("A")))
        val appended = queueEntries(q + q[0])
        assertEquals(q[0].queueEntryId, appended[0].queueEntryId)
        assertNotEquals(appended[0].queueEntryId, appended[1].queueEntryId)
    }
    @Test fun insertingNextCannotStealTheIdentityOfALaterOccurrence() {
        val q = queueEntries(listOf(track("A"), track("D"), track("F"), track("D")))
        val selected = q.last().queueEntryId
        val inserted = insertQueueEntries(q, listOf(q.last(), q.last()), 1)
        assertEquals(listOf("A", "D", "D", "D", "F", "D"), inserted.map { it.id })
        assertEquals(q.map { it.queueEntryId }, (listOf(inserted[0]) + inserted.drop(3)).map { it.queueEntryId })
        assertEquals(6, inserted.map { it.queueEntryId }.toSet().size)
        assertEquals(5, queueEntryIndex(inserted, selected))
        val removed = inserted.filter { it.queueEntryId != selected }
        assertEquals(5, removed.size)
        assertEquals(3, removed.count { it.id == "D" })
    }
    @Test fun unshuffleDoesNotExchangeDuplicateOccurrences() {
        val q = queueEntries(listOf(track("A"), track("D"), track("F"), track("A"), track("D")))
        val current = listOf(q[4], q[3], q[2], q[1], q[0])
        val restored = restoreUnshuffledQueue(current, 1, q)
        assertEquals(listOf(q[4], q[3], q[0], q[1], q[2]), restored)
    }
    @Test fun edgeSpeedIsContinuousAndIndependentOfPointerMoveEvents() {
        assertEquals(0f, queueEdgeSpeed(300f, 600f, 64f, 800f))
        assertEquals(-800f, queueEdgeSpeed(0f, 600f, 64f, 800f))
        assertEquals(800f, queueEdgeSpeed(600f, 600f, 64f, 800f))
        assertEquals(-400f, queueEdgeSpeed(32f, 600f, 64f, 800f))
        assertEquals(400f, queueEdgeSpeed(568f, 600f, 64f, 800f))
        assertEquals(800f, queueEdgeSpeed(900f, 600f, 64f, 800f))
    }
}
