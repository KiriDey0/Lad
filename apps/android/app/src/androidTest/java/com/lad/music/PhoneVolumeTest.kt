// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaController.PlaybackInfo
import android.test.InstrumentationTestCase
import android.view.KeyEvent
import kotlinx.coroutines.*

@Suppress("DEPRECATION")
class PhoneVolumeTest : InstrumentationTestCase() {
    private lateinit var activity: MainActivity
    private lateinit var engine: MusicEngine

    override fun setUp() {
        super.setUp()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        activity =
            monitor.waitForActivityWithTimeout(20000) as? MainActivity
                ?: error(
                    "Launch com.lad.music.qa from adb while the test waits; MIUI blocks app-initiated QA launches"
                )
        instrumentation.removeMonitor(monitor)
        assertEquals("com.lad.music.qa", activity.packageName)
        engine = (activity.application as LadApplication).engine
        instrumentation.runOnMainSync {
            engine.demo()
            engine.settings(engine.state.value.settings.copy(syncVolume = false))
        }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun waitFor(condition: () -> Boolean) {
        repeat(50) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue("Condition timed out: volume=${engine.state.value.volume}, output=${engine.state.value.output}", condition())
    }

    fun testRemoteVolumeKeysAndMediaSessionRespectOptIn() {
        main {
            engine.task {
                engine.jump(0)
                engine.transfer(Device("demo-midi", "QA", "", ""))
            }
        }
        waitFor { engine.mediaSessionToken != null && engine.state.value.output == "station" }
        val audio = activity.getSystemService(AudioManager::class.java)
        val localLevel = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val localAppVolume = engine.state.value.volume
        val controller = MediaController(activity, engine.mediaSessionToken!!)
        waitFor { controller.playbackInfo.playbackType == PlaybackInfo.PLAYBACK_TYPE_LOCAL }
        main { engine.settings(engine.state.value.settings.copy(syncVolume = true)) }
        waitFor { controller.playbackInfo.playbackType == PlaybackInfo.PLAYBACK_TYPE_REMOTE }
        controller.setVolumeTo(30, 0)
        waitFor { kotlin.math.abs(engine.state.value.volume - .3f) < .01f }
        waitFor { activity.hasWindowFocus() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP)
        waitFor { kotlin.math.abs(engine.state.value.volume - .35f) < .01f }
        waitFor { controller.playbackInfo.currentVolume == 35 }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)
        waitFor { controller.playbackInfo.currentVolume == 30 }
        controller.adjustVolume(AudioManager.ADJUST_RAISE, 0)
        waitFor { controller.playbackInfo.currentVolume == 35 }
        main { engine.settings(engine.state.value.settings.copy(syncVolume = false)) }
        waitFor { controller.playbackInfo.playbackType == PlaybackInfo.PLAYBACK_TYPE_LOCAL }
        assertEquals(
            "Casting must preserve local hardware volume",
            localLevel,
            audio.getStreamVolume(AudioManager.STREAM_MUSIC),
        )
        main { engine.task { engine.transferPhone() } }
        waitFor { engine.state.value.output == "phone" }
        assertEquals(localAppVolume, engine.state.value.volume, .001f)
    }

    override fun tearDown() {
        main {
            engine.settings(engine.state.value.settings.copy(syncVolume = false))
            engine.logout()
        }
        super.tearDown()
    }
}
