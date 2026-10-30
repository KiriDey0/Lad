// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music.wave

import android.content.Context
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlin.math.abs

data class RemoteWavePlayback(val id: String?, val positionMs: Long, val playing: Boolean, val available: Boolean)
data class RemoteWaveCorrection(val seekMs: Long?, val rate: Float)
fun remoteWaveCorrection(target: Long, actual: Long): RemoteWaveCorrection {
    val error = target - actual
    return if (abs(error) > 650) RemoteWaveCorrection(target, 1f)
    else RemoteWaveCorrection(null, (1 + error * .00008).coerceIn(.96, 1.04).toFloat())
}

/** Visible-screen-only silent PCM mirror. No audio focus, wake lock, MediaSession or listening reports. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RemoteWaveAudio(
    private val context: Context,
    private val scope: CoroutineScope,
    private val stream: suspend (String) -> String,
    private val playback: () -> RemoteWavePlayback,
) {
    val audio = WaveAudio()
    private var job: Job? = null
    private var player: ExoPlayer? = null
    private var generation = 0L
    var enabled = false
        set(value) {
            if (field == value) return
            field = value
            val epoch = ++generation
            audio.enabled = value
            if (!value) {
                job?.cancel();job = null
                player?.release();player = null
                audio.clear()
            } else job = scope.launch { follow(epoch) }
        }

    private suspend fun follow(epoch: Long) {
        var track: String? = null
        var requested: String? = null
        var retryAt = 0L
        try {
            while (currentCoroutineContext().isActive) {
                val remote = playback()
                if (remote.id != requested) {requested = remote.id;retryAt = 0L}
                if (!remote.available || !remote.playing || remote.id.isNullOrEmpty()) {
                    player?.release();player = null;track = null;audio.clear()
                } else if (track != remote.id || player == null) {
                    if (android.os.SystemClock.elapsedRealtime() >= retryAt) {
                        player?.release();player = null;audio.clear()
                        try {
                            val url = stream(remote.id)
                            currentCoroutineContext().ensureActive()
                            val current = playback()
                            if (current.id == remote.id && current.available && current.playing && enabled) {
                                val p = ExoPlayer.Builder(context, audio.renderers(context)).build()
                                player = p
                                // Silence BEFORE prepare. Tee receives PCM before listening gain.
                                p.volume = 0f
                                p.setAudioAttributes(AudioAttributes.DEFAULT, false)
                                p.setHandleAudioBecomingNoisy(false)
                                p.setMediaItem(MediaItem.fromUri(url), current.positionMs)
                                p.prepare();p.playWhenReady = true
                                track = current.id
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) {retryAt = android.os.SystemClock.elapsedRealtime() + 15000}
                    }
                } else {
                    val p = player!!
                    if (p.playerError != null || p.playbackState == Player.STATE_ENDED) {
                        p.release();player = null;track = null;audio.clear()
                        retryAt = android.os.SystemClock.elapsedRealtime() + 15000
                    } else if (p.playbackState == Player.STATE_READY) {
                        val correction = remoteWaveCorrection(remote.positionMs, p.currentPosition)
                        correction.seekMs?.let { p.seekTo(it) }
                        p.setPlaybackSpeed(correction.rate)
                    }
                }
                delay(200)
            }
        } finally {
            if (generation == epoch) {player?.release();player = null;audio.clear()}
        }
    }
}
