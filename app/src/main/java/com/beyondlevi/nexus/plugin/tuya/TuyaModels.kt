package com.beyondlevi.nexus.plugin.tuya

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class TuyaHome(
    val id: String,
    val name: String,
    /** `OWNER`, `ADMIN`, `MEMBER` … as reported by Tuya. */
    val role: String = "",
) {
    /**
     * The home-scoped room endpoints answer only for the home's owner; on a
     * shared home they return `1106 permission deny`, so we do not call them.
     */
    val canReadRooms: Boolean get() = role.equals("OWNER", ignoreCase = true)
}

data class TuyaRoom(
    val id: String,
    val name: String,
    val deviceIds: List<String> = emptyList(),
) {
    companion object {
        /** Bucket for devices the account never assigned to a room. */
        const val UNASSIGNED_ID = "__unassigned"
    }
}

data class TuyaDevice(
    val id: String,
    val name: String,
    val category: String = "",
    val online: Boolean = true,
    /** The home that owns the device (`owner_id` on the wire). */
    val homeId: String = "",
    val roomId: String? = null,
)

/** How a single device datapoint is driven on the one-axis HUD. */
enum class TuyaControlKind {
    /** Boolean datapoint: SELECT flips it. */
    SWITCH,

    /** Enum datapoint: SELECT opens a value list. */
    ENUM,

    /** Integer datapoint: SELECT opens a stepper. */
    NUMBER,

    /** Reported but not writable (sensors, battery, countdown readouts). */
    READ_ONLY,
}

data class TuyaEnumOption(
    val raw: String,
    val label: String,
)

/**
 * One actionable (or observable) datapoint of a device, built by merging the
 * device's `functions` (what can be written) with its `status` (what it
 * currently reports).
 */
data class TuyaControl(
    val code: String,
    val label: String,
    val kind: TuyaControlKind,
    val value: Any? = null,
    val options: List<TuyaEnumOption> = emptyList(),
    val min: Int = 0,
    val max: Int = 100,
    val step: Int = 1,
    val scale: Int = 0,
    val unit: String = "",
) {
    val factor: Double get() = Math.pow(10.0, scale.toDouble())

    fun booleanValue(): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", ignoreCase = true) || value == "1"
        else -> false
    }

    fun intValue(): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        is Boolean -> if (value) 1 else 0
        else -> null
    }

    /**
     * Clamps to the datapoint's declared range and snaps to its step grid.
     * Tuya steps are offsets from `min` (a 16..30 step-3 datapoint accepts
     * 16/19/22/…), so the grid is anchored there, not at zero.
     */
    fun coerce(raw: Int): Int {
        val low = minOf(min, max)
        val high = maxOf(min, max)
        val clamped = raw.coerceIn(low, high)
        if (step <= 1) return clamped
        val steps = Math.round((clamped - low).toDouble() / step).toInt()
        var snapped = low + steps * step
        // The top of the range is rarely a multiple of the step; stay on the grid.
        while (snapped > high) snapped -= step
        return snapped.coerceAtLeast(low)
    }

    /** Human-readable current value for a HUD row trail. */
    fun displayValue(): String = when (kind) {
        TuyaControlKind.SWITCH -> if (booleanValue()) "on" else "off"
        TuyaControlKind.ENUM -> {
            val raw = value?.toString().orEmpty()
            options.firstOrNull { it.raw == raw }?.label ?: raw.ifBlank { "--" }
        }
        TuyaControlKind.NUMBER -> intValue()?.let { formatNumber(it) } ?: "--"
        TuyaControlKind.READ_ONLY -> value?.toString().orEmpty().ifBlank { "--" }
    }

    fun formatNumber(raw: Int): String {
        val scaled = raw / factor
        val text = if (scale <= 0) scaled.toInt().toString() else String.format(Locale.US, "%.${scale}f", scaled)
        return if (unit.isBlank()) text else "$text$unit"
    }

    val isActionable: Boolean get() = kind != TuyaControlKind.READ_ONLY
}

data class TuyaDeviceDetail(
    val device: TuyaDevice,
    val controls: List<TuyaControl>,
    /** Why there is nothing to show, when there is nothing to show. */
    val note: String? = null,
)

