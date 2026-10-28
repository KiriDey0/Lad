// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.app.*
import android.content.Intent
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class PlaybackService : Service() {
    private lateinit var engine: MusicEngine
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var signature = ""
    private var metadataSignature: Pair<Track?, Long>? = null
    private var playbackSignature = ""
    private var remoteVolume: VolumeProvider? = null
    private var volumeOutput = ""

    override fun onCreate() {
        super.onCreate()
        engine = (application as LadApplication).engine
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("playback", "Воспроизведение", NotificationManager.IMPORTANCE_LOW)
        )
        session =
            MediaSession(this, "Лад").apply {
                setCallback(
                    object : MediaSession.Callback() {
                        override fun onPlay() {
                            engine.task { engine.resume() }
                        }

                        override fun onPause() {
                            engine.task { engine.pause() }
                        }

                        override fun onSkipToNext() {
                            engine.task { engine.advance(1) }
                        }

                        override fun onSkipToPrevious() {
                            engine.task { engine.advance(-1) }
                        }

                        override fun onSeekTo(pos: Long) {
                            engine.task { engine.seek(pos) }
                        }

                        override fun onStop() {
                            engine.task {
                                engine.pause()
                                stopSelf()
                            }
                        }
                    }
                )
                isActive = true
            }
        engine.mediaSessionToken = session.sessionToken
        scope.launch {
            engine.state.collect { s ->
                val t = s.track
                val metadata = t to s.durationMs
                if (metadata != metadataSignature) {
                    metadataSignature = metadata
                    session.setMetadata(
                        MediaMetadata.Builder()
                            .putString(MediaMetadata.METADATA_KEY_TITLE, t?.title ?: "Лад")
                            .putString(MediaMetadata.METADATA_KEY_ARTIST, t?.artist ?: "")
                            .putString(MediaMetadata.METADATA_KEY_ALBUM, t?.album ?: "")
                            .putLong(MediaMetadata.METADATA_KEY_DURATION, s.durationMs)
                            .build()
                    )
                }
                val playback =
                    "${t?.id}:${s.output}:${s.playing}:${s.loading}:${s.positionMs / 1000}"
                if (playback != playbackSignature) {
                    playbackSignature = playback
                    session.setPlaybackState(
                        PlaybackState.Builder()
                            .setActions(
                                PlaybackState.ACTION_PLAY or
                                    PlaybackState.ACTION_PAUSE or
                                    PlaybackState.ACTION_PLAY_PAUSE or
                                    PlaybackState.ACTION_SKIP_TO_NEXT or
                                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                                    PlaybackState.ACTION_SEEK_TO or
                                    PlaybackState.ACTION_STOP
                            )
                            .setState(
                                if (s.loading) PlaybackState.STATE_BUFFERING
                                else if (s.playing) PlaybackState.STATE_PLAYING
                                else PlaybackState.STATE_PAUSED,
                                s.positionMs,
                                if (s.playing) 1f else 0f,
                                SystemClock.elapsedRealtime(),
                            )
                            .build()
                    )
                }
                val output = "${s.output}:${s.deviceId}:${s.settings.syncVolume}"
                if (volumeOutput != output) {
                    volumeOutput = output
                    if (s.output == "station" && s.settings.syncVolume) {
                        remoteVolume =
                            object :
                                VolumeProvider(
                                    VolumeProvider.VOLUME_CONTROL_ABSOLUTE,
                                    100,
                                    (s.volume * 100).toInt(),
                                ) {
                                override fun onSetVolumeTo(volume: Int) {
                                    engine.task { engine.volume(volume / 100f) }
                                }

                                override fun onAdjustVolume(direction: Int) {
                                    engine.task { engine.adjustVolume(direction * .05f) }
                                }
                            }
                        session.setPlaybackToRemote(remoteVolume!!)
                    } else {
                        remoteVolume = null
                        session.setPlaybackToLocal(
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .build()
                        )
                    }
                }
                val volume = (s.volume * 100).toInt().coerceIn(0, 100)
                remoteVolume?.let { if (it.currentVolume != volume) it.setCurrentVolume(volume) }
                val next = "${t?.id}:${s.playing}:${s.outputName}:${s.profile?.uid}"
                if (next != signature) {
                    signature = next
                    manager.notify(71, notification(s))
                }
            }
        }
    }

    private fun action(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun notification(s: MusicState): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, "playback")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(s.track?.title ?: "Лад")
            .setContentText((s.track?.artist ?: "Выбери музыку") + " · " + s.outputName)
            .setContentIntent(open)
            .setDeleteIntent(action("stop"))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(s.playing)
            .addAction(
                Notification.Action.Builder(
                        android.R.drawable.ic_media_previous,
                        "Предыдущий",
                        action("previous"),
                    )
                    .build()
            )
            .addAction(
                Notification.Action.Builder(
                        if (s.playing) android.R.drawable.ic_media_pause
                        else android.R.drawable.ic_media_play,
                        if (s.playing) "Пауза" else "Продолжить",
                        action("toggle"),
                    )
                    .build()
            )
            .addAction(
                Notification.Action.Builder(
                        android.R.drawable.ic_media_next,
                        "Следующий",
                        action("next"),
                    )
                    .build()
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(71, notification(engine.state.value))
        when (intent?.action) {
            "toggle" -> engine.task { engine.toggle() }
            "next" -> engine.task { engine.advance(1) }
            "previous" -> engine.task { engine.advance(-1) }
            "stop" ->
                engine.task {
                    engine.pause()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        engine.mediaSessionToken = null
        session.release()
        super.onDestroy()
    }
}
