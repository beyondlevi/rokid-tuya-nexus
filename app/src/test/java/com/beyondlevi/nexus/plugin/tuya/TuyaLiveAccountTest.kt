package com.beyondlevi.nexus.plugin.tuya

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Exercises the SHIPPING client against the real Tuya cloud, so the signing and
 * parsing are proven by the same code the plugin runs — not by a side script.
 *
 * Skipped unless TUYA_ACCESS_ID / TUYA_ACCESS_SECRET are in the environment, so
 * an ordinary `testDebugUnitTest` on a machine without credentials still runs.
 */
class TuyaLiveAccountTest {

    private val accessId = System.getenv("TUYA_ACCESS_ID").orEmpty()
    private val accessSecret = System.getenv("TUYA_ACCESS_SECRET").orEmpty()
    private val region = TuyaRegion.fromCode(System.getenv("TUYA_REGION"))
    private val configuredUid = System.getenv("TUYA_UID").orEmpty()

    private fun cloud(): TuyaCloud = TuyaCloud(
        api = TuyaApi(accessId, accessSecret, region.endpoint),
        configuredUid = configuredUid,
    )

    private fun requireCredentials() {
        assumeTrue(
            "no TUYA_ACCESS_ID / TUYA_ACCESS_SECRET in the environment",
            accessId.isNotEmpty() && accessSecret.isNotEmpty(),
        )
    }

    /**
     * The datapoint endpoints sit behind the project's IoT Core subscription.
     * When it has lapsed every business call returns 28841002 and there is
     * nothing to assert about the account, so the test reports why and skips
     * instead of failing on something outside the code.
     */
    private fun <T> requiringIotCore(block: () -> T): T? = try {
        block()
    } catch (failure: TuyaApiException) {
        assumeTrue(
            "Tuya project is not serving data: ${failure.apiCode} ${failure.message}",
            failure.apiCode != IOT_CORE_EXPIRED,
        )
        throw failure
    }

    @Test
    fun `the shipping client authenticates against the real endpoint`() {
        requireCredentials()
        // Any call drives the token exchange; a signature error would surface as
        // code 1004/1005 rather than a business-level code.
        val failure = runCatching { cloud().homes() }.exceptionOrNull()
        if (failure is TuyaApiException) {
            assertTrue(
                "signing must be accepted by ${region.endpoint} (got ${failure.apiCode})",
                failure.apiCode !in SIGNING_ERRORS,
            )
        }
    }

    @Test
    fun `the account exposes homes with rooms and devices`() {
        requireCredentials()
        val client = cloud()
        val homes = requiringIotCore { client.homes() } ?: return
        assertTrue("the account must expose at least one home", homes.isNotEmpty())

        val snapshot = requiringIotCore { client.homeSnapshot(homes.first()) } ?: return
        assertTrue("the first home must expose at least one room", snapshot.rooms.isNotEmpty())
        assertTrue(
            "every room the HUD lists must resolve to devices",
            snapshot.rooms.all { room -> snapshot.devicesByRoom[room.id]?.isNotEmpty() == true },
        )
    }

    @Test
    fun `a real device types its datapoints into HUD controls`() {
        requireCredentials()
        val client = cloud()
        val homes = requiringIotCore { client.homes() } ?: return
        val device = homes.asSequence()
            .mapNotNull { home -> requiringIotCore { client.homeSnapshot(home) } }
            .flatMap { it.devicesByRoom.values.asSequence().flatten() }
            .firstOrNull()
        assumeTrue("no device on this account", device != null)

        val detail = requiringIotCore { client.deviceDetail(device!!) } ?: return
        assertTrue("a real device must expose at least one datapoint", detail.controls.isNotEmpty())
        assertTrue(
            "every control must carry a code and a label",
            detail.controls.all { it.code.isNotBlank() && it.label.isNotBlank() },
        )
    }

    private companion object {
        const val IOT_CORE_EXPIRED = "28841002"
        val SIGNING_ERRORS = setOf("1004", "1005", "1000")
    }
}
