// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.test.InstrumentationTestCase
import android.os.SystemClock
import kotlinx.coroutines.*
import java.net.URL
import java.net.Socket
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opt-in physical-device test; uses only folders already granted to the QA app. */
@Suppress("DEPRECATION")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LocalLibraryDeviceTest : InstrumentationTestCase() {
    private lateinit var engine:MusicEngine
    override fun setUp() {
        super.setUp()
        check(instrumentation.targetContext.packageName.endsWith(".qa"))
        instrumentation.runOnMainSync {engine=MusicEngine(instrumentation.targetContext,serviceStarter={})}
    }
    override fun tearDown() {
        instrumentation.runOnMainSync {engine.scope.cancel();engine.player.release()}
        super.tearDown()
    }
    fun testGrantedLibraryPlaybackLyricsAndCastPreparation() = runBlocking {
        val context=instrumentation.targetContext
        check(context.packageName.endsWith(".qa"))
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
            // Instrumentation has no visible Activity or foreground audio service.
            // Isolate decoding from Android's background audio-focus policy.
            engine.player.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT,false)
            engine.volume(.02f)
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
        val raw=android.media.MediaExtractor()
        try {
            raw.setDataSource(context,library.uri(track.id),null)
            val index=(0 until raw.trackCount).first {raw.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/")==true}
            val format=raw.getTrackFormat(index)
            if(format.getString(android.media.MediaFormat.KEY_MIME)=="audio/raw") {
                raw.selectTrack(index)
                val direct=MessageDigest.getInstance("SHA-256")
                val buffer=ByteBuffer.allocate(1024*1024)
                while(true){buffer.clear();val size=raw.readSampleData(buffer,0);if(size<0)break;direct.update(buffer.array(),0,size);raw.advance()}
                val prepared=MessageDigest.getInstance("SHA-256")
                pcm.inputStream().use {input->check(input.skip(44)==44L);val chunk=ByteArray(65536);while(true){val n=input.read(chunk);if(n<0)break;prepared.update(chunk,0,n)}}
                assertTrue("Raw extractor PCM must be preserved byte for byte",direct.digest().contentEquals(prepared.digest()))
            }
        } finally {raw.release()}
        val server=LocalMediaServer(context,library)
        try {
            val url=server.url(track.id,"127.0.0.1",pcm)
            val endpoint=URL(url)
            Socket(endpoint.host,endpoint.port).use {socket->
                socket.soTimeout=5000
                socket.getOutputStream().write("GET ${endpoint.path} HTTP/1.1\r\nHost: ${endpoint.host}\r\nRange: bytes=0-43\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                val response=socket.getInputStream().readBytes()
                val split=response.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")
                assertTrue(response.toString(Charsets.ISO_8859_1).startsWith("HTTP/1.1 206"))
                assertTrue(header.contentEquals(response.copyOfRange(split+4,response.size)))
            }
        } finally {server.close();withContext(Dispatchers.Main){engine.pause()}}
    }
}
