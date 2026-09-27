package com.jasiri.sos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SosEntryState { ACTIVE, CANCELLED, RESOLVED }

data class SosEntry(
    val sosId: Long,
    /** Mesh peer that sent the first SOS payload for this sosId. */
    val originPeerID: String,
    /** Latest body. */
    val body: SosBody,
    /** Latest accepted SOS seq. */
    val seq: Int,
    /** Timestamp from the latest accepted SOS payload. */
    val sosTimestampSeconds: Long,
    val firstHeardAtMillis: Long,
    /** Last time an SOS payload (new or re-broadcast) for this entry was accepted. */
    val lastHeardAtMillis: Long,
    val state: SosEntryState,
    /** ACTIVE but no SOS payload heard for [SosBoardConfig.staleAfterMillis]; always false when closed. */
    val stale: Boolean,
    /** Peer IDs; may include this phone's own peer ID. */
    val ackedBy: Set<String>,
    /** Peer IDs; may include this phone's own peer ID. */
    val claimedBy: Set<String>,
    val resolvedBy: String?,
    val stateChangedAtMillis: Long
)

data class SosBoardConfig(
    val staleAfterMillis: Long = 15 * 60_000,
    /** Drop ACTIVE entries not heard for this long. */
    val activeRetentionMillis: Long = 6 * 60 * 60_000L,
    /** Drop CANCELLED/RESOLVED entries this long after their state change. */
    val closedRetentionMillis: Long = 60 * 60_000L,
    val maxEntries: Int = 500
)

/**
 * The local picture of other people's SOS, built from signature-verified [ReceivedSos] events,
 * plus the responder actions this phone can take.
 *
 * All state is guarded by one lock. [SosSender.send] is called while holding it, so it must not
 * block. No public method throws.
 */
