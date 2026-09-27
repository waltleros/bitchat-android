package com.jasiri.sos

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SosRuntimeTest {
    private val inbox = MutableSharedFlow<ReceivedSos>(extraBufferCapacity = 64)

    // 1
    @Test
    fun `before attach own SOS fails to send and board is empty`() = runTest {
        val runtime = runtime()

        runtime.own.start(BODY)

        assertTrue(runtime.own.status.value.consecutiveFailures >= 1)
        assertTrue(runtime.entries.value.isEmpty())
        assertNull(runtime.myPeerID.value)
    }

    // 2
    @Test
    fun `after attach own SOS goes out through transport`() = runTest {
        val runtime = runtime()
        val recorder = Recorder()
        attach(runtime, ME, recorder)

        runtime.own.start(BODY)

        assertEquals(1, recorder.sent.size)
        assertEquals(SosKind.SOS, recorder.sent[0].kind)
        assertEquals(ME, runtime.myPeerID.value)
    }

    // 3
    @Test
    fun `received SOS from another peer appears in entries`() = runTest {
        val runtime = runtime()
        attach(runtime, ME, Recorder())

        emitSos(OTHER, OTHER_SOS_ID)

        assertEquals(1, runtime.entries.value.size)
        assertEquals(OTHER, runtime.entries.value[0].originPeerID)
        assertEquals(OTHER_SOS_ID, runtime.entries.value[0].sosId)
    }

    // 4
    @Test
    fun `own echoes are ignored`() = runTest {
        val runtime = runtime()
        attach(runtime, ME, Recorder())

        emitSos(ME, OTHER_SOS_ID)

        assertTrue(runtime.entries.value.isEmpty())
    }

    // 5
    @Test
    fun `acknowledge sends ACK through transport`() = runTest {
        val runtime = runtime()
        val recorder = Recorder()
        attach(runtime, ME, recorder)
        emitSos(OTHER, OTHER_SOS_ID)

        assertTrue(runtime.acknowledge(OTHER_SOS_ID))

        val last = recorder.sent.last()
        assertEquals(SosKind.ACK, last.kind)
        assertEquals(OTHER_SOS_ID, last.sosId)
    }

    // 6
    @Test
    fun `responder actions return false without transport`() = runTest {
        val runtime = runtime()
        assertFalse(runtime.acknowledge(OTHER_SOS_ID))

        val recorder = Recorder()
        attach(runtime, ME, recorder)
        emitSos(OTHER, OTHER_SOS_ID)
        runtime.detach()

        assertFalse(runtime.acknowledge(OTHER_SOS_ID))
        assertFalse(runtime.claim(OTHER_SOS_ID))
        runCurrent()
        assertTrue(recorder.sent.isEmpty())
        assertTrue(runtime.entries.value.single().ackedBy.isEmpty())
        assertTrue(runtime.entries.value.single().claimedBy.isEmpty())
    }

    // 7
    @Test
    fun `detach keeps board and own SOS but sends fail`() = runTest {
        val runtime = runtime()
        val recorder = Recorder()
        attach(runtime, ME, recorder)
        runtime.own.start(BODY)
        emitSos(OTHER, OTHER_SOS_ID)

        runtime.detach()
        advanceTimeBy(30_000)
        runCurrent()

        assertTrue(runtime.own.status.value.consecutiveFailures >= 1)
        assertEquals(OwnSosState.ACTIVE, runtime.own.status.value.state)
        assertEquals(1, runtime.entries.value.size)
        assertEquals(1, recorder.sent.size)
    }

    // 8
    @Test
    fun `re-attach with same peerID keeps everything`() = runTest {
        val runtime = runtime()
        val first = Recorder()
        attach(runtime, ME, first)
        runtime.own.start(BODY)
        val sosId = runtime.own.status.value.sosId
        emitSos(OTHER, OTHER_SOS_ID)
        runtime.detach()

        val second = Recorder()
        attach(runtime, ME, second)
        advanceTimeBy(30_000)
        runCurrent()

        assertTrue(second.sent.isNotEmpty())
        assertEquals(SosKind.SOS, second.sent[0].kind)
        assertEquals(sosId, second.sent[0].sosId)
        assertEquals(OwnSosState.ACTIVE, runtime.own.status.value.state)
        assertEquals(listOf(OTHER_SOS_ID), runtime.entries.value.map { it.sosId })
    }

    // 9
    @Test
    fun `identity change abandons own SOS and resets board`() = runTest {
        val runtime = runtime()
        val recorder = Recorder()
        attach(runtime, ME, recorder)
        runtime.own.start(BODY)
        emitSos(OTHER, OTHER_SOS_ID)
        assertEquals(1, runtime.entries.value.size)

        attach(runtime, NEW_ME, recorder)

        assertEquals(OwnSosState.IDLE, runtime.own.status.value.state)
        assertTrue(runtime.entries.value.isEmpty())
        assertEquals(NEW_ME, runtime.myPeerID.value)

        advanceTimeBy(20 * MINUTE)
        runCurrent()
        assertEquals(1, recorder.sent.size)
        assertTrue(runtime.entries.value.isEmpty())

        emitSos(ME, OLD_IDENTITY_SOS_ID)
        assertEquals(1, runtime.entries.value.size)
        assertEquals(ME, runtime.entries.value[0].originPeerID)
    }

    // 10
    @Test
    fun `ticker marks silent entries stale`() = runTest {
        val runtime = runtime()
        attach(runtime, ME, Recorder())
        emitSos(OTHER, OTHER_SOS_ID)
        assertFalse(runtime.entries.value.single().stale)

        advanceTimeBy(16 * MINUTE)
        runCurrent()

        assertTrue(runtime.entries.value.single().stale)
    }

    // 11
    @Test
    fun `throwing transport counts as failure without crashing`() = runTest {
        val runtime = runtime()
        runtime.attach(ME) { throw IllegalStateException("transport exploded") }
        runCurrent()

        runtime.own.start(BODY)

        assertTrue(runtime.own.status.value.consecutiveFailures >= 1)
        assertEquals(OwnSosState.ACTIVE, runtime.own.status.value.state)
    }

    private fun TestScope.runtime(): SosRuntime =
        SosRuntime(backgroundScope, { testScheduler.currentTime }, inbox)

    /** Attaches, then lets the new board's inbox collector subscribe before anything is emitted. */
    private fun TestScope.attach(runtime: SosRuntime, peerID: String, recorder: Recorder) {
        runtime.attach(peerID, recorder::send)
        runCurrent()
    }

    private fun TestScope.emitSos(from: String, sosId: Long, seq: Int = 0) {
        val now = testScheduler.currentTime
        val payload = SosPayload(SosKind.SOS, sosId, seq, now / 1000, BODY)
        assertTrue(inbox.tryEmit(ReceivedSos(from, payload, now)))
        runCurrent()
    }

    /** Fake transport: records every payload it is handed (decoded) and returns [result]. */
    private class Recorder(var result: Boolean = true) {
        val sent = mutableListOf<SosPayload>()

        fun send(bytes: ByteArray): Boolean {
            sent += requireNotNull(SosCodec.decode(bytes)) { "runtime sent an undecodable payload" }
            return result
        }
    }

    private companion object {
        const val ME = "aaaaaaaaaaaaaaaa"
        const val OTHER = "bbbbbbbbbbbbbbbb"
        const val NEW_ME = "cccccccccccccccc"
        const val OTHER_SOS_ID = 0x0B0B0B0B0B0B0B0BL
        const val OLD_IDENTITY_SOS_ID = 0x0A0A0A0A0A0A0A0AL
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
    }
}
