package com.jasiri.sos

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class SosInboxTest {

    @Test
    fun `golden SOS is accepted and emitted once`() = runTest {
        val sender = "golden-sender"
        val received = collectFrom(sender)

        assertTrue(SosInbox.accept(sender, GOLDEN, nowMillis = 1_790_000_000_123L))
        runCurrent()

        val expected = SosCodec.decode(GOLDEN)
        assertNotNull(expected)
        assertEquals(1, received.size)
        assertEquals(sender, received[0].senderPeerID)
        assertEquals(expected, received[0].payload)
        assertEquals(1_790_000_000_123L, received[0].receivedAtMillis)
    }

    @Test
    fun `garbage or truncated bytes are rejected and not emitted`() = runTest {
        val sender = "garbage-sender"
        val received = collectFrom(sender)

        val inputs = listOf(
            ByteArray(0),
            byteArrayOf(0x01),
            GOLDEN.copyOf(16),
            GOLDEN.copyOf(GOLDEN.size - 1),
            GOLDEN.copyOf().also { it[0] = 2 },
            ByteArray(33) { 0xFF.toByte() },
            ByteArray(64) { 0x00 }
        )
        inputs.forEach { bytes ->
            assertFalse("expected rejection for ${bytes.size} bytes", SosInbox.accept(sender, bytes))
        }
        runCurrent()

        assertTrue(received.isEmpty())
    }

    @Test
    fun `random input never throws`() {
        val random = Random(0x4A415349L)
        repeat(1_000) {
            val bytes = random.nextBytes(random.nextInt(0, 80))
            val accepted = SosInbox.accept("fuzz-sender", bytes)
            assertEquals(SosCodec.decode(bytes) != null, accepted)
        }
    }

    /** Subscribes before emitting (the flow has no replay); filters because [SosInbox] is global. */
    private fun TestScope.collectFrom(sender: String): List<ReceivedSos> {
        val received = mutableListOf<ReceivedSos>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            SosInbox.events.filter { it.senderPeerID == sender }.toList(received)
        }
        return received
    }

    private companion object {
        val GOLDEN: ByteArray =
            "0101010203040506070800016ab13b80010201ff3ad75815f29378000f001e4001"
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
