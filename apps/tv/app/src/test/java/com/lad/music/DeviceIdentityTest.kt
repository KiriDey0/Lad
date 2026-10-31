// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceIdentityTest {
    private val devices = listOf(Device("speaker-b", "Яндекс Станция", "", "cert"), Device("speaker-a", "Яндекс Станция", "", "cert"))
    private fun metadata() = JSONObject("""{"status":"ok","households":[{"id":"home","name":"Дом"}],"rooms":[{"id":"k","name":"Кухня","household_id":"home"},{"id":"r","name":"Спальня","household_id":"home"}],"devices":[{"id":"cloud-a","name":"Яндекс Станция","room":"k","household_id":"home","quasar_info":{"device_id":"speaker-a"}},{"id":"cloud-b","name":"Топаз","room":"r","household_id":"home","quasar_info":{"device_id":"speaker-b"}}]}""")

    @Test fun bindRoomsAndNamesOnlyByExplicitGlagolIdentity() {
        val result = enrichDeviceIdentities(devices, metadata())
        assertEquals("speaker-b", result[0].id)
        assertEquals("Топаз", result[0].displayTitle)
        assertEquals("Спальня", result[0].room)
        assertEquals("Кухня", result[1].displayTitle)
        assertEquals("Кухня · Дом", result[1].selectionLabel)
        assertFalse(deviceIdentityAmbiguous(result[0], result))
        val m = metadata()
        repeat(2) {
            m.getJSONArray("devices").getJSONObject(it).remove("quasar_info")
            m.getJSONArray("devices").getJSONObject(it).put("id", "speaker-a")
        }
        assertTrue(enrichDeviceIdentities(devices, m).all { it.room.isEmpty() })
    }

    @Test fun missingMetadataStaysUnknownAndDuplicateChoicesAreAmbiguous() {
        val result = enrichDeviceIdentities(devices, null)
        assertTrue(result.all { it.metadataStatus == "unavailable" && it.room.isEmpty() })
        assertTrue(deviceIdentityAmbiguous(result[0], result))
        assertTrue(result[0].locationDescription.contains("Комната недоступна"))
        assertFalse(deviceIdentityAmbiguous(result[0], listOf(result[0])))
        assertFalse(deviceIdentityAmbiguous(result[0], listOf(result[0], result[1].copy(room = "Кухня"))))
    }

    @Test fun conflictingBindingsAndRoomsInOtherHomesAreNotTrusted() {
        val m = metadata()
        m.getJSONArray("rooms").getJSONObject(0).put("household_id", "another-home")
        assertEquals("", enrichDeviceIdentities(devices, m)[1].room)
        m.getJSONArray("devices").put(JSONObject(m.getJSONArray("devices").getJSONObject(0).toString()))
        assertEquals("conflict", enrichDeviceIdentities(devices, m)[1].metadataStatus)
        m.getJSONArray("devices").remove(2)
        m.getJSONArray("devices").getJSONObject(0).put("room", JSONObject.NULL)
        assertEquals("", enrichDeviceIdentities(devices, m)[1].room)
    }
}
