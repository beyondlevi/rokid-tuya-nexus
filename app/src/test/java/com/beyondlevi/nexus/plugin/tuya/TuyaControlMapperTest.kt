package com.beyondlevi.nexus.plugin.tuya

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Datapoint typing: each Tuya function kind becomes the right HUD control. */
class TuyaControlMapperTest {

    private fun functions(vararg entries: String) =
        JSONObject("""{"category":"dj","functions":[${entries.joinToString(",")}]}""")

    private fun status(vararg entries: String) = JSONArray("[${entries.joinToString(",")}]")

    private val lamp = TuyaDevice("d1", "Abajur", category = "dj")

    @Test
    fun `boolean enum and integer functions map to their control kinds`() {
        val detail = TuyaControlMapper.build(
            lamp,
            functions(
                """{"code":"switch_led","name":"Power","type":"Boolean","values":"{}"}""",
                """{"code":"work_mode","name":"Mode","type":"Enum","values":"{\"range\":[\"white\",\"colour\"]}"}""",
                """{"code":"bright_value_v2","name":"Brightness","type":"Integer","values":"{\"min\":10,\"max\":1000,\"step\":10,\"scale\":0,\"unit\":\"\"}"}""",
            ),
            status(
                """{"code":"switch_led","value":true}""",
                """{"code":"work_mode","value":"colour"}""",
                """{"code":"bright_value_v2","value":640}""",
            ),
        )

        val byCode = detail.controls.associateBy(TuyaControl::code)
        assertEquals(TuyaControlKind.SWITCH, byCode.getValue("switch_led").kind)
        assertTrue(byCode.getValue("switch_led").booleanValue())

        val mode = byCode.getValue("work_mode")
        assertEquals(TuyaControlKind.ENUM, mode.kind)
        assertEquals(listOf("white", "colour"), mode.options.map(TuyaEnumOption::raw))
        assertEquals("Colour", mode.displayValue())

        val brightness = byCode.getValue("bright_value_v2")
        assertEquals(TuyaControlKind.NUMBER, brightness.kind)
        assertEquals(640, brightness.intValue())
        assertEquals(10, brightness.step)
    }

    @Test
    fun `the category primary control leads the list`() {
        val detail = TuyaControlMapper.build(
            lamp,
            functions(
                """{"code":"bright_value_v2","name":"Brightness","type":"Integer","values":"{\"min\":10,\"max\":1000}"}""",
                """{"code":"switch_led","name":"Power","type":"Boolean","values":"{}"}""",
            ),
            status(),
        )
        assertEquals("switch_led", detail.controls.first().code)
    }

    @Test
    fun `an IR air conditioner reads power from its aliased status code`() {
        val ac = TuyaDevice("d2", "Ar", category = "ktkzq")
        val detail = TuyaControlMapper.build(
            ac,
            functions(
                """{"code":"switch","name":"Power","type":"Boolean","values":"{}"}""",
                """{"code":"temp","name":"Temperature","type":"Integer","values":"{\"min\":16,\"max\":30,\"step\":1,\"unit\":\"C\"}"}""",
            ),
            // The device reports under different codes than it accepts.
            status(
                """{"code":"power","value":true}""",
                """{"code":"temp_set","value":23}""",
            ),
        )
        val byCode = detail.controls.associateBy(TuyaControl::code)
        assertTrue("power alias resolves to switch", byCode.getValue("switch").booleanValue())
        assertEquals("temp alias resolves", 23, byCode.getValue("temp").intValue())
        assertEquals("23C", byCode.getValue("temp").displayValue())
    }

    @Test
    fun `scaled integers render with their decimal point and unit`() {
        val detail = TuyaControlMapper.build(
            TuyaDevice("d3", "Termostato", category = "wk"),
            functions(
                """{"code":"temp_set","name":"Target","type":"Integer","values":"{\"min\":50,\"max\":350,\"step\":5,\"scale\":1,\"unit\":\"°C\"}"}""",
            ),
            status("""{"code":"temp_set","value":225}"""),
        )
        assertEquals("22.5°C", detail.controls.single().displayValue())
    }

    @Test
    fun `reported-only datapoints become read-only rows`() {
        val detail = TuyaControlMapper.build(
            TuyaDevice("d4", "Sensor", category = "wsdcg"),
            functions(),
            status(
                """{"code":"va_temperature","value":274}""",
                """{"code":"battery_percentage","value":91}""",
            ),
        )
        assertEquals(2, detail.controls.size)
        assertTrue(detail.controls.all { it.kind == TuyaControlKind.READ_ONLY })
        assertTrue(detail.controls.none(TuyaControl::isActionable))
    }

    @Test
    fun `noisy and opaque datapoints are dropped`() {
        val detail = TuyaControlMapper.build(
            lamp,
            functions(
                """{"code":"switch_led","name":"Power","type":"Boolean","values":"{}"}""",
                """{"code":"countdown_1","name":"Countdown","type":"Integer","values":"{\"min\":0,\"max\":86400}"}""",
                """{"code":"music_data","name":"Music","type":"Json","values":"{}"}""",
                """{"code":"scene_data_v2","name":"Scene","type":"Raw","values":"{}"}""",
            ),
            status(),
        )
        assertEquals(listOf("switch_led"), detail.controls.map(TuyaControl::code))
    }

    @Test
    fun `an enum with no declared range is skipped rather than shown empty`() {
        val detail = TuyaControlMapper.build(
            lamp,
            functions("""{"code":"work_mode","name":"Mode","type":"Enum","values":"{}"}"""),
            status(),
        )
        assertTrue(detail.controls.none { it.code == "work_mode" })
    }

    @Test
    fun `a missing status leaves the control valueless instead of guessing`() {
        val detail = TuyaControlMapper.build(
            lamp,
            functions("""{"code":"switch_led","name":"Power","type":"Boolean","values":"{}"}"""),
            status(),
        )
        val control = detail.controls.single()
        assertNull(control.value)
        assertFalse(control.booleanValue())
        assertEquals("off", control.displayValue())
    }

    @Test
    fun `stepper snapping respects step and bounds`() {
        val control = TuyaControl(
            code = "temp",
            label = "Temp",
            kind = TuyaControlKind.NUMBER,
            min = 16,
            max = 30,
            step = 3,
        )
        assertEquals(16, control.coerce(15))
        assertEquals("clamped, and never off the grid", 28, control.coerce(99))
        assertEquals("the grid is anchored at min, not at zero", 22, control.coerce(23))
    }
}
