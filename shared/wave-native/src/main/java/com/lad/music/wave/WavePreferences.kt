// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music.wave

import org.json.JSONObject

data class WaveChoice(val value: String, val name: String)

data class WaveGroup(val key: String, val name: String, val values: List<WaveChoice>)

data class WavePreferences(val groups: List<WaveGroup>, val selected: Map<String, String>) {
    fun validate() {
        check(
            groups.isNotEmpty() && groups.all { g -> g.values.any { it.value == selected[g.key] } }
        ) {
            "Выбери доступные настройки волны"
        }
        check(
            listOf("diversity", "mood_energy", "language").all { !selected[it].isNullOrBlank() }
        ) {
            "Яндекс не вернул полный набор настроек волны"
        }
    }

    companion object {
        fun parse(info: JSONObject): WavePreferences {
            val restrictions =
                info.optJSONObject("station")?.optJSONObject("restrictions2") ?: JSONObject()
            val settings = info.optJSONObject("settings2") ?: JSONObject()
            val selected = mutableMapOf<String, String>()
            val groups =
                listOf(
                        "diversity" to "Характер волны",
                        "mood_energy" to "Настроение",
                        "language" to "Язык",
                    )
                    .mapNotNull { (key, name) ->
                        val apiKey = if (key == "mood_energy") "moodEnergy" else key
                        selected[key] = settings.optString(apiKey, "")
                        val values =
                            restrictions.optJSONObject(apiKey)?.optJSONArray("possibleValues")
                        val choices =
                            (0 until (values?.length() ?: 0))
                                .mapNotNull { i ->
                                    values?.optJSONObject(i)?.let {
                                        WaveChoice(it.optString("value"), it.optString("name"))
                                    }
                                }
                                .filter { it.value.isNotBlank() }
                        if (choices.isEmpty()) null else WaveGroup(key, name, choices)
                    }
            check(groups.isNotEmpty()) { "Настройки волны сейчас недоступны у Яндекса" }
            return WavePreferences(groups, selected)
        }
    }
}
