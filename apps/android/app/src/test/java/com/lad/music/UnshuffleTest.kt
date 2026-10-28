// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music
import org.junit.Assert.*
import org.junit.Test
class UnshuffleTest {
    private fun t(id: String, liked: Boolean = false) = Track(id, id, emptyList(), liked = liked)
    @Test fun duplicateCurrentOccurrenceStaysInPlace() {
        val original = listOf(t("a"), t("b"), t("a"), t("c"), t("d"))
        val current = listOf(t("a"), t("c"), t("a"), t("d"), t("b"))
        val restored = restoreUnshuffledQueue(current, 2, original)
        assertEquals(listOf("a", "c", "a", "b", "d"), restored.map { it.id })
        assertEquals("a", restored[2].id)
    }
    @Test fun restoresOrderWithoutLosingLikes() {
        val original = listOf(t("a"), t("b"), t("c"))
        val current = listOf(t("a", true), t("c", true), t("b", true))
        val restored = restoreUnshuffledQueue(current, 0, original)
        assertEquals(listOf("a", "b", "c"), restored.map { it.id })
        assertTrue(restored.all { it.liked })
    }
    @Test fun noSelectionRestoresEntireQueue() {
        val original = listOf(t("a"), t("b"))
        assertEquals(original, restoreUnshuffledQueue(original.reversed(), -1, original))
    }
}
