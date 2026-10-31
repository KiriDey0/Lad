// SPDX-License-Identifier: AGPL-3.0-only
package com.lad.music

import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

private fun cleanDeviceText(value: String) = value.trim().replace(Regex("\\s+"), " ").take(160)
private fun deviceKey(value: String) = Normalizer.normalize(cleanDeviceText(value), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

/** The cloud device ID and Glagol ID are different. Only quasar_info is an explicit binding. */
fun enrichDeviceIdentities(devices: List<Device>, info: JSONObject?): List<Device> {
    val valid = info?.text("status") == "ok" && info.optJSONArray("devices") != null
    val records = if (valid) info!!.array("devices").objects() else emptyList()
    val index = records.filter { it.optJSONObject("quasar_info")?.text("device_id").orEmpty().isNotBlank() }
        .groupBy { it.getJSONObject("quasar_info").text("device_id") }
    return devices.map { original ->
        val d = original.copy(model = original.model.ifBlank { original.name }, room = "", household = "",
            metadataStatus = if (valid) "unmatched" else "unavailable")
        val matches = index[d.id].orEmpty()
        if (matches.size != 1) d.copy(metadataStatus = if (matches.size > 1) "conflict" else d.metadataStatus)
        else {
            val m = matches.single()
            val house = m.text("household_id")
            val room = m.text("room")
            val rooms = info!!.array("rooms").objects().filter {
                room.isNotBlank() && it.text("id") == room && it.text("household_id") == house
            }
            val houses = info.array("households").objects().filter { house.isNotBlank() && it.text("id") == house }
            d.copy(name = cleanDeviceText(m.text("name")).ifBlank { d.model },
                room = cleanDeviceText(rooms.singleOrNull()?.text("name").orEmpty()),
                household = cleanDeviceText(houses.singleOrNull()?.text("name").orEmpty()), metadataStatus = "ready")
        }
    }
}

val Device.displayTitle: String get() = if (room.isNotBlank() && deviceKey(name) == deviceKey(model)) room else name
val Device.selectionLabel: String get() = listOf(displayTitle, room, household).filter { it.isNotBlank() }.distinctBy(::deviceKey).joinToString(" · ")
val Device.locationDescription: String get() = listOf(
    room.takeIf { it != displayTitle }.orEmpty(),
    model.takeIf { it != displayTitle }.orEmpty(), household,
    if (room.isBlank()) (if (metadataStatus == "ready") "Комната не указана" else "Комната недоступна") else "",
).filter { it.isNotBlank() }.distinctBy(::deviceKey).joinToString(" · ")

fun deviceIdentityAmbiguous(device: Device, devices: List<Device>): Boolean {
    fun identity(d: Device) = listOf(d.name, d.room, d.household).map(::deviceKey)
    return devices.any { it.id != device.id && identity(it) == identity(device) }
}

const val AMBIGUOUS_DEVICE = "У нескольких Станций совпадают имя и комната. Лад не может определить, где включится звук."

fun requireIdentifiableDevice(device: Device, devices: List<Device>, allowAmbiguous: Boolean = false) {
    check(allowAmbiguous || !deviceIdentityAmbiguous(device, devices)) { AMBIGUOUS_DEVICE }
}
