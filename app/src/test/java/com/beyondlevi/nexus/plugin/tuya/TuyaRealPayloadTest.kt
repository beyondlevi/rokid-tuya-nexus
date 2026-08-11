package com.beyondlevi.nexus.plugin.tuya

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing pinned against payloads captured verbatim from the live account
 * (`src/test/resources/fixtures`), so a change in our parsing that would break
 * a real device fails here rather than on the glasses.
 *
 * The VRF 03 fixture is an `infrared_ac` and is the awkward case on purpose: it
 * repeats every function four times, names each datapoint after its own code,
 * ships `values` as a JSON string, reports numbers as strings, and reports
 * under different codes than it accepts.
 */
class TuyaRealPayloadTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing fixture $name"
        }.bufferedReader().readText()

    private fun homesResult() = JSONObject().put(TuyaApi.RESULT_ARRAY_KEY, JSONArray(fixture("homes.json")))

    private val vrf03 = TuyaDevice(
        id = "eb349a15f549d54711i67m",
        name = "VRF 03",
        category = "infrared_ac",
        online = true,
        homeId = "262103233",
    )

    private fun vrf03Detail(): TuyaDeviceDetail = TuyaControlMapper.build(
        vrf03,
        JSONObject(fixture("vrf03_functions.json")),
        JSONArray(fixture("vrf03_status.json")),
    )

    @Test
    fun `the account's homes parse with their role`() {
        val homes = homesResult().list("homes").map { entry ->
            TuyaHome(
                id = entry.firstString("home_id"),
                name = entry.firstString("name"),
                role = entry.firstString("role"),
            )
        }
        assertEquals(2, homes.size)

        val extreme = homes.single { it.name.trim() == "Extreme Digital" }
        assertEquals("262103233", extreme.id)
        assertEquals("ADMIN", extreme.role)
        assertTrue(
            "a home the account only administers cannot serve its rooms",
            !extreme.canReadRooms,
        )
        assertTrue("the owned home can", homes.single { it.name == "Nobrega" }.canReadRooms)
    }

    @Test
    fun `rooms come from the rooms key, without inline devices`() {
        val rooms = JSONObject(fixture("rooms_nobrega.json")).list("rooms")
        assertEquals(6, rooms.size)
        assertEquals("Sala de Estar", rooms.first().firstString("name"))
        assertEquals("62347749", rooms.first().firstString("room_id"))
        assertTrue(
            "no room inlines its devices, so they must be fetched per room",
            rooms.all { it.list("device_list", "deviceList", "devices").isEmpty() },
        )
    }

    @Test
    fun `VRF 03 collapses its repeated functions into four controls`() {
        val controls = vrf03Detail().controls
        assertEquals(
            "the payload repeats each function four times",
            listOf("switch", "mode", "fan", "temp"),
            controls.map(TuyaControl::code).sortedBy {
                listOf("switch", "mode", "fan", "temp").indexOf(it)
            },
        )
        assertEquals(4, controls.size)
    }

    @Test
    fun `VRF 03 datapoints get their kinds, ranges and friendly labels`() {
        val byCode = vrf03Detail().controls.associateBy(TuyaControl::code)

        val power = byCode.getValue("switch")
        assertEquals(TuyaControlKind.SWITCH, power.kind)
        assertEquals("a datapoint named after its code gets a readable label", "Power", power.label)

        val mode = byCode.getValue("mode")
        assertEquals(TuyaControlKind.ENUM, mode.kind)
        assertEquals(
            listOf("dehumidification", "cold", "auto", "wind_dry", "heat"),
            mode.options.map(TuyaEnumOption::raw),
        )

        val fan = byCode.getValue("fan")
        assertEquals(TuyaControlKind.ENUM, fan.kind)
        assertEquals("Fan speed", fan.label)

        val temp = byCode.getValue("temp")
        assertEquals(TuyaControlKind.NUMBER, temp.kind)
        assertEquals("Temperature", temp.label)
        assertEquals(16, temp.min)
        assertEquals(30, temp.max)
        assertEquals(1, temp.step)
        assertEquals("℃", temp.unit)
    }

    @Test
    fun `VRF 03 resolves the status codes it reports under, not the ones it accepts`() {
        val byCode = vrf03Detail().controls.associateBy(TuyaControl::code)

        // status: power="1", temp="22", wind="0" — strings, and aliased codes.
        assertTrue("`power` resolves onto the writable `switch`", byCode.getValue("switch").booleanValue())
        assertEquals("on", byCode.getValue("switch").displayValue())
        assertEquals("a numeric string parses", 22, byCode.getValue("temp").intValue())
        assertEquals("22℃", byCode.getValue("temp").displayValue())
        assertNotNull("`wind` resolves onto the writable `fan`", byCode.getValue("fan").value)
    }

    @Test
    fun `an enum whose reported value is not one of its options shows the raw value`() {
        // The IR bridge reports mode as an index ("0"), not as one of the
        // writable option strings. Rendering a guessed option would state
        // something we cannot verify, so the raw value is shown instead.
        val mode = vrf03Detail().controls.single { it.code == "mode" }
        assertEquals("0", mode.value?.toString())
        assertEquals("0", mode.displayValue())
        assertTrue(
            "it stays selectable, because writing uses the declared options",
            mode.isActionable,
        )
    }

    @Test
    fun `the HUD renders VRF 03 as a walkable one-axis card`() {
        val state = TuyaPluginState()
        state.reset()
        state.applyHomes(listOf(TuyaHome("262103233", "Extreme Digital", "ADMIN")))
        state.select()
        state.applyHome(
            TuyaHomeSnapshot(
                rooms = listOf(TuyaRoom(TuyaRoom.UNASSIGNED_ID, "All devices", listOf(vrf03.id))),
                devicesByRoom = mapOf(TuyaRoom.UNASSIGNED_ID to listOf(vrf03)),
            ),
        )
        state.select()
        state.select()
        state.applyDevice(vrf03Detail())

        val card = state.card()
        assertEquals("VRF 03", card.title)
        val rows = card.richLines.orEmpty()
        assertEquals("four datapoints plus Refresh", 5, rows.size)
        assertEquals(1, rows.count { it.selected })
        assertEquals("Power", rows.first().text)
        assertEquals(listOf("on"), rows.first().trail)
        assertTrue(checkNotNull(card.contentKey).length <= 128)

        // Every row reachable, and SELECT on Temperature opens the stepper
        // seeded from the reported 22 °C.
        repeat(3) { state.move(1) }
        assertEquals("Temperature", state.card().richLines.orEmpty().first { it.selected }.text)
        state.select()
        state.move(1)
        val effect = state.select() as TuyaEffect.SendCommand
        assertEquals("temp", effect.code)
        assertEquals("one step up from the reported value", 23, effect.value)
    }
}
