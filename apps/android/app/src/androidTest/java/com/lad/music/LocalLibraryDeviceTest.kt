// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.test.InstrumentationTestCase
import android.os.SystemClock
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opt-in physical-device test; uses only folders already granted to the QA app. */
@Suppress("DEPRECATION")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LocalLibraryDeviceTest : InstrumentationTestCase() {
    fun testGrantedLibraryPlaybackLyricsAndCastPreparation() = runBlocking {
        val context=instrumentation.targetContext
        check(context.packageName.endsWith(".qa"))
        val engine=(context.applicationContext as LadApplication).engine
        val library=engine.localLibrary
        val deadline=SystemClock.elapsedRealtime()+120000
        while(library.scanning.value && SystemClock.elapsedRealtime()<deadline)delay(100)
        assertFalse("Folder scan must finish",library.scanning.value)
        val tracks=withContext(Dispatchers.IO){library.tracks()}
        assertTrue("Select a folder in the QA application before running this test",tracks.isNotEmpty())
        val flac=tracks.firstOrNull {library.format(it.id)=="FLAC" && library.lyrics(it.id).synced}
        assertNotNull("A FLAC with embedded synced lyrics is required",flac)
        val track=flac!!
        val lyrics=withContext(Dispatchers.IO){library.lyrics(track.id)}
        assertTrue(lyrics.lines.size>10)
        assertTrue(lyrics.lines.zipWithNext().all {(a,b)->(a.timeMs?:0L)<=(b.timeMs?:0L)})
        withContext(Dispatchers.Main) {
            engine.player.volume=.02f
            engine.play(listOf(track),0,"Device QA")
        }
        val playbackDeadline=SystemClock.elapsedRealtime()+10000
        while(engine.state.value.positionMs<500 && SystemClock.elapsedRealtime()<playbackDeadline)delay(100)
        assertEquals(track.id,engine.state.value.track?.id)
        assertTrue("Actual local decoder must advance",engine.state.value.positionMs>=500)
        withContext(Dispatchers.Main){engine.seek(60000);engine.pause()}
        assertTrue(engine.state.value.positionMs>=59000)
        val pcm=LocalPcmCache(context).prepare(library.uri(track.id),track.id)
        val header=ByteArray(44).also {bytes->java.io.DataInputStream(pcm.inputStream()).use {it.readFully(bytes)}}
        assertEquals("RIFF",String(header,0,4))
        val info=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertTrue(info.getInt(24)>0)
        assertTrue(info.getShort(34).toInt() in listOf(16,24,32))
        val server=LocalMediaServer(context,library)
        try {
            val url=server.url(track.id,"127.0.0.1",pcm)
            val request=URL(url).openConnection() as HttpURLConnection
            request.setRequestProperty("Range","bytes=0-43")
            try {assertEquals(206,request.responseCode);assertTrue(header.contentEquals(request.inputStream.use {it.readBytes()}))} finally {request.disconnect()}
        } finally {server.close();withContext(Dispatchers.Main){engine.pause()}}
    }
}
