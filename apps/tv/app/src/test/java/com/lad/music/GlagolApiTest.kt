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
                iotBase = server.url("/").toString().trimEnd('/'),
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
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals("midi", api.devices().single().id)
        assertEquals(3, server.requestCount)
        server.takeRequest() // connection closed before the first request headers arrived
        assertEquals("OAuth fixture-token", server.takeRequest().getHeader("Authorization"))
        val metadata = server.takeRequest()
        assertEquals("GET", metadata.method)
        assertEquals("/v1.0/user/info", metadata.path)
        assertEquals("Bearer fixture-token", metadata.getHeader("Authorization"))
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
    fun namesAndRoomsAreOptionalReadOnlyMetadataAndRedirectsNeverReceiveTheToken() = runBlocking {
        fun glagol() = MockResponse().setBody("""{"devices":[{"id":"a","name":"Яндекс Станция","glagol":{"security":{"server_certificate":"cert"}}},{"id":"b","name":"Яндекс Станция","glagol":{"security":{"server_certificate":"cert"}}}]}""")
        server.enqueue(glagol())
        server.enqueue(MockResponse().setBody("""{"status":"ok","rooms":[{"id":"k","name":"Кухня","household_id":"h"},{"id":"b","name":"Спальня","household_id":"h"}],"households":[{"id":"h","name":"Дом"}],"devices":[{"id":"cloud-b","name":"Яндекс Станция","household_id":"h","room":"b","quasar_info":{"device_id":"b"}},{"id":"cloud-a","name":"Яндекс Станция","household_id":"h","room":"k","quasar_info":{"device_id":"a"}}]}"""))
        val devices = api.devices()
        assertEquals("Кухня", devices[0].displayTitle)
        assertEquals("Спальня", devices[1].displayTitle)
        assertFalse(deviceIdentityAmbiguous(devices[0], devices))
        repeat(2) { assertEquals("GET", server.takeRequest().method) }
        val other = MockWebServer().also { it.start() }
        try {
            server.enqueue(glagol())
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/no-credentials")))
            val fallback = api.devices()
            assertTrue(fallback.all { it.room.isBlank() && it.metadataStatus == "unavailable" })
            assertTrue(deviceIdentityAmbiguous(fallback[0], fallback))
            assertEquals(0, other.requestCount)
        } finally { other.shutdown() }
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
