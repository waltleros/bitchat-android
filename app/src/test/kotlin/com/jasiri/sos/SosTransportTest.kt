package com.jasiri.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SosTransportTest {

    private val payload = byteArrayOf(1, 2, 3, 4)

    @Test
    fun `queued with one peer succeeds`() {
        val transport = peerGatedTransport(send = { true }, peerCount = { 1 })
        assertTrue(transport(payload))
    }

    @Test
    fun `queued with no peers fails`() {
        val transport = peerGatedTransport(send = { true }, peerCount = { 0 })
        assertFalse(transport(payload))
    }

    @Test
    fun `not queued fails even with peers`() {
        val transport = peerGatedTransport(send = { false }, peerCount = { 3 })
        assertFalse(transport(payload))
    }

    @Test
    fun `send throwing fails without escaping`() {
        val transport = peerGatedTransport(send = { throw IllegalStateException("boom") }, peerCount = { 2 })
        assertFalse(transport(payload))
    }

    @Test
    fun `peerCount throwing fails without escaping`() {
        val transport = peerGatedTransport(send = { true }, peerCount = { throw IllegalStateException("boom") })
        assertFalse(transport(payload))
    }

    @Test
    fun `send is attempted even with no peers and gets the payload unchanged`() {
        val sent = mutableListOf<ByteArray>()
        val transport = peerGatedTransport(
            send = { bytes -> sent.add(bytes); true },
            peerCount = { 0 }
        )

        assertFalse(transport(payload))

        assertEquals(1, sent.size)
        assertSame(payload, sent[0])
        assertTrue(sent[0].contentEquals(byteArrayOf(1, 2, 3, 4)))
    }
}