class SosBoard(
    private val myPeerID: String,
    private val sender: SosSender,
    private val clockMillis: () -> Long,
    private val config: SosBoardConfig = SosBoardConfig()
) {
    private val lock = Any()
    private val bySosId = LinkedHashMap<Long, SosEntry>()
    private val _entries = MutableStateFlow<List<SosEntry>>(emptyList())

    /**
     * Sorted: ACTIVE non-stale first, then ACTIVE stale, then closed. Within each group by
     * severity (highest first), then by lastHeardAtMillis (most recent first).
     */
    val entries: StateFlow<List<SosEntry>> = _entries.asStateFlow()

    fun onReceived(event: ReceivedSos) {
        try {
            synchronized(lock) {
                val now = clockMillis()
                if (event.senderPeerID != myPeerID) apply(event.payload, event.senderPeerID, now)
                tickLocked(now)
            }
        } catch (_: Exception) {
        }
    }

    fun collectFrom(flow: Flow<ReceivedSos>, scope: CoroutineScope): Job =
        scope.launch { flow.collect { onReceived(it) } }

    /** Recomputes stale flags, prunes old entries and publishes [entries]. */
    fun tick() {
        try {
            synchronized(lock) { tickLocked(clockMillis()) }
        } catch (_: Exception) {
        }
    }

    fun acknowledge(sosId: Long): Boolean =
        respond(sosId, SosKind.ACK) { entry, _ -> entry.copy(ackedBy = entry.ackedBy + myPeerID) }

    fun claim(sosId: Long): Boolean =
        respond(sosId, SosKind.CLAIM) { entry, _ ->
            entry.copy(claimedBy = entry.claimedBy + myPeerID, ackedBy = entry.ackedBy + myPeerID)
        }

    /** Requires that this phone has claimed the SOS first. */
    fun resolve(sosId: Long): Boolean =
        respond(
            sosId,
            SosKind.RESOLVE,
            precondition = { myPeerID in it.claimedBy }
        ) { entry, now ->
            entry.copy(state = SosEntryState.RESOLVED, resolvedBy = myPeerID, stateChangedAtMillis = now)
        }

    private fun apply(p: SosPayload, from: String, now: Long) {
        val entry = bySosId[p.sosId]
        if (entry == null) {
            if (p.kind != SosKind.SOS) return
            val body = p.body ?: return
            bySosId[p.sosId] = SosEntry(
                sosId = p.sosId,
                originPeerID = from,
                body = body,
                seq = p.seq,
                sosTimestampSeconds = p.timestampSeconds,
                firstHeardAtMillis = now,
                lastHeardAtMillis = now,
                state = SosEntryState.ACTIVE,
                stale = false,
                ackedBy = emptySet(),
                claimedBy = emptySet(),
                resolvedBy = null,
                stateChangedAtMillis = now
            )
            return
        }
        if (entry.state != SosEntryState.ACTIVE) return
        val fromOrigin = from == entry.originPeerID

        val updated = when (p.kind) {
            SosKind.SOS -> {
                if (!fromOrigin) return
                val body = p.body ?: return
                val diff = (p.seq - entry.seq) and 0xFFFF
                when (diff) {
                    0 -> entry.copy(lastHeardAtMillis = now)
                    in 1..32767 -> entry.copy(
                        body = body,
                        seq = p.seq,
                        sosTimestampSeconds = p.timestampSeconds,
                        lastHeardAtMillis = now
                    )
                    else -> return
                }
            }
            SosKind.ACK -> {
                if (fromOrigin) return
                entry.copy(ackedBy = entry.ackedBy + from)
            }
            SosKind.CLAIM -> {
                if (fromOrigin) return
                entry.copy(claimedBy = entry.claimedBy + from, ackedBy = entry.ackedBy + from)
            }
            SosKind.CANCEL -> {
                if (!fromOrigin) return
                entry.copy(state = SosEntryState.CANCELLED, stateChangedAtMillis = now)
            }
            SosKind.RESOLVE -> {
                if (!fromOrigin && from !in entry.claimedBy) return
                entry.copy(state = SosEntryState.RESOLVED, resolvedBy = from, stateChangedAtMillis = now)
            }
        }
        bySosId[p.sosId] = updated
    }

    private fun respond(
        sosId: Long,
        kind: SosKind,
        precondition: (SosEntry) -> Boolean = { true },
        applyLocally: (SosEntry, Long) -> SosEntry
    ): Boolean {
        return try {
            synchronized(lock) {
                val now = clockMillis()
                val entry = bySosId[sosId]
                if (entry == null || entry.state != SosEntryState.ACTIVE || !precondition(entry)) {
                    return false
                }
                val sent = try {
                    sender.send(SosCodec.encode(SosPayload(kind, sosId, 0, now / 1000, body = null)))
                } catch (_: Exception) {
                    false
                }
                bySosId[sosId] = applyLocally(entry, now)
                tickLocked(now)
                sent
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun tickLocked(now: Long) {
        val iterator = bySosId.entries.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            val entry = item.value
            val expired = when (entry.state) {
                SosEntryState.ACTIVE -> now - entry.lastHeardAtMillis >= config.activeRetentionMillis
                SosEntryState.CANCELLED, SosEntryState.RESOLVED ->
                    now - entry.stateChangedAtMillis >= config.closedRetentionMillis
            }
            if (expired) {
                iterator.remove()
                continue
            }
            val stale = entry.state == SosEntryState.ACTIVE &&
                now - entry.lastHeardAtMillis >= config.staleAfterMillis
            if (stale != entry.stale) item.setValue(entry.copy(stale = stale))
        }

        val overflow = bySosId.size - config.maxEntries.coerceAtLeast(0)
        if (overflow > 0) {
            bySosId.values
                .sortedWith(compareBy<SosEntry> { it.lastHeardAtMillis }.thenBy { it.firstHeardAtMillis })
                .take(overflow)
                .forEach { bySosId.remove(it.sosId) }
        }

        _entries.value = bySosId.values.sortedWith(DISPLAY_ORDER)
    }

    private companion object {
        val DISPLAY_ORDER: Comparator<SosEntry> =
            compareBy<SosEntry> { group(it) }
                .thenByDescending { it.body.severity }
                .thenByDescending { it.lastHeardAtMillis }
                .thenBy { it.sosId }

        fun group(entry: SosEntry): Int = when {
            entry.state != SosEntryState.ACTIVE -> 2
            entry.stale -> 1
            else -> 0
        }
    }
}
