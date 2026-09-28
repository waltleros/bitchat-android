package com.jasiri.quick

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickRuntimeTest {
    private val inbox = MutableSharedFlow<ReceivedQuick>(extraBufferCapacity = 64)

    // 1
    @Test
    fun `queue creates one pending entry and sends nothing before the undo window ends`() = runTest {
        val runtime = runtime()
        val recorder = Recorder()
        attach(runtime, ME, recorder)

        val key = runtime.queue(PRESET_WATER, LOCATION)

        assertNotNull(key)
        val entry = runtime.feed.value.single()
        assertEquals(key, entry.key)
        assertTrue(entry.mine)
        assertEquals(QuickSendStatus.PENDING, entry.status)
        assertEquals(testScheduler.currentTime + UNDO, entry.undoDeadlineMillis)

        advanceTimeBy(UNDO - 1)
        runCurrent()
        assertTrue(recorder.sent.isEmpty())
    }

    // 2
    @Test
    fun `after the undo window exactly one payload is sent and the entry is SENT`() = runTest {
        val runtime = runtime()
        val recorder = Recorder()
        attach(runtime, ME, recorder)

        runtime.queue(PRESET_WATER, LOCATION)
        advanceTimeBy(UNDO)
        runCurrent()

        assertEquals(1, recorder.sent.size)
        assertEquals(PRESET_WATER, recorder.sent[0].presetId)
        assertEquals(LOCATION, recorder.sent[0].location)
        assertEquals(UNDO / 1000, recorder.sent[0].timestampSeconds)
        val entry = runtime.feed.value.single()
        assertEquals(QuickSendStatus.SENT, entry.status)
        assertEquals(UNDO, entry.sentAtMillis)
        assertNull(entry.undoDeadlineMillis)
    }

    // 3
    @Test
    fun `undo within the window removes the entry and nothing is ever sent`() = runTest {
        val runtime = runtime()
        val recorder = Recorder()
        attach(runtime, ME, recorder)

        val key = runtime.queue(PRESET_WATER, null)!!
        advanceTimeBy(2_000)

        assertTrue(runtime.undo(key))
        assertTrue(runtime.feed.value.isEmpty())
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(recorder.sent.isEmpty())
    }

    // 4
    @Test
    fun `undo after sending returns false`() = runTest {
        val runtime = runtime()
        attach(runtime, ME, Recorder())

        val key = runtime.queue(PRESET_WATER, null)!!
        advanceTimeBy(UNDO)
        runCurrent()

        assertFalse(runtime.undo(key))
        assertEquals(QuickSendStatus.SENT, runtime.feed.value.single().status)
    }

    // 5
    @Test
    fun `failed send is NOT_SENT and retry resends the same msgId`() = runTest {
        val runtime = runtime()
        val recorder = Recorder(result = false)
        attach(runtime, ME, recorder)

        val key = runtime.queue(PRESET_WATER, null)!!
        advanceTimeBy(UNDO)
        runCurrent()
        assertEquals(QuickSendStatus.NOT_SENT, runtime.feed.value.single().status)

        recorder.result = true
        assertTrue(runtime.retry(key))

        assertEquals(QuickSendStatus.SENT, runtime.feed.value.single().status)
        assertEquals(2, recorder.sent.size)
        assertEquals(recorder.sent[0].msgId, recorder.sent[1].msgId)
        assertEquals(recorder.sent[0], recorder.sent[1])
    }

    // 6
    @Test
    fun `queue before any attach ends NOT_SENT`() = runTest {
        val runtime = runtime()

        runtime.queue(PRESET_WATER, null)
        advanceTimeBy(UNDO)
        runCurrent()

        assertEquals(QuickSendStatus.NOT_SENT, runtime.feed.value.single().status)
    }

    // 7
    @Test
    fun `unknown preset is rejected and the feed is unchanged`() = runTest {
        val runtime = runtime()
        attach(runtime, ME, Recorder())

        assertNull(runtime.queue(4242, null))
        assertTrue(runtime.feed.value.isEmpty())
    }

    // 8
    @Test
    fun `own rate limit allows five per minute and undo frees a slot`() = runTest {
        val runtime = runtime()
        attach(runtime, ME, Recorder())

        val keys = (1..5).map { runtime.queue(PRESET_WATER, null) }
        assertTrue(keys.all { it != null })
        assertNull(runtime.queue(PRESET_WATER, null))

        assertTrue(runtime.undo(keys[0]!!))
        assertNotNull(runtime.queue(PRESET_WATER, null))
        assertNull(runtime.queue(PRESET_WATER, null))

        advanceTimeBy(61_000)
        runCurrent()
        assertNotNull(runtime.queue(PRESET_WATER, null))
    }

    // 9
    @Test
    fun `received event from another peer appears once and counts as unread`() = runTest {
        val runtime = runtime()
        attach(runtime, ME, Recorder())

        emitQuick(OTHER, msgId = 11, presetId = PRESET_MEDICAL)

        val entry = runtime.feed.value.single()
        assertFalse(entry.mine)
        assertEquals(OTHER, entry.senderPeerID)
        assertEquals(QuickSendStatus.SENT, entry.status)
        assertEquals(PRESET_MEDICAL, entry.presetId)
        assertEquals("$OTHER:11", entry.key)
        assertEquals(1, runtime.unreadCount.value)

        runtime.markAllRead()
        assertEquals(0, runtime.unreadCount.value)
    }

    // 10
    @Test
    fun `own echoes are ignored and duplicates are added once`() = runTest {
        val runtime = runtime()
        attach(runtime, ME, Recorder())

        emitQuick(ME, msgId = 11)
        assertTrue(runtime.feed.value.isEmpty())

        emitQuick(OTHER, msgId = 12)
        emitQuick(OTHER, msgId = 12)
        assertEquals(1, runtime.feed.value.size)
        assertEquals(1, runtime.unreadCount.value)
    }

    // 11
    @Test
    fun `identity change clears the feed and cancels pending sends`() = runTest {
        val runtime = runtime()
        val recorder = Recorder()
        attach(runtime, ME, recorder)
        runtime.queue(PRESET_WATER, null)
        emitQuick(OTHER, msgId = 11)
        assertEquals(2, runtime.feed.value.size)

        attach(runtime, NEW_ME, recorder)

        assertTrue(runtime.feed.value.isEmpty())
        assertEquals(0, runtime.unreadCount.value)
        assertEquals(NEW_ME, runtime.myPeerID.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(recorder.sent.isEmpty())
    }

    // 12
    @Test
    fun `feed is capped and old entries are pruned`() = runTest {
        val capped = runtime(QuickConfig(feedMax = 3))
        attach(capped, ME, Recorder())
        for (msgId in 1L..5L) {
            emitQuick(OTHER, msgId)
            advanceTimeBy(1_000)
        }
        assertEquals(listOf("$OTHER:5", "$OTHER:4", "$OTHER:3"), capped.feed.value.map { it.key })

        val retained = runtime(QuickConfig(feedRetentionMillis = 60 * MINUTE))
        attach(retained, ME, Recorder())
        emitQuick(OTHER, msgId = 100)
        advanceTimeBy(61 * MINUTE)
        emitQuick(OTHER, msgId = 101)
        assertEquals(listOf("$OTHER:101"), retained.feed.value.map { it.key })
    }

    // Helpers

    private fun TestScope.runtime(config: QuickConfig = QuickConfig()): QuickRuntime =
        QuickRuntime(
            scope = backgroundScope,
            clockMillis = { testScheduler.currentTime },
            inbox = inbox,
            config = config
        )

    /** Attaches, then lets the inbox collector subscribe before anything is emitted. */
    private fun TestScope.attach(runtime: QuickRuntime, peerID: String, recorder: Recorder) {
        runtime.attach(peerID, recorder::send)
        runCurrent()
    }

    private fun TestScope.emitQuick(from: String, msgId: Long, presetId: Int = PRESET_WATER) {
        val now = testScheduler.currentTime
        val payload = QuickPayload(msgId, presetId, now / 1000, location = null)
        assertTrue(inbox.tryEmit(ReceivedQuick(from, payload, now)))
        runCurrent()
    }

    /** Fake transport: records every payload it is handed (decoded) and returns [result]. */
    private class Recorder(var result: Boolean = true) {
        val sent = mutableListOf<QuickPayload>()

        fun send(bytes: ByteArray): Boolean {
            sent += requireNotNull(QuickCodec.decode(bytes)) { "runtime sent an undecodable payload" }
            return result
        }
    }

    private companion object {
        const val ME = "aaaaaaaaaaaaaaaa"
        const val OTHER = "bbbbbbbbbbbbbbbb"
        const val NEW_ME = "cccccccccccccccc"
        const val MINUTE = 60_000L
        const val UNDO = 5_000L
        const val PRESET_WATER = 2
        const val PRESET_MEDICAL = 3

        val LOCATION = QuickLocation(
            latE7 = -12_863_890,
            lonE7 = 368_172_230,
            accuracyMeters = 13,
            approximate = false
        )
    }
}
