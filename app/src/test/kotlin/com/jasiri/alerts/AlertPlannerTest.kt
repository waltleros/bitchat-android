package com.jasiri.alerts

import com.jasiri.quick.QuickFeedEntry
import com.jasiri.quick.QuickSendStatus
import com.jasiri.sos.SosBody
import com.jasiri.sos.SosCategory
import com.jasiri.sos.SosEntry
import com.jasiri.sos.SosEntryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPlannerTest {

    private val me = "me"
    private val a = "peerA"

    private fun sos(
        sosId: Long,
        origin: String = a,
        state: SosEntryState = SosEntryState.ACTIVE,
        stale: Boolean = false
    ) = SosEntry(
        sosId = sosId,
        originPeerID = origin,
        body = SosBody(
            category = SosCategory.MEDICAL,
            severity = 3,
            location = null,
            batteryPercent = null,
            peopleCount = null
        ),
        seq = 0,
        sosTimestampSeconds = 1_000L,
        firstHeardAtMillis = 1_000L,
        lastHeardAtMillis = 1_000L,
        state = state,
        stale = stale && state == SosEntryState.ACTIVE,
        ackedBy = emptySet(),
        claimedBy = emptySet(),
        resolvedBy = null,
        stateChangedAtMillis = 1_000L
    )

    private fun quick(
        key: String,
        presetId: Int,
        mine: Boolean = false,
        receivedAt: Long = 1_000L
    ) = QuickFeedEntry(
        key = key,
        mine = mine,
        senderPeerID = if (mine) null else a,
        presetId = presetId,
        location = null,
        sentAtMillis = receivedAt,
        receivedAtMillis = receivedAt,
        status = QuickSendStatus.SENT,
        undoDeadlineMillis = null
    )

    @Test
    fun `active SOS from a peer posts once`() {
        val planner = SosAlertPlanner()
        val entries = listOf(sos(1))
        val first = planner.plan(entries, me)
        assertEquals(listOf(1L), first.post.map { it.sosId })
        assertTrue(first.cancel.isEmpty())
        assertTrue(planner.plan(entries, me).post.isEmpty())
    }

    @Test
    fun `own SOS and unknown identity never post`() {
        assertTrue(SosAlertPlanner().plan(listOf(sos(1, origin = me)), me).post.isEmpty())
        assertTrue(SosAlertPlanner().plan(listOf(sos(1)), null).post.isEmpty())
    }

    @Test
    fun `cancelled, resolved or vanished SOS is cancelled`() {
        val cancelled = SosAlertPlanner()
        cancelled.plan(listOf(sos(1)), me)
        assertEquals(listOf(1L), cancelled.plan(listOf(sos(1, state = SosEntryState.CANCELLED)), me).cancel)

        val resolved = SosAlertPlanner()
        resolved.plan(listOf(sos(2)), me)
        assertEquals(listOf(2L), resolved.plan(listOf(sos(2, state = SosEntryState.RESOLVED)), me).cancel)

        val vanished = SosAlertPlanner()
        vanished.plan(listOf(sos(3)), me)
        assertEquals(listOf(3L), vanished.plan(emptyList(), me).cancel)
    }

    @Test
    fun `stale SOS stays and a re-activated id is not re-posted`() {
        val planner = SosAlertPlanner()
        planner.plan(listOf(sos(1)), me)
        val stale = planner.plan(listOf(sos(1, stale = true)), me)
        assertTrue(stale.cancel.isEmpty())
        assertTrue(stale.post.isEmpty())

        planner.plan(listOf(sos(1, state = SosEntryState.CANCELLED)), me)
        assertTrue(planner.plan(listOf(sos(1)), me).post.isEmpty())
    }

    @Test
    fun `oldest remembered id is evicted beyond maxRemembered`() {
        val planner = SosAlertPlanner(maxRemembered = 2)
        planner.plan(listOf(sos(1)), me)
        planner.plan(listOf(sos(2)), me)
        planner.plan(listOf(sos(3)), me)
        assertEquals(listOf(1L), planner.plan(listOf(sos(1)), me).post.map { it.sosId })
    }

    @Test
    fun `only received WARNING presets alert`() {
        val plan = QuickAlertPlanner().plan(listOf(quick("peerA:1", presetId = 6)), nowMillis = 0L)
        assertNotNull(plan)
        assertEquals(1, plan!!.newWarnings.size)
        assertTrue(plan.buzz)

        assertNull(QuickAlertPlanner().plan(listOf(quick("peerA:2", presetId = 1)), 0L))
        assertNull(QuickAlertPlanner().plan(listOf(quick("peerA:3", presetId = 2)), 0L))
        assertNull(QuickAlertPlanner().plan(listOf(quick("me:1", presetId = 6, mine = true)), 0L))
    }

    @Test
    fun `each key alerts once`() {
        val planner = QuickAlertPlanner()
        val feed = listOf(quick("peerA:1", presetId = 6))
        assertNotNull(planner.plan(feed, 0L))
        assertNull(planner.plan(feed, 1_000L))
    }

    @Test
    fun `buzz at most once per gap`() {
        val planner = QuickAlertPlanner()
        val w1 = quick("peerA:1", presetId = 6, receivedAt = 0L)
        val w2 = quick("peerA:2", presetId = 6, receivedAt = 10_000L)
        val w3 = quick("peerA:3", presetId = 6, receivedAt = 31_000L)
        assertTrue(planner.plan(listOf(w1), 0L)!!.buzz)
        assertFalse(planner.plan(listOf(w2, w1), 10_000L)!!.buzz)
        assertTrue(planner.plan(listOf(w3, w2, w1), 31_000L)!!.buzz)
    }

    @Test
    fun `recent caps at five newest first and resets on all read`() {
        val planner = QuickAlertPlanner()
        var last: QuickAlertPlan? = null
        for (i in 1..7) {
            val entry = quick("peerA:$i", presetId = 6, receivedAt = i * 1_000L)
            last = planner.plan(listOf(entry), i * 1_000L)
        }
        assertEquals(listOf("peerA:7", "peerA:6", "peerA:5", "peerA:4", "peerA:3"), last!!.recent.map { it.key })

        planner.onAllRead()
        val next = planner.plan(listOf(quick("peerA:8", presetId = 6, receivedAt = 8_000L)), 8_000L)
        assertEquals(1, next!!.recent.size)
    }

    @Test
    fun `unknown preset id is ignored`() {
        assertNull(QuickAlertPlanner().plan(listOf(quick("peerA:1", presetId = 4242)), 0L))
    }
}
