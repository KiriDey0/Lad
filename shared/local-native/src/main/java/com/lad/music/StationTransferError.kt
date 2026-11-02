// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

internal class StationTransferError(stage: String, cause: Exception) :
    IllegalStateException("Не удалось передать музыку ($stage): ${transferDetail(cause)}", cause)

private fun transferDetail(cause: Exception): String = when (cause) {
    is TimeoutCancellationException -> "Истекло время ожидания ответа Станции. Проверь локальную сеть."
    else -> cause.message?.takeIf { it.isNotBlank() && it != "null" }
        ?: cause.javaClass.simpleName.ifBlank { "Неизвестная ошибка" }
}

internal fun stationTransferError(stage: String, cause: Exception): StationTransferError {
    // A cancelled screen/operation must stay cancelled, rather than becoming a UI error.
    if (cause is CancellationException && cause !is TimeoutCancellationException) throw cause
    return cause as? StationTransferError ?: StationTransferError(stage, cause)
}
