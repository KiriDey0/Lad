// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.test.InstrumentationTestCase
import android.os.Build

/** Uses the separate QA package and its own APK; never reads the user's account. */
@Suppress("DEPRECATION")
class UpdateApkSignatureTest : InstrumentationTestCase() {
    fun testInstalledAndArchiveSignaturesMatch() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".qa"))
        val pm = context.packageManager
        val flags = updateSignatureFlags()
        val installed = pm.getPackageInfo(context.packageName, flags)
        val archive = pm.getPackageArchiveInfo(context.applicationInfo.sourceDir, flags)
        assertNotNull(archive)
        if (Build.VERSION.SDK_INT >= 28) {
            val old = installed.signingInfo?.apkContentsSigners
            val fresh = archive!!.signingInfo?.apkContentsSigners
            assertFalse(old.isNullOrEmpty())
            assertEquals(old!!.toSet(), fresh?.toSet())
        } else {
            assertFalse(installed.signatures.isNullOrEmpty())
            assertEquals(installed.signatures!!.toSet(), archive!!.signatures?.toSet())
        }
    }
}
