package com.bitchat.android.mesh

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients

/**
 * Builds outgoing JASIRI quick message mesh packets. The payload is opaque here: callers validate
 * it before building, and the transport signs the packet before broadcasting.
 */
object JasiriQuickPackets {   // JASIRI
    /** Returns an unsigned, unrouted broadcast JASIRI_QUICK packet with the default mesh TTL. */
    fun buildBroadcast(senderID: ByteArray, payload: ByteArray, timestampMs: Long): BitchatPacket {
        require(senderID.size == 8) { "senderID must be 8 bytes, was ${senderID.size}" }
        require(payload.isNotEmpty()) { "payload must not be empty" }
        return BitchatPacket(
            version = 1u,
            type = MessageType.JASIRI_QUICK.value,
            senderID = senderID,
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = timestampMs.toULong(),
            payload = payload,
            signature = null,
            ttl = com.bitchat.android.util.AppConstants.MESSAGE_TTL_HOPS,
            route = null
        )
    }
}
