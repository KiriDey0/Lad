// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.app.*
import android.content.*
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.*
import android.provider.Settings as AndroidSettings
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal object LockScreenSession {
    var dismissedUntilSleep = false
    val playback = RecentPlaybackSession(SystemClock::elapsedRealtime)
}

private fun screenInteractive(context: Context): Boolean {
    val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    return context.getSystemService(PowerManager::class.java).isInteractive &&
        (display == null || display.state == Display.STATE_ON)
}

/** Display events also cover doze/off transitions that can precede SCREEN_OFF. */
private class LockDisplayObserver(context: Context, changed: () -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(DisplayManager::class.java)
    private var state = manager.getDisplay(Display.DEFAULT_DISPLAY)?.state
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) {
            if (id != Display.DEFAULT_DISPLAY) return
            val next = manager.getDisplay(id)?.state
            if (state != next) { state = next; changed() }
        }
    }
    init { manager.registerDisplayListener(listener, Handler(Looper.getMainLooper())) }
    override fun close() { manager.unregisterDisplayListener(listener) }
}

private fun automaticPlayerAllowed(context: Context, state: MusicState): Boolean {
    val audio = context.getSystemService(AudioManager::class.java)
    return shouldShowLockPlayer(
        state.settings.lockScreen,
        context.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
        LockScreenSession.playback.available(), state.track != null && !state.demo,
        AndroidSettings.canDrawOverlays(context),
        audio.mode in listOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE),
        LockScreenSession.dismissedUntilSleep,
    )
}

internal fun playbackOpenIntent(context: Context, enabled: Boolean): PendingIntent {
    val target = if (enabled) LockScreenActivity::class.java else MainActivity::class.java
    val intent = Intent(context, target).putExtra("notification", true)
    val options = if (Build.VERSION.SDK_INT >= 35) ActivityOptions.makeBasic().apply {
        setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
    }.toBundle() else null
    return PendingIntent.getActivity(context, 17, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, options)
}

/** Runs only for the lifetime of the existing media playback service. */
internal class LockScreenLauncher(private val service: Service, private val engine: MusicEngine) : AutoCloseable {
    private var launchRequested = false
    private fun show() {
        if (!launchRequested && screenInteractive(service) && automaticPlayerAllowed(service, engine.state.value)) {
            launchRequested = true
            runCatching {
                service.startActivity(Intent(service, LockScreenActivity::class.java)
                    .putExtra("automatic", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }.onFailure { launchRequested = false }
        }
    }
    private fun sleeping() {
        launchRequested = false
        LockScreenSession.dismissedUntilSleep = false
    }
    private val displayObserver = LockDisplayObserver(service) {
        if (screenInteractive(service)) show() else sleeping()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> sleeping()
                Intent.ACTION_SCREEN_ON -> show()
                Intent.ACTION_USER_PRESENT -> launchRequested = false
            }
        }
    }
    init { ContextCompat.registerReceiver(service, receiver, IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
    }, ContextCompat.RECEIVER_NOT_EXPORTED) }
    override fun close() { displayObserver.close(); service.unregisterReceiver(receiver) }
}

class LockScreenActivity : ComponentActivity() {
    private val engine get() = (application as LadApplication).engine
    private var interactive by mutableStateOf(false)
    private var foreground by mutableStateOf(false)
    private var lyricsVisible by mutableStateOf(false)
    private var displayObserver: LockDisplayObserver? = null
    private val keyguard get() = getSystemService(KeyguardManager::class.java)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> hideImmediately()
                Intent.ACTION_SCREEN_ON -> refreshVisibility()
                Intent.ACTION_USER_PRESENT -> { hideImmediately(); finish() }
            }
        }
    }
    private fun hideImmediately() {
        interactive = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.visibility = View.INVISIBLE
    }
    private fun refreshVisibility() {
        if (!screenInteractive(this)) { hideImmediately(); return }
        // Recheck after Activity dispatch: unlock/stop/expiry can race with screen-on.
        if (intent.getBooleanExtra("automatic", false) && !automaticPlayerAllowed(this, engine.state.value)) {
            hideImmediately(); finish(); return
        }
        interactive = true
        window.decorView.visibility = if (foreground) View.VISIBLE else View.INVISIBLE
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshVisibility()
    }
    private fun closePlayer() { LockScreenSession.dismissedUntilSleep = true; finish() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only this restricted activity is allowed over keyguard. Main/settings never are.
        if (intent.getBooleanExtra("notification", false) &&
            (!engine.state.value.settings.lockScreen.enabled || !keyguard.isKeyguardLocked)) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish(); return
        }
        if (intent.getBooleanExtra("automatic", false) && !automaticPlayerAllowed(this, engine.state.value)) {
            finish(); return
        }
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightNavigationBars = false
        }
        volumeControlStream = AudioManager.STREAM_MUSIC
        interactive = screenInteractive(this)
        displayObserver = LockDisplayObserver(this, ::refreshVisibility)
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            val s by engine.state.collectAsStateWithLifecycle()
            val visible = interactive && foreground
            val keepAwake = visible && (
                s.settings.lockScreen.keepScreenOn ||
                    (s.settings.lockScreen.keepScreenOnLyrics && lyricsVisible)
            )
            DisposableEffect(keepAwake) {
                if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            LadTheme(s.settings.copy(scale = 1f, accent = 0xFFE2E6EC, tinted = false)) {
                // Keep the composition/artwork/lyrics mode across display sleep. Only
                // visible clocks and lyric work resume when the display is interactive.
                LockPlayer(s, engine, ::closePlayer, ::unlock, active = visible) { lyricsVisible = it }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closePlayer()
        })
    }
    private fun unlock() {
        if (keyguard.isKeyguardLocked) keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { finish() }
        }) else finish()
    }
    override fun onStart() { super.onStart(); foreground = true; refreshVisibility() }
    override fun onStop() { foreground = false; hideImmediately(); super.onStop() }
    override fun onDestroy() { displayObserver?.close(); runCatching { unregisterReceiver(receiver) }; super.onDestroy() }
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val s = engine.state.value
        if (s.output == "station" && s.settings.syncVolume && keyCode in listOf(android.view.KeyEvent.KEYCODE_VOLUME_UP, android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            engine.task { engine.adjustVolume(if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) .05f else -.05f) }; return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val s = engine.state.value
        if (s.output == "station" && s.settings.syncVolume && keyCode in listOf(android.view.KeyEvent.KEYCODE_VOLUME_UP, android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }
}
