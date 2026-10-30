// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.test.InstrumentationTestCase
import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import com.lad.music.wave.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

@Suppress("DEPRECATION")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RemoteWaveAudioTest : InstrumentationTestCase() {
    fun testRemoteDecoderIsSilentButProducesPCMAndReleasesInBackground() {
        val context=instrumentation.targetContext
        check(context.packageName.endsWith(".qa"))
        val rate=8000; val size=rate*8
        val buffer=ByteBuffer.allocate(44+size*2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36+size*2).put("WAVEfmt ".toByteArray()).putInt(16)
        buffer.putShort(1).putShort(1).putInt(rate).putInt(rate*2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(size*2)
        repeat(size){buffer.putShort((sin(it*2*PI*125/rate)*6000).toInt().toShort())}
        val file=File(context.cacheDir,"remote-wave-fixture.wav");file.writeBytes(buffer.array())
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        lateinit var mirror:RemoteWaveAudio
        val start=SystemClock.elapsedRealtime()
        val field=RemoteWaveAudio::class.java.getDeclaredField("player").apply{isAccessible=true}
        try {
            instrumentation.runOnMainSync {
                mirror=RemoteWaveAudio(context,scope,{file.toURI().toString()}) {
                    RemoteWavePlayback("fixture",SystemClock.elapsedRealtime()-start,true,true)
                }
                mirror.enabled=true
            }
            val deadline=SystemClock.elapsedRealtime()+6000
            var pcm=false
            while(SystemClock.elapsedRealtime()<deadline && !pcm) {
                pcm=mirror.audio.sample().any{it>.01f};Thread.sleep(30)
            }
            assertTrue("Muted decoder must still supply actual PCM",pcm)
            instrumentation.runOnMainSync {
                val player=field.get(mirror) as ExoPlayer
                assertEquals("No audible mirror",0f,player.volume)
                mirror.enabled=false
                assertNull("Decoder is released when screen is inactive",field.get(mirror))
                assertTrue(mirror.audio.sample().all{it==0f})
            }
        }finally {
            instrumentation.runOnMainSync{mirror.enabled=false;scope.cancel()}
            file.delete()
        }
    }
}