/**
 * Turns the raw `functions` + `status` payloads into the control list the HUD
 * drives. Pure and JVM-testable: no network, no Android.
 */
object TuyaControlMapper {

    /**
     * Datapoints that are noise on a HUD: countdowns, schedules, opaque blobs
     * and cloud-only bookkeeping. Dropping them keeps the one-axis list short.
     */
    private val HIDDEN_CODE_PREFIXES = listOf(
        "countdown",
        "timer",
        "schedule",
        "random_time",
        "cycle_time",
        "switch_backlight",
        "relay_status",
        "child_lock_",
    )

    private val HIDDEN_TYPES = setOf("raw", "bitmap", "json")

    /**
     * Codes whose reported status uses a different name than the writable
     * function (IR-controlled ACs are the usual offender: write `switch`,
     * report `power`). Each list is a family of aliases for one concept.
     */
    private val STATUS_ALIASES = listOf(
        listOf("switch", "switch_1", "switch_led", "power", "power_switch", "PowerSwitch"),
        listOf("temp_set", "temp", "temp_value", "TempSet", "temp_set_f", "temp_current"),
        listOf("bright_value", "bright_value_v2", "bright_value_1", "brightness"),
        // Verified on an infrared_ac VRF: writes `fan`, reports `wind`.
        listOf("fan", "fan_speed_enum", "windspeed", "fan_speed", "wind"),
        listOf("mode", "work_mode"),
    )

    /**
     * Friendly labels for the datapoints Tuya names after their own code
     * (infrared devices report `name` == `code`, so the raw HUD label would
     * read "switch" / "temp" / "fan").
     */
    private val FRIENDLY_LABELS = mapOf(
        "switch" to "Power",
        "switch_1" to "Power",
        "switch_led" to "Power",
        "power" to "Power",
        "temp" to "Temperature",
        "temp_set" to "Temperature",
        "temp_current" to "Current temperature",
        "mode" to "Mode",
        "work_mode" to "Mode",
        "fan" to "Fan speed",
        "fan_speed_enum" to "Fan speed",
        "wind" to "Fan speed",
        "bright_value" to "Brightness",
        "bright_value_v2" to "Brightness",
        "battery_percentage" to "Battery",
        "va_temperature" to "Temperature",
        "va_humidity" to "Humidity",
    )

    /** Preferred first row per Tuya category code, when the device exposes it. */
    private val PRIMARY_BY_CATEGORY = mapOf(
        "dj" to listOf("switch_led", "switch"),           // light
        "dd" to listOf("switch_led", "switch"),           // light strip
        "xdd" to listOf("switch_led", "switch"),          // ceiling light
        "kg" to listOf("switch_1", "switch"),             // switch
        "cz" to listOf("switch_1", "switch"),             // socket
        "pc" to listOf("switch_1", "switch"),             // power strip
        "tgkg" to listOf("switch_led_1", "switch_1"),     // dimmer switch
        "ktkzq" to listOf("switch", "power"),             // air conditioner (IR)
        "wk" to listOf("switch", "temp_set"),             // thermostat
        "cl" to listOf("control", "percent_control"),     // curtain
        "fs" to listOf("switch", "fan_speed_enum"),       // fan
    )

    fun build(
        device: TuyaDevice,
        functionsPayload: JSONObject,
        statusPayload: JSONArray,
    ): TuyaDeviceDetail {
        val status = readStatus(statusPayload)
        val controls = readFunctions(functionsPayload)
            .map { it.copy(value = resolveValue(it.code, status)) }
            .toMutableList()

        // Surface reported-only datapoints (sensors) that no function covers.
        val known = controls.map(TuyaControl::code).toMutableSet()
        status.forEach { (code, value) ->
            if (code in known || isHidden(code)) return@forEach
            if (aliasFamily(code)?.any { it in known } == true) return@forEach
            known += code
            controls += TuyaControl(
                code = code,
                label = labelFor(code, ""),
                kind = TuyaControlKind.READ_ONLY,
                value = value,
            )
        }

        return TuyaDeviceDetail(device, order(device.category, controls))
    }

