package com.beyondlevi.nexus.plugin.tuya

import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Concurrency contract of the runtime: a request belongs to the view that asked
 * for it, and stops mattering the moment the wearer leaves that view.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TuyaPluginRuntimeTest {

    private val home = TuyaHome("h1", "Casa", role = "OWNER")
    private val device = TuyaDevice("d1", "Lamp", category = "dj")

    private val snapshot = TuyaHomeSnapshot(
        rooms = listOf(TuyaRoom("r1", "Room", listOf("d1"))),
        devicesByRoom = mapOf("r1" to listOf(device)),
    )

    private val detail = TuyaDeviceDetail(
        device = device,
        controls = listOf(TuyaControl("switch", "Power", TuyaControlKind.SWITCH, value = false)),
    )

    private class FakeHost(
        private val homes: List<TuyaHome>,
        private val snapshot: TuyaHomeSnapshot,
    ) : TuyaRuntimeHost {
        val cards = mutableListOf<NexusCard>()
        var hidden = false
        var deviceLoads = 0
        var commands = 0

        /** Released by the test, so a load can be held open across an input. */
        var deviceGate: CompletableDeferred<TuyaDeviceDetail>? = null

        override fun isConfigured() = true
        override suspend fun loadHomes() = homes
        override suspend fun loadHome(home: TuyaHome) = snapshot
        override suspend fun loadDevice(device: TuyaDevice): TuyaDeviceDetail {
            deviceLoads += 1
            return deviceGate?.await() ?: error("no gate set")
        }

        override suspend fun sendCommand(device: TuyaDevice, code: String, value: Any) {
            commands += 1
        }

        override fun sendCard(card: NexusCard, show: Boolean) {
            cards += card
        }

        override fun hideSurface() {
            hidden = true
        }
    }

    private fun down(keyCode: Int) = NexusInputEvent("tuya", keyCode, KeyEvent.ACTION_DOWN)

    @Test
    fun `back during a device load discards the late reply`() = runTest {
        val host = FakeHost(listOf(home), snapshot)
        val runtime = TuyaPluginRuntime(
            host = host,
            dispatcher = StandardTestDispatcher(testScheduler),
            clockMs = { testScheduler.currentTime },
        )

        runtime.open()
        advanceUntilIdle()                                   // homes loaded

        runtime.input(down(KeyEvent.KEYCODE_DPAD_CENTER))    // open the home
        advanceUntilIdle()
        runtime.input(down(KeyEvent.KEYCODE_DPAD_CENTER))    // enter the room

        host.deviceGate = CompletableDeferred()
        runtime.input(down(KeyEvent.KEYCODE_DPAD_CENTER))    // open the device: now in flight
        advanceUntilIdle()
        assertEquals(1, host.deviceLoads)

        runtime.input(down(KeyEvent.KEYCODE_BACK))           // leave before it lands
        advanceUntilIdle()
        val afterBack = host.cards.last()

        host.deviceGate!!.complete(detail)                   // the reply arrives late
        advanceUntilIdle()

        assertEquals(
            "the abandoned reply must not render anything",
            afterBack,
            host.cards.last(),
        )
        assertTrue(
            "and must not reopen the device screen",
            host.cards.last().title != device.name,
        )
    }

    @Test
    fun `closing the session discards an in-flight load`() = runTest {
        val host = FakeHost(listOf(home), snapshot)
        val runtime = TuyaPluginRuntime(
            host = host,
            dispatcher = StandardTestDispatcher(testScheduler),
            clockMs = { testScheduler.currentTime },
        )

        runtime.open()
        advanceUntilIdle()
        runtime.input(down(KeyEvent.KEYCODE_DPAD_CENTER))
        advanceUntilIdle()
        runtime.input(down(KeyEvent.KEYCODE_DPAD_CENTER))

        host.deviceGate = CompletableDeferred()
        runtime.input(down(KeyEvent.KEYCODE_DPAD_CENTER))
        advanceUntilIdle()

        runtime.close()
        val afterClose = host.cards.size
        host.deviceGate!!.complete(detail)
        advanceUntilIdle()

        assertTrue("the surface was hidden", host.hidden)
        assertEquals("nothing renders after close", afterClose, host.cards.size)
    }

    @Test
    fun `back at the root hides the surface`() = runTest {
        val host = FakeHost(listOf(home), snapshot)
        val runtime = TuyaPluginRuntime(
            host = host,
            dispatcher = StandardTestDispatcher(testScheduler),
            clockMs = { testScheduler.currentTime },
        )

        runtime.open()
        advanceUntilIdle()
        runtime.input(down(KeyEvent.KEYCODE_BACK))
        advanceUntilIdle()

        assertTrue("BACK at the home list self-closes", host.hidden)
    }
}
