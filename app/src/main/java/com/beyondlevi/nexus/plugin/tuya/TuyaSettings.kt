package com.beyondlevi.nexus.plugin.tuya

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persisted Tuya IoT project credentials.
 *
 * The Access Secret is a reusable cloud credential, so it is never written to
 * an ordinary preferences file: the store is [EncryptedSharedPreferences],
 * whose keys and values are sealed with an AES master key held in the Android
 * Keystore. Any plaintext store written by an earlier version is migrated on
 * first use and then deleted, so upgrading does not leave the secret readable
 * on disk.
 */
class TuyaSettings(context: Context) {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = openStore(appContext)

    var accessId: String
        get() = prefs.getString(KEY_ACCESS_ID, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_ACCESS_ID, value.trim()).apply()

    var accessSecret: String
        get() = prefs.getString(KEY_ACCESS_SECRET, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_ACCESS_SECRET, value.trim()).apply()

    var region: TuyaRegion
        get() = TuyaRegion.fromCode(prefs.getString(KEY_REGION, TuyaRegion.WESTERN_AMERICA.code))
        set(value) = prefs.edit().putString(KEY_REGION, value.code).apply()

    /** Smart Life account UID; resolved automatically when left blank. */
    var uid: String
        get() = prefs.getString(KEY_UID, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_UID, value.trim()).apply()

    val isConfigured: Boolean
        get() = accessId.isNotEmpty() && accessSecret.isNotEmpty()

    /** Forgets the credentials, both in the encrypted store and on disk. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val NAME = "tuya_plugin_secure"
        const val LEGACY_NAME = "tuya_plugin"
        const val KEY_ACCESS_ID = "access_id"
        const val KEY_ACCESS_SECRET = "access_secret"
        const val KEY_REGION = "region"
        const val KEY_UID = "uid"
        const val TAG = "TuyaSettings"

        private fun openStore(context: Context): SharedPreferences {
            val encrypted = runCatching { createEncrypted(context) }.getOrElse { failure ->
                // A device whose Keystore rejects the master key must not silently
                // fall back to plaintext: fail closed with an in-memory store, so
                // the plugin asks for the keys again instead of persisting them.
                Log.w(TAG, "encrypted preferences unavailable; not persisting credentials", failure)
                return context.getSharedPreferences(VOLATILE_NAME, Context.MODE_PRIVATE)
                    .also { it.edit().clear().apply() }
            }
            migrateLegacyStore(context, encrypted)
            return encrypted
        }

        const val VOLATILE_NAME = "tuya_plugin_volatile"

        private fun createEncrypted(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        /** Moves a pre-1.0.1 plaintext store into the encrypted one, then deletes it. */
        private fun migrateLegacyStore(context: Context, target: SharedPreferences) {
            val legacy = context.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE)
            if (legacy.all.isEmpty()) return
            target.edit().apply {
                listOf(KEY_ACCESS_ID, KEY_ACCESS_SECRET, KEY_REGION, KEY_UID).forEach { key ->
                    legacy.getString(key, null)?.let { putString(key, it) }
                }
            }.apply()
            legacy.edit().clear().commit()
            context.deleteSharedPreferences(LEGACY_NAME)
            Log.i(TAG, "migrated credentials out of the plaintext store")
        }
    }
}
