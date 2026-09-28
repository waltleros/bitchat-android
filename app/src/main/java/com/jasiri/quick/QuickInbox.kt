package com.jasiri.quick

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A quick message received from the mesh after the carrying packet passed signature checks. */
data class ReceivedQuick(
    val senderPeerID: String,
    val payload: QuickPayload,
    val receivedAtMillis: Long
)

/**
 * Flood limit for received quick messages. Pure, testable core. All methods thread-safe (one lock).
 * Never throws.
 *
 * Rules, in order: undecodable → INVALID; (sender, msgId) seen within [dedupeMillis] → DUPLICATE
 * (the first-seen time is not refreshed); sender already has [maxPerWindow] accepted messages in
 * the last [windowMillis] → RATE_LIMITED; otherwise ACCEPT. Only ACCEPT counts toward the rate.
 */
class QuickGate(
    private val maxPerWindow: Int = 6,
    private val windowMillis: Long = 60_000,
    private val dedupeMillis: Long = 10 * 60_000,
    private val maxTrackedSenders: Int = 1_000,
    private val maxTrackedIds: Int = 5_000
) {
    enum class Verdict { ACCEPT, INVALID, DUPLICATE, RATE_LIMITED }

    private data class Key(val senderPeerID: String, val msgId: Long)

    private val lock = Any()

    /** Insertion order, so the head is always the oldest first-seen entry. */
    private val seenIds = object : LinkedHashMap<Key, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Long>): Boolean =
            size > maxTrackedIds
    }

    /** Access order (LRU): accepted timestamps per sender, oldest first. */
    private val acceptedBySender = object : LinkedHashMap<String, ArrayDeque<Long>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<Long>>): Boolean =
            size > maxTrackedSenders
    }

    fun check(senderPeerID: String, rawPayload: ByteArray, nowMillis: Long): Pair<Verdict, QuickPayload?> {
        return try {
            val payload = QuickCodec.decode(rawPayload) ?: return Verdict.INVALID to null
            synchronized(lock) { decide(Key(senderPeerID, payload.msgId), nowMillis) } to payload
        } catch (_: Exception) {
            Verdict.INVALID to null
        }
    }

    private fun decide(key: Key, nowMillis: Long): Verdict {
        pruneExpiredIds(nowMillis)
        val seenAt = seenIds[key]
        if (seenAt != null && nowMillis - seenAt < dedupeMillis) return Verdict.DUPLICATE

        val accepted = acceptedBySender.getOrPut(key.senderPeerID) { ArrayDeque() }
        while (accepted.isNotEmpty() && nowMillis - accepted.first() >= windowMillis) accepted.removeFirst()
        if (accepted.size >= maxPerWindow) return Verdict.RATE_LIMITED

        accepted.addLast(nowMillis)
        seenIds.remove(key)
        seenIds[key] = nowMillis
        return Verdict.ACCEPT
    }

    private fun pruneExpiredIds(nowMillis: Long) {
        val iterator = seenIds.values.iterator()
        while (iterator.hasNext()) {
            if (nowMillis - iterator.next() >= dedupeMillis) iterator.remove() else break
        }
    }

    internal fun trackedSenderCount(): Int = synchronized(lock) { acceptedBySender.size }
}

/**
 * Process-wide hand-off point between the mesh receive path and quick message consumers; same
 * contract as `SosInbox`. [accept] is called on the packet-processing path, so it must never
 * throw or suspend. Events are not replayed.
 */
object QuickInbox {
    private val gate = QuickGate()

    private val _events = MutableSharedFlow<ReceivedQuick>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<ReceivedQuick> = _events.asSharedFlow()

    /** Returns true only for ACCEPT (the caller relays only then). Never throws. */
    fun accept(
        senderPeerID: String,
        rawPayload: ByteArray,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        return try {
            val (verdict, payload) = gate.check(senderPeerID, rawPayload, nowMillis)
            if (verdict != QuickGate.Verdict.ACCEPT || payload == null) return false
            _events.tryEmit(ReceivedQuick(senderPeerID, payload, nowMillis))
            true
        } catch (_: Exception) {
            false
        }
    }
}
