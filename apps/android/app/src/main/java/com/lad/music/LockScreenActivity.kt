// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.app.*
import android.content.*
import android.media.AudioManager
import android.os.*
import android.provider.Settings as AndroidSettings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*

internal object LockScreenSession { var dismissedUntilSleep = false }

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
    private val handler = Handler(Looper.getMainLooper())
    private val show = Runnable {
        val state = engine.state.value
        val keyguard = service.getSystemService(KeyguardManager::class.java)
        val power = service.getSystemService(PowerManager::class.java)
        val audio = service.getSystemService(AudioManager::class.java)
        if (power.isInteractive && shouldShowLockPlayer(state.settings.lockScreen, keyguard.isKeyguardLocked,
                state.playing, state.track != null, AndroidSettings.canDrawOverlays(service),
                audio.mode in listOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE),
                LockScreenSession.dismissedUntilSleep)) {
            runCatching { service.startActivity(Intent(service, LockScreenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.removeCallbacks(show)
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> LockScreenSession.dismissedUntilSleep = false
                Intent.ACTION_SCREEN_ON -> handler.postDelayed(show, 200)
            }
        }
    }
    init { ContextCompat.registerReceiver(service, receiver, IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
    }, ContextCompat.RECEIVER_NOT_EXPORTED) }
    override fun close() { handler.removeCallbacks(show); service.unregisterReceiver(receiver) }
}

class LockScreenActivity : ComponentActivity() {
    private val engine get() = (application as LadApplication).engine
    private var interactive by mutableStateOf(false)
    private var foreground by mutableStateOf(false)
    private val keyguard get() = getSystemService(KeyguardManager::class.java)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> interactive = false
                Intent.ACTION_SCREEN_ON -> interactive = true
                Intent.ACTION_USER_PRESENT -> finish()
            }
        }
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
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightNavigationBars = false
        }
        volumeControlStream = AudioManager.STREAM_MUSIC
        interactive = getSystemService(PowerManager::class.java).isInteractive
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            val s by engine.state.collectAsStateWithLifecycle()
            val visible = interactive && foreground
            LadTheme(s.settings.copy(scale = 1f, accent = 0xFFE2E6EC, tinted = false)) {
                if (visible) LockPlayer(s, engine, ::closePlayer, ::unlock)
                else Box(Modifier.fillMaxSize().background(Color(0xFF0C0D0F)))
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
    override fun onStart() { super.onStart(); foreground = true; interactive = getSystemService(PowerManager::class.java).isInteractive }
    override fun onStop() { foreground = false; super.onStop() }
    override fun onDestroy() { runCatching { unregisterReceiver(receiver) }; super.onDestroy() }
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
