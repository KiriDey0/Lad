// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music
import org.junit.Assert.*
import org.junit.Test

class InstalledAppVersionTest {
    private val target = UpdateRelease("0.5.3",50003,"Lad.apk","https://github.com/","0".repeat(64),1,"",true)
    @Test fun installedMetadataWinsOverOldProcessVersion() {
        assertTrue(updateAlreadyInstalled(target, InstalledAppVersion("0.5.3",50003)))
        assertTrue(updateAlreadyInstalled(target, InstalledAppVersion("0.5.4",50004)))
    }
    @Test fun cancelledOrIncompleteInstallStaysAvailable() {
        assertFalse(updateAlreadyInstalled(target, InstalledAppVersion("0.5.2",50002)))
        assertFalse(updateAlreadyInstalled(target, InstalledAppVersion("0.5.3",50002)))
        assertFalse(updateAlreadyInstalled(target, InstalledAppVersion("0.5.2",50003)))
    }
}