    private fun readFunctions(payload: JSONObject): List<TuyaControl> {
        val array = payload.optJSONArray("functions") ?: JSONArray()
        val out = mutableListOf<TuyaControl>()
        val seen = mutableSetOf<String>()
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val code = entry.optString("code").trim()
            if (code.isEmpty() || !seen.add(code) || isHidden(code)) continue
            val type = entry.optString("type").lowercase(Locale.ROOT)
            if (type in HIDDEN_TYPES) continue
            val values = parseValues(entry.opt("values"))
            val label = labelFor(code, entry.optString("name"))
            out += when (type) {
                "boolean" -> TuyaControl(code, label, TuyaControlKind.SWITCH)
                "enum" -> {
                    val range = values.optJSONArray("range") ?: JSONArray()
                    val options = (0 until range.length())
                        .map { range.optString(it) }
                        .filter(String::isNotBlank)
                        .map { TuyaEnumOption(it, prettify(it)) }
                    if (options.isEmpty()) continue else TuyaControl(
                        code = code,
                        label = label,
                        kind = TuyaControlKind.ENUM,
                        options = options,
                    )
                }
                "integer" -> TuyaControl(
                    code = code,
                    label = label,
                    kind = TuyaControlKind.NUMBER,
                    min = values.optInt("min", 0),
                    max = values.optInt("max", 100),
                    step = values.optInt("step", 1).coerceAtLeast(1),
                    scale = values.optInt("scale", 0),
                    unit = values.optString("unit").trim(),
                )
                else -> TuyaControl(code, label, TuyaControlKind.READ_ONLY)
            }
        }
        return out
    }

    private fun readStatus(payload: JSONArray): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (index in 0 until payload.length()) {
            val entry = payload.optJSONObject(index) ?: continue
            val code = entry.optString("code").trim()
            if (code.isNotEmpty()) out[code] = entry.opt("value")
        }
        return out
    }

    private fun resolveValue(code: String, status: Map<String, Any?>): Any? {
        if (status.containsKey(code)) return status[code]
        aliasFamily(code)?.forEach { alias -> if (status.containsKey(alias)) return status[alias] }
        return null
    }

    private fun aliasFamily(code: String): List<String>? =
        STATUS_ALIASES.firstOrNull { family -> family.any { it.equals(code, ignoreCase = true) } }

    private fun order(category: String, controls: List<TuyaControl>): List<TuyaControl> {
        val preferred = PRIMARY_BY_CATEGORY[category].orEmpty()
        return controls.sortedWith(
            compareBy(
                { control -> preferred.indexOf(control.code).let { if (it < 0) Int.MAX_VALUE else it } },
                { control -> if (control.isActionable) 0 else 1 },
                { control -> KIND_ORDER.indexOf(control.kind) },
                { control -> control.label.lowercase(Locale.ROOT) },
            ),
        )
    }

    private fun parseValues(raw: Any?): JSONObject = when (raw) {
        is JSONObject -> raw
        is String -> runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        else -> JSONObject()
    }

    private fun isHidden(code: String): Boolean =
        HIDDEN_CODE_PREFIXES.any { code.startsWith(it, ignoreCase = true) }

    /**
     * Tuya's own `name` wins when it says something the code does not. Infrared
     * devices set `name` to the code verbatim, so those fall through to the
     * friendly table and then to prettifying.
     */
    fun labelFor(code: String, reportedName: String): String {
        val name = reportedName.trim()
        if (name.isNotEmpty() && !name.equals(code, ignoreCase = true)) return name
        return FRIENDLY_LABELS[code.lowercase(Locale.ROOT)] ?: prettify(code)
    }

    /** `bright_value_v2` -> `Bright value v2`; used when Tuya sends no name. */
    fun prettify(code: String): String = code
        .replace('_', ' ')
        .trim()
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

    private val KIND_ORDER = listOf(
        TuyaControlKind.SWITCH,
        TuyaControlKind.ENUM,
        TuyaControlKind.NUMBER,
        TuyaControlKind.READ_ONLY,
    )
}
