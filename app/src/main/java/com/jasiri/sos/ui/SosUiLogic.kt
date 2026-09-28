package com.jasiri.sos.ui

import com.jasiri.sos.OwnSosState
import com.jasiri.sos.OwnSosStatus
import com.jasiri.sos.SOS_ACCURACY_UNKNOWN
import com.jasiri.sos.SosEntry
import com.jasiri.sos.SosEntryState
import com.jasiri.sos.SosLocation
import java.util.Locale

/** How long ago something was heard, bucketed for display. */
sealed interface HeardAgo {
    /** Less than a minute ago; also used when the timestamp is in the future. */
    object JustNow : HeardAgo
    data class Minutes(val n: Int) : HeardAgo
    data class Hours(val n: Int) : HeardAgo
}

fun heardAgo(thenMillis: Long, nowMillis: Long): HeardAgo {
    val diff = nowMillis - thenMillis
    return when {
        diff < 60_000L -> HeardAgo.JustNow
        diff < 3_600_000L -> HeardAgo.Minutes((diff / 60_000L).toInt())
        else -> HeardAgo.Hours((diff / 3_600_000L).toInt())
    }
}

sealed interface OwnSosDisplay {
    object Idle : OwnSosDisplay
    data class Sending(val successfulSends: Int, val lastSent: HeardAgo?) : OwnSosDisplay
    object NotSent : OwnSosDisplay
    object Cancelling : OwnSosDisplay
    object Cancelled : OwnSosDisplay
    object Expired : OwnSosDisplay
}

fun ownSosDisplay(status: OwnSosStatus, nowMillis: Long): OwnSosDisplay = when (status.state) {
    OwnSosState.IDLE -> OwnSosDisplay.Idle
    OwnSosState.ACTIVE ->
        if (status.notSentWarning) {
            OwnSosDisplay.NotSent
        } else {
            OwnSosDisplay.Sending(
                status.successfulSends,
                status.lastSuccessAtMillis?.let { heardAgo(it, nowMillis) }
            )
        }
    OwnSosState.CANCELLING -> OwnSosDisplay.Cancelling
    OwnSosState.CANCELLED -> OwnSosDisplay.Cancelled
    OwnSosState.EXPIRED -> OwnSosDisplay.Expired
}

/** Location line on the own status card. */
sealed interface LocStatus {
    object Off : LocStatus
    object Getting : LocStatus
    data class Shared(val accuracyM: Int) : LocStatus
    object NoPermission : LocStatus
    object Unavailable : LocStatus
}

/** Count of entries that are ACTIVE and not stale. Used for the header badge. */
fun alertBadgeCount(entries: List<SosEntry>): Int =
    entries.count { it.state == SosEntryState.ACTIVE && !it.stale }

enum class SosAction { ACKNOWLEDGE, CLAIM, RESOLVE }

fun availableActions(entry: SosEntry, myPeerID: String?): List<SosAction> {
    if (myPeerID == null || entry.state != SosEntryState.ACTIVE) return emptyList()
    val acked = myPeerID in entry.ackedBy
    val claimed = myPeerID in entry.claimedBy
    return buildList {
        if (!acked && !claimed) add(SosAction.ACKNOWLEDGE)
        if (!claimed) add(SosAction.CLAIM)
        if (claimed) add(SosAction.RESOLVE)
    }
}

/** Nickname if present and not blank, otherwise the first 8 chars of peerID. */
fun peerLabel(peerID: String, nicknames: Map<String, String>): String =
    nicknames[peerID]?.takeIf { it.isNotBlank() } ?: peerID.take(8)

/** Returns raw if it is in 0..100, otherwise null. */
fun batteryPercentOrNull(raw: Int): Int? = raw.takeIf { it in 0..100 }

/** 5 decimals, '.' separator regardless of locale, e.g. "-1.28638, 36.81722". */
fun formatCoords(loc: SosLocation): String =
    String.format(Locale.ROOT, "%.5f, %.5f", loc.latE7 / 1e7, loc.lonE7 / 1e7)

/** "geo:-1.28638,36.81722?q=-1.28638,36.81722(SOS)" (5 decimals, Locale.ROOT). */
fun geoUri(loc: SosLocation): String {
    val lat = loc.latE7 / 1e7
    val lon = loc.lonE7 / 1e7
    return String.format(Locale.ROOT, "geo:%.5f,%.5f?q=%.5f,%.5f(SOS)", lat, lon, lat, lon)
}

/** accuracyMeters == 65535 -> null (unknown), else the value. */
fun accuracyOrNull(loc: SosLocation): Int? = loc.accuracyMeters.takeIf { it != SOS_ACCURACY_UNKNOWN }

const val SOS_HOLD_MILLIS = 3_000L
const val SOS_DEFAULT_SEVERITY = 3
