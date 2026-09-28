package com.jasiri.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickPayloadTest {

    private val golden1 = QuickPayload(
        msgId = 0x0102030405060708L,
        presetId = 2,
        timestampSeconds = 0x6AB13B80L,
        location = null
    )
    private val golden1Hex = "0101010203040506070800026ab13b8000"

    private val golden2 = golden1.copy(
        location = QuickLocation(
            latE7 = -12863890,
            lonE7 = 368172230,
            accuracyMeters = 13,
            approximate = false
        )
    )
    private val golden2Hex = "0101010203040506070800026ab13b8001ff3bb66e15f1dcc6000d"

    @Test
    fun `golden vector without location encodes exactly`() {
        val bytes = QuickCodec.encode(golden1)
        assertEquals(QuickCodec.LEN_NO_LOCATION, bytes.size)
        assertEquals(golden1Hex, bytes.toHex())
    }

    @Test
    fun `golden vector with location encodes exactly`() {
        val bytes = QuickCodec.encode(golden2)
        assertEquals(QuickCodec.LEN_WITH_LOCATION, bytes.size)
        assertEquals(golden2Hex, bytes.toHex())
    }

    @Test
    fun `golden vectors decode back to equal objects`() {
        assertEquals(golden1, QuickCodec.decode(golden1Hex.hexToBytes()))
        assertEquals(golden2, QuickCodec.decode(golden2Hex.hexToBytes()))
    }

    @Test
    fun `approximate location sets flags 0x03 and round-trips`() {
        val payload = golden2.copy(location = golden2.location!!.copy(approximate = true))
        val bytes = QuickCodec.encode(payload)
        assertEquals(0x03, bytes[16].toInt() and 0xFF)
        assertEquals(payload, QuickCodec.decode(bytes))
    }

    @Test
    fun `encode rejects invalid header fields`() {
        assertEncodeRejects(golden1.copy(msgId = 0L))
        assertEncodeRejects(golden1.copy(presetId = 0))
        assertEncodeRejects(golden1.copy(presetId = 65536))
        assertEncodeRejects(golden1.copy(timestampSeconds = -1L))
    }

    @Test
    fun `encode clamps large accuracy and rejects negative accuracy`() {
        val large = golden2.copy(location = golden2.location!!.copy(accuracyMeters = 70000))
        val decoded = QuickCodec.decode(QuickCodec.encode(large))
        assertEquals(65535, decoded!!.location!!.accuracyMeters)

        assertEncodeRejects(golden2.copy(location = golden2.location!!.copy(accuracyMeters = -1)))
    }

    @Test
    fun `decode rejects wrong lengths`() {
        for (length in listOf(0, 16, 18, 26, 28)) {
            assertNull("length $length", QuickCodec.decode(ByteArray(length) { 1 }))
        }
    }

    @Test
    fun `decode rejects unknown version and kind`() {
        assertNull(QuickCodec.decode(golden1With(offset = 0, value = 2)))
        assertNull(QuickCodec.decode(golden1With(offset = 1, value = 2)))
    }

    @Test
    fun `decode rejects hasLocation flag that does not match length`() {
        assertNull(QuickCodec.decode(golden1With(offset = 16, value = 0x01)))
        val withLocationButNoFlag = golden2Hex.hexToBytes().also { it[16] = 0x00 }
        assertNull(QuickCodec.decode(withLocationButNoFlag))
    }

    @Test
    fun `decode accepts preset ids not in the catalog`() {
        val unknown = golden1.copy(presetId = 4242)
        assertNull(QuickCatalog.byId(4242))
        val decoded = QuickCodec.decode(QuickCodec.encode(unknown))
        assertEquals(unknown, decoded)
        assertEquals(4242, decoded!!.presetId)
    }

    private fun assertEncodeRejects(payload: QuickPayload) {
        try {
            QuickCodec.encode(payload)
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException for $payload")
    }

    private fun golden1With(offset: Int, value: Int): ByteArray =
        golden1Hex.hexToBytes().also { it[offset] = value.toByte() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
