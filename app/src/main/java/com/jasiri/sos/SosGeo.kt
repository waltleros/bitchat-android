package com.jasiri.sos

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0

/** Used for either side's accuracy when it is [SOS_ACCURACY_UNKNOWN]. */
private const val UNKNOWN_ACCURACY_METERS = 100

/** Below this combined uncertainty, "very close" still uses this many metres. */
private const val MIN_UNCERTAINTY_METERS = 20

enum class Compass8 { N, NE, E, SE, S, SW, W, NW }

private fun SosLocation.latRadians(): Double = Math.toRadians(latE7 / 1e7)
private fun SosLocation.lonRadians(): Double = Math.toRadians(lonE7 / 1e7)

/** Great-circle distance in metres (haversine, Earth radius 6_371_000 m). */
fun distanceMeters(from: SosLocation, to: SosLocation): Double {
    val lat1 = from.latRadians()
    val lat2 = to.latRadians()
    val dLat = lat2 - lat1
    val dLon = to.lonRadians() - from.lonRadians()
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return EARTH_RADIUS_METERS * c
}

/** Initial bearing from -> to, degrees 0..<360 (0 = north, 90 = east). */
fun bearingDegrees(from: SosLocation, to: SosLocation): Double {
    val lat1 = from.latRadians()
    val lat2 = to.latRadians()
    val dLon = to.lonRadians() - from.lonRadians()
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    val degrees = Math.toDegrees(atan2(y, x))
    return ((degrees % 360.0) + 360.0) % 360.0
}

/** 8-way compass: N covers [337.5, 22.5), NE [22.5, 67.5), ... NW [292.5, 337.5). */
fun compass8(bearing: Double): Compass8 {
    val normalized = ((bearing % 360.0) + 360.0) % 360.0
    val index = floor((normalized + 22.5) / 45.0).toInt() % 8
    return Compass8.entries[index]
}

sealed class SosDistance {
    /** Closer than the combined uncertainty: no direction. withinMeters rounded UP to a multiple of 10. */
    data class VeryClose(val withinMeters: Int) : SosDistance()

    /** text is already formatted, e.g. "350 m", "1.2 km", "14 km". */
    data class Away(val text: String, val direction: Compass8) : SosDistance()
}

/**
 * - uncertainty = acc(me) + acc(them), where acc = accuracyMeters, or 100 if SOS_ACCURACY_UNKNOWN.
 *   Clamp the uncertainty to at least 20.
 * - distance <= uncertainty -> VeryClose(roundUpTo10(uncertainty))
 * - else Away(formatDistance(distance), compass8(bearing(me -> them)))
 */
fun sosDistance(me: SosLocation, them: SosLocation): SosDistance {
    val uncertainty = (accuracyOrDefault(me) + accuracyOrDefault(them)).coerceAtLeast(MIN_UNCERTAINTY_METERS)
    val distance = distanceMeters(me, them)
    return if (distance <= uncertainty) {
        SosDistance.VeryClose(roundUpTo10(uncertainty))
    } else {
        SosDistance.Away(formatDistance(distance), compass8(bearingDegrees(me, them)))
    }
}

/**
 * < 1000 m: nearest 10 m, "350 m" (minimum "10 m");
 * 1000..<10_000: one decimal km with '.' (Locale.ROOT), "1.2 km";
 * >= 10_000: whole km, "14 km".
 * Round to the nearest 10 m FIRST; if that result is >= 1000, use the km form (so 999 m -> "1.0 km").
 * Tenths of a km are rounded from the raw metres (9_949 m -> "9.9 km"), with integer arithmetic
 * so no locale or half-up formatting of an already rounded value can creep in.
 */
fun formatDistance(meters: Double): String {
    val rounded10 = ((meters / 10.0).roundToLong() * 10L).coerceAtLeast(10L)
    if (rounded10 < 1000L) return "$rounded10 m"
    val tenths = (meters / 100.0).roundToLong()
    if (tenths < 100L) return "${tenths / 10L}.${tenths % 10L} km"
    return "${(meters / 1000.0).roundToLong()} km"
}

private fun accuracyOrDefault(location: SosLocation): Int =
    if (location.accuracyMeters == SOS_ACCURACY_UNKNOWN) UNKNOWN_ACCURACY_METERS else location.accuracyMeters

private fun roundUpTo10(meters: Int): Int = ((meters + 9) / 10) * 10
