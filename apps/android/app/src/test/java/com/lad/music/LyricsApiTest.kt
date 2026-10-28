// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.*
import org.junit.Assert.*

class LyricsApiTest {
    private lateinit var server: MockWebServer
    private lateinit var cdn: MockWebServer
    private lateinit var api: YandexApi

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls =
            HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        cdn =
            MockWebServer().apply {
                useHttps(serverTls.sslSocketFactory(), false)
                start()
            }
        api =
            YandexApi(
                { "test-token" },
                server.url("/").toString().trimEnd('/'),
                OkHttpClient.Builder()
                    .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                    .build(),
            )
    }

    @After
    fun close() {
        server.shutdown()
        cdn.shutdown()
    }

    private fun metadata() =
        MockResponse().setBody("""{"result":{"downloadUrl":"${cdn.url("/lyrics")}"}}""")

    @Test
    fun timedLyricsUseSignedClientRequestAndDownloadWithoutCredentials() = runBlocking {
        server.enqueue(metadata())
        cdn.enqueue(MockResponse().setBody("[00:01.20]First\n[00:02.30]Second"))
        val lyrics = api.lyrics("123:456")
        assertTrue(lyrics.synced)
        assertEquals(listOf(1200L, 2300L), lyrics.lines.map { it.timeMs })
        val request = server.takeRequest()
        assertEquals("/tracks/123/lyrics", request.requestUrl!!.encodedPath)
        assertEquals("LRC", request.requestUrl!!.queryParameter("format"))
        assertEquals(44, request.requestUrl!!.queryParameter("sign")!!.length)
        assertTrue(request.requestUrl!!.queryParameter("timeStamp")!!.toLong() > 0)
        assertEquals(YandexApi.MUSIC_CLIENT, request.getHeader("X-Yandex-Music-Client"))
        assertEquals("OAuth test-token", request.getHeader("Authorization"))
        val file = cdn.takeRequest()
        assertNull(file.getHeader("Authorization"))
        assertNull(file.getHeader("X-Yandex-Music-Client"))
    }

    @Test
    fun plainTextStillLoadsWhenTimedFormatIsDenied() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(metadata())
        cdn.enqueue(MockResponse().setBody("First\nSecond"))
        val lyrics = api.lyrics("123")
        assertFalse(lyrics.synced)
        assertEquals(listOf("First", "Second"), lyrics.lines.map { it.text })
        assertTrue(lyrics.lines.all { it.timeMs == null })
        assertEquals("LRC", server.takeRequest().requestUrl!!.queryParameter("format"))
        assertEquals("TEXT", server.takeRequest().requestUrl!!.queryParameter("format"))
    }

    @Test
    fun forbiddenResponseIsAnErrorRatherThanMissingLyrics() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(403)) }
        val error = runCatching { api.lyrics("123") }.exceptionOrNull()
        assertTrue(error is ApiError)
        assertEquals(403, (error as ApiError).code)
    }

    @Test
    fun missingBothFormatsReturnsEmptyLyrics() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(404)) }
        assertTrue(api.lyrics("123").lines.isEmpty())
    }
}
