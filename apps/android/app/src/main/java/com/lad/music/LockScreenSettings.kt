// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import org.json.JSONObject

enum class LockStyle(val key: String, val title: String, val description: String) {
    ATMOSPHERE("atmosphere", "Атмосфера", "Крупные часы, мягкий фон и стеклянный плеер."),
    AIR("air", "Воздух", "Тонкие часы, больше обложки и свободные кнопки."),
    MUSIC("music", "Только музыка", "Без часов и даты. Максимально крупная обложка.");
    companion object { fun from(key: String) = entries.find { it.key == key } ?: ATMOSPHERE }
}
enum class LockClock(val title: String) { LARGE("Крупные"), LIGHT("Тонкие"), COMPACT("Компактные") }
enum class LockTimeFormat(val title: String) { SYSTEM("Как в системе"), H24("24 часа"), H12("12 часов") }
enum class LockTone(val title: String) { SOFT("Мягкий"), DARK("Тёмный"), BLACK("Чёрный") }
enum class LockMotion(val title: String) { NORMAL("Плавно"), REDUCED("Растворение"), OFF("Без анимации") }

data class LockStyleOptions(
    val clock: LockClock = LockClock.LARGE,
    val format: LockTimeFormat = LockTimeFormat.SYSTEM,
    val seconds: Boolean = false,
    val date: Boolean = true,
    val tone: LockTone = LockTone.DARK,
    val motion: LockMotion = LockMotion.NORMAL,
) {
    fun json() = JSONObject().put("clock", clock.name).put("format", format.name)
        .put("seconds", seconds).put("date", date).put("tone", tone.name).put("motion", motion.name)
    companion object {
        fun defaults(style: LockStyle) = LockStyleOptions(clock = if (style == LockStyle.ATMOSPHERE) LockClock.LARGE else LockClock.LIGHT)
        fun from(j: JSONObject, style: LockStyle): LockStyleOptions {
            val d = defaults(style)
            return d.copy(
                clock = LockClock.entries.find { it.name == j.optString("clock") } ?: d.clock,
                format = LockTimeFormat.entries.find { it.name == j.optString("format") } ?: d.format,
                seconds = j.optBoolean("seconds", d.seconds), date = j.optBoolean("date", d.date),
                tone = LockTone.entries.find { it.name == j.optString("tone") } ?: d.tone,
                motion = LockMotion.entries.find { it.name == j.optString("motion") } ?: d.motion,
            )
        }
    }
}

data class LockScreenSettings(
    val enabled: Boolean = false,
    val automatic: Boolean = false,
    val style: LockStyle = LockStyle.ATMOSPHERE,
    val styles: Map<LockStyle, LockStyleOptions> = emptyMap(),
) {
    fun options(forStyle: LockStyle = style) = styles[forStyle] ?: LockStyleOptions.defaults(forStyle)
    fun withOptions(value: LockStyleOptions) = copy(styles = styles + (style to value))
    fun json(): JSONObject = JSONObject().put("enabled", enabled).put("automatic", automatic)
        .put("style", style.key).put("styles", JSONObject().apply {
            styles.forEach { (key, value) -> put(key.key, value.json()) }
        })
    companion object {
        fun parse(value: String?): LockScreenSettings = runCatching {
            val j = JSONObject(value ?: "{}")
            val entries = j.optJSONObject("styles") ?: JSONObject()
            LockScreenSettings(j.optBoolean("enabled"), j.optBoolean("automatic"), LockStyle.from(j.optString("style")),
                LockStyle.entries.mapNotNull { style -> entries.optJSONObject(style.key)?.let { style to LockStyleOptions.from(it, style) } }.toMap())
        }.getOrDefault(LockScreenSettings())
    }
}

/** Shared by the receiver and tests: never interrupt an unlocked phone, a call, or stopped playback. */
internal fun shouldShowLockPlayer(settings: LockScreenSettings, locked: Boolean, playing: Boolean,
    hasTrack: Boolean, overlayAllowed: Boolean, callActive: Boolean, dismissed: Boolean): Boolean =
    settings.enabled && settings.automatic && locked && playing && hasTrack && overlayAllowed && !callActive && !dismissed
