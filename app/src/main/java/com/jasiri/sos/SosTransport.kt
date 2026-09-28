package com.jasiri.sos

/**
 * Wraps a mesh send so it only reports success when someone could actually hear it.
 * Always attempts the send (a peer may be connecting right now); returns
 * `queued && peerCount() > 0`. Never throws: an exception from send → false,
 * an exception from peerCount → treated as 0.
 */
fun peerGatedTransport(
    send: (ByteArray) -> Boolean,
    peerCount: () -> Int
): (ByteArray) -> Boolean = { payload ->
    val queued = try {
        send(payload)
    } catch (_: Exception) {
        false
    }
    val peers = try {
        peerCount()
    } catch (_: Exception) {
        0
    }
    queued && peers > 0
}
