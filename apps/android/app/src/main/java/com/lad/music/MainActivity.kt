// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private fun remoteVolumeKey(keyCode: Int): Boolean {
        val s = (application as LadApplication).engine.state.value
        return s.settings.syncVolume &&
            s.output == "station" &&
            keyCode in
                listOf(
                    android.view.KeyEvent.KEYCODE_VOLUME_UP,
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                )
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (remoteVolumeKey(keyCode)) {
            val engine = (application as LadApplication).engine
            val delta = if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) .05f else -.05f
            engine.task { engine.adjustVolume(delta) }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (remoteVolumeKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        setContent { LadApp((application as LadApplication).engine) }
        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        )
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
