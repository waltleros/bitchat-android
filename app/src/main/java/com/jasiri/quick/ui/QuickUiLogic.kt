package com.jasiri.quick.ui

import com.jasiri.quick.QuickCatalog
import com.jasiri.quick.QuickFeedEntry
import com.jasiri.quick.QuickLocation
import com.jasiri.quick.QuickSendStatus
import com.jasiri.sos.SosLocation

/** Language shown under English when nothing better is available (Kenya-first). */
private const val DEFAULT_SECONDARY_LANGUAGE = "sw"

/** Second label language under English. Phone language if it is not "en" and is complete;
 *  otherwise "sw" (Kenya-first default) if "sw" is complete; otherwise null. */
fun secondaryLanguage(phoneLang: String, complete: Set<String>): String? = when {
    phoneLang != "en" && phoneLang in complete -> phoneLang
    DEFAULT_SECONDARY_LANGUAGE in complete -> DEFAULT_SECONDARY_LANGUAGE
    else -> null
}

/** [primary] is always English. */
data class TileText(val primary: String, val secondary: String?)

/** Null for unknown ids. */
fun tileText(presetId: Int, secondaryLang: String?): TileText? {
    val preset = QuickCatalog.byId(presetId) ?: return null
    val secondary = secondaryLang
        ?.takeIf { it != "en" }
        ?.let { QuickCatalog.label(presetId, it) }
    return TileText(preset.en, secondary)
}

/** For unknown ids on received entries: "Unknown preset #N — update JASIRI". */
fun unknownPresetText(presetId: Int): String = "Unknown preset #$presetId — update JASIRI"

/** Whole seconds left, rounded UP, never below 0. e.g. deadline-now = 4001 ms -> 5; 0 -> 0. */
fun undoSecondsLeft(deadlineMillis: Long, nowMillis: Long): Int {
    val left = deadlineMillis - nowMillis
    if (left <= 0L) return 0
    return ((left + 999L) / 1000L).toInt()
}

/** "" for 0, "1".."9", "9+" above 9. */
fun badgeText(count: Int): String = when {
    count <= 0 -> ""
    count > 9 -> "9+"
    else -> count.toString()
}

fun QuickLocation.toSosLocation(): SosLocation = SosLocation(
    latE7 = latE7,
    lonE7 = lonE7,
    accuracyMeters = accuracyMeters,
    fixAgeSeconds = 0,
    approximate = approximate
)

fun SosLocation.toQuickLocation(): QuickLocation = QuickLocation(
    latE7 = latE7,
    lonE7 = lonE7,
    accuracyMeters = accuracyMeters,
    approximate = approximate
)

/** The quick button is only for the Bluetooth mesh: hidden while a location (geohash) channel is selected. */
fun quickButtonVisible(selected: com.bitchat.android.geohash.ChannelID): Boolean =
    selected !is com.bitchat.android.geohash.ChannelID.Location

/** The pending own entry with the EARLIEST undo deadline, or null. Drives the undo bar. */
fun nextPending(feed: List<QuickFeedEntry>): QuickFeedEntry? =
    feed.filter { it.mine && it.status == QuickSendStatus.PENDING && it.undoDeadlineMillis != null }
        .minByOrNull { it.undoDeadlineMillis!! }
