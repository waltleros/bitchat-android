package com.jasiri.sos.location

import com.jasiri.sos.OwnSosState
import com.jasiri.sos.OwnSosStatus
import com.jasiri.sos.SosLocation
import com.jasiri.sos.isBetterFix
import java.util.concurrent.ConcurrentHashMap

const val SOS_LOCATION_REFRESH_MILLIS = 2 * 60_000L

/**
 * Process-wide, thread-safe set of sosIds for which the user turned "Share my location" OFF.
 * Kept for the process lifetime, which is also the lifetime of the own SOS.
 */
object SosLocationOptOut {
    private val ids: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun add(sosId: Long) {
        ids.add(sosId)
    }

    fun remove(sosId: Long) {
        ids.remove(sosId)
    }

    fun contains(sosId: Long): Boolean = sosId in ids

    /** Test hook only. */
    internal fun clear() {
        ids.clear()
    }
}

/**
 * True only if: state == ACTIVE, sosId != null, sosId not opted out, hasPermission, locationEnabled.
 */
fun shouldRefreshLocation(
    status: OwnSosStatus,
    optedOut: Boolean,
    hasPermission: Boolean,
    locationEnabled: Boolean
): Boolean =
    status.state == OwnSosState.ACTIVE &&
        status.sosId != null &&
        !optedOut &&
        hasPermission &&
        locationEnabled

/**
 * Decide whether a fresh fix replaces what the SOS currently carries.
 * - fresh == null -> false (keep the old location; never clear it because GPS failed).
 * - current == null -> true.
 * - otherwise isBetterFix(fresh, freshTimeMillis, current, currentFixTimeMillis).
 * currentFixTimeMillis = time the current fix was taken (keeper remembers it; null if unknown -> true).
 */
fun shouldApplyFix(
    fresh: SosLocation?,
    freshTimeMillis: Long,
    current: SosLocation?,
    currentFixTimeMillis: Long?
): Boolean {
    if (fresh == null) return false
    if (current == null) return true
    return isBetterFix(fresh, freshTimeMillis, current, currentFixTimeMillis)
}
