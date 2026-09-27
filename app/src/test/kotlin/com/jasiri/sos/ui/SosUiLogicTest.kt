package com.jasiri.sos.ui

import com.jasiri.sos.OwnSosState
import com.jasiri.sos.OwnSosStatus
import com.jasiri.sos.SosBody
import com.jasiri.sos.SosCategory
import com.jasiri.sos.SosEntry
import com.jasiri.sos.SosEntryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SosUiLogicTest {

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val ME = "aaaaaaaaaaaaaaaa"
        const val ALICE = "bbbbbbbbbbbbbbbb"
        const val BOB = "cccccccccccccccc"
        val BODY = SosBody(SosCategory.MEDICAL, 3, null, 50, null)
    }

    private fun entry(
        sosId: Long = 1L,
        state: SosEntryState = SosEntryState.ACTIVE,
        stale: Boolean = false,
        ackedBy: Set<String> = emptySet(),
        claimedBy: Set<String> = emptySet()
    ) = SosEntry(
        sosId = sosId,
        originPeerID = ALICE,
        body = BODY,
        seq = 0,
        sosTimestampSeconds = NOW / 1000,
        firstHeardAtMillis = NOW,
        lastHeardAtMillis = NOW,
        state = state,
        stale = stale,
        ackedBy = ackedBy,
        claimedBy = claimedBy,
        resolvedBy = null,
        stateChangedAtMillis = NOW
    )

    private fun active(successfulSends: Int = 0, lastSuccessAtMillis: Long? = null, notSentWarning: Boolean = false) =
        OwnSosStatus.IDLE.copy(
            state = OwnSosState.ACTIVE,
            sosId = 42L,
            body = BODY,
            startedAtMillis = NOW - 5 * 60_000,
            lastAttemptAtMillis = lastSuccessAtMillis,
            lastSuccessAtMillis = lastSuccessAtMillis,
            successfulSends = successfulSends,
            notSentWarning = notSentWarning
        )

    @Test
    fun `heardAgo boundaries`() {
        assertEquals(HeardAgo.JustNow, heardAgo(NOW, NOW))
        assertEquals(HeardAgo.JustNow, heardAgo(NOW - 59_999, NOW))
        assertEquals(HeardAgo.Minutes(1), heardAgo(NOW - 60_000, NOW))
        assertEquals(HeardAgo.Minutes(59), heardAgo(NOW - 3_599_999, NOW))
        assertEquals(HeardAgo.Hours(1), heardAgo(NOW - 3_600_000, NOW))
        assertEquals(HeardAgo.Hours(2), heardAgo(NOW - 7_300_000, NOW))
    }

    @Test
    fun `heardAgo with negative diff is just now`() {
        assertEquals(HeardAgo.JustNow, heardAgo(NOW + 10_000, NOW))
    }

    @Test
    fun `ownSosDisplay idle`() {
        assertEquals(OwnSosDisplay.Idle, ownSosDisplay(OwnSosStatus.IDLE, NOW))
    }

    @Test
    fun `ownSosDisplay active shows sends and last sent`() {
        val status = active(successfulSends = 3, lastSuccessAtMillis = NOW - 90_000)
        assertEquals(OwnSosDisplay.Sending(3, HeardAgo.Minutes(1)), ownSosDisplay(status, NOW))
    }

    @Test
    fun `ownSosDisplay active with not-sent warning`() {
        val status = active(notSentWarning = true)
        assertEquals(OwnSosDisplay.NotSent, ownSosDisplay(status, NOW))
    }

    @Test
    fun `ownSosDisplay cancelling cancelled expired`() {
        val base = active(successfulSends = 2, lastSuccessAtMillis = NOW)
        assertEquals(OwnSosDisplay.Cancelling, ownSosDisplay(base.copy(state = OwnSosState.CANCELLING), NOW))
        assertEquals(OwnSosDisplay.Cancelled, ownSosDisplay(base.copy(state = OwnSosState.CANCELLED), NOW))
        assertEquals(OwnSosDisplay.Expired, ownSosDisplay(base.copy(state = OwnSosState.EXPIRED), NOW))
    }

    @Test
    fun `alertBadgeCount counts only active fresh entries`() {
        val entries = listOf(
            entry(sosId = 1),
            entry(sosId = 2),
            entry(sosId = 3, stale = true),
            entry(sosId = 4, state = SosEntryState.CANCELLED),
            entry(sosId = 5, state = SosEntryState.RESOLVED)
        )
        assertEquals(2, alertBadgeCount(entries))
    }

    @Test
    fun `availableActions fresh active entry`() {
        assertEquals(listOf(SosAction.ACKNOWLEDGE, SosAction.CLAIM), availableActions(entry(), ME))
    }

    @Test
    fun `availableActions after ack and after claim`() {
        assertEquals(listOf(SosAction.CLAIM), availableActions(entry(ackedBy = setOf(ME, BOB)), ME))
        assertEquals(listOf(SosAction.RESOLVE), availableActions(entry(claimedBy = setOf(ME)), ME))
        assertEquals(
            listOf(SosAction.RESOLVE),
            availableActions(entry(ackedBy = setOf(ME), claimedBy = setOf(ME)), ME)
        )
    }

    @Test
    fun `availableActions empty for closed entries or unknown peer id`() {
        assertTrue(availableActions(entry(state = SosEntryState.CANCELLED), ME).isEmpty())
        assertTrue(availableActions(entry(state = SosEntryState.RESOLVED), ME).isEmpty())
        assertTrue(availableActions(entry(), null).isEmpty())
    }

    @Test
    fun `peerLabel uses nickname else first 8 chars`() {
        val peer = "0123456789abcdef"
        assertEquals("alice", peerLabel(peer, mapOf(peer to "alice")))
        assertEquals("01234567", peerLabel(peer, mapOf(peer to "   ")))
        assertEquals("01234567", peerLabel(peer, emptyMap()))
    }

    @Test
    fun `batteryPercentOrNull range`() {
        assertNull(batteryPercentOrNull(-1))
        assertEquals(0, batteryPercentOrNull(0))
        assertEquals(100, batteryPercentOrNull(100))
        assertNull(batteryPercentOrNull(101))
    }
}
