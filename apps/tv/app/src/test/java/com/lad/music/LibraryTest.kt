// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*

class LibraryTest {
    private lateinit var server: MockWebServer
    private lateinit var api: YandexApi
    private var failArtists = false
    private var failOwn = false

    @Before
    fun setup() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    if (
                        failArtists && path.endsWith("likes/artists") ||
                            failOwn && path.endsWith("playlists/list")
                    )
                        return MockResponse().setResponseCode(503).setBody("{}")
                    val body =
                        when {
                            path.endsWith("playlists/list") ->
                                """[{"kind":1,"title":"Owned","owner":{"uid":42,"login":"owner"},"trackCount":7}]"""
                            path.endsWith("likes/playlists") ->
                                """[{"playlist":{"kind":1,"title":"Other","owner":{"uid":99},"trackCount":12}},{"playlist":{"kind":1,"title":"Owned","owner":{"uid":42},"trackCount":7}}]"""
                            path.endsWith("likes/albums") ->
                                """[{"album":{"id":5,"title":"Album","artists":[]}}]"""
                            path.endsWith("likes/artists") ->
                                """[{"artist":{"id":7,"name":"One","cover":{"uri":"avatars.yandex.net/artist/%%"}}},{"id":8,"name":"Two"}]"""
                            else -> return MockResponse().setResponseCode(404)
                        }
                    return MockResponse().setBody("""{"result":$body}""")
                }
            }
        server.start()
        api = YandexApi({ "test-token" }, server.url("/").toString().trimEnd('/'))
    }

    @After
    fun close() {
        server.shutdown()
    }

    @Test
    fun groupsRetainServerMembershipAndDistinctOwners() = runBlocking {
        val library = api.library("42")
        assertEquals(listOf("Owned"), library.ownPlaylists.map { it.title })
        assertEquals(listOf("Other", "Owned"), library.likedPlaylists.map { it.title })
        assertEquals(listOf("42", "99"), library.playlists.map { it.owner })
        assertEquals(listOf("5"), library.albums.map { it.id })
        assertEquals(listOf("7", "8"), library.artists.map { it.id })
        assertNotNull(library.artists[0].cover)
        assertEquals("", library.artistsError)
        val requests = List(4) { server.takeRequest() }
        assertTrue(requests.all { it.getHeader("Authorization") == "OAuth test-token" })
        assertEquals(
            "true",
            requests
                .first { it.requestUrl!!.encodedPath.endsWith("likes/artists") }
                .requestUrl!!
                .queryParameter("with-timestamps"),
        )
    }

    @Test
    fun optionalArtistFailureDoesNotEraseAlbumsOrPlaylists() = runBlocking {
        failArtists = true
        val library = api.library("42")
        assertEquals(1, library.ownPlaylists.size)
        assertEquals(2, library.likedPlaylists.size)
        assertEquals(1, library.albums.size)
        assertTrue(library.artists.isEmpty())
        assertTrue(library.artistsError.contains("503"))
    }

    @Test
    fun coreCollectionFailureIsNotReportedAsAnEmptyCollection() = runBlocking {
        failOwn = true
        assertTrue(runCatching { api.library("42") }.isFailure)
    }
}
