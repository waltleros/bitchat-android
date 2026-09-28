package com.jasiri.sos

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Wire value of [SosLocation.accuracyMeters] meaning "unknown or worse than 65 km". */
const val SOS_ACCURACY_UNKNOWN = 0xFFFF

private const val MAX_FIX_AGE_SECONDS = 0xFFFFL
private const val SAME_AGE_WINDOW_MILLIS = 5_000L

/**
 * Converts a raw fix into the wire type. Returns null if lat/lon are NaN/infinite or out of
 * range (|lat| > 90, |lon| > 180).
 * - latE7/lonE7 = round(deg * 1e7) as Int.
 * - accuracyMeters = ceil(accuracy) clamped to 0..65535; null or negative/NaN accuracy -> 65535 (unknown/poor).
 * - fixAgeSeconds = (nowMillis - fixTimeMillis) / 1000, clamped to 0..65535 (future fix -> 0).
 */
fun toSosLocation(
    latDeg: Double,
    lonDeg: Double,
    accuracyMeters: Float?,
    fixTimeMillis: Long,
    nowMillis: Long,
    approximate: Boolean = false
): SosLocation? {
    if (!latDeg.isFinite() || !lonDeg.isFinite()) return null
    if (abs(latDeg) > 90.0 || abs(lonDeg) > 180.0) return null

    val accuracy = if (accuracyMeters == null || accuracyMeters.isNaN() || accuracyMeters < 0f) {
        SOS_ACCURACY_UNKNOWN
    } else {
        ceil(accuracyMeters.toDouble()).coerceAtMost(SOS_ACCURACY_UNKNOWN.toDouble()).toInt()
    }

    val ageMillis = nowMillis - fixTimeMillis
    val fixAgeSeconds = if (ageMillis <= 0L) 0 else (ageMillis / 1000L).coerceAtMost(MAX_FIX_AGE_SECONDS).toInt()

    return SosLocation(
        latE7 = (latDeg * 1e7).roundToLong().toInt(),
        lonE7 = (lonDeg * 1e7).roundToLong().toInt(),
        accuracyMeters = accuracy,
        fixAgeSeconds = fixAgeSeconds,
        approximate = approximate
    )
}

/**
 * True if `candidate` should replace `current`: current null, or candidate is newer by > 5 s,
 * or same age (±5 s) but strictly more accurate. A null [currentTimeMillis] (age unknown) also
 * lets the candidate win.
 */
fun isBetterFix(
    candidate: SosLocation,
    candidateTimeMillis: Long,
    current: SosLocation?,
    currentTimeMillis: Long?
): Boolean {
    if (current == null || currentTimeMillis == null) return true
    val newerBy = candidateTimeMillis - currentTimeMillis
    if (newerBy > SAME_AGE_WINDOW_MILLIS) return true
    if (newerBy < -SAME_AGE_WINDOW_MILLIS) return false
    return candidate.accuracyMeters < current.accuracyMeters
}
