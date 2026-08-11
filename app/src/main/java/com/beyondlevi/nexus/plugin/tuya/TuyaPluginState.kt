package com.beyondlevi.nexus.plugin.tuya

import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusCardLine
import com.anezium.rokidbus.client.plugin.NexusRowTone
import java.security.MessageDigest
import kotlin.math.ceil

/** What the state machine asks the runtime to do after an input. */
internal sealed interface TuyaEffect {
    data object None : TuyaEffect
    data object Close : TuyaEffect
    data object LoadHomes : TuyaEffect
    data class LoadHome(val home: TuyaHome) : TuyaEffect
    data class LoadDevice(val device: TuyaDevice) : TuyaEffect
    data class SendCommand(
        val device: TuyaDevice,
        val code: String,
        val value: Any,
        val label: String,
    ) : TuyaEffect
}

/** Everything the plugin knows about one home, fetched in a single step. */
data class TuyaHomeSnapshot(
    val rooms: List<TuyaRoom>,
    val devicesByRoom: Map<String, List<TuyaDevice>>,
)

/**
 * The whole HUD navigation model: homes -> rooms -> devices -> device controls,
 * driven exclusively by the four R08 verbs (NEXT / PREV / SELECT / BACK).
 *
 * Deliberately free of Android, coroutines and network so the one-axis contract
 * is proven by plain JVM tests.
 */
internal class TuyaPluginState {

    enum class View { MESSAGE, HOMES, ROOMS, DEVICES, DEVICE, ENUM_PICK, NUMBER_ADJUST }

    var view: View = View.MESSAGE
        private set

    var selectedIndex: Int = 0
        private set

    private var message: String = "Connecting to Tuya..."
    private var busy: Boolean = false
    private var status: String? = null

    private var homes: List<TuyaHome> = emptyList()
    private var rooms: List<TuyaRoom> = emptyList()
    private var devicesByRoom: Map<String, List<TuyaDevice>> = emptyMap()
    private var detail: TuyaDeviceDetail? = null

    private var home: TuyaHome? = null
    private var room: TuyaRoom? = null
    private var device: TuyaDevice? = null

    private var homeIndex = 0
    private var roomIndex = 0
    private var deviceIndex = 0
    private var controlIndex = 0

    private var pendingControl: TuyaControl? = null
    private var pendingNumber: Int = 0

    // ---------------------------------------------------------------- data in

    fun reset() {
        view = View.MESSAGE
        message = "Connecting to Tuya..."
        busy = true
        status = null
        homes = emptyList()
        rooms = emptyList()
        devicesByRoom = emptyMap()
        detail = null
        home = null
        room = null
        device = null
        homeIndex = 0
        roomIndex = 0
        deviceIndex = 0
        controlIndex = 0
        pendingControl = null
        selectedIndex = 0
    }

    fun showMessage(text: String) {
        view = View.MESSAGE
        message = text
        busy = false
        status = null
        selectedIndex = 0
    }

    fun setBusy(text: String?) {
        busy = true
        status = text
    }

    fun setStatus(text: String?) {
        busy = false
        status = text
    }

    fun applyHomes(loaded: List<TuyaHome>) {
        busy = false
        status = null
        homes = loaded
        if (loaded.isEmpty()) {
            showMessage("No homes on this Tuya account.")
            return
        }
        homeIndex = homeIndex.coerceIn(0, loaded.lastIndex)
        view = View.HOMES
        selectedIndex = homeIndex
    }

    fun applyHome(snapshot: TuyaHomeSnapshot) {
        busy = false
        status = null
        rooms = snapshot.rooms
        devicesByRoom = snapshot.devicesByRoom
        if (snapshot.rooms.isEmpty()) {
            view = View.MESSAGE
            message = "No rooms or devices in ${home?.name ?: "this home"}."
            selectedIndex = 0
            return
        }
        roomIndex = 0
        view = View.ROOMS
        selectedIndex = 0
    }

    fun applyDevice(loaded: TuyaDeviceDetail) {
        busy = false
        status = null
        detail = loaded
        device = loaded.device
        controlIndex = controlIndex.coerceIn(0, maxOf(0, deviceRowCount(loaded) - 1))
        view = View.DEVICE
        selectedIndex = controlIndex
    }

    /** A load failed: keep the user where they are and explain in the subtitle. */
    fun applyFailure(text: String) {
        busy = false
        if (view == View.MESSAGE || homes.isEmpty()) {
            showMessage(text)
        } else {
            status = text
        }
    }

