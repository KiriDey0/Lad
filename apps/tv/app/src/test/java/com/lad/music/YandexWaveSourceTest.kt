// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class YandexWaveSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var api: YandexApi
    @Before fun setup() {
        server = MockWebServer()
        server.start()
        api = YandexApi({ "test-token" }, server.url("/").toString().trimEnd('/'))
    }
    @After fun close() { server.shutdown() }
    private fun response(id: Int, session: String = "") = MockResponse().setBody(
        """{"result":{"radioSessionId":"$session","batchId":"b$id","sequence":[{"track":{"id":$id,"title":"Track","artists":[]}}]}}""")
    private fun settings() = MockResponse().setBody("""{"result":[{
        "settings2":{"diversity":"favorite","moodEnergy":"calm","language":"russian"},
        "station":{"restrictions2":{
            "diversity":{"possibleValues":[{"value":"favorite","serializedSeed":"settingDiversity:favorite"}]},
            "moodEnergy":{"possibleValues":[{"value":"calm","serializedSeed":"settingMoodEnergy:calm"}]},
            "language":{"possibleValues":[{"value":"russian","serializedSeed":"settingLanguage:russian"}]}
        }}
    }]}""")
    private fun readSettingsRequest() {
        assertEquals("/rotor/station/user:onyourwave/info", server.takeRequest().path)
    }

    @Test fun safeRequestUsesServerFilterAndSessionQueueAndFeedback() = runBlocking {
        val source = YandexWaveSource(api) { true }
        server.enqueue(settings())
        server.enqueue(response(1, "safe"))
        source.fetch("old")
        readSettingsRequest()
        val first = server.takeRequest()
        assertEquals("/rotor/session/new", first.path)
        val body = JSONObject(first.body.readUtf8())
        assertFalse(body.getBoolean("allowExplicit"))
        assertEquals("user:onyourwave", body.getJSONArray("seeds").getString(0))
        assertEquals(listOf("user:onyourwave", "settingDiversity:favorite", "settingMoodEnergy:calm", "settingLanguage:russian"),
            body.getJSONArray("seeds").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals("old", body.getJSONArray("queue").getString(0))
        assertFalse(body.has("child"))
        server.enqueue(response(2))
        source.fetch("1")
        val next = server.takeRequest()
        assertEquals("/rotor/session/safe/tracks", next.path)
        assertEquals(listOf("old", "1"), JSONObject(next.body.readUtf8()).getJSONArray("queue").let { a -> (0 until a.length()).map { a.getString(it) } })
        server.enqueue(MockResponse().setBody("""{"result":{}}"""))
        source.report("skip", Track("1", "Track", emptyList()), "b1", 0)
        val report = server.takeRequest()
        assertEquals("/rotor/session/safe/feedback", report.path)
        val event = JSONObject(report.body.readUtf8()).getJSONObject("event")
        assertEquals("skip", event.getString("type"))
        assertEquals(0, event.getLong("totalPlayedSeconds"))
    }

    @Test fun unavailableSessionFallsBackOnNextAttemptAndStopsAtTwentyBatchRequests() = runBlocking {
        val context = api.waveSession { true }
        server.enqueue(settings())
        server.enqueue(MockResponse().setResponseCode(404))
        repeat(19) { server.enqueue(MockResponse().setBody("""{"result":{"batchId":"legacy","sequence":[]}}""")) }
        assertTrue(context.replenish(emptyList(), "old", true).isEmpty())
        assertEquals(21, server.requestCount) // One preference read, twenty batch requests.
        readSettingsRequest()
        assertEquals("/rotor/session/new", server.takeRequest().path)
        repeat(19) { assertEquals("/rotor/station/user:onyourwave/tracks?queue=old", server.takeRequest().path) }
    }

    @Test fun expiredLoginDoesNotFallBackOrKeepRetrying() = runBlocking {
        val context = api.waveSession { true }
        server.enqueue(MockResponse().setResponseCode(401))
        val failure = runCatching { context.replenish(emptyList(), "", true) }.exceptionOrNull()
        assertTrue(failure is ApiError && failure.code == 401)
        assertEquals(1, server.requestCount)
    }

    @Test fun changingFilterRecreatesSessionAndUnfilteredWaveKeepsLegacyEndpoint() = runBlocking {
        var block = true
        val source = YandexWaveSource(api) { block }
        server.enqueue(settings())
        server.enqueue(response(1, "first")); source.fetch(""); readSettingsRequest(); server.takeRequest()
        block = false
        server.enqueue(response(2)); source.fetch("1")
        assertEquals("/rotor/station/user:onyourwave/tracks?queue=1", server.takeRequest().path)
        block = true
        server.enqueue(settings())
        server.enqueue(response(3, "second")); source.fetch("2")
        readSettingsRequest()
        val request = server.takeRequest()
        assertEquals("/rotor/session/new", request.path)
        assertFalse(JSONObject(request.body.readUtf8()).getBoolean("allowExplicit"))
    }

    @Test fun unknownSessionIsRecreatedWithFilterAndHistory() = runBlocking {
        val source = YandexWaveSource(api) { true }
        server.enqueue(settings())
        server.enqueue(response(1, "expired")); source.fetch(""); readSettingsRequest(); server.takeRequest()
        server.enqueue(MockResponse().setBody("""{"result":{"unknownSession":true}}"""))
        assertTrue(source.fetch("1").first.isEmpty()); server.takeRequest()
        server.enqueue(response(2, "renewed")); source.fetch("1")
        val request = server.takeRequest()
        assertEquals("/rotor/session/new", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertFalse(body.getBoolean("allowExplicit"))
        assertEquals("1", body.getJSONArray("queue").getString(0))
    }

    @Test fun missingPreferenceSeedsKeepsLegacyWaveInsteadOfResettingPreferences() = runBlocking {
        val source = YandexWaveSource(api) { true }
        server.enqueue(MockResponse().setBody("""{"result":[{"settings2":{},"station":{}}]}"""))
        server.enqueue(response(1))
        source.fetch("old")
        readSettingsRequest()
        assertEquals("/rotor/station/user:onyourwave/tracks?queue=old", server.takeRequest().path)
    }
}
