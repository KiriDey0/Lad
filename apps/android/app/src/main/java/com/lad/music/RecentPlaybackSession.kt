// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

/** Event-driven history for the live media session. No scheduled tasks or wake locks. */
internal class RecentPlaybackSession(
    private val clock: () -> Long,
    private val pauseRetentionMs: Long = 60 * 60 * 1000L,
) {
    private var playing = false
    private var pausedAt: Long? = null

    fun update(isPlaying: Boolean, hasTrack: Boolean) {
        if (!hasTrack) { clear(); return }
        // Position updates while paused must not refresh the retention window.
        if (playing && !isPlaying) pausedAt = clock()
        if (isPlaying) pausedAt = null
        playing = isPlaying
    }

    fun available(): Boolean = playing || pausedAt?.let {
        val elapsed = clock() - it
        elapsed >= 0 && elapsed < pauseRetentionMs
    } == true

    fun clear() { playing = false; pausedAt = null }
}