    // ------------------------------------------------------------------ input

    fun move(delta: Int): Boolean {
        if (delta == 0) return false
        if (view == View.NUMBER_ADJUST) {
            val control = pendingControl ?: return false
            val next = control.coerce(pendingNumber + delta * control.step)
            if (next == pendingNumber) return false
            pendingNumber = next
            return true
        }
        val size = rowCount()
        if (size <= 0) return false
        selectedIndex = ((selectedIndex + delta) % size + size) % size
        rememberSelection()
        status = null
        return true
    }

    fun select(): TuyaEffect {
        if (busy) return TuyaEffect.None
        return when (view) {
            View.MESSAGE -> TuyaEffect.LoadHomes
            View.HOMES -> {
                val picked = homes.getOrNull(selectedIndex) ?: return TuyaEffect.None
                home = picked
                homeIndex = selectedIndex
                setBusy("Loading ${picked.name}...")
                TuyaEffect.LoadHome(picked)
            }
            View.ROOMS -> {
                val picked = rooms.getOrNull(selectedIndex) ?: return TuyaEffect.None
                room = picked
                roomIndex = selectedIndex
                deviceIndex = 0
                view = View.DEVICES
                selectedIndex = 0
                status = null
                TuyaEffect.None
            }
            View.DEVICES -> {
                val picked = roomDevices().getOrNull(selectedIndex) ?: return TuyaEffect.None
                device = picked
                deviceIndex = selectedIndex
                controlIndex = 0
                setBusy("Reading ${picked.name}...")
                TuyaEffect.LoadDevice(picked)
            }
            View.DEVICE -> selectDeviceRow()
            View.ENUM_PICK -> {
                val control = pendingControl ?: return TuyaEffect.None
                val option = control.options.getOrNull(selectedIndex) ?: return TuyaEffect.None
                val target = device ?: return TuyaEffect.None
                view = View.DEVICE
                selectedIndex = controlIndex
                setBusy("${control.label}: ${option.label}")
                TuyaEffect.SendCommand(target, control.code, option.raw, "${control.label} ${option.label}")
            }
            View.NUMBER_ADJUST -> {
                val control = pendingControl ?: return TuyaEffect.None
                val target = device ?: return TuyaEffect.None
                view = View.DEVICE
                selectedIndex = controlIndex
                setBusy("${control.label}: ${control.formatNumber(pendingNumber)}")
                TuyaEffect.SendCommand(
                    target,
                    control.code,
                    pendingNumber,
                    "${control.label} ${control.formatNumber(pendingNumber)}",
                )
            }
        }
    }

    fun back(): TuyaEffect = when (view) {
        View.MESSAGE -> TuyaEffect.Close
        View.HOMES -> TuyaEffect.Close
        View.ROOMS -> {
            view = View.HOMES
            selectedIndex = homeIndex
            status = null
            busy = false
            TuyaEffect.None
        }
        View.DEVICES -> {
            view = View.ROOMS
            selectedIndex = roomIndex
            status = null
            busy = false
            TuyaEffect.None
        }
        View.DEVICE -> {
            view = View.DEVICES
            selectedIndex = deviceIndex
            detail = null
            status = null
            busy = false
            TuyaEffect.None
        }
        View.ENUM_PICK, View.NUMBER_ADJUST -> {
            view = View.DEVICE
            selectedIndex = controlIndex
            pendingControl = null
            status = null
            busy = false
            TuyaEffect.None
        }
    }

    private fun selectDeviceRow(): TuyaEffect {
        val loaded = detail ?: return TuyaEffect.None
        val target = device ?: return TuyaEffect.None
        controlIndex = selectedIndex
        if (selectedIndex == loaded.controls.size) {
            // Trailing "Refresh" row.
            setBusy("Refreshing...")
            return TuyaEffect.LoadDevice(target)
        }
        val control = loaded.controls.getOrNull(selectedIndex) ?: return TuyaEffect.None
        return when (control.kind) {
            TuyaControlKind.SWITCH -> {
                val next = !control.booleanValue()
                setBusy("${control.label}: ${if (next) "on" else "off"}")
                TuyaEffect.SendCommand(
                    target,
                    control.code,
                    next,
                    "${control.label} ${if (next) "on" else "off"}",
                )
            }
            TuyaControlKind.ENUM -> {
                pendingControl = control
                view = View.ENUM_PICK
                selectedIndex = control.options
                    .indexOfFirst { it.raw == control.value?.toString() }
                    .coerceAtLeast(0)
                TuyaEffect.None
            }
            TuyaControlKind.NUMBER -> {
                pendingControl = control
                pendingNumber = control.coerce(control.intValue() ?: control.min)
                view = View.NUMBER_ADJUST
                TuyaEffect.None
            }
            TuyaControlKind.READ_ONLY -> {
                status = "${control.label} is read-only."
                TuyaEffect.None
            }
        }
    }

