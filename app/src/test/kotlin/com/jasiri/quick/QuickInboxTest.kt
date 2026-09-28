package com.jasiri.quick

import com.jasiri.quick.QuickGate.Verdict
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickInboxTest {

    private companion object {
        const val T0 = 1_800_000_000_000L
        const val SECOND = 1_000L
        const val MINUTE = 60_000L
        const val ALICE = "aaaabbbbccccdddd"
        const val BOB = "1111222233334444"
    }

    private fun payload(msgId: Long, presetId: Int = 2): ByteArray =
        QuickCodec.encode(QuickPayload(msgId, presetId, T0 / 1000, location = null))

    @Test
    fun `valid payload is accepted and returned`() {
        val gate = QuickGate()
        val (verdict, decoded) = gate.check(ALICE, payload(msgId = 1), T0)
        assertEquals(Verdict.ACCEPT, verdict)
        assertEquals(QuickPayload(1, 2, T0 / 1000, null), decoded)
    }

    @Test
    fun `garbage is invalid`() {
        val gate = QuickGate()
        val (verdict, decoded) = gate.check(ALICE, byteArrayOf(1, 2, 3), T0)
        assertEquals(Verdict.INVALID, verdict)
        assertNull(decoded)
    }

    @Test
    fun `same sender and msgId a minute later is a duplicate`() {
        val gate = QuickGate()
        assertEquals(Verdict.ACCEPT, gate.check(ALICE, payload(msgId = 7), T0).first)
        assertEquals(Verdict.DUPLICATE, gate.check(ALICE, payload(msgId = 7), T0 + MINUTE).first)
    }

    @Test
    fun `same sender and msgId after the dedupe window is accepted again`() {
        val gate = QuickGate()
        assertEquals(Verdict.ACCEPT, gate.check(ALICE, payload(msgId = 7), T0).first)
        assertEquals(Verdict.ACCEPT, gate.check(ALICE, payload(msgId = 7), T0 + 11 * MINUTE).first)
    }

    @Test
    fun `same msgId from a different sender is accepted`() {
        val gate = QuickGate()
        assertEquals(Verdict.ACCEPT, gate.check(ALICE, payload(msgId = 7), T0).first)
        assertEquals(Verdict.ACCEPT, gate.check(BOB, payload(msgId = 7), T0 + SECOND).first)
    }

    @Test
    fun `seventh distinct message within a minute is rate limited`() {
        val gate = QuickGate()
        for (i in 0 until 6) {
            assertEquals("message $i", Verdict.ACCEPT, gate.check(ALICE, payload(msgId = 100L + i), T0 + i * SECOND).first)
        }
        assertEquals(Verdict.RATE_LIMITED, gate.check(ALICE, payload(msgId = 106), T0 + 6 * SECOND).first)
    }

    @Test
    fun `sender is accepted again once the window slides past the first message`() {
        val gate = QuickGate()
        for (i in 0 until 6) {
            assertEquals(Verdict.ACCEPT, gate.check(ALICE, payload(msgId = 100L + i), T0 + i * SECOND).first)
        }
        assertEquals(Verdict.RATE_LIMITED, gate.check(ALICE, payload(msgId = 106), T0 + 6 * SECOND).first)
        assertEquals(Verdict.ACCEPT, gate.check(ALICE, payload(msgId = 107), T0 + 61 * SECOND).first)
    }

    @Test
    fun `rate limits are per sender`() {
        val gate = QuickGate()
        for (i in 0 until 6) {
            assertEquals(Verdict.ACCEPT, gate.check(ALICE, payload(msgId = 100L + i), T0 + i * SECOND).first)
        }
        assertEquals(Verdict.RATE_LIMITED, gate.check(ALICE, payload(msgId = 106), T0 + 6 * SECOND).first)
        assertEquals(Verdict.ACCEPT, gate.check(BOB, payload(msgId = 200), T0 + 6 * SECOND).first)
    }

    @Test
    fun `tracked senders are bounded`() {
        val gate = QuickGate(maxTrackedSenders = 3)
        listOf("peer000000000001", "peer000000000002", "peer000000000003", "peer000000000004")
            .forEachIndexed { i, sender ->
                assertEquals(Verdict.ACCEPT, gate.check(sender, payload(msgId = 300L + i), T0 + i * SECOND).first)
            }
        assertEquals(3, gate.trackedSenderCount())
    }

    @Test
    fun `inbox accept emits exactly one event`() = runTest {
        val received = mutableListOf<ReceivedQuick>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            QuickInbox.events.toList(received)
        }
        runCurrent()

        val msgId = 0x5A5A_0000_0000_0001L
        assertTrue(QuickInbox.accept("inboxsmoketest01", payload(msgId), T0))
        runCurrent()

        assertEquals(1, received.size)
        assertEquals("inboxsmoketest01", received[0].senderPeerID)
        assertEquals(msgId, received[0].payload.msgId)
        assertEquals(T0, received[0].receivedAtMillis)
    }
}
