// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.test.InstrumentationTestCase
import androidx.core.graphics.drawable.toBitmap
import java.io.File

/** Exercises the real service, framework MediaSession and notification in the isolated QA app. */
@Suppress("DEPRECATION")
class PlaybackArtworkTest : InstrumentationTestCase() {
    private lateinit var activity: MainActivity
    private lateinit var engine: MusicEngine
    private val covers = mutableListOf<File>()
    private var artworkDebug = ""
    private var diagnostic: () -> String = { artworkDebug }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, condition: () -> Boolean) {
        repeat(150) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue("$message (${diagnostic()}; track=${engine.state.value.track?.id})", condition())
    }
    private fun cover(color: Int): String {
        val file = File.createTempFile("artwork-", ".png", activity.cacheDir)
        val bitmap = Bitmap.createBitmap(1200, 1200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        covers += file
        return file.toURI().toString()
    }
    override fun setUp() {
        super.setUp()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        activity = monitor.waitForActivityWithTimeout(20000) as? MainActivity
            ?: error("Launch com.lad.music.qa from adb while this test waits")
        instrumentation.removeMonitor(monitor)
        assertEquals("com.lad.music.qa", activity.packageName)
        engine = (activity.application as LadApplication).engine
        main { engine.demo(); engine.settings(engine.state.value.settings.copy(syncVolume = false)) }
    }
    fun testArtworkUpdatesInSessionAndNotificationAndClearsForMissingCovers() {
        val tracks = listOf(
            Track("red", "Red fixture", emptyList(), durationMs = 180000, cover = cover(Color.RED)),
            Track("blue", "Blue fixture", emptyList(), durationMs = 180000, cover = cover(Color.BLUE)),
            Track("none", "No artwork", emptyList(), durationMs = 180000),
            Track("missing", "Missing file", emptyList(), durationMs = 180000, cover = File(activity.cacheDir, "nonexistent-artwork.png").toURI().toString()),
        )
        main { engine.task { engine.play(tracks, 0, "Artwork QA") } }
        await("Service did not publish a session") { engine.mediaSessionToken != null }
        val controller = MediaController(activity, engine.mediaSessionToken!!)
        val manager = activity.getSystemService(NotificationManager::class.java)
        fun image() = controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        fun notification() = manager.activeNotifications.firstOrNull { it.id == 71 }?.notification
        fun notificationImage() = notification()?.getLargeIcon()?.loadDrawable(activity)?.toBitmap()
        diagnostic = {
            val b = image(); val n = notification(); val icon = notificationImage()
            "sessionTitle=${controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)}, sessionImage=${b?.let { "${it.width}x${it.height}" }}, notificationTitle=${n?.extras?.getString(Notification.EXTRA_TITLE)}, notificationImage=${icon?.let { "${it.width}x${it.height}/${it.getPixel(it.width/2,it.height/2)}" }}"
        }
        fun cleared(title: String): Boolean {
            val icon = notificationImage()
            val color = icon?.getPixel(icon.width / 2, icon.height / 2)
            return controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) == title &&
                image() == null && notification()?.extras?.getString(Notification.EXTRA_TITLE) == title &&
                color != Color.RED && color != Color.BLUE
        }
        fun shows(title: String, color: Int): Boolean {
            val bitmap = image()
            val icon = notificationImage()
            artworkDebug = "title=${controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)}, session=${bitmap?.let { "${it.width}x${it.height}/${it.getPixel(it.width/2,it.height/2)}" }}, notification=${icon?.let { "${it.width}x${it.height}/${it.getPixel(it.width/2,it.height/2)}" }}"
            if (bitmap == null || icon == null) return false
            return controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) == title &&
                bitmap.width <= 384 && bitmap.height <= 384 &&
                bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) == color &&
                icon.getPixel(icon.width / 2, icon.height / 2) == color
        }
        await("Red cover missing from session or notification") { shows("Red fixture", Color.RED) }
        main { engine.task { engine.pause() } }
        await("Pause lost the cover") { !engine.state.value.playing && shows("Red fixture", Color.RED) }
        main { engine.task { engine.jump(1) } }
        await("Track change did not replace cover") { shows("Blue fixture", Color.BLUE) }
        // Switch several times without waiting for artwork: a stale request must never win.
        main { engine.task { engine.jump(0); engine.jump(1); engine.jump(2) } }
        await("A track without artwork kept the previous cover") {
            cleared("No artwork")
        }
        main { engine.task { engine.jump(3) } }
        await("Failed artwork should preserve transport/title and clear the old image") {
            cleared("Missing file") && engine.state.value.playing
        }
        main { engine.task { engine.jump(1) } }
        await("Cached cover did not recover after a failed load") { shows("Blue fixture", Color.BLUE) }
    }
    override fun tearDown() {
        if (::engine.isInitialized) {
            main { engine.logout() }
            await("QA service did not stop") { engine.mediaSessionToken == null }
            val manager = activity.getSystemService(NotificationManager::class.java)
            await("QA notification was not removed") { manager.activeNotifications.none { it.id == 71 } }
        }
        covers.forEach { it.delete() }
        super.tearDown()
    }
}
