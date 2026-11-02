// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import org.junit.Assert.*
import org.junit.Test

class LocalTransferReadTest {
    @Test fun noRequestDoesNotClaimBrokenWifi() {
        assertEquals("Станция не запросила аудиофайл у телефона.", LocalTransferRead().describe())
    }

    @Test fun httpFailureIsNotReportedAsSuccessfulTransfer() {
        val read = LocalTransferRead().apply { requested = true; status = 404 }
        assertEquals("При запросе файла телефон вернул HTTP 404.", read.describe())
    }

    @Test fun headRequestDoesNotClaimAudioWasSent() {
        val read = LocalTransferRead().apply { requested = true; status = 200 }
        assertEquals("Станция запросила файл, но аудиоданные ещё не переданы.", read.describe())
    }

    @Test fun bodyBytesAreReportedSeparatelyFromRequestReceipt() {
        val read = LocalTransferRead().apply { requested = true; status = 206; bytes.addAndGet(4096) }
        assertEquals("Станция запросила файл; телефон передал 4096 байт аудио.", read.describe())
    }

    @Test fun readFailureSurvivesPartialTransfer() {
        val read = LocalTransferRead().apply { requested = true; status = 206; bytes.addAndGet(4096); failure = "IOException" }
        assertEquals("Ошибка чтения локального потока: IOException.", read.describe())
    }
}
