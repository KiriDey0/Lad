// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class StationTransferErrorTest {
    @Test fun missingSystemMessageRetainsTypeStageAndCause() {
        val original = IllegalStateException()
        val error = stationTransferError("подготовка аудиофайла", original)
        assertEquals("Не удалось передать музыку (подготовка аудиофайла): IllegalStateException", error.message)
        assertSame(original, error.cause)
    }

    @Test fun blankAndLiteralNullMessagesHaveUsefulFallbacks() {
        for (message in listOf("", "  ", "null")) {
            val error = stationTransferError("открытие локального потока", java.io.IOException(message))
            assertTrue(error.message!!.endsWith(": IOException"))
        }
    }

    @Test fun preciseInnerStageSurvivesTransferRollbackWrapper() {
        val original = java.io.IOException("Нет доступа к папке")
        val prepared = stationTransferError("чтение локального файла", original)
        val outer = stationTransferError("запуск на Станции", prepared)
        assertSame(prepared, outer)
        assertSame(original, outer.cause)
        assertTrue(outer.message!!.contains("Нет доступа к папке"))
    }

    @Test fun cancelledOperationDoesNotBecomeTransferError() {
        val cancelled = CancellationException("Screen closed")
        try {
            stationTransferError("подключение к Станции", cancelled)
            fail("Cancellation must propagate")
        } catch (error: CancellationException) {
            assertSame(cancelled, error)
        }
    }

    @Test fun timeoutReportsNetworkHintAndRetainsCause() = runBlocking {
        try {
            withTimeout(1) { delay(1000) }
            fail("Expected timeout")
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            val error = stationTransferError("запуск на Станции", timeout)
            assertSame(timeout, error.cause)
            assertTrue(error.message!!.contains("Проверь локальную сеть"))
        }
    }
}
