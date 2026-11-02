// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat

data class InstalledAppVersion(val name: String, val build: Long)

/** PackageManager describes the installed APK, even if a process keeps old inlined constants. */
fun installedAppVersion(context: Context): InstalledAppVersion {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return InstalledAppVersion(requireNotNull(info.versionName), PackageInfoCompat.getLongVersionCode(info))
}

internal fun updateAlreadyInstalled(release: UpdateRelease, installed: InstalledAppVersion): Boolean =
    UpdateProtocol.compareVersion(installed.name, release.version) >= 0 && installed.build >= release.build
