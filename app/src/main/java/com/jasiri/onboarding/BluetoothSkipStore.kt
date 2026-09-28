package com.jasiri.onboarding

import android.content.Context

/** Persists the user's "Skip" on the Bluetooth check. SharedPreferences "jasiri_prefs", key "bt_check_skipped". Never throws. */
object BluetoothSkipStore {
    private const val PREFS = "jasiri_prefs"
    private const val KEY_SKIPPED = "bt_check_skipped"

    fun isSkipped(context: Context): Boolean = try {
        prefs(context).getBoolean(KEY_SKIPPED, false)
    } catch (_: Exception) {
        false
    }

    fun markSkipped(context: Context) {
        try {
            prefs(context).edit().putBoolean(KEY_SKIPPED, true).apply()
        } catch (_: Exception) {
        }
    }

    fun clear(context: Context) {
        try {
            val prefs = prefs(context)
            if (prefs.contains(KEY_SKIPPED)) prefs.edit().remove(KEY_SKIPPED).apply()
        } catch (_: Exception) {
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
