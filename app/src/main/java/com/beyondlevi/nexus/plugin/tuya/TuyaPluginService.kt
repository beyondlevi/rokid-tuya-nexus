package com.beyondlevi.nexus.plugin.tuya

import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

class TuyaPluginService : NexusPluginService() {
    private var runtime: TuyaPluginRuntime? = null
    private var surface: NexusSurfaceSession? = null
    private val repository by lazy { TuyaRepository(applicationContext) }

    private val host = object : TuyaRuntimeHost {
        override fun isConfigured(): Boolean = repository.isConfigured

        override suspend fun loadHomes(): List<TuyaHome> = repository.homes()

        override suspend fun loadHome(home: TuyaHome): TuyaHomeSnapshot = repository.homeSnapshot(home)

        override suspend fun loadDevice(device: TuyaDevice): TuyaDeviceDetail =
            repository.deviceDetail(device)

        override suspend fun sendCommand(device: TuyaDevice, code: String, value: Any) =
            repository.sendCommand(device, code, value)

        override fun sendCard(card: NexusCard, show: Boolean) {
            val session = surface ?: return
            // A rejected surface (another plugin owns the HUD, payload refused)
            // is dropped on the floor here: never retry-loop on the hub.
            if (show) session.showCard(card) else session.updateCard(card)
        }

        override fun hideSurface() {
            surface?.hide()
        }
    }

    override fun onNexusOpen() {
        surface = nexusSurfaceSession(SURFACE_ID)
        repository.invalidate()
        ensureRuntime().open()
    }

    override fun onNexusClose() {
        runtime?.close()
        surface = null
    }

    override fun onNexusInput(event: NexusInputEvent) {
        runtime?.input(event)
    }

    override fun onDestroy() {
        runtime?.destroy()
        runtime = null
        surface = null
        super.onDestroy()
    }

    private fun ensureRuntime(): TuyaPluginRuntime =
        runtime ?: TuyaPluginRuntime(host).also { runtime = it }

    private companion object {
        const val SURFACE_ID = "tuya"
    }
}