    private fun rememberSelection() {
        when (view) {
            View.HOMES -> homeIndex = selectedIndex
            View.ROOMS -> roomIndex = selectedIndex
            View.DEVICES -> deviceIndex = selectedIndex
            View.DEVICE -> controlIndex = selectedIndex
            else -> Unit
        }
    }

    private fun rowCount(): Int = when (view) {
        View.MESSAGE -> 0
        View.HOMES -> homes.size
        View.ROOMS -> rooms.size
        View.DEVICES -> roomDevices().size
        View.DEVICE -> detail?.let(::deviceRowCount) ?: 0
        View.ENUM_PICK -> pendingControl?.options?.size ?: 0
        View.NUMBER_ADJUST -> 0
    }

    /** Controls plus the trailing Refresh row. */
    private fun deviceRowCount(loaded: TuyaDeviceDetail): Int = loaded.controls.size + 1

    private fun roomDevices(): List<TuyaDevice> = devicesByRoom[room?.id].orEmpty()

    // -------------------------------------------------------------- rendering

    fun card(): NexusCard = when (view) {
        View.MESSAGE -> messageCard()
        View.HOMES -> listCard(
            title = "Tuya",
            rows = homes.map { row(it.name, sub = null, trail = "") },
            hint = "tap opens · back exits",
        )
        View.ROOMS -> listCard(
            title = home?.name ?: "Home",
            rows = rooms.map { room ->
                val count = devicesByRoom[room.id]?.size ?: 0
                row(room.name, sub = null, trail = if (count > 0) "$count" else "0")
            },
            hint = "tap opens · back = homes",
        )
        View.DEVICES -> listCard(
            title = room?.name ?: "Room",
            rows = roomDevices().map { device ->
                row(
                    text = device.name,
                    sub = null,
                    trail = if (device.online) "" else "offline",
                    tone = if (device.online) NexusRowTone.NORMAL else NexusRowTone.DIM,
                )
            },
            hint = "tap opens · back = rooms",
        )
        View.DEVICE -> deviceCard()
        View.ENUM_PICK -> {
            val control = pendingControl
            listCard(
                title = control?.label ?: "Options",
                rows = control?.options.orEmpty().map { option ->
                    row(
                        text = option.label,
                        sub = null,
                        trail = if (option.raw == control?.value?.toString()) "now" else "",
                    )
                },
                hint = "tap applies · back cancels",
            )
        }
        View.NUMBER_ADJUST -> numberCard()
    }

    private fun messageCard(): NexusCard {
        val lines = listOf(message)
        return NexusCard(
            title = "Tuya",
            lines = lines,
            footer = "back exits",
            contentKey = contentKey("message", lines.joinToString("|")),
            handlesBack = true,
            subtitle = status,
        )
    }

    private fun deviceCard(): NexusCard {
        val loaded = detail ?: return messageCard()
        val rows = loaded.controls.map { control ->
            row(
                text = control.label,
                sub = null,
                trail = control.displayValue(),
                tone = if (control.isActionable) NexusRowTone.NORMAL else NexusRowTone.DIM,
            )
        } + row("Refresh", sub = null, trail = "")
        return listCard(
            title = loaded.device.name,
            rows = rows,
            hint = "tap acts · back = devices",
            // A device with no datapoints still needs to say why, and BACK must
            // keep meaning "up to the device list" — so this stays a DEVICE card.
            banner = loaded.note.takeIf { loaded.controls.isEmpty() },
        )
    }

    private fun numberCard(): NexusCard {
        val control = pendingControl ?: return messageCard()
        val lines = listOf(
            control.formatNumber(pendingNumber),
            "range ${control.formatNumber(control.min)} - ${control.formatNumber(control.max)}",
        )
        return NexusCard(
            title = control.label,
            lines = lines,
            footer = "swipe adjusts · tap applies · back cancels",
            contentKey = contentKey("number", "${control.code}|$pendingNumber"),
            handlesBack = true,
            subtitle = device?.name,
        )
    }

