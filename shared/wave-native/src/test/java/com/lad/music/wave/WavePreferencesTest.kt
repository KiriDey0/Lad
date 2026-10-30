// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music.wave

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WavePreferencesTest {
    @Test
    fun importsOnlyValuesReturnedByYandexAndRejectsUnknownChoices() {
        val info =
            JSONObject(
                """{"station":{"restrictions2":{"diversity":{"possibleValues":[{"value":"discover","name":"Незнакомая"}]},"language":{"possibleValues":[{"value":"any","name":"Любой"}]},"moodEnergy":{"possibleValues":[{"value":"all","name":"Любое"}]}}},"settings2":{"diversity":"discover","moodEnergy":"all","language":"any"}}"""
            )
        val p = WavePreferences.parse(info)
        p.validate()
        assertEquals(3, p.groups.size)
        assertEquals("Незнакомая", p.groups[0].values[0].name)
        assertTrue(
            runCatching { p.copy(selected = p.selected + ("diversity" to "invented")).validate() }
                .isFailure
        )
        assertTrue(runCatching { WavePreferences.parse(JSONObject()) }.isFailure)
    }
}
