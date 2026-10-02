package com.jasiri.sos.location

import android.content.Context

/**
 * Remembers that the SOS page has asked for location permission at least once, so a later
 * "no rationale" answer can be read as permanently denied. SharedPreferences "jasiri_prefs",
 * key "loc_perm_asked". Never throws.
 */
object LocPermissionStore {
    private const val PREFS = "jasiri_prefs"
    private const val KEY_ASKED = "loc_perm_asked"

    fun markAsked(context: Context) {
        try {
            prefs(context).edit().putBoolean(KEY_ASKED, true).apply()
        } catch (_: Exception) {
        }
    }

    fun wasAsked(context: Context): Boolean = try {
        prefs(context).getBoolean(KEY_ASKED, false)
    } catch (_: Exception) {
        false
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