    /**
     * Renders a page of focusable rows. The card surface never scrolls to the
     * marked row, so the emitted slice always contains the focused index and the
     * position hint lives in the footer, keeping every emitted line focusable.
     */
    private fun listCard(
        title: String,
        rows: List<NexusCardLine>,
        hint: String,
        banner: String? = null,
    ): NexusCard {
        if (rows.isEmpty()) {
            return NexusCard(
                title = title.cardText(TITLE_LIMIT),
                lines = listOf("Nothing here."),
                footer = hint,
                contentKey = contentKey("empty", title),
                handlesBack = true,
                subtitle = status,
            )
        }
        val page = selectedIndex / ROWS_PER_PAGE
        val start = page * ROWS_PER_PAGE
        val end = minOf(start + ROWS_PER_PAGE, rows.size)
        val slice = rows.subList(start, end).mapIndexed { offset, line ->
            line.copy(selected = start + offset == selectedIndex)
        }
        val pageCount = ceil(rows.size.toDouble() / ROWS_PER_PAGE).toInt().coerceAtLeast(1)
        val footer = buildString {
            append(selectedIndex + 1)
            append('/')
            append(rows.size)
            if (pageCount > 1) {
                append(" · page ")
                append(page + 1)
                append('/')
                append(pageCount)
            }
            append(" · ")
            append(hint)
        }.cardText(FOOTER_LIMIT)

        return NexusCard(
            title = title.cardText(TITLE_LIMIT),
            lines = emptyList(),
            footer = footer,
            contentKey = contentKey(
                view.name,
                buildString {
                    append(title).append('|').append(status).append('|').append(banner)
                    append('|').append(footer)
                    slice.forEach {
                        append('|').append(it.text).append('~').append(it.sub)
                            .append('~').append(it.trail.joinToString(",")).append('~').append(it.selected)
                    }
                },
            ),
            richLines = slice,
            handlesBack = true,
            subtitle = (status ?: banner ?: if (busy) "Working..." else null)?.cardText(SUB_LIMIT),
        )
    }

    /**
     * Builds one list row. The HUD draws a row title on a single ellipsised
     * line and puts `trail` to its right, so the title is sized against the
     * trail width instead of being clipped by the renderer. `badge` is never
     * drawn on a list row — per-row values ride in `trail`.
     */
    private fun row(
        text: String,
        sub: String?,
        trail: String,
        tone: NexusRowTone = NexusRowTone.NORMAL,
    ): NexusCardLine {
        val trailText = trail.cardText(TRAIL_LIMIT)
        val titleColumns = if (trailText.isEmpty()) {
            TITLE_COLUMNS
        } else {
            (TITLE_COLUMNS - (ceil(trailText.length * TRAIL_COST).toInt() + 1)).coerceAtLeast(8)
        }
        return NexusCardLine(
            text = text.cardText(titleColumns),
            sub = sub?.cardText(SUB_COLUMNS)?.takeIf(String::isNotBlank),
            trail = if (trailText.isEmpty()) emptyList() else listOf(trailText),
            tone = tone,
        )
    }

    private fun contentKey(scope: String, content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$scope|$content".toByteArray(Charsets.UTF_8))
        return buildString(40) {
            append(scope.take(6)).append('-')
            for (index in 0 until 12) {
                val byte = digest[index].toInt() and 0xff
                append(HEX[byte ushr 4])
                append(HEX[byte and 0x0f])
            }
        }
    }

    internal companion object {
        /** The card body clips at 15 text lines; 12 rows leaves slack for wraps. */
        const val ROWS_PER_PAGE = 12

        /** Measured RG-glasses row metrics (see the field notes). */
        const val TITLE_COLUMNS = 28
        const val SUB_COLUMNS = 36
        const val TRAIL_LIMIT = 24
        const val TRAIL_COST = 0.8

        const val TITLE_LIMIT = 120
        const val FOOTER_LIMIT = 240
        const val SUB_LIMIT = 240

        private const val HEX = "0123456789abcdef"
    }
}

private fun String.cardText(limit: Int): String {
    val collapsed = trim().replace(Regex("\\s+"), " ")
    return if (collapsed.length <= limit) collapsed else collapsed.take(limit)
}
