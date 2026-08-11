package com.beyondlevi.nexus.plugin.tuya

import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

internal interface TuyaRuntimeHost {
    fun isConfigured(): Boolean
    suspend fun loadHomes(): List<TuyaHome>
    suspend fun loadHome(home: TuyaHome): TuyaHomeSnapshot
    suspend fun loadDevice(device: TuyaDevice): TuyaDeviceDetail
    suspend fun sendCommand(device: TuyaDevice, code: String, value: Any)
    fun sendCard(card: NexusCard, show: Boolean)
    fun hideSurface()
}

/**
 * Adapter between the hub lifecycle/input and [TuyaPluginState]: runs the
 * effects the state machine asks for, and re-renders after each one. The state
 * machine itself stays synchronous and testable.
 */
internal class TuyaPluginRuntime(
    private val host: TuyaRuntimeHost,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val settleDelayMs: Long = COMMAND_SETTLE_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val state = TuyaPluginState()
    private var work: Job? = null

    /** Bumped by open/close: invalidates work from a previous session. */
    private var generation = 0L

    /** Bumped by every launch and by any navigation that abandons the request. */
    private var workGeneration = 0L

    private var open = false
    private var lastDirectionAtMs = Long.MIN_VALUE

    fun open() {
        generation += 1
        invalidateWork()
        open = true
        lastDirectionAtMs = Long.MIN_VALUE
        state.reset()

        if (!host.isConfigured()) {
            state.showMessage("Add your Tuya keys in the Nexus phone app first.")
            render(show = true)
            return
        }
        render(show = true)
        run(TuyaEffect.LoadHomes)
    }

    fun close() {
        generation += 1
        open = false
        invalidateWork()
        state.reset()
        host.hideSurface()
    }

    fun destroy() {
        close()
        scope.cancel()
    }

    fun input(event: NexusInputEvent) {
        if (!open || event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            in BACK_KEYS -> {
                // Leaving a view abandons whatever that view was loading. Without
                // this, a reply that lands after BACK still applies and reopens the
                // screen the wearer just left.
                invalidateWork()
                if (state.back() == TuyaEffect.Close) close() else render(show = false)
            }
            in FORWARD_KEYS -> if (debounced()) move(1)
            in BACKWARD_KEYS -> if (debounced()) move(-1)
            in TAP_KEYS -> {
                val effect = state.select()
                render(show = false)
                run(effect)
            }
        }
    }

    private fun move(delta: Int) {
        if (state.move(delta)) render(show = false)
    }

    /** The ring can emit paired aliases; act once per real gesture. */
    private fun debounced(): Boolean {
        val now = clockMs()
        if (lastDirectionAtMs != Long.MIN_VALUE && now - lastDirectionAtMs < DIRECTION_DEBOUNCE_MS) return false
        lastDirectionAtMs = now
        return true
    }

    private fun run(effect: TuyaEffect) {
        when (effect) {
            is TuyaEffect.None -> Unit
            is TuyaEffect.Close -> close()
            is TuyaEffect.LoadHomes -> launchWork {
                val homes = host.loadHomes()
                ({ state.applyHomes(homes) })
            }
            is TuyaEffect.LoadHome -> launchWork {
                val snapshot = host.loadHome(effect.home)
                ({ state.applyHome(snapshot) })
            }
            is TuyaEffect.LoadDevice -> launchWork {
                val detail = host.loadDevice(effect.device)
                ({ state.applyDevice(detail) })
            }
            is TuyaEffect.SendCommand -> launchWork { stillCurrent ->
                host.sendCommand(effect.device, effect.code, effect.value)
                if (!stillCurrent()) return@launchWork null
                state.setStatus("Sent: ${effect.label}")
                render(show = false)
                // Tuya reports the new status a beat after accepting the command.
                delay(settleDelayMs)
                if (!stillCurrent()) return@launchWork null
                val detail = host.loadDevice(effect.device)
                ({ state.applyDevice(detail) })
            }
        }
    }

    /** Drops the outstanding request so a late reply can no longer apply. */
    private fun invalidateWork() {
        workGeneration += 1
        work?.cancel()
        work = null
    }

    /**
     * Runs one request off the input thread. The suspending part may only read;
     * it returns the mutation to perform, which runs **only if the view that
     * asked for it is still the current one** — cancellation alone is not enough,
     * because a coroutine already past its last suspension point still completes.
     */
    private fun launchWork(block: suspend (stillCurrent: () -> Boolean) -> (() -> Unit)?) {
        invalidateWork()
        val session = generation
        val ticket = workGeneration
        val stillCurrent = { open && generation == session && workGeneration == ticket }

        work = scope.launch {
            val apply: (() -> Unit)? = try {
                block(stillCurrent)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val message = describe(failure)
                ({ state.applyFailure(message) })
            }
            if (!stillCurrent()) return@launch
            apply?.invoke()
            render(show = false)
        }
    }

    private fun describe(failure: Throwable): String = when (failure) {
        is TuyaApiException -> when (failure.apiCode) {
            "1004", "1005", "1106" -> "Tuya rejected the keys. Check them in the phone app."
            "1010", "1011", "1012", "1013" -> "Tuya session expired. Try again."
            "28841002" -> "Tuya IoT Core subscription expired. Renew it at iot.tuya.com."
            "28841101" -> "This Tuya project has no API access to the account."
            "2406", "2001" -> "This device is not linked to the project."
            "no_seed" -> "No device is linked to this Tuya project yet."
            else -> "Tuya error ${failure.apiCode}."
        }
        else -> failure.message?.take(120) ?: "Could not reach Tuya."
    }

    private fun render(show: Boolean) {
        if (!open) return
        host.sendCard(state.card(), show)
    }

    internal companion object {
        const val DIRECTION_DEBOUNCE_MS = 250L
        const val COMMAND_SETTLE_MS = 700L

        val FORWARD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT,
        )
        val BACKWARD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        )
        val TAP_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NOTIFICATION,
            KeyEvent.KEYCODE_SPACE,
        )
        val BACK_KEYS = setOf(KeyEvent.KEYCODE_BACK)
    }
}
