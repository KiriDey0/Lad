// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import org.junit.Assert.*
import org.junit.Test

class LocalArtistsTest {
    private fun track(id: String, vararg artists: String) = Track(id, "Track", artists.map { ArtistRef("", it) })
    @Test fun collaborationBelongsToEveryParticipantWithoutChangingTrackIds() {
        val result=LocalArtists.normalize(listOf(track("a","DEEP-EX-SENSE"),track("b","Лжедмитрий IV"),
            track("c","DEEP-EX-SENSE, Лжедмитрий IV, Asper X")))
        assertEquals(listOf("DEEP-EX-SENSE","Лжедмитрий IV","Asper X"),result[2].artists.map { it.name })
        assertEquals("c",result[2].id)
        assertEquals(result[0].artists[0].id,result[2].artists[0].id)
        assertEquals(result[1].artists[0].id,result[2].artists[1].id)
    }
    @Test fun explicitCreditAndStructuredValuesKeepAllGuests() {
        val result=LocalArtists.normalize(listOf(track("a","A (ft. B)"),track("b","A","B","a")))
        assertEquals(listOf("A","B"),result[0].artists.map { it.name })
        assertEquals(listOf("A","B"),result[1].artists.map { it.name })
    }
    @Test fun punctuationInBandNamesIsPreserved() {
        val names=listOf("AC/DC","Earth, Wind & Fire","Король и Шут","A (band)","FT Island")
        val result=LocalArtists.normalize(names.mapIndexed { i,n -> track("$i",n) })
        assertEquals(names,result.map { it.artists.single().name })
    }
}
