// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import org.junit.Assert.*
import org.junit.Test

class LockScreenSettingsTest {
    @Test fun upgradesAndInvalidPreferencesKeepTheFeatureOptIn() {
        for(value in listOf(null,"", "broken", "{}", "{\"style\":\"unknown\"}")) {
            val s=LockScreenSettings.parse(value)
            assertFalse(s.enabled);assertFalse(s.automatic);assertEquals(LockStyle.ATMOSPHERE,s.style)
        }
    }
    @Test fun eachStyleRetainsItsOwnClockBackgroundAndMotion() {
        val first=LockScreenSettings(enabled=true,style=LockStyle.AIR)
            .withOptions(LockStyleOptions(clock=LockClock.COMPACT,seconds=true,date=false,tone=LockTone.BLACK,motion=LockMotion.OFF))
        val second=first.copy(style=LockStyle.ATMOSPHERE).withOptions(LockStyleOptions(format=LockTimeFormat.H12,tone=LockTone.SOFT))
        val saved=LockScreenSettings.parse(second.json().toString())
        assertEquals(first.options(),saved.options(LockStyle.AIR))
        assertEquals(LockTimeFormat.H12,saved.options().format)
        assertEquals(LockClock.LIGHT,saved.options(LockStyle.MUSIC).clock)
        assertEquals(second,saved)
    }
    @Test fun unknownFutureFieldsFallBackWithoutLosingExistingOptions() {
        val s=LockScreenSettings.parse("""{"enabled":true,"style":"air","styles":{"air":{"clock":"future","seconds":true,"tone":"future"}}}""")
        assertTrue(s.enabled);assertTrue(s.options().seconds);assertEquals(LockClock.LIGHT,s.options().clock);assertEquals(LockTone.DARK,s.options().tone)
    }
    @Test fun screenAwakeOptionsRoundTripIndependently() {
        val s = LockScreenSettings(enabled = true, keepScreenOnLyrics = true)
        val saved = LockScreenSettings.parse(s.json().toString())
        assertFalse(saved.keepScreenOn)
        assertTrue(saved.keepScreenOnLyrics)
    }
    @Test fun automaticDisplayRequiresEveryGuard() {
        val s=LockScreenSettings(enabled=true,automatic=true)
        fun show(locked:Boolean=true,playing:Boolean=true,track:Boolean=true,permission:Boolean=true,call:Boolean=false,dismissed:Boolean=false,settings:LockScreenSettings=s)=
            shouldShowLockPlayer(settings,locked,playing,track,permission,call,dismissed)
        assertTrue(show());assertFalse(show(locked=false));assertFalse(show(playing=false));assertFalse(show(track=false))
        assertFalse(show(permission=false));assertFalse(show(call=true));assertFalse(show(dismissed=true))
        assertFalse(show(settings=s.copy(enabled=false)));assertFalse(show(settings=s.copy(automatic=false)))
    }
}
