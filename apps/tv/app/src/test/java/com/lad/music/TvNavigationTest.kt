// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import org.junit.Assert.*
import org.junit.Test

class TvNavigationTest {
    @Test
    fun russianKeyboardIsQwertyAndHasEveryLetter() {
        val rows = TvKeyboard.rows("RU")
        assertEquals("ЙЦУКЕНГШЩЗХЪ", rows[0].joinToString(""))
        assertEquals("ФЫВАПРОЛДЖЭ", rows[1].joinToString(""))
        assertEquals(33, rows.flatten().distinct().size)
        assertTrue(rows.flatten().containsAll(listOf("Ё", "Ъ", "Ь")))
    }

    @Test
    fun englishKeyboardIsQwerty() {
        val rows = TvKeyboard.rows("EN")
        assertEquals("QWERTYUIOP", rows[0].joinToString(""))
        assertEquals(26, rows.flatten().distinct().size)
    }

    @Test
    fun keyboardEditsAndDeletesSafely() {
        assertEquals("йцу кен", TvKeyboard.edit(TvKeyboard.edit("йцу", "Пробел"), "КЕН"))
        assertEquals("", TvKeyboard.edit("", "⌫"))
        assertEquals("йц", TvKeyboard.edit("йцу", "⌫"))
        assertEquals(200, TvKeyboard.edit("а".repeat(200), "Б").length)
    }

    @Test
    fun backRestoresExactFocusAndScroll() {
        val h = TvHistory()
        h.push(TvPlace("library", "lib-4-2", mapOf("library" to 840)))
        h.push(TvPlace("collection", "track-12"))
        assertEquals("track-12", h.pop()!!.focus)
        val p = h.pop()!!
        assertEquals(840, p.scroll["library"])
        assertEquals("lib-4-2", p.focus)
        assertNull(h.pop())
    }

    @Test
    fun reorderDoesNotChangeQueueUntilApplied() {
        val move = QueueMove(3, 0, 5)
        move.step(-1)
        move.step(-1)
        move.step(-1)
        assertEquals(1, move.target)
        move.step(1)
        assertEquals(2, move.target)
        assertEquals(3, move.from)
    }

    @Test
    fun reorderClampsAtBothEnds() {
        val m = QueueMove(1, 0, 3)
        repeat(5) { m.step(1) }
        assertEquals(2, m.target)
        repeat(5) { m.step(-1) }
        assertEquals(1, m.target)
    }
}
