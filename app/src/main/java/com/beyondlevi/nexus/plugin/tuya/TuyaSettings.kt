package com.beyondlevi.nexus.plugin.tuya

import android.content.Context
import android.content.SharedPreferences

/** Persisted Tuya IoT project credentials. Nothing else is stored. */
class TuyaSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

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

    private companion object {
        const val NAME = "tuya_plugin"
        const val KEY_ACCESS_ID = "access_id"
        const val KEY_ACCESS_SECRET = "access_secret"
        const val KEY_REGION = "region"
        const val KEY_UID = "uid"
    }
}
