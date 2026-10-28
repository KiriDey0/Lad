// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

/** Shared by the UI and JVM navigation tests. No touch gestures required. */
object TvKeyboard {
    fun rows(layout: String): List<List<String>> =
        when (layout) {
            "EN" -> listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM").map { it.map(Char::toString) }
            "123" -> listOf("1234567890", "-/:;()&@\"", ".,?!'+=_%").map { it.map(Char::toString) }
            else ->
                listOf("ЙЦУКЕНГШЩЗХЪ", "ФЫВАПРОЛДЖЭ", "ЯЧСМИТЬБЮЁ").map { it.map(Char::toString) }
        }

    fun edit(query: String, key: String): String =
        when (key) {
            "⌫" -> query.dropLast(1)
            "Пробел" -> (query + " ").take(200)
            else -> (query + key.lowercase()).take(200)
        }
}

data class TvPlace(
    val page: String,
    val focus: String,
    val scroll: Map<String, Int> = emptyMap(),
    val card: Card? = null,
    val catalog: Catalog = Catalog(),
    val tab: String = "Плейлисты",
    val query: String = "",
)

class TvHistory {
    private val places = mutableListOf<TvPlace>()

    fun push(place: TvPlace) {
        places.add(place)
    }

    fun pop(): TvPlace? = if (places.isEmpty()) null else places.removeAt(places.lastIndex)

    fun clear() = places.clear()
}

/** Up/down preview never mutates the actual queue; Back can always cancel. */
class QueueMove(val from: Int, val current: Int, val count: Int) {
    var target = from
        private set

    fun step(direction: Int) {
        var next = (target + direction).coerceIn(0, count - 1)
        if (next == current) next = (next + direction).coerceIn(0, count - 1)
        if (next != current) target = next
    }
}
