package com.jasiri.sos

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SosBoardTest {
    private var now = T0
    private val sender = FakeSender()

    private fun board(config: SosBoardConfig = SosBoardConfig()) =
        SosBoard(ME, sender, { now }, config)

    // 1
    @Test
    fun `new SOS creates one ACTIVE entry`() {
        val board = board()

        board.onReceived(received(ALICE, sos(ID_1, seq = 0, body = BODY)))

        assertEquals(1, board.entries.value.size)
        val entry = board.entry(ID_1)
        assertEquals(ID_1, entry.sosId)
        assertEquals(ALICE, entry.originPeerID)
        assertEquals(BODY, entry.body)
        assertEquals(0, entry.seq)
        assertEquals(SOS_TS, entry.sosTimestampSeconds)
        assertEquals(T0, entry.firstHeardAtMillis)
        assertEquals(T0, entry.lastHeardAtMillis)
        assertEquals(SosEntryState.ACTIVE, entry.state)
        assertFalse(entry.stale)
        assertTrue(entry.ackedBy.isEmpty())
        assertTrue(entry.claimedBy.isEmpty())
        assertNull(entry.resolvedBy)
        assertEquals(T0, entry.stateChangedAtMillis)
    }

    // 2
    @Test
    fun `seq ordering uses 16-bit serial arithmetic`() {
        val board = board()
        board.onReceived(received(ALICE, sos(ID_1, seq = 0, body = BODY)))

        now = T0 + 1_000
        board.onReceived(received(ALICE, sos(ID_1, seq = 1, body = BODY_2, ts = SOS_TS + 30)))
        var entry = board.entry(ID_1)
        assertEquals(1, entry.seq)
        assertEquals(BODY_2, entry.body)
        assertEquals(SOS_TS + 30, entry.sosTimestampSeconds)
        assertEquals(T0 + 1_000, entry.lastHeardAtMillis)
        assertEquals(T0, entry.firstHeardAtMillis)

        now = T0 + 2_000
        board.onReceived(received(ALICE, sos(ID_1, seq = 1, body = BODY_3, ts = SOS_TS + 60)))
        entry = board.entry(ID_1)
        assertEquals(1, entry.seq)
        assertEquals(BODY_2, entry.body)
        assertEquals(SOS_TS + 30, entry.sosTimestampSeconds)
        assertEquals(T0 + 2_000, entry.lastHeardAtMillis)

        now = T0 + 3_000
        board.onReceived(received(ALICE, sos(ID_1, seq = 0, body = BODY_3)))
        entry = board.entry(ID_1)
        assertEquals(1, entry.seq)
        assertEquals(BODY_2, entry.body)
        assertEquals(T0 + 2_000, entry.lastHeardAtMillis)

        board.onReceived(received(ALICE, sos(ID_2, seq = 65535, body = BODY)))
        board.onReceived(received(ALICE, sos(ID_2, seq = 0, body = BODY_2)))
        entry = board.entry(ID_2)
        assertEquals(0, entry.seq)
        assertEquals(BODY_2, entry.body)
    }

    // 3
    @Test
    fun `SOS for an existing sosId from another peer is ignored`() {
        val board = board()
        board.onReceived(received(ALICE, sos(ID_1, seq = 0, body = BODY)))

        now = T0 + 1_000
        board.onReceived(received(BOB, sos(ID_1, seq = 5, body = BODY_2)))

        val entry = board.entry(ID_1)
        assertEquals(ALICE, entry.originPeerID)
        assertEquals(BODY, entry.body)
        assertEquals(0, entry.seq)
        assertEquals(T0, entry.lastHeardAtMillis)
    }

    // 4
    @Test
    fun `ACK and CLAIM from responders are recorded, from origin or for unknown ids ignored`() {
        val board = board()
        board.onReceived(received(ALICE, sos(ID_1, seq = 0, body = BODY)))

        board.onReceived(received(BOB, response(SosKind.ACK, ID_1)))
        board.onReceived(received(CAROL, response(SosKind.CLAIM, ID_1)))

        var entry = board.entry(ID_1)
        assertEquals(setOf(BOB, CAROL), entry.ackedBy)
        assertEquals(setOf(CAROL), entry.claimedBy)

        board.onReceived(received(ALICE, response(SosKind.ACK, ID_1)))
        board.onReceived(received(ALICE, response(SosKind.CLAIM, ID_1)))
        entry = board.entry(ID_1)
        assertEquals(setOf(BOB, CAROL), entry.ackedBy)
        assertEquals(setOf(CAROL), entry.claimedBy)

        board.onReceived(received(BOB, response(SosKind.ACK, UNKNOWN_ID)))
        assertEquals(listOf(ID_1), board.entries.value.map { it.sosId })
    }

    // 5
    @Test
    fun `only the origin can cancel and a cancelled SOS stays closed`() {
        val board = board()
        board.onReceived(received(ALICE, sos(ID_1, seq = 0, body = BODY)))

        board.onReceived(received(BOB, response(SosKind.CANCEL, ID_1)))
        assertEquals(SosEntryState.ACTIVE, board.entry(ID_1).state)

        now = T0 + 5_000
        board.onReceived(received(ALICE, response(SosKind.CANCEL, ID_1, seq = 1)))
        assertEquals(SosEntryState.CANCELLED, board.entry(ID_1).state)
        assertEquals(T0 + 5_000, board.entry(ID_1).stateChangedAtMillis)

        now = T0 + 10_000
        board.onReceived(received(ALICE, sos(ID_1, seq = 2, body = BODY_2)))
        val entry = board.entry(ID_1)
        assertEquals(SosEntryState.CANCELLED, entry.state)
        assertEquals(0, entry.seq)
        assertEquals(BODY, entry.body)
        assertEquals(T0 + 5_000, entry.stateChangedAtMillis)
    }

    // 6
    @Test
    fun `RESOLVE is accepted from a claimer or the origin only`() {
        val board = board()
        board.onReceived(received(ALICE, sos(ID_1, seq = 0, body = BODY)))
        board.onReceived(received(CAROL, response(SosKind.CLAIM, ID_1)))

        board.onReceived(received(BOB, response(SosKind.RESOLVE, ID_1)))
        assertEquals(SosEntryState.ACTIVE, board.entry(ID_1).state)

        now = T0 + 7_000
        board.onReceived(received(CAROL, response(SosKind.RESOLVE, ID_1)))
        var entry = board.entry(ID_1)
        assertEquals(SosEntryState.RESOLVED, entry.state)
        assertEquals(CAROL, entry.resolvedBy)
        assertEquals(T0 + 7_000, entry.stateChangedAtMillis)

        board.onReceived(received(ALICE, sos(ID_2, seq = 0, body = BODY)))
        board.onReceived(received(ALICE, response(SosKind.RESOLVE, ID_2)))
        entry = board.entry(ID_2)
        assertEquals(SosEntryState.RESOLVED, entry.state)
        assertEquals(ALICE, entry.resolvedBy)
    }

    // 7
    @Test
    fun `events from this phone's own peer ID are ignored`() {
        val board = board()

        board.onReceived(received(ME, sos(ID_1, seq = 0, body = BODY)))
        assertTrue(board.entries.value.isEmpty())

        board.onReceived(received(ALICE, sos(ID_2, seq = 0, body = BODY)))
        board.onReceived(received(ME, response(SosKind.ACK, ID_2)))
        board.onReceived(received(ME, response(SosKind.CLAIM, ID_2)))
        board.onReceived(received(ME, response(SosKind.RESOLVE, ID_2)))

        val entry = board.entry(ID_2)
        assertTrue(entry.ackedBy.isEmpty())
        assertTrue(entry.claimedBy.isEmpty())
        assertEquals(SosEntryState.ACTIVE, entry.state)
    }

    // 8
    @Test
    fun `tick marks stale entries and prunes by retention`() {
        val board = board()
        board.onReceived(received(ALICE, sos(ID_1, seq = 0, body = BODY)))

        now = T0 + 15 * MINUTE - 1
        board.tick()
        assertFalse(board.entry(ID_1).stale)
        now = T0 + 15 * MINUTE
        board.tick()
        assertTrue(board.entry(ID_1).stale)

        now = T0 + 6 * HOUR - 1
        board.tick()
        assertEquals(1, board.entries.value.size)
        now = T0 + 6 * HOUR
        board.tick()
        assertTrue(board.entries.value.isEmpty())

        val cancelledAt = now
        board.onReceived(received(BOB, sos(ID_2, seq = 0, body = BODY)))
        board.onReceived(received(BOB, response(SosKind.CANCEL, ID_2, seq = 1)))
        assertEquals(SosEntryState.CANCELLED, board.entry(ID_2).state)
        assertFalse(board.entry(ID_2).stale)

        now = cancelledAt + HOUR - 1
        board.tick()
        assertEquals(1, board.entries.value.size)
        assertFalse(board.entry(ID_2).stale)
        now = cancelledAt + HOUR
        board.tick()
        assertTrue(board.entries.value.isEmpty())
    }

    // 9
    @Test
    fun `entries are sorted fresh, stale, closed then severity then recency`() {
        val board = board()
        board.onReceived(received(DAVE, sos(ID_4, seq = 0, body = BODY.copy(severity = 3))))

        val t1 = T0 + 20 * MINUTE
        now = t1
        board.onReceived(received(ALICE, sos(ID_1, seq = 0, body = BODY.copy(severity = 1))))
        now = t1 + 1_000
        board.onReceived(received(BOB, sos(ID_2, seq = 0, body = BODY.copy(severity = 3))))
        now = t1 + 2_000
        board.onReceived(received(CAROL, sos(ID_3, seq = 0, body = BODY.copy(severity = 1))))
        now = t1 + 3_000
        board.onReceived(received(ERIN, sos(ID_5, seq = 0, body = BODY.copy(severity = 3))))
        board.onReceived(received(ERIN, response(SosKind.CANCEL, ID_5, seq = 1)))

        val entries = board.entries.value
        assertEquals(listOf(ID_2, ID_3, ID_1, ID_4, ID_5), entries.map { it.sosId })
        assertTrue(entries.first { it.sosId == ID_4 }.stale)
    }

    // 10
    @Test
    fun `maxEntries drops the entry heard least recently`() {
        val board = board(SosBoardConfig(maxEntries = 3))
        board.onReceived(received(ALICE, sos(ID_1, seq = 0, body = BODY)))
        now = T0 + 1_000
        board.onReceived(received(BOB, sos(ID_2, seq = 0, body = BODY)))
        now = T0 + 2_000
        board.onReceived(received(CAROL, sos(ID_3, seq = 0, body = BODY)))
        now = T0 + 3_000
        board.onReceived(received(ALICE, sos(ID_1, seq = 0, body = BODY)))

        now = T0 + 4_000
        board.onReceived(received(DAVE, sos(ID_4, seq = 0, body = BODY)))

        assertEquals(setOf(ID_1, ID_3, ID_4), board.entries.value.map { it.sosId }.toSet())
    }

    // 11
    @Test
    fun `acknowledge and claim send encoded responses and update local sets`() {
        val board = board()
        board.onReceived(received(ALICE, sos(ID_1, seq = 3, body = BODY)))

        now = T0 + 12_345
        assertTrue(board.acknowledge(ID_1))
        assertEquals(1, sender.sent.size)
        assertResponse(sender.sent[0], SosKind.ACK, ID_1, (T0 + 12_345) / 1000)
        assertEquals(setOf(ME), board.entry(ID_1).ackedBy)
        assertTrue(board.entry(ID_1).claimedBy.isEmpty())

        now = T0 + 20_000
        assertTrue(board.claim(ID_1))
        assertEquals(2, sender.sent.size)
        assertResponse(sender.sent[1], SosKind.CLAIM, ID_1, (T0 + 20_000) / 1000)
        assertEquals(setOf(ME), board.entry(ID_1).claimedBy)
        assertEquals(setOf(ME), board.entry(ID_1).ackedBy)

        assertFalse(board.acknowledge(UNKNOWN_ID))
        assertFalse(board.claim(UNKNOWN_ID))
        assertEquals(2, sender.sent.size)

        board.onReceived(received(ALICE, response(SosKind.CANCEL, ID_1, seq = 4)))
        assertFalse(board.acknowledge(ID_1))
        assertFalse(board.claim(ID_1))
        assertEquals(2, sender.sent.size)
    }

    // 12
    @Test
    fun `resolve requires this phone to have claimed first`() {
        val board = board()
        board.onReceived(received(ALICE, sos(ID_1, seq = 0, body = BODY)))

        assertFalse(board.resolve(ID_1))
        assertTrue(sender.sent.isEmpty())
        assertEquals(SosEntryState.ACTIVE, board.entry(ID_1).state)

        board.acknowledge(ID_1)
        assertFalse(board.resolve(ID_1))
        assertEquals(1, sender.sent.size)

        board.claim(ID_1)
        now = T0 + 60_000
        assertTrue(board.resolve(ID_1))

        assertEquals(3, sender.sent.size)
        assertResponse(sender.sent[2], SosKind.RESOLVE, ID_1, (T0 + 60_000) / 1000)
        val entry = board.entry(ID_1)
        assertEquals(SosEntryState.RESOLVED, entry.state)
        assertEquals(ME, entry.resolvedBy)
        assertEquals(T0 + 60_000, entry.stateChangedAtMillis)

        assertFalse(board.resolve(ID_1))
        assertEquals(3, sender.sent.size)
    }

    // 13
    @Test
    fun `failed or throwing sends return false but still update local state`() {
        val board = board()
        board.onReceived(received(ALICE, sos(ID_1, seq = 0, body = BODY)))

        sender.result = false
        assertFalse(board.acknowledge(ID_1))
        assertEquals(setOf(ME), board.entry(ID_1).ackedBy)

        sender.throwOnSend = true
        assertFalse(board.claim(ID_1))
        assertEquals(setOf(ME), board.entry(ID_1).claimedBy)

        assertFalse(board.resolve(ID_1))
        assertEquals(SosEntryState.RESOLVED, board.entry(ID_1).state)
        assertEquals(ME, board.entry(ID_1).resolvedBy)

        assertEquals(3, sender.sent.size)
    }

    // 14
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `collectFrom applies events emitted on the flow`() = runTest {
        val board = board()
        val flow = MutableSharedFlow<ReceivedSos>()
        val job = board.collectFrom(flow, backgroundScope)
        runCurrent()

        flow.emit(received(ALICE, sos(ID_1, seq = 0, body = BODY)))
        runCurrent()
        flow.emit(received(BOB, response(SosKind.ACK, ID_1)))
        runCurrent()

        assertEquals(listOf(ID_1), board.entries.value.map { it.sosId })
        assertEquals(setOf(BOB), board.entry(ID_1).ackedBy)

        job.cancel()
        runCurrent()
        flow.emit(received(CAROL, sos(ID_2, seq = 0, body = BODY)))
        runCurrent()
        assertEquals(listOf(ID_1), board.entries.value.map { it.sosId })
    }

    private fun SosBoard.entry(sosId: Long): SosEntry = entries.value.single { it.sosId == sosId }

    private fun received(from: String, payload: SosPayload) = ReceivedSos(from, payload, now)

    private fun sos(sosId: Long, seq: Int, body: SosBody, ts: Long = SOS_TS) =
        SosPayload(SosKind.SOS, sosId, seq, ts, body)

    private fun response(kind: SosKind, sosId: Long, seq: Int = 0) =
        SosPayload(kind, sosId, seq, SOS_TS, body = null)

    private fun assertResponse(payload: SosPayload, kind: SosKind, sosId: Long, timestampSeconds: Long) {
        assertEquals(kind, payload.kind)
        assertEquals(sosId, payload.sosId)
        assertEquals(0, payload.seq)
        assertEquals(timestampSeconds, payload.timestampSeconds)
        assertNull(payload.body)
    }

    /** Records every attempt (decoded), then returns [result] or throws when [throwOnSend]. */
    private class FakeSender : SosSender {
        val sent = mutableListOf<SosPayload>()
        var result = true
        var throwOnSend = false

        override fun send(payload: ByteArray): Boolean {
            sent += requireNotNull(SosCodec.decode(payload)) { "board sent an undecodable payload" }
            if (throwOnSend) throw IllegalStateException("transport exploded")
            return result
        }
    }

    private companion object {
        const val ME = "me"
        const val ALICE = "alice"
        const val BOB = "bob"
        const val CAROL = "carol"
        const val DAVE = "dave"
        const val ERIN = "erin"

        const val ID_1 = 0x0101010101010101L
        const val ID_2 = 0x0202020202020202L
        const val ID_3 = 0x0303030303030303L
        const val ID_4 = 0x0404040404040404L
        const val ID_5 = 0x0505050505050505L
        const val UNKNOWN_ID = 0x0909090909090909L

        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val T0 = 1_790_000_000_000L
        const val SOS_TS = 1_790_000_000L

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
        val BODY_2 = BODY.copy(category = SosCategory.TRAPPED, severity = 3)
        val BODY_3 = BODY.copy(category = SosCategory.FIRE, severity = 1)
    }
}
