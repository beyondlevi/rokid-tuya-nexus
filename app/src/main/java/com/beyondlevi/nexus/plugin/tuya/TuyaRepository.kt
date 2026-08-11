package com.beyondlevi.nexus.plugin.tuya

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android-side wiring: reads the stored credentials, keeps one [TuyaCloud] per
 * credential set, and moves every call off the main thread.
 */
class TuyaRepository(context: Context) {
    private val settings = TuyaSettings(context)

    private var cloud: TuyaCloud? = null
    private var signature: String = ""

    val isConfigured: Boolean get() = settings.isConfigured

    @Synchronized
    private fun cloud(): TuyaCloud {
        val current = "${settings.accessId}|${settings.accessSecret}|${settings.region.code}|${settings.uid}"
        val existing = cloud
        if (existing != null && current == signature) return existing
        val fresh = TuyaCloud(
            api = TuyaApi(
                accessId = settings.accessId,
                accessSecret = settings.accessSecret,
                endpoint = settings.region.endpoint,
            ),
            configuredUid = settings.uid,
        )
        cloud = fresh
        signature = current
        return fresh
    }

    /** Forces the next call to rebuild the client (after a settings change). */
    @Synchronized
    fun invalidate() {
        cloud = null
        signature = ""
    }

    suspend fun homes(): List<TuyaHome> = withContext(Dispatchers.IO) { cloud().homes() }

    suspend fun homeSnapshot(home: TuyaHome): TuyaHomeSnapshot =
        withContext(Dispatchers.IO) { cloud().homeSnapshot(home) }

    suspend fun deviceDetail(device: TuyaDevice): TuyaDeviceDetail =
        withContext(Dispatchers.IO) { cloud().deviceDetail(device) }

    suspend fun sendCommand(device: TuyaDevice, code: String, value: Any) =
        withContext(Dispatchers.IO) { cloud().sendCommand(device.id, code, value) }

    /** Settings-screen probe: resolves the UID and counts what the account exposes. */
    suspend fun connectionCheck(): String = withContext(Dispatchers.IO) {
        val client = cloud()
        val uid = client.uid()
        val homes = client.homes()
        val devices = homes.sumOf { home -> client.homeSnapshot(home).devicesByRoom.values.sumOf(List<*>::size) }
        "Connected · uid $uid · ${homes.size} home(s) · $devices device(s)"
    }
}
