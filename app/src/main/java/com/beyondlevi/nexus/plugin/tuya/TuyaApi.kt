package com.beyondlevi.nexus.plugin.tuya

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Datacenter the Tuya IoT project lives in. The account only exists in one. */
enum class TuyaRegion(val code: String, val label: String, val endpoint: String) {
    CHINA("cn", "China", "https://openapi.tuyacn.com"),
    WESTERN_AMERICA("us", "Western America", "https://openapi.tuyaus.com"),
    EASTERN_AMERICA("us-e", "Eastern America", "https://openapi-ueaz.tuyaus.com"),
    CENTRAL_EUROPE("eu", "Central Europe", "https://openapi.tuyaeu.com"),
    WESTERN_EUROPE("eu-w", "Western Europe", "https://openapi-weaz.tuyaeu.com"),
    INDIA("in", "India", "https://openapi.tuyain.com"),
    ;

    companion object {
        fun fromCode(code: String?): TuyaRegion =
            entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) } ?: WESTERN_AMERICA
    }
}

/** A Tuya business error (`success: false`), carrying the API's own code. */
class TuyaApiException(
    val apiCode: String,
    message: String,
    val path: String,
) : IOException("Tuya $path failed ($apiCode): $message")

/**
 * Minimal Tuya Cloud client: HMAC-SHA256 request signing, token lifecycle and
 * JSON transport. Deliberately free of Android imports so the whole request
 * path is exercised by JVM unit tests.
 */
class TuyaApi(
    private val accessId: String,
    private val accessSecret: String,
    endpoint: String,
    private val client: OkHttpClient = defaultClient(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val nonceFactory: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {
    private val base: HttpUrl = endpoint.trimEnd('/').toHttpUrl()

    private var token: String? = null
    private var tokenExpiresAtMs: Long = 0L

    /** Drops the cached token so the next call re-authenticates. */
    fun invalidateToken() {
        token = null
        tokenExpiresAtMs = 0L
    }

    fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject =
        execute("GET", path, query, null)

    fun post(path: String, body: JSONObject): JSONObject =
        execute("POST", path, emptyMap(), body)

    /**
     * Signs and performs one call, refreshing the token once when Tuya reports
     * an expired/invalid token (codes 1010/1011/1012 and friends).
     */
    private fun execute(
        method: String,
        path: String,
        query: Map<String, String>,
        body: JSONObject?,
    ): JSONObject = try {
        send(method, path, query, body, accessToken())
    } catch (failure: TuyaApiException) {
        if (failure.apiCode in TOKEN_ERROR_CODES) {
            invalidateToken()
            send(method, path, query, body, accessToken())
        } else {
            throw failure
        }
    }

    private fun accessToken(): String {
        val cached = token
        if (cached != null && nowMs() < tokenExpiresAtMs) return cached
        val result = send("GET", TOKEN_PATH, mapOf("grant_type" to "1"), null, "")
        val fresh = result.optString("access_token").takeIf(String::isNotBlank)
            ?: throw TuyaApiException("no_token", "token response carried no access_token", TOKEN_PATH)
        token = fresh
        // expire_time is in seconds; renew a minute early to avoid a race.
        val ttlMs = result.optLong("expire_time", DEFAULT_TOKEN_TTL_SECONDS) * 1000L
        tokenExpiresAtMs = nowMs() + (ttlMs - TOKEN_RENEW_MARGIN_MS).coerceAtLeast(0L)
        return fresh
    }

    private fun send(
        method: String,
        path: String,
        query: Map<String, String>,
        body: JSONObject?,
        accessToken: String,
    ): JSONObject {
        val bodyText = body?.toString().orEmpty()
        val timestamp = nowMs().toString()
        val nonce = nonceFactory()
        val stringToSign = stringToSign(method, path, query, bodyText)
        val sign = hmacSha256Hex(accessId + accessToken + timestamp + nonce + stringToSign, accessSecret)

        val url = base.newBuilder().apply {
            path.trim('/').split('/').forEach(::addPathSegment)
            query.toSortedMap().forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()

        val request = Request.Builder()
            .url(url)
            .header("client_id", accessId)
            .header("sign", sign)
            .header("t", timestamp)
            .header("nonce", nonce)
            .header("sign_method", SIGN_METHOD)
            .apply {
                if (accessToken.isNotEmpty()) header("access_token", accessToken)
                if (bodyText.isEmpty()) {
                    method(method, null)
                } else {
                    method(method, bodyText.toRequestBody(JSON_MEDIA_TYPE))
                }
            }
            .build()

        val raw = client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful && text.isBlank()) {
                throw TuyaApiException("http_${response.code}", response.message, path)
            }
            text
        }

        val envelope = try {
            JSONObject(raw)
        } catch (_: Throwable) {
            throw TuyaApiException("bad_json", "response was not JSON", path)
        }
        if (!envelope.optBoolean("success", false)) {
            throw TuyaApiException(
                envelope.opt("code")?.toString() ?: "unknown",
                envelope.optString("msg", "unknown error"),
                path,
            )
        }
        return envelope.optJSONObject("result")
            ?: JSONObject().put(RESULT_ARRAY_KEY, envelope.optJSONArray("result") ?: org.json.JSONArray())
    }

    internal fun stringToSign(
        method: String,
        path: String,
        query: Map<String, String>,
        bodyText: String,
    ): String {
        val url = if (query.isEmpty()) {
            path
        } else {
            path + "?" + query.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        }
        return "$method\n${sha256Hex(bodyText)}\n\n$url"
    }

    companion object {
        /** Result arrays are wrapped under this key so callers always get a JSONObject. */
        const val RESULT_ARRAY_KEY = "__list"

        private const val TOKEN_PATH = "/v1.0/token"
        private const val SIGN_METHOD = "HMAC-SHA256"
        private const val DEFAULT_TOKEN_TTL_SECONDS = 7200L
        private const val TOKEN_RENEW_MARGIN_MS = 60_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val TOKEN_ERROR_CODES = setOf("1010", "1011", "1012", "1013", "1004")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

        fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()

        fun hmacSha256Hex(payload: String, secret: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).toHex().uppercase(Locale.ROOT)
        }

        private fun ByteArray.toHex(): String = buildString(size * 2) {
            for (byte in this@toHex) {
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }

        private const val HEX = "0123456789abcdef"
    }
}
