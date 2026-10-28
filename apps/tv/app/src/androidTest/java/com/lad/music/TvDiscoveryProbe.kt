// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import android.test.InstrumentationTestCase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Own-account-independent LAN discovery; no music or authorization requests. */
@Suppress("DEPRECATION")
class TvDiscoveryProbe : InstrumentationTestCase() {
    fun testLanDiscovery() = runBlocking {
        val result = StationDiscovery(instrumentation.targetContext).discover()
        val array = JSONArray()
        result.values.forEach { value ->
            array.put(
                JSONObject()
                    .put("host", value.first)
                    .put("port", value.second)
                    .put("platform", value.third)
            )
        }
        java.io
            .File(instrumentation.targetContext.getExternalFilesDir(null), "discovery-probe.json")
            .writeText(array.toString(2))
        assertTrue("Yandex devices must resolve on the actual LAN", result.isNotEmpty())
    }
}
