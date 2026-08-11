package com.beyondlevi.nexus.plugin.tuya

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Tuya Smart Home hierarchy the plugin navigates: homes -> rooms ->
 * devices -> datapoints. Pure JVM (no Android) so the same class backs both the
 * plugin and the live JVM verification test.
 */
class TuyaCloud(
    private val api: TuyaApi,
    private val configuredUid: String = "",
) {
    private var cachedUid: String = configuredUid.trim()

    /** Why the last seed-device lookup failed, so [uid] can report the truth. */
    private var lastSeedFailure: TuyaApiException? = null

    /**
     * The account UID. The IoT project only knows the devices it was linked to,
     * so with no UID configured we read one device and take the UID that owns
     * it — that opens the whole account (every home), which the project-scoped
     * device endpoints alone do not.
     */
    fun uid(): String {
        cachedUid.takeIf(String::isNotEmpty)?.let { return it }
        val seed = seedDeviceId() ?: throw (
            // Surface why the lookup failed. Reporting "no device linked" when
            // Tuya actually refused the call (expired subscription, revoked
            // permission) sends the user to fix the wrong thing.
            lastSeedFailure ?: TuyaApiException(
                "no_seed",
                "no device is linked to this project; set the UID manually",
                "/v1.0/iot-01/associated-users/devices",
            )
            )
        val detail = api.get("/v1.0/devices/$seed")
        val uid = detail.optString("uid").trim()
        if (uid.isEmpty()) {
            throw TuyaApiException("no_uid", "seed device carries no uid", "/v1.0/devices/$seed")
        }
        cachedUid = uid
        return uid
    }

    private fun seedDeviceId(): String? {
        lastSeedFailure = null
        listOf<Pair<String, () -> String?>>(
            "/v1.0/iot-01/associated-users/devices" to {
                api.get("/v1.0/iot-01/associated-users/devices", mapOf("size" to "20"))
                    .list("devices").firstId()
            },
            "/v2.0/cloud/thing/device" to {
                api.get("/v2.0/cloud/thing/device", mapOf("page_size" to "20"))
                    .list("devices", "list").firstId()
            },
        ).forEach { (_, lookup) ->
            try {
                lookup()?.let { return it }
            } catch (failure: TuyaApiException) {
                lastSeedFailure = failure
            } catch (_: Throwable) {
                // Transport-level trouble: the next candidate may still answer.
            }
        }
        return null
    }

    fun homes(): List<TuyaHome> {
        val result = api.get("/v1.0/users/${uid()}/homes")
        return result.list("homes").mapNotNull { entry ->
            val id = entry.firstString("home_id", "homeId", "gid", "id")
            if (id.isEmpty()) return@mapNotNull null
            TuyaHome(
                id = id,
                name = entry.firstString("name").ifBlank { "Home $id" },
                role = entry.firstString("role"),
            )
        }
    }

    /** Rooms of a home plus every device grouped by the room it sits in. */
    fun homeSnapshot(home: TuyaHome): TuyaHomeSnapshot {
        val devices = homeDevices(home).associateBy(TuyaDevice::id).toMutableMap()
        val rooms = mutableListOf<TuyaRoom>()
        val assigned = mutableSetOf<String>()

        if (home.canReadRooms) {
            readRooms(home.id).forEach { (roomId, roomName) ->
                val ids = roomDeviceIds(home.id, roomId).filter(devices::containsKey)
                if (ids.isEmpty()) return@forEach
                assigned += ids
                rooms += TuyaRoom(id = roomId, name = roomName, deviceIds = ids)
            }
        }

        val leftovers = devices.keys - assigned
        if (leftovers.isNotEmpty()) {
            rooms += TuyaRoom(
                id = TuyaRoom.UNASSIGNED_ID,
                // When no room carries devices this IS the whole home, so name it plainly.
                name = if (rooms.isEmpty()) "All devices" else "Other devices",
                deviceIds = devices.keys.filter { it in leftovers },
            )
        }

        val byRoom = rooms.associate { room ->
            room.id to room.deviceIds.mapNotNull { id ->
                devices[id]?.copy(roomId = room.id)
            }
        }
        return TuyaHomeSnapshot(rooms = rooms, devicesByRoom = byRoom)
    }

    private data class RawRoom(val id: String, val name: String)

    /** `/v1.0/homes/{id}/rooms` answers `{..., "rooms":[{room_id, name}]}`. */
    private fun readRooms(homeId: String): List<RawRoom> {
        val result = runCatching { api.get("/v1.0/homes/$homeId/rooms") }.getOrNull() ?: return emptyList()
        return result.list("rooms").mapNotNull { entry ->
            val id = entry.firstString("room_id", "roomId", "id")
            if (id.isEmpty()) return@mapNotNull null
            RawRoom(id = id, name = entry.firstString("name").ifBlank { "Room $id" })
        }
    }

    /** The room's devices are a separate call; the room object never inlines them. */
    private fun roomDeviceIds(homeId: String, roomId: String): List<String> =
        runCatching {
            api.get("/v1.0/homes/$homeId/rooms/$roomId/devices")
                .list("devices")
                .mapNotNull { it.firstString("id", "device_id", "devId").takeIf(String::isNotEmpty) }
        }.getOrElse { emptyList() }

    /**
     * Devices of one home, taken from the account-wide list and filtered by
     * `owner_id`. That list is the only one that answers for a home the account
     * does not own — `/v1.0/homes/{id}/devices` returns `1106 permission deny`
     * there, so it is a fallback, not the primary source.
     */
    private fun homeDevices(home: TuyaHome): List<TuyaDevice> {
        val fromAccount = runCatching {
            api.get("/v1.0/users/${uid()}/devices").list("devices").map(::parseDevice)
        }.getOrElse { emptyList() }
        fromAccount.filter { it.homeId == home.id }
            .takeIf(List<TuyaDevice>::isNotEmpty)
            ?.let { return it }

        return runCatching {
            api.get("/v1.0/homes/${home.id}/devices").list("devices").map(::parseDevice)
        }.getOrElse { emptyList() }
    }

    private fun parseDevice(entry: JSONObject): TuyaDevice = TuyaDevice(
        id = entry.firstString("id", "device_id", "devId"),
        name = entry.firstString("name", "device_name").ifBlank { "Device" },
        category = entry.firstString("category", "category_code"),
        online = when (val raw = entry.opt("online") ?: entry.opt("is_online")) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            null -> true
            else -> true
        },
        homeId = entry.firstString("owner_id", "ownerId", "home_id"),
    )

    fun deviceDetail(device: TuyaDevice): TuyaDeviceDetail {
        var refusal: TuyaApiException? = null
        val functions = try {
            api.get("/v1.0/devices/${device.id}/functions")
        } catch (failure: TuyaApiException) {
            refusal = failure
            JSONObject()
        }
        val status = try {
            api.get("/v1.0/devices/${device.id}/status").rawList("status")
        } catch (failure: TuyaApiException) {
            refusal = refusal ?: failure
            JSONArray()
        }
        val detail = TuyaControlMapper.build(device, functions, status)
        if (detail.controls.isNotEmpty()) return detail
        // Some categories (Tuya code 2009) simply have no standard instruction
        // set — say so instead of showing an empty list.
        return detail.copy(
            // Kept short on purpose: the HUD subtitle is one ellipsised line,
            // and a longer sentence loses its tail on the glasses.
            note = when (refusal?.apiCode) {
                null -> "No datapoints reported."
                "2009" -> "No controls for this device type."
                else -> "Tuya refused this device (${refusal.apiCode})."
            },
        )
    }

    fun sendCommand(deviceId: String, code: String, value: Any) {
        val command = JSONObject().put("code", code).put("value", value)
        val body = JSONObject().put("commands", JSONArray().put(command))
        api.post("/v1.0/devices/$deviceId/commands", body)
    }
}

// ------------------------------------------------------------------ JSON glue

/** Tuya returns list results either bare or under a named key; accept both. */
internal fun JSONObject.rawList(vararg keys: String): JSONArray {
    optJSONArray(TuyaApi.RESULT_ARRAY_KEY)?.let { if (it.length() > 0 || keys.isEmpty()) return it }
    keys.forEach { key -> optJSONArray(key)?.let { return it } }
    return optJSONArray(TuyaApi.RESULT_ARRAY_KEY) ?: JSONArray()
}

internal fun JSONObject.list(vararg keys: String): List<JSONObject> {
    val array = rawList(*keys)
    return (0 until array.length()).mapNotNull(array::optJSONObject)
}

internal fun JSONObject.firstString(vararg keys: String): String {
    keys.forEach { key ->
        val raw = opt(key) ?: return@forEach
        val text = raw.toString().trim()
        if (text.isNotEmpty() && text != "null") return text
    }
    return ""
}

internal fun List<JSONObject>.firstId(): String? =
    firstNotNullOfOrNull { it.firstString("id", "device_id", "devId").takeIf(String::isNotEmpty) }
