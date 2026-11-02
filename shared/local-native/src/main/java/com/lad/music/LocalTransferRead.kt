// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import java.util.concurrent.atomic.AtomicLong

/** Per-attempt facts only: no URLs, access tokens, paths or audio metadata. */
internal class LocalTransferRead {
    @Volatile var requested = false
    @Volatile var status = 0
    @Volatile var failure = ""
    val bytes = AtomicLong()

    fun describe(): String = when {
        failure.isNotBlank() -> "Ошибка чтения локального потока: $failure."
        !requested -> "Станция не запросила аудиофайл у телефона."
        status >= 400 -> "При запросе файла телефон вернул HTTP $status."
        bytes.get() > 0 -> "Станция запросила файл; телефон передал ${bytes.get()} байт аудио."
        else -> "Станция запросила файл, но аудиоданные ещё не переданы."
    }
}
