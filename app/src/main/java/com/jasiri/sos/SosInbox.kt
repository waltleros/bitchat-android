package com.jasiri.sos

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** An SOS payload received from the mesh after the carrying packet passed signature checks. */
data class ReceivedSos(
    val senderPeerID: String,
    val payload: SosPayload,
    val receivedAtMillis: Long
)

/**
 * Process-wide hand-off point between the mesh receive path and SOS consumers.
 *
 * [accept] is called on the packet-processing path, so it must never throw or suspend.
 * Events are not replayed; collectors only see SOS received after they subscribe.
 */
object SosInbox {
    private val _events = MutableSharedFlow<ReceivedSos>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<ReceivedSos> = _events.asSharedFlow()

    /** Returns true when [rawPayload] is a valid v1 payload; the caller relays only in that case. */
    fun accept(
        senderPeerID: String,
        rawPayload: ByteArray,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        return try {
            val payload = SosCodec.decode(rawPayload) ?: return false
            _events.tryEmit(ReceivedSos(senderPeerID, payload, nowMillis))
            true
        } catch (_: Exception) {
            false
        }
    }
}
