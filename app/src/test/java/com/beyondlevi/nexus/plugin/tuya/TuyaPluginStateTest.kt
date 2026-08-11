package com.beyondlevi.nexus.plugin.tuya

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The R08 one-axis navigability contract: every action in every view is
 * reachable with NEXT / PREV / SELECT / BACK alone, selection wraps, exactly
 * one row is marked, and BACK never dead-ends.
 */
class TuyaPluginStateTest {

    private val homes = listOf(
        TuyaHome("h1", "Casa"),
        TuyaHome("h2", "Praia"),
    )

    private val lamp = TuyaDevice("d1", "Abajur", category = "dj", online = true)
    private val ac = TuyaDevice("d2", "Ar condicionado", category = "ktkzq", online = true)
    private val sensor = TuyaDevice("d3", "Sensor", category = "wsdcg", online = false)

    private val snapshot = TuyaHomeSnapshot(
        rooms = listOf(
            TuyaRoom("r1", "Sala", listOf("d1", "d2")),
            TuyaRoom("r2", "Quarto", listOf("d3")),
        ),
        devicesByRoom = mapOf(
            "r1" to listOf(lamp, ac),
            "r2" to listOf(sensor),
        ),
    )

    private val lampDetail = TuyaDeviceDetail(
        device = lamp,
        controls = listOf(
            TuyaControl("switch_led", "Power", TuyaControlKind.SWITCH, value = false),
            TuyaControl(
                code = "work_mode",
                label = "Mode",
                kind = TuyaControlKind.ENUM,
                value = "white",
                options = listOf(
                    TuyaEnumOption("white", "White"),
                    TuyaEnumOption("colour", "Colour"),
                    TuyaEnumOption("scene", "Scene"),
                ),
            ),
            TuyaControl(
                code = "bright_value_v2",
                label = "Brightness",
                kind = TuyaControlKind.NUMBER,
                value = 500,
                min = 10,
                max = 1000,
                step = 10,
            ),
            TuyaControl("battery", "Battery", TuyaControlKind.READ_ONLY, value = 87),
        ),
    )

    private fun openedAtHomes(): TuyaPluginState = TuyaPluginState().apply {
        reset()
        applyHomes(homes)
    }

    private fun openedAtDevice(): TuyaPluginState = openedAtHomes().apply {
        select()                       // home -> LoadHome
        applyHome(snapshot)
        select()                       // room -> DEVICES
        select()                       // device -> LoadDevice
        applyDevice(lampDetail)
    }

    // ------------------------------------------------------------ navigation

    @Test
    fun `home list wraps in both directions`() {
        val state = openedAtHomes()
        assertEquals(TuyaPluginState.View.HOMES, state.view)
        assertEquals(0, state.selectedIndex)

        state.move(-1)
        assertEquals("PREV from the first row wraps to the last", 1, state.selectedIndex)
        state.move(1)
        assertEquals("NEXT from the last row wraps to the first", 0, state.selectedIndex)
    }

    @Test
    fun `select walks homes to rooms to devices to controls`() {
        val state = openedAtHomes()

        assertEquals(TuyaEffect.LoadHome(homes[0]), state.select())
        state.applyHome(snapshot)
        assertEquals(TuyaPluginState.View.ROOMS, state.view)

        assertEquals(TuyaEffect.None, state.select())
        assertEquals(TuyaPluginState.View.DEVICES, state.view)

        assertEquals(TuyaEffect.LoadDevice(lamp), state.select())
        state.applyDevice(lampDetail)
        assertEquals(TuyaPluginState.View.DEVICE, state.view)
    }

    @Test
    fun `back pops every level and restores the previous focus`() {
        val state = openedAtHomes()
        state.move(1)                       // focus the second home
        state.select()
        state.applyHome(snapshot)
        state.move(1)                       // focus the second room
        state.select()                      // DEVICES of "Quarto"
        state.select()                      // open the device
        state.applyDevice(lampDetail)

        assertEquals(TuyaEffect.None, state.back())
        assertEquals(TuyaPluginState.View.DEVICES, state.view)

        assertEquals(TuyaEffect.None, state.back())
        assertEquals(TuyaPluginState.View.ROOMS, state.view)
        assertEquals("returns to the room it came from", 1, state.selectedIndex)

        assertEquals(TuyaEffect.None, state.back())
        assertEquals(TuyaPluginState.View.HOMES, state.view)
        assertEquals("returns to the home it came from", 1, state.selectedIndex)

        assertEquals("BACK at the root self-closes", TuyaEffect.Close, state.back())
    }

    @Test
    fun `every device of a room is reachable with repeated NEXT`() {
        val state = openedAtHomes()
        state.select()
        state.applyHome(snapshot)
        state.select()

        val seen = mutableSetOf<Int>()
        repeat(4) {
            seen += state.selectedIndex
            state.move(1)
        }
        assertEquals("two devices, each visited, wrapping", setOf(0, 1), seen)
    }

    // --------------------------------------------------------- device control

    @Test
    fun `switch row toggles the reported value`() {
        val state = openedAtDevice()
        val effect = state.select() as TuyaEffect.SendCommand
        assertEquals("switch_led", effect.code)
        assertEquals("off flips to on", true, effect.value)
    }

