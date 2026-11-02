// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import org.junit.Assert.*
import org.junit.Test

class LocalAudioRequestTest {
    private val token = "fixture-token"
    private val id = "local-track-0123456789abcdef0123456789abcdef"

    @Test fun stationQueryDoesNotBecomePartOfTrackId() {
        // Request shape captured from a real Station Midi fetching a synthetic WAV.
        assertEquals(id, localAudioRequestId("/$token/$id?vsid=station-session", token))
        assertEquals(id, localAudioRequestId("/$token/$id?vsid=station-session&extra=1", token))
    }

    @Test fun ordinaryRequestStillResolves() {
        assertEquals(id, localAudioRequestId("/$token/$id", token))
        assertEquals(id, localAudioRequestId("/$token/$id?", token))
    }

    @Test fun queryCannotSubstituteAuthenticationOrPath() {
        assertNull(localAudioRequestId("/wrong/$id?token=$token", token))
        assertNull(localAudioRequestId("/?path=/$token/$id", token))
        assertNull(localAudioRequestId("/$token/$id", ""))
    }

    @Test fun invalidIdsAndTraversalRemainRejected() {
        for (asset in listOf("../$id", "$id/extra", "$id.wav", "${id}0", "local-track-short", "$id%3Fvsid=1", "%2e%2e%2f$id")) {
            assertNull(asset, localAudioRequestId("/$token/$asset?vsid=station-session", token))
        }
    }
}
