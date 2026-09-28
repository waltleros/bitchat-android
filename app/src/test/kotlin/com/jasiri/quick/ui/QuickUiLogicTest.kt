package com.jasiri.quick.ui

import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import com.jasiri.quick.QuickFeedEntry
import com.jasiri.quick.QuickLocation
import com.jasiri.quick.QuickSendStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickUiLogicTest {

    private fun entry(
        key: String,
        mine: Boolean,
        status: QuickSendStatus,
        deadline: Long?
    ) = QuickFeedEntry(
        key = key,
        mine = mine,
        senderPeerID = if (mine) null else "peer0001",
        presetId = 1,
        location = null,
        sentAtMillis = 1_000L,
        receivedAtMillis = 1_000L,
        status = status,
        undoDeadlineMillis = deadline
    )

    @Test
    fun `secondaryLanguage prefers complete phone language, else sw, else null`() {
        assertEquals("sw", secondaryLanguage("en", setOf("en", "sw")))
        assertEquals("sw", secondaryLanguage("sw", setOf("en", "sw")))
        assertEquals("sw", secondaryLanguage("fr", setOf("en", "sw")))
        assertEquals("ha", secondaryLanguage("ha", setOf("en", "sw", "ha")))
        assertNull(secondaryLanguage("en", setOf("en")))
    }

    @Test
    fun `tileText gives English primary and optional secondary, null for unknown`() {
        assertEquals(TileText("Need water", "Nahitaji maji"), tileText(2, "sw"))
        assertEquals(TileText("Need water", null), tileText(2, null))
        assertNull(tileText(4242, "sw"))
    }

    @Test
    fun `unknownPresetText names the id`() {
        assertEquals("Unknown preset #4242 — update JASIRI", unknownPresetText(4242))
    }

    @Test
    fun `undoSecondsLeft rounds up and never goes below zero`() {
        val now = 10_000L
        assertEquals(5, undoSecondsLeft(now + 5000, now))
        assertEquals(5, undoSecondsLeft(now + 4001, now))
        assertEquals(4, undoSecondsLeft(now + 4000, now))
        assertEquals(1, undoSecondsLeft(now + 1, now))
        assertEquals(0, undoSecondsLeft(now, now))
        assertEquals(0, undoSecondsLeft(now - 300, now))
    }

    @Test
    fun `badgeText caps at 9+`() {
        assertEquals("", badgeText(0))
        assertEquals("1", badgeText(1))
        assertEquals("9", badgeText(9))
        assertEquals("9+", badgeText(10))
    }

    @Test
    fun `location conversion round-trips and zeroes fix age`() {
        val quick = QuickLocation(latE7 = -12_863_800, lonE7 = 368_172_200, accuracyMeters = 25, approximate = true)
        val sos = quick.toSosLocation()
        assertEquals(0, sos.fixAgeSeconds)
        assertEquals(quick.latE7, sos.latE7)
        assertEquals(quick.lonE7, sos.lonE7)
        assertEquals(quick.accuracyMeters, sos.accuracyMeters)
        assertEquals(quick.approximate, sos.approximate)
        assertEquals(quick, sos.toQuickLocation())
    }

    @Test
    fun `nextPending picks earliest own pending deadline and ignores others`() {
        val feed = listOf(
            entry("me:late", mine = true, status = QuickSendStatus.PENDING, deadline = 9_000L),
            entry("me:sent", mine = true, status = QuickSendStatus.SENT, deadline = null),
            entry("me:notsent", mine = true, status = QuickSendStatus.NOT_SENT, deadline = null),
            entry("peer0001:1", mine = false, status = QuickSendStatus.SENT, deadline = null),
            entry("peer0001:2", mine = false, status = QuickSendStatus.PENDING, deadline = 1_000L),
            entry("me:early", mine = true, status = QuickSendStatus.PENDING, deadline = 6_000L)
        )
        assertEquals("me:early", nextPending(feed)?.key)
        assertNull(nextPending(emptyList()))
    }

    @Test
    fun `nextPending ignores pending entries without a deadline`() {
        val feed = listOf(
            entry("me:nodeadline", mine = true, status = QuickSendStatus.PENDING, deadline = null)
        )
        assertNull(nextPending(feed))
    }

    @Test
    fun `quick button visible on the mesh channel`() {
        assertTrue(quickButtonVisible(ChannelID.Mesh))
    }

    @Test
    fun `quick button hidden in a location channel`() {
        val location = ChannelID.Location(
            GeohashChannel(level = GeohashChannelLevel.entries.first(), geohash = "u4pru")
        )
        assertFalse(quickButtonVisible(location))
    }
}
