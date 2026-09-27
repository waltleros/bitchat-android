package com.jasiri.sos

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnSosControllerTest {

    // 1
    @Test
    fun `start sends one SOS immediately`() = runTest {
        val h = harness()

        h.controller.start(BODY)

        assertEquals(1, h.sender.sent.size)
        val first = h.sender.sent[0].payload
        assertEquals(SosKind.SOS, first.kind)
        assertEquals(SOS_ID, first.sosId)
        assertEquals(0, first.seq)
        assertEquals(BODY, first.body)
        val status = h.controller.status.value
        assertEquals(OwnSosState.ACTIVE, status.state)
        assertEquals(SOS_ID, status.sosId)
        assertEquals(1, status.successfulSends)
        assertEquals(0, status.consecutiveFailures)
        assertFalse(status.notSentWarning)
        assertEquals(0L, status.startedAtMillis)
    }

    // 2, 3
    @Test
    fun `fast phase sends every 30s then slow phase every 120s`() = runTest {
        val h = harness()
        h.controller.start(BODY)

        advanceTimeBy(10 * MINUTE)
        runCurrent()

        assertEquals(1 + 20, h.sender.sent.size)
        assertEquals((0..20).map { it * 30_000L }, h.sender.sent.map { it.atMillis })
        h.sender.sent.forEach {
            assertEquals(SosKind.SOS, it.payload.kind)
            assertEquals(SOS_ID, it.payload.sosId)
            assertEquals(0, it.payload.seq)
        }

        advanceTimeBy(10 * MINUTE)
        runCurrent()

        assertEquals(21 + 5, h.sender.sent.size)
        assertEquals(
            listOf(720_000L, 840_000L, 960_000L, 1_080_000L, 1_200_000L),
            h.sender.sent.drop(21).map { it.atMillis }
        )
    }

    // 4
    @Test
    fun `payload timestamp is the virtual time in seconds at the moment of sending`() = runTest {
        val h = harness()
        advanceTimeBy(1_234_567)

        h.controller.start(BODY)
        advanceTimeBy(15 * MINUTE)
        runCurrent()

        assertTrue(h.sender.sent.size > 20)
        h.sender.sent.forEach { assertEquals(it.atMillis / 1000, it.payload.timestampSeconds) }
        assertEquals(1_234L, h.sender.sent[0].payload.timestampSeconds)
    }

    // 5
    @Test
    fun `update increments seq, sends immediately and restarts the 30s timer`() = runTest {
        val h = harness()
        h.controller.start(BODY)
        advanceTimeBy(10_000)

        h.controller.update(BODY_2)

        assertEquals(2, h.sender.sent.size)
        val updated = h.sender.sent[1]
        assertEquals(10_000L, updated.atMillis)
        assertEquals(1, updated.payload.seq)
        assertEquals(SOS_ID, updated.payload.sosId)
        assertEquals(BODY_2, updated.payload.body)
        assertEquals(1, h.controller.status.value.seq)
        assertEquals(BODY_2, h.controller.status.value.body)

        advanceTimeBy(29_999)
        assertEquals(2, h.sender.sent.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, h.sender.sent.size)
        assertEquals(40_000L, h.sender.sent[2].atMillis)
        assertEquals(1, h.sender.sent[2].payload.seq)
        assertEquals(BODY_2, h.sender.sent[2].payload.body)
    }

    // 6
    @Test
    fun `failed send sets the warning and retries after 10s`() = runTest {
        val h = harness()
        h.sender.result = false

        h.controller.start(BODY)

        var status = h.controller.status.value
        assertTrue(status.notSentWarning)
        assertEquals(1, status.consecutiveFailures)
        assertEquals(0, status.successfulSends)
        assertEquals(0L, status.lastAttemptAtMillis)
        assertNull(status.lastSuccessAtMillis)

        h.sender.result = true
        advanceTimeBy(9_999)
        assertEquals(1, h.sender.sent.size)
        advanceTimeBy(1)
        runCurrent()

        assertEquals(2, h.sender.sent.size)
        assertEquals(10_000L, h.sender.sent[1].atMillis)
        status = h.controller.status.value
        assertFalse(status.notSentWarning)
        assertEquals(0, status.consecutiveFailures)
        assertEquals(1, status.successfulSends)
        assertEquals(10_000L, status.lastSuccessAtMillis)

        advanceTimeBy(29_999)
        assertEquals(2, h.sender.sent.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, h.sender.sent.size)
        assertEquals(40_000L, h.sender.sent[2].atMillis)
    }

    // 7
    @Test
    fun `sender exception behaves like a failed send and does not stop the scheduler`() = runTest {
        val h = harness()
        h.sender.throwOnSend = true

        h.controller.start(BODY)

        assertEquals(OwnSosState.ACTIVE, h.controller.status.value.state)
        assertTrue(h.controller.status.value.notSentWarning)
        assertEquals(1, h.controller.status.value.consecutiveFailures)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, h.sender.sent.size)
        assertEquals(2, h.controller.status.value.consecutiveFailures)

        h.sender.throwOnSend = false
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(3, h.sender.sent.size)
        assertEquals(20_000L, h.sender.sent[2].atMillis)
        assertFalse(h.controller.status.value.notSentWarning)
        assertEquals(0, h.controller.status.value.consecutiveFailures)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(4, h.sender.sent.size)
        assertEquals(50_000L, h.sender.sent[3].atMillis)
    }

    // 8
    @Test
    fun `cancel sends three CANCEL payloads 30s apart and then stops`() = runTest {
        val h = harness()
        h.controller.start(BODY)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, h.sender.sent.size)

        h.controller.cancel()

        assertEquals(OwnSosState.CANCELLING, h.controller.status.value.state)
        assertEquals(4, h.sender.sent.size)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(OwnSosState.CANCELLING, h.controller.status.value.state)
        assertEquals(5, h.sender.sent.size)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(OwnSosState.CANCELLED, h.controller.status.value.state)
        assertEquals(6, h.sender.sent.size)

        val cancels = h.sender.sent.drop(3)
        assertEquals(listOf(60_000L, 90_000L, 120_000L), cancels.map { it.atMillis })
        cancels.forEach {
            assertEquals(SosKind.CANCEL, it.payload.kind)
            assertEquals(SOS_ID, it.payload.sosId)
            assertEquals(1, it.payload.seq)
            assertNull(it.payload.body)
        }

        advanceTimeBy(60 * MINUTE)
        runCurrent()
        assertEquals(6, h.sender.sent.size)
        assertEquals(OwnSosState.CANCELLED, h.controller.status.value.state)
    }

    // 9
    @Test
    fun `SOS expires at its lifetime without sending CANCEL`() = runTest {
        val h = harness(OwnSosConfig(lifetimeMillis = 5 * MINUTE))
        h.controller.start(BODY)

        advanceTimeBy(5 * MINUTE - 1)
        assertEquals(OwnSosState.ACTIVE, h.controller.status.value.state)
        assertEquals(10, h.sender.sent.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(OwnSosState.EXPIRED, h.controller.status.value.state)
        assertEquals(10, h.sender.sent.size)

        advanceTimeBy(60 * MINUTE)
        runCurrent()
        assertEquals(10, h.sender.sent.size)
        assertTrue(h.sender.sent.none { it.payload.kind == SosKind.CANCEL })
    }

    // 10
    @Test
    fun `start while ACTIVE acts like update and start while CANCELLING is ignored`() = runTest {
        val h = harness()
        h.controller.start(BODY)
        advanceTimeBy(10_000)

        h.controller.start(BODY_2)

        assertEquals(1, h.idCalls)
        assertEquals(2, h.sender.sent.size)
        assertEquals(SOS_ID, h.sender.sent[1].payload.sosId)
        assertEquals(1, h.sender.sent[1].payload.seq)
        assertEquals(BODY_2, h.sender.sent[1].payload.body)
        assertEquals(OwnSosState.ACTIVE, h.controller.status.value.state)

        h.controller.cancel()
        val sentBefore = h.sender.sent.size
        val statusBefore = h.controller.status.value

        h.controller.start(BODY)

        assertEquals(1, h.idCalls)
        assertEquals(sentBefore, h.sender.sent.size)
        assertEquals(statusBefore, h.controller.status.value)
    }

    // 11
    @Test
    fun `start with an invalid body throws and leaves the controller IDLE`() = runTest {
        val h = harness()

        assertThrows(IllegalArgumentException::class.java) {
            h.controller.start(BODY.copy(severity = 5))
        }

        assertEquals(OwnSosStatus.IDLE, h.controller.status.value)
        assertTrue(h.sender.sent.isEmpty())
        assertEquals(0, h.idCalls)
        advanceTimeBy(10 * MINUTE)
        runCurrent()
        assertTrue(h.sender.sent.isEmpty())
    }

    // 12
    @Test
    fun `resetToIdle clears a CANCELLED SOS and is a no-op while ACTIVE`() = runTest {
        val h = harness()
        h.controller.start(BODY)

        val activeStatus = h.controller.status.value
        h.controller.resetToIdle()
        assertEquals(activeStatus, h.controller.status.value)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, h.sender.sent.size)

        h.controller.cancel()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(OwnSosState.CANCELLED, h.controller.status.value.state)

        h.controller.resetToIdle()

        val status = h.controller.status.value
        assertEquals(OwnSosState.IDLE, status.state)
        assertNull(status.sosId)
        assertEquals(0, status.seq)
        assertNull(status.body)
        assertNull(status.startedAtMillis)
        assertNull(status.lastAttemptAtMillis)
        assertNull(status.lastSuccessAtMillis)
        assertEquals(0, status.successfulSends)
        assertEquals(0, status.consecutiveFailures)
        assertFalse(status.notSentWarning)
    }

    // 13
    @Test
    fun `start after resetToIdle creates a fresh SOS`() = runTest {
        val h = harness()
        h.controller.start(BODY)
        h.controller.cancel()
        advanceTimeBy(60_000)
        runCurrent()
        h.controller.resetToIdle()
        val sentBefore = h.sender.sent.size

        h.controller.start(BODY_2)

        assertEquals(2, h.idCalls)
        assertEquals(sentBefore + 1, h.sender.sent.size)
        val fresh = h.sender.sent.last()
        assertEquals(SosKind.SOS, fresh.payload.kind)
        assertEquals(SOS_ID, fresh.payload.sosId)
        assertEquals(0, fresh.payload.seq)
        assertEquals(BODY_2, fresh.payload.body)
        val status = h.controller.status.value
        assertEquals(OwnSosState.ACTIVE, status.state)
        assertEquals(0, status.seq)
        assertEquals(1, status.successfulSends)
        assertEquals(60_000L, status.startedAtMillis)
    }

    @Test
    fun `abandon while ACTIVE goes IDLE and sends nothing more`() = runTest {
        val h = harness()
        h.controller.start(BODY)

        h.controller.abandon()

        assertEquals(OwnSosState.IDLE, h.controller.status.value.state)
        assertNull(h.controller.status.value.sosId)
        assertEquals(1, h.sender.sent.size)

        advanceTimeBy(20 * MINUTE)
        runCurrent()
        assertEquals(1, h.sender.sent.size)
    }

    @Test
    fun `abandon while CANCELLING stops remaining cancels`() = runTest {
        val h = harness()
        h.controller.start(BODY)
        h.controller.cancel()
        assertEquals(OwnSosState.CANCELLING, h.controller.status.value.state)
        assertEquals(1, h.sender.sent.count { it.payload.kind == SosKind.CANCEL })

        h.controller.abandon()
        advanceTimeBy(2 * MINUTE)
        runCurrent()

        assertEquals(OwnSosState.IDLE, h.controller.status.value.state)
        assertEquals(1, h.sender.sent.count { it.payload.kind == SosKind.CANCEL })
        assertEquals(2, h.sender.sent.size)
    }

    private class Harness(val controller: OwnSosController, val sender: FakeSender) {
        var idCalls = 0
    }

    private fun TestScope.harness(config: OwnSosConfig = OwnSosConfig()): Harness {
        val sender = FakeSender { testScheduler.currentTime }
        lateinit var harness: Harness
        val controller = OwnSosController(
            sender = sender,
            scope = backgroundScope,
            clockMillis = { testScheduler.currentTime },
            newSosId = { harness.idCalls++; SOS_ID },
            config = config
        )
        harness = Harness(controller, sender)
        return harness
    }

    private data class Sent(val payload: SosPayload, val atMillis: Long)

    /** Records every attempt (decoded), then returns [result] or throws when [throwOnSend]. */
    private class FakeSender(private val clock: () -> Long) : SosSender {
        val sent = mutableListOf<Sent>()
        var result = true
        var throwOnSend = false

        override fun send(payload: ByteArray): Boolean {
            val decoded = requireNotNull(SosCodec.decode(payload)) { "controller sent an undecodable payload" }
            sent += Sent(decoded, clock())
            if (throwOnSend) throw IllegalStateException("transport exploded")
            return result
        }
    }

    private companion object {
        const val SOS_ID = 0x0102030405060708L
        const val MINUTE = 60_000L

        val BODY = SosBody(
            category = SosCategory.MEDICAL,
            severity = 2,
            location = SosLocation(
                latE7 = -12_921_000,
                lonE7 = 368_219_000,
                accuracyMeters = 15,
                fixAgeSeconds = 30,
                approximate = false
            ),
            batteryPercent = 64,
            peopleCount = 1
        )
        val BODY_2 = BODY.copy(category = SosCategory.TRAPPED, severity = 3, peopleCount = 2)
    }
}
