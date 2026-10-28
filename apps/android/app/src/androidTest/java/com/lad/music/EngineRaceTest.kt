// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.test.InstrumentationTestCase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

@Suppress("DEPRECATION")
class EngineRaceTest : InstrumentationTestCase() {
    private lateinit var engine: MusicEngine
    private val entered = CountDownLatch(1)
    private val release = CompletableDeferred<String>()
    private lateinit var pending: Job

    override fun setUp() {
        super.setUp()
        check(instrumentation.targetContext.packageName.endsWith(".qa"))
        instrumentation.runOnMainSync {
            engine = MusicEngine(instrumentation.targetContext,
                streamLoader = { entered.countDown(); release.await() }, serviceStarter = {})
            engine.settings(engine.state.value.settings.copy(blockExplicit = false))
            pending = engine.task { engine.play(listOf(Track("fixture", "Silent fixture", emptyList(), durationMs = 10000)), 0, "QA") }
        }
        assertTrue("Stream request must be suspended", entered.await(5, TimeUnit.SECONDS))
    }

    private fun finishAndCheck() {
        release.complete("data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQAAAAA=")
        runBlocking { withTimeout(5000) { pending.join() } }
        instrumentation.runOnMainSync {
            assertEquals("Stale request must not load a MediaItem", 0, engine.player.mediaItemCount)
            assertFalse(engine.state.value.playing)
            assertFalse(engine.state.value.loading)
        }
    }

    fun testLogoutCannotResurrectAccountOrQueue() {
        instrumentation.runOnMainSync { engine.logout() }
        finishAndCheck()
        assertNull(engine.state.value.profile)
        assertTrue(engine.state.value.queue.isEmpty())
    }
    fun testPauseCancelsPendingAutoplay() {
        instrumentation.runOnMainSync { engine.task { engine.pause() } }
        finishAndCheck()
    }
    fun testContentFilterCancelsPendingAutoplay() {
        instrumentation.runOnMainSync { engine.settings(engine.state.value.settings.copy(blockExplicit = true)) }
        finishAndCheck()
        assertTrue(engine.state.value.settings.blockExplicit)
    }
    override fun tearDown() {
        instrumentation.runOnMainSync { engine.scope.cancel(); engine.player.release() }
        super.tearDown()
    }
}
