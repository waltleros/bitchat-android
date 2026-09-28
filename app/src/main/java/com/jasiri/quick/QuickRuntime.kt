package com.jasiri.quick

import com.jasiri.sos.secureRandomNonZeroLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class QuickSendStatus { PENDING, SENT, NOT_SENT }

data class QuickFeedEntry(
    /** Mine: "me:<localId>"; received: "<senderPeerID>:<msgId>". */
    val key: String,
    val mine: Boolean,
    /** Null when mine. */
    val senderPeerID: String?,
    val presetId: Int,
    val location: QuickLocation?,
    /** Mine: when actually sent (queued time while PENDING); received: payload timestamp * 1000. */
    val sentAtMillis: Long,
    /** Mine: same as [sentAtMillis]; received: local receive time. */
    val receivedAtMillis: Long,
    /** Received entries are always SENT. */
    val status: QuickSendStatus,
    /** Non-null only while PENDING. */
    val undoDeadlineMillis: Long?
)

data class QuickConfig(
    val undoMillis: Long = 5_000,
    /** Stays under peers' gate of 6 per 60 s. */
    val ownMaxPerWindow: Int = 5,
    val ownWindowMillis: Long = 60_000,
    val feedMax: Int = 200,
    val feedRetentionMillis: Long = 6 * 60 * 60_000L
)

/**
 * Sends this phone's quick messages (after an undo window) and keeps a feed of sent and received
 * ones, over a mesh transport that can come and go and a peer identity that can change (panic wipe).
 *
 * All state is guarded by one lock. The shared sender never takes the lock and is always called
 * outside it. No public method throws.
 */
