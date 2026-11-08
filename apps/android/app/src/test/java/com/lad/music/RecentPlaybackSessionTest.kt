// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import org.junit.Assert.*
import org.junit.Test

class RecentPlaybackSessionTest {
    private var now = 10_000L
    private val hour = 60 * 60 * 1000L
    private val session = RecentPlaybackSession({ now })

    @Test fun restoredQueueIsNotEvidenceOfRecentPlayback() {
        session.update(false, true)
        assertFalse(session.available())
    }

    @Test fun longPlaybackDoesNotExpireAndPauseStartsRetention() {
        session.update(true, true)
        now += hour * 4
        assertTrue(session.available())
        session.update(false, true)
        now += hour - 1
        assertTrue(session.available())
        now++
        assertFalse(session.available())
    }

    @Test fun positionAndMetadataUpdatesDoNotExtendPausedSession() {
        session.update(true, true)
        session.update(false, true)
        repeat(10) { now += hour / 10; session.update(false, true) }
        assertFalse(session.available())
    }

    @Test fun resumeStartsANewRetentionWindowOnNextPause() {
        session.update(true, true)
        session.update(false, true)
        now += hour * 2
        assertFalse(session.available())
        session.update(true, true)
        assertTrue(session.available())
        session.update(false, true)
        assertTrue(session.available())
    }

    @Test fun stopOrRemovedTrackClearsSession() {
        session.update(true, true)
        session.update(false, true)
        session.clear()
        assertFalse(session.available())
        session.update(true, true)
        session.update(false, false)
        assertFalse(session.available())
    }

    @Test fun timeMovingBackCannotReviveAPausedSession() {
        session.update(true, true)
        session.update(false, true)
        now--
        assertFalse(session.available())
    }

    @Test fun pausedSessionStillHonorsLockscreenGuards() {
        session.update(true, true)
        session.update(false, true)
        val settings = LockScreenSettings(enabled = true, automatic = true)
        fun show(locked: Boolean = true, call: Boolean = false, dismissed: Boolean = false) =
            shouldShowLockPlayer(settings, locked, session.available(), true, true, call, dismissed)
        assertTrue(show())
        assertFalse(show(locked = false))
        assertFalse(show(call = true))
        assertFalse(show(dismissed = true))
        now += hour
        assertFalse(show())
    }
}
