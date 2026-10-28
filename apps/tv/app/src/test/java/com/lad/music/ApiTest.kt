// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*

class ApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: YandexApi

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        api = YandexApi({ "test-token" }, server.url("/").toString().trimEnd('/'))
    }

    @After
    fun close() {
        server.shutdown()
    }

    @Test
    fun catalogGetsTokenButExternalFilesDoNot() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"result":{"account":{"uid":42,"displayName":"Tester"},"plus":{"hasPlus":true}}}"""
                )
        )
        val profile = api.profile()
        assertEquals("42", profile.uid)
        assertTrue(profile.plus)
        assertEquals("OAuth test-token", server.takeRequest().getHeader("Authorization"))
        server.enqueue(MockResponse().setBody("public lyrics"))
        api.raw(server.url("/public").toString())
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun albumLikeUsesAlbumEndpointAndChecksAcknowledgment() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":"ok"}"""))
        api.like("42", "123", "album", true)
        val request = server.takeRequest()
        assertEquals("/users/42/likes/albums/add-multiple", request.path)
        assertEquals("album-ids=123", request.body.readUtf8())
        server.enqueue(MockResponse().setBody("""{"result":"no"}"""))
        assertTrue(runCatching { api.like("42", "123", "album", false) }.isFailure)
    }

    @Test
    fun likedTracksKeepServerOrderWhenBatchResponseIsReversed() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"result":{"library":{"tracks":[{"id":1},{"id":2}]}}}""")
        )
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"result":[{"id":2,"title":"B","artists":[]},{"id":1,"title":"A","artists":[]}]}"""
                )
        )
        val liked = api.liked("42", limit = 2)
        assertEquals(listOf("1", "2"), liked.tracks.map { it.id })
        assertTrue(liked.tracks.all { it.liked })
        assertEquals(2, liked.total)
    }

    @Test
    fun waveCursorAndZeroSecondFilteredSkipAreSentToYandex() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result":{"sequence":[],"batchId":"b2"}}"""))
        api.wave("123")
        assertEquals("123", server.takeRequest().requestUrl!!.queryParameter("queue"))
        server.enqueue(MockResponse().setBody("""{"result":"ok"}"""))
        api.radioFeedback("skip", Track("123", "E", emptyList(), explicit = true), "b2", 0)
        val request = server.takeRequest()
        assertEquals("b2", request.requestUrl!!.queryParameter("batch-id"))
        val payload = org.json.JSONObject(request.body.readUtf8())
        assertTrue(payload.has("totalPlayedSeconds"))
        assertEquals(0, payload.getLong("totalPlayedSeconds"))
    }
}
