// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GlagolApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: YandexApi

    @Before
    fun setup() {
        server = MockWebServer().also { it.start() }
        api =
            YandexApi(
                { "fixture-token" },
                http = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
                quasarBase =
                    server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/'),
            )
    }

    @After fun close() = server.shutdown()

    @Test
    fun retriesTransientDisconnectForDeviceList() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"devices":[{"id":"midi","name":"Миди","platform":"cucumber","glagol":{"security":{"server_certificate":"fixture-certificate"}}}]}"""
                )
        )
        assertEquals("midi", api.devices().single().id)
        assertEquals(2, server.requestCount)
        server.takeRequest() // connection closed before the first request headers arrived
        assertEquals("OAuth fixture-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun authenticationFailureIsNotRetried() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            api.devices()
            fail("Must report expired session")
        } catch (e: ApiError) {
            assertEquals(401, e.code)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun tokenRequestUsesAccountDeviceIdentity() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"token":"fixture-conversation"}"""))
        assertEquals(
            "fixture-conversation",
            api.stationToken(Device("midi", "Миди", "cucumber", "certificate")),
        )
        val request = server.takeRequest()
        assertEquals("midi", request.requestUrl!!.queryParameter("device_id"))
        assertEquals("cucumber", request.requestUrl!!.queryParameter("platform"))
    }
}
