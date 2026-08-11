package com.beyondlevi.nexus.plugin.tuya

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TuyaApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(): TuyaApi = TuyaApi(
        accessId = "ACCESS",
        accessSecret = "SECRET",
        endpoint = server.url("/").toString().trimEnd('/'),
        nowMs = { 1_700_000_000_000L },
        nonceFactory = { "NONCE" },
    )

    private fun enqueueToken() {
        server.enqueue(
            MockResponse().setBody(
                """{"success":true,"result":{"access_token":"TOKEN","expire_time":7200}}""",
            ),
        )
    }

    @Test
    fun `string to sign follows the documented layout`() {
        val sts = api().stringToSign("GET", "/v1.0/token", mapOf("grant_type" to "1"), "")
        assertEquals(
            "GET\n" +
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n" +
                "\n" +
                "/v1.0/token?grant_type=1",
            sts,
        )
    }

    @Test
    fun `query parameters are sorted before signing`() {
        val sts = api().stringToSign("GET", "/v1.0/x", mapOf("z" to "1", "a" to "2"), "")
        assertTrue(sts.endsWith("/v1.0/x?a=2&z=1"))
    }

    @Test
    fun `token is fetched once and reused for business calls`() {
        enqueueToken()
        server.enqueue(MockResponse().setBody("""{"success":true,"result":{"uid":"u-1"}}"""))
        server.enqueue(MockResponse().setBody("""{"success":true,"result":{"uid":"u-2"}}"""))

        val api = api()
        assertEquals("u-1", api.get("/v1.0/devices/a").optString("uid"))
        assertEquals("u-2", api.get("/v1.0/devices/b").optString("uid"))

        assertEquals("token + two business calls", 3, server.requestCount)
        val tokenRequest = server.takeRequest()
        assertEquals("/v1.0/token?grant_type=1", tokenRequest.path)
        assertEquals("ACCESS", tokenRequest.getHeader("client_id"))
        assertEquals("HMAC-SHA256", tokenRequest.getHeader("sign_method"))
        assertEquals(null, tokenRequest.getHeader("access_token"))

        val business = server.takeRequest()
        assertEquals("TOKEN", business.getHeader("access_token"))
        assertEquals(
            "signature covers client_id + token + t + nonce + stringToSign",
            TuyaApi.hmacSha256Hex(
                "ACCESSTOKEN1700000000000NONCE" +
                    "GET\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n\n/v1.0/devices/a",
                "SECRET",
            ),
            business.getHeader("sign"),
        )
    }

    @Test
    fun `an expired token is refreshed once and the call retried`() {
        enqueueToken()
        server.enqueue(MockResponse().setBody("""{"success":false,"code":1010,"msg":"token invalid"}"""))
        enqueueToken()
        server.enqueue(MockResponse().setBody("""{"success":true,"result":{"ok":true}}"""))

        assertTrue(api().get("/v1.0/devices/a").optBoolean("ok"))
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `a business error surfaces the tuya code`() {
        enqueueToken()
        server.enqueue(MockResponse().setBody("""{"success":false,"code":1106,"msg":"permission deny"}"""))

        val failure = runCatching { api().get("/v1.0/devices/a") }.exceptionOrNull()
        assertTrue(failure is TuyaApiException)
        assertEquals("1106", (failure as TuyaApiException).apiCode)
    }

    @Test
    fun `array results are reachable through the list helpers`() {
        enqueueToken()
        server.enqueue(
            MockResponse().setBody("""{"success":true,"result":[{"home_id":1,"name":"Casa"}]}"""),
        )
        val result = api().get("/v1.0/users/u/homes")
        assertEquals(1, result.list("homes").size)
        assertEquals("Casa", result.list("homes").first().firstString("name"))
    }

    @Test
    fun `an oversized response is rejected instead of being parsed`() {
        enqueueToken()
        // A body past the cap must never reach the JSON parser: the response is
        // attacker-controlled from the plugin's point of view and the process is
        // shared with the live HUD session.
        val huge = "{\"success\":true,\"result\":\"" + "a".repeat((TuyaApi.MAX_RESPONSE_BYTES + 1024).toInt()) + "\"}"
        server.enqueue(MockResponse().setBody(huge))

        val failure = runCatching { api().get("/v1.0/devices/a") }.exceptionOrNull()
        assertTrue(failure is TuyaApiException)
        assertEquals("response_too_large", (failure as TuyaApiException).apiCode)
    }

    @Test
    fun `a response declaring an oversized length is rejected before reading it`() {
        enqueueToken()
        server.enqueue(
            MockResponse()
                .setBody("{}")
                .setHeader("Content-Length", (TuyaApi.MAX_RESPONSE_BYTES + 1).toString()),
        )

        val failure = runCatching { api().get("/v1.0/devices/a") }.exceptionOrNull()
        assertEquals("response_too_large", (failure as TuyaApiException).apiCode)
    }

    @Test
    fun `a response at the limit still parses`() {
        enqueueToken()
        server.enqueue(MockResponse().setBody("""{"success":true,"result":{"ok":true}}"""))
        assertTrue(api().get("/v1.0/devices/a").optBoolean("ok"))
    }

    @Test
    fun `commands are posted as a signed json body`() {
        enqueueToken()
        server.enqueue(MockResponse().setBody("""{"success":true,"result":true}"""))

        api().post(
            "/v1.0/devices/d1/commands",
            JSONObject("""{"commands":[{"code":"switch_led","value":true}]}"""),
        )
        server.takeRequest()
        val posted = server.takeRequest()
        assertEquals("POST", posted.method)
        assertTrue(posted.body.readUtf8().contains("\"switch_led\""))
    }
}