    @Test
    fun `enum row opens a picker focused on the current value`() {
        val state = openedAtDevice()
        state.move(1)                        // Mode
        assertEquals(TuyaEffect.None, state.select())
        assertEquals(TuyaPluginState.View.ENUM_PICK, state.view)
        assertEquals("focus starts on the active option", 0, state.selectedIndex)

        state.move(1)
        val effect = state.select() as TuyaEffect.SendCommand
        assertEquals("work_mode", effect.code)
        assertEquals("colour", effect.value)
        assertEquals("applying returns to the device", TuyaPluginState.View.DEVICE, state.view)
    }

    @Test
    fun `enum picker cancels without sending`() {
        val state = openedAtDevice()
        state.move(1)
        state.select()
        assertEquals(TuyaEffect.None, state.back())
        assertEquals(TuyaPluginState.View.DEVICE, state.view)
        assertEquals("focus returns to the row it opened from", 1, state.selectedIndex)
    }

    @Test
    fun `number row steps within range and clamps at both ends`() {
        val state = openedAtDevice()
        state.move(2)                        // Brightness
        state.select()
        assertEquals(TuyaPluginState.View.NUMBER_ADJUST, state.view)

        state.move(1)
        var effect = state.select() as TuyaEffect.SendCommand
        assertEquals("one NEXT adds exactly one step", 510, effect.value)

        state.applyDevice(lampDetail)        // the command settled; state refreshed
        state.select()                       // reopen the stepper
        repeat(200) { state.move(1) }
        effect = state.select() as TuyaEffect.SendCommand
        assertEquals("cannot exceed the declared max", 1000, effect.value)

        state.applyDevice(lampDetail)
        state.select()
        repeat(400) { state.move(-1) }
        effect = state.select() as TuyaEffect.SendCommand
        assertEquals("cannot fall below the declared min", 10, effect.value)
    }

    @Test
    fun `a second tap while a command is in flight is ignored`() {
        val state = openedAtDevice()
        assertTrue(state.select() is TuyaEffect.SendCommand)
        assertEquals(
            "no duplicate command until the device reports back",
            TuyaEffect.None,
            state.select(),
        )
        state.applyDevice(lampDetail)
        assertTrue("and it accepts input again afterwards", state.select() is TuyaEffect.SendCommand)
    }

    @Test
    fun `read-only row never sends a command`() {
        val state = openedAtDevice()
        state.move(3)                        // Battery
        assertEquals(TuyaEffect.None, state.select())
        assertEquals(TuyaPluginState.View.DEVICE, state.view)
    }

    @Test
    fun `the trailing row refreshes the open device`() {
        val state = openedAtDevice()
        state.move(4)                        // past the four controls
        assertEquals(TuyaEffect.LoadDevice(lamp), state.select())
    }

    // ------------------------------------------------------------- rendering

    @Test
    fun `exactly one row is marked and it is the focused one`() {
        val state = openedAtDevice()
        state.move(2)
        val rows = state.card().richLines.orEmpty()
        assertEquals(1, rows.count { it.selected })
        assertEquals("Brightness", rows.first { it.selected }.text)
    }

    @Test
    fun `a long list pages so the focused row stays on screen`() {
        val many = (1..40).map { TuyaHome("h$it", "Home $it") }
        val state = TuyaPluginState().apply {
            reset()
            applyHomes(many)
        }

        repeat(39) { index ->
            state.move(1)
            val rows = state.card().richLines.orEmpty()
            assertTrue(
                "page never exceeds the viewport budget",
                rows.size <= TuyaPluginState.ROWS_PER_PAGE,
            )
            assertEquals(
                "the focused row is always inside the rendered page (index ${index + 1})",
                1,
                rows.count { it.selected },
            )
        }
    }

    @Test
    fun `content keys stay inside the 128 char cap on every view`() {
        val state = openedAtHomes()
        val keys = mutableListOf<String?>()
        keys += state.card().contentKey
        state.select(); state.applyHome(snapshot); keys += state.card().contentKey
        state.select(); keys += state.card().contentKey
        state.select(); state.applyDevice(lampDetail); keys += state.card().contentKey
        state.move(1); state.select(); keys += state.card().contentKey
        state.back(); state.move(1); state.select(); keys += state.card().contentKey

        keys.forEach { key ->
            assertNotNull(key)
            assertTrue("contentKey '$key' must be <= 128 chars", key!!.length <= 128)
        }
    }

    @Test
    fun `card rows respect the measured HUD title budget`() {
        val wide = TuyaHomeSnapshot(
            rooms = listOf(TuyaRoom("r1", "Sala", listOf("d1"))),
            devicesByRoom = mapOf(
                "r1" to listOf(
                    TuyaDevice("d1", "Luminária do corredor de entrada principal", online = false),
                ),
            ),
        )
        val state = openedAtHomes()
        state.select()
        state.applyHome(wide)
        state.select()

        val row = state.card().richLines.orEmpty().single()
        assertTrue("title is sized against the trail", row.text.length <= TuyaPluginState.TITLE_COLUMNS)
        assertEquals("offline rides in trail, never badge", listOf("offline"), row.trail)
        assertNull("badge is never drawn on a list row", row.badge)
    }

    @Test
    fun `an unconfigured message view still exits on back`() {
        val state = TuyaPluginState().apply {
            reset()
            showMessage("Add your Tuya keys in the Nexus phone app first.")
        }
        assertEquals(TuyaPluginState.View.MESSAGE, state.view)
        assertEquals(TuyaEffect.Close, state.back())
    }
}