class QuickRuntime(
    private val scope: CoroutineScope,
    private val clockMillis: () -> Long,
    private val inbox: Flow<ReceivedQuick>,
    private val newMsgId: () -> Long = { secureRandomNonZeroLong() },
    private val config: QuickConfig = QuickConfig()
) {
    private class OwnRecord(
        val msgId: Long,
        val presetId: Int,
        val location: QuickLocation?,
        var job: Job? = null,
        /** Encoded at the first send attempt; reused unchanged by [retry]. */
        var bytes: ByteArray? = null,
        /** True while a send is in flight; blocks undo and a concurrent retry. */
        var sending: Boolean = false
    )

    private val lock = Any()

    @Volatile
    private var transport: ((ByteArray) -> Boolean)? = null

    private val sender: (ByteArray) -> Boolean = { payload ->
        val t = transport
        if (t == null) {
            false
        } else {
            try {
                t(payload)
            } catch (_: Exception) {
                false
            }
        }
    }

    private val entries = HashMap<String, QuickFeedEntry>()
    private val ownRecords = HashMap<String, OwnRecord>()
    /** Own rate window: (entry key, time) per queue or retry, oldest first. */
    private val ownSlots = ArrayDeque<Pair<String, Long>>()
    private var unread = 0
    private var localCounter = 0L
    private var generation = 0L
    private var collectorJob: Job? = null

    private val _feed = MutableStateFlow<List<QuickFeedEntry>>(emptyList())

    /** Newest first by receivedAtMillis, ties by key. */
    val feed: StateFlow<List<QuickFeedEntry>> = _feed.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)

    /** Received (not mine) entries added since the last [markAllRead]. */
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val _myPeerID = MutableStateFlow<String?>(null)

    /** Null before the first attach. */
    val myPeerID: StateFlow<String?> = _myPeerID.asStateFlow()

    fun attach(peerID: String, transport: (ByteArray) -> Boolean) {
        try {
            synchronized(lock) {
                this.transport = transport
                val current = _myPeerID.value
                if (current == peerID && collectorJob != null) return
                if (current != null && current != peerID) {
                    ownRecords.values.forEach { it.job?.cancel() }
                    ownRecords.clear()
                    entries.clear()
                    ownSlots.clear()
                    unread = 0
                }
                generation++
                collectorJob?.cancel()
                _myPeerID.value = peerID
                val collectorGeneration = generation
                collectorJob = scope.launch {
                    inbox.collect { onReceived(it, collectorGeneration) }
                }
                publishLocked()
            }
        } catch (_: Exception) {
        }
    }

    /** Drops the transport only; the feed and pending sends are kept. */
    fun detach() {
        transport = null
    }

    /**
     * Queue a preset. Returns the new entry key, or null if rejected (unknown preset id per
     * QuickCatalog.byId, own rate limit reached, or a location the codec rejects). Never throws.
     */
    fun queue(presetId: Int, location: QuickLocation?): String? {
        return try {
            if (QuickCatalog.byId(presetId) == null) return null
            synchronized(lock) {
                val now = clockMillis()
                if (!takeSlotLocked(now)) return null
                val msgId = newMsgId()
                QuickCodec.encode(QuickPayload(msgId, presetId, now / 1000, location))
                val key = "me:${++localCounter}"
                ownSlots.addLast(key to now)
                val record = OwnRecord(msgId, presetId, location)
                ownRecords[key] = record
                entries[key] = QuickFeedEntry(
                    key = key,
                    mine = true,
                    senderPeerID = null,
                    presetId = presetId,
                    location = location,
                    sentAtMillis = now,
                    receivedAtMillis = now,
                    status = QuickSendStatus.PENDING,
                    undoDeadlineMillis = now + config.undoMillis
                )
                record.job = scope.launch {
                    delay(config.undoMillis)
                    sendPending(key)
                }
                publishLocked()
                key
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Cancel a PENDING entry (removes it from the feed). Returns false if not pending. */
    fun undo(key: String): Boolean {
        return try {
            synchronized(lock) {
                val record = ownRecords[key] ?: return false
                if (entries[key]?.status != QuickSendStatus.PENDING || record.sending) return false
                record.job?.cancel()
                ownRecords.remove(key)
                entries.remove(key)
                val slot = ownSlots.indexOfFirst { it.first == key }
                if (slot >= 0) ownSlots.removeAt(slot)
                publishLocked()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Re-send a NOT_SENT own entry with the SAME msgId/bytes. Counts toward own rate limit.
     * Returns true if the entry is now SENT.
     */
    fun retry(key: String): Boolean {
        return try {
            val bytes = synchronized(lock) {
                val record = ownRecords[key] ?: return false
                val entry = entries[key] ?: return false
                if (entry.status != QuickSendStatus.NOT_SENT || record.sending) return false
                val stored = record.bytes ?: return false
                val now = clockMillis()
                if (!takeSlotLocked(now)) return false
                ownSlots.addLast(key to now)
                record.sending = true
                stored
            }
            val sent = sender(bytes)
            synchronized(lock) { finishSendLocked(key, sent, clockMillis()) }
            sent
        } catch (_: Exception) {
            false
        }
    }

    fun markAllRead() {
        try {
            synchronized(lock) {
                unread = 0
                publishLocked()
            }
        } catch (_: Exception) {
        }
    }

    private fun sendPending(key: String) {
        val sendTime = clockMillis()
        val bytes = synchronized(lock) {
            val record = ownRecords[key] ?: return
            if (entries[key]?.status != QuickSendStatus.PENDING || record.sending) return
            val encoded = try {
                QuickCodec.encode(QuickPayload(record.msgId, record.presetId, sendTime / 1000, record.location))
            } catch (_: Exception) {
                null
            }
            if (encoded == null) {
                finishSendLocked(key, sent = false, atMillis = sendTime)
                return
            }
            record.bytes = encoded
            record.sending = true
            encoded
        }
        val sent = try {
            sender(bytes)
        } catch (_: Exception) {
            false
        }
        synchronized(lock) { finishSendLocked(key, sent, sendTime) }
    }

    /** No-op if the entry disappeared meanwhile (identity change or pruning). */
    private fun finishSendLocked(key: String, sent: Boolean, atMillis: Long) {
        val record = ownRecords[key] ?: return
        val entry = entries[key] ?: return
        record.sending = false
        record.job = null
        entries[key] = entry.copy(
            status = if (sent) QuickSendStatus.SENT else QuickSendStatus.NOT_SENT,
            sentAtMillis = atMillis,
            receivedAtMillis = atMillis,
            undoDeadlineMillis = null
        )
        publishLocked()
    }

    private fun onReceived(event: ReceivedQuick, collectorGeneration: Long) {
        try {
            synchronized(lock) {
                if (collectorGeneration != generation) return
                if (event.senderPeerID == _myPeerID.value) return
                val key = "${event.senderPeerID}:${event.payload.msgId}"
                if (entries.containsKey(key)) return
                entries[key] = QuickFeedEntry(
                    key = key,
                    mine = false,
                    senderPeerID = event.senderPeerID,
                    presetId = event.payload.presetId,
                    location = event.payload.location,
                    sentAtMillis = event.payload.timestampSeconds * 1000,
                    receivedAtMillis = event.receivedAtMillis,
                    status = QuickSendStatus.SENT,
                    undoDeadlineMillis = null
                )
                unread++
                publishLocked()
            }
        } catch (_: Exception) {
        }
    }

    /** Drops slots older than the window; true if one more fits. */
    private fun takeSlotLocked(now: Long): Boolean {
        while (ownSlots.isNotEmpty() && now - ownSlots.first().second >= config.ownWindowMillis) {
            ownSlots.removeFirst()
        }
        return ownSlots.size < config.ownMaxPerWindow
    }

    private fun publishLocked() {
        val now = clockMillis()
        entries.values
            .filter { it.status != QuickSendStatus.PENDING && now - it.receivedAtMillis > config.feedRetentionMillis }
            .forEach { dropLocked(it.key) }

        val sorted = entries.values.sortedWith(DISPLAY_ORDER).toMutableList()
        var index = sorted.lastIndex
        while (sorted.size > config.feedMax && index >= 0) {
            val candidate = sorted[index]
            if (candidate.status != QuickSendStatus.PENDING) {
                sorted.removeAt(index)
                dropLocked(candidate.key)
            }
            index--
        }

        unread = unread.coerceAtMost(sorted.count { !it.mine })
        _feed.value = sorted
        _unreadCount.value = unread
    }

    private fun dropLocked(key: String) {
        entries.remove(key)
        ownRecords.remove(key)?.job?.cancel()
    }

    private companion object {
        val DISPLAY_ORDER: Comparator<QuickFeedEntry> =
            compareByDescending<QuickFeedEntry> { it.receivedAtMillis }.thenBy { it.key }
    }
}
