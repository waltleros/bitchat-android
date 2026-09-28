package com.jasiri.alerts

import com.jasiri.quick.QuickCatalog
import com.jasiri.quick.QuickFeedEntry
import com.jasiri.quick.QuickLocation
import com.jasiri.quick.QuickTone
import com.jasiri.sos.SosCategory
import com.jasiri.sos.SosEntry
import com.jasiri.sos.SosEntryState
import com.jasiri.sos.SosLocation

data class SosAlert(val sosId: Long, val originPeerID: String, val category: SosCategory, val location: SosLocation?)
data class SosAlertPlan(val post: List<SosAlert>, val cancel: List<Long>)

/** Not thread-safe; JasiriAlerts calls it from one collector. */
class SosAlertPlanner(private val maxRemembered: Int = 500) {
    private val alerted = LinkedHashSet<Long>()
    private val showing = LinkedHashSet<Long>()

    /**
     * post   = entries with state ACTIVE, originPeerID != myPeerID, sosId never alerted before
     *          (in entries order). If myPeerID is null, post nothing.
     * cancel = sosIds currently "showing" whose entry is missing or no longer ACTIVE.
     * Stale ACTIVE entries stay showing (not cancelled).
     * Remembers alerted sosIds (evict oldest beyond maxRemembered) so a sosId never alerts twice.
     * A sosId that is still showing is never posted again, even if it was evicted.
     */
    fun plan(entries: List<SosEntry>, myPeerID: String?): SosAlertPlan {
        val activeIds = HashSet<Long>()
        entries.forEach { if (it.state == SosEntryState.ACTIVE) activeIds += it.sosId }

        val cancel = showing.filter { it !in activeIds }
        showing.removeAll(cancel.toSet())

        val post = mutableListOf<SosAlert>()
        if (myPeerID != null) {
            for (entry in entries) {
                if (entry.state != SosEntryState.ACTIVE) continue
                if (entry.originPeerID == myPeerID) continue
                if (entry.sosId in alerted || entry.sosId in showing) continue
                post += SosAlert(entry.sosId, entry.originPeerID, entry.body.category, entry.body.location)
                remember(entry.sosId)
                showing += entry.sosId
            }
        }
        return SosAlertPlan(post, cancel)
    }

    private fun remember(sosId: Long) {
        alerted += sosId
        while (alerted.size > maxRemembered) {
            val oldest = alerted.iterator()
            oldest.next()
            oldest.remove()
        }
    }
}

data class QuickWarning(
    val key: String,
    val senderPeerID: String,
    val presetId: Int,
    val location: QuickLocation?,
    val receivedAtMillis: Long
)

data class QuickAlertPlan(
    /** Newest first. */
    val newWarnings: List<QuickWarning>,
    /** What the grouped notification shows, newest first, at most maxRecent. */
    val recent: List<QuickWarning>,
    /** Vibrate/sound this time? */
    val buzz: Boolean
)

/** Not thread-safe; JasiriAlerts guards it with one lock. */
class QuickAlertPlanner(
    private val buzzGapMillis: Long = 20_000,
    private val maxRecent: Int = 5,
    private val maxRemembered: Int = 500
) {
    private val alertedKeys = LinkedHashSet<String>()
    private var recent: List<QuickWarning> = emptyList()
    private var lastBuzzMillis: Long? = null

    /**
     * A warning = entry with mine == false, senderPeerID != null, and QuickCatalog.byId(presetId)?.tone == WARNING.
     * Unknown preset ids are ignored. Each key is alerted at most once (remember keys, evict oldest beyond maxRemembered).
     * Returns null when there are no new warnings.
     * Otherwise: prepend new warnings to recent (cap maxRecent);
     * buzz = true if never buzzed or nowMillis - lastBuzz >= buzzGapMillis; when buzz, lastBuzz = nowMillis.
     */
    fun plan(feed: List<QuickFeedEntry>, nowMillis: Long): QuickAlertPlan? {
        val newWarnings = feed
            .asSequence()
            .filter { !it.mine && it.senderPeerID != null && it.key !in alertedKeys }
            .filter { QuickCatalog.byId(it.presetId)?.tone == QuickTone.WARNING }
            .map { QuickWarning(it.key, it.senderPeerID!!, it.presetId, it.location, it.receivedAtMillis) }
            .sortedByDescending { it.receivedAtMillis }
            .toList()
        if (newWarnings.isEmpty()) return null

        newWarnings.forEach { remember(it.key) }
        recent = (newWarnings + recent).take(maxRecent)

        val last = lastBuzzMillis
        val buzz = last == null || nowMillis - last >= buzzGapMillis
        if (buzz) lastBuzzMillis = nowMillis
        return QuickAlertPlan(newWarnings, recent, buzz)
    }

    /** User opened the quick sheet: clear recent (remembered keys stay remembered). */
    fun onAllRead() {
        recent = emptyList()
    }

    private fun remember(key: String) {
        alertedKeys += key
        while (alertedKeys.size > maxRemembered) {
            val oldest = alertedKeys.iterator()
            oldest.next()
            oldest.remove()
        }
    }
}
