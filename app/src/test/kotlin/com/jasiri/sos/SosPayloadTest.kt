package com.jasiri.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

class SosPayloadTest {

    private val goldenSos = SosPayload(
        kind = SosKind.SOS,
        sosId = 0x0102030405060708L,
        seq = 1,
        timestampSeconds = 1_790_000_000L,
        body = SosBody(
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
    )
    private val goldenSosHex = "0101010203040506070800016ab13b80010201ff3ad75815f29378000f001e4001"

    private val goldenClaim = SosPayload(
        kind = SosKind.CLAIM,
        sosId = 0x0102030405060708L,
        seq = 2,
        timestampSeconds = 1_790_000_060L,
        body = null
    )
    private val goldenClaimHex = "0103010203040506070800026ab13bbc"

    @Test
    fun `golden SOS vector encodes and decodes exactly`() {
        val encoded = SosCodec.encode(goldenSos)
        assertEquals(SosCodec.SOS_SIZE, encoded.size)
        assertEquals(goldenSosHex, encoded.toHex())
        assertEquals(goldenSos, SosCodec.decode(goldenSosHex.hexToBytes()))
    }

    @Test
    fun `golden CLAIM vector encodes and decodes exactly`() {
        val encoded = SosCodec.encode(goldenClaim)
        assertEquals(SosCodec.HEADER_SIZE, encoded.size)
        assertEquals(goldenClaimHex, encoded.toHex())
        assertEquals(goldenClaim, SosCodec.decode(goldenClaimHex.hexToBytes()))
    }

    @Test
    fun `every kind round-trips`() {
        SosKind.entries.forEach { kind ->
            val payload = if (kind == SosKind.SOS) goldenSos else goldenClaim.copy(kind = kind)
            assertRoundTrip(payload)
        }
    }

    @Test
    fun `every category round-trips`() {
        SosCategory.entries.forEach { category ->
            assertRoundTrip(goldenSos.withBody { copy(category = category) })
        }
    }

    @Test
    fun `boundary values round-trip`() {
        listOf(
            -900_000_000 to -1_800_000_000,
            900_000_000 to 1_800_000_000,
            -900_000_000 to 1_800_000_000,
            900_000_000 to -1_800_000_000
        ).forEach { (lat, lon) ->
            assertRoundTrip(
                goldenSos.withBody {
                    copy(location = location!!.copy(latE7 = lat, lonE7 = lon, approximate = true))
                }
            )
        }
        listOf(0, 65_535).forEach { seq ->
            assertRoundTrip(goldenSos.copy(seq = seq))
            assertRoundTrip(goldenClaim.copy(seq = seq))
        }
        listOf(0L, 4_294_967_295L).forEach { timestamp ->
            assertRoundTrip(goldenSos.copy(timestampSeconds = timestamp))
            assertRoundTrip(goldenClaim.copy(timestampSeconds = timestamp))
        }
        listOf(Long.MIN_VALUE, -1L, Long.MAX_VALUE).forEach { sosId ->
            assertRoundTrip(goldenSos.copy(sosId = sosId))
            assertRoundTrip(goldenClaim.copy(sosId = sosId))
        }
        assertRoundTrip(
            goldenSos.withBody {
                copy(
                    location = location!!.copy(accuracyMeters = 65_535, fixAgeSeconds = 65_535),
                    batteryPercent = 100,
                    peopleCount = 255
                )
            }
        )
        assertRoundTrip(goldenSos.withBody { copy(batteryPercent = 0, severity = 1) })
        assertRoundTrip(goldenSos.withBody { copy(severity = 3) })
    }

    @Test
    fun `sosId with high bit set encodes as unsigned big-endian`() {
        val encoded = SosCodec.encode(goldenClaim.copy(sosId = Long.MIN_VALUE))
        assertEquals("8000000000000000", encoded.copyOfRange(2, 10).toHex())
    }

    @Test
    fun `SOS without location and with unknown battery and people round-trips`() {
        val payload = goldenSos.withBody { copy(location = null, batteryPercent = null, peopleCount = null) }
        val encoded = SosCodec.encode(payload)

        assertEquals("00", encoded.copyOfRange(18, 19).toHex())
        assertEquals("0000000000000000", encoded.copyOfRange(19, 27).toHex())
        assertEquals("ff", encoded.copyOfRange(31, 32).toHex())
        assertEquals("00", encoded.copyOfRange(32, 33).toHex())
        assertEquals(payload, SosCodec.decode(encoded))
    }

    @Test
    fun `accuracy and fix age above 65535 are clamped`() {
        val payload = goldenSos.withBody {
            copy(location = location!!.copy(accuracyMeters = 100_000, fixAgeSeconds = 100_000))
        }
        val encoded = SosCodec.encode(payload)

        assertEquals("ffffffff", encoded.copyOfRange(27, 31).toHex())
        val location = SosCodec.decode(encoded)!!.body!!.location!!
        assertEquals(65_535, location.accuracyMeters)
        assertEquals(65_535, location.fixAgeSeconds)
    }

    @Test
    fun `every truncated prefix of the golden SOS is rejected`() {
        val bytes = goldenSosHex.hexToBytes()
        for (length in 0 until SosCodec.SOS_SIZE) {
            assertNull("length $length", SosCodec.decode(bytes.copyOf(length)))
        }
    }

    @Test
    fun `invalid header and body fields are rejected`() {
        assertNull("version 2", SosCodec.decode(goldenSosWith(0, 2)))
        assertNull("kind 0", SosCodec.decode(goldenSosWith(1, 0)))
        assertNull("kind 6", SosCodec.decode(goldenSosWith(1, 6)))
        assertNull("severity 0", SosCodec.decode(goldenSosWith(17, 0)))
        assertNull("severity 4", SosCodec.decode(goldenSosWith(17, 4)))
        assertNull("battery 101", SosCodec.decode(goldenSosWith(31, 101)))

        val latTooLarge = goldenSosHex.hexToBytes()
        // 900000001 = 0x35A4E901
        "35a4e901".hexToBytes().copyInto(latTooLarge, destinationOffset = 19)
        assertNull("lat 900000001", SosCodec.decode(latTooLarge))
    }

    @Test
    fun `out of range coordinates are ignored when hasLocation is clear`() {
        val bytes = goldenSosHex.hexToBytes()
        bytes[18] = 0x02 // approximate bit set, hasLocation clear
        "7fffffff7fffffff".hexToBytes().copyInto(bytes, destinationOffset = 19)

        val decoded = SosCodec.decode(bytes)
        assertEquals(goldenSos.withBody { copy(location = null) }, decoded)
    }

    @Test
    fun `reserved flag bits and unknown categories are tolerated`() {
        val bytes = goldenSosHex.hexToBytes()
        bytes[16] = 42
        bytes[18] = 0xFD.toByte() // every bit except locationApproximate

        val decoded = SosCodec.decode(bytes)
        assertEquals(goldenSos.withBody { copy(category = SosCategory.OTHER) }, decoded)
    }

    @Test
    fun `trailing bytes are ignored`() {
        val sosBytes = goldenSosHex.hexToBytes() + byteArrayOf(1, 2, 3, 4, 5)
        assertEquals(goldenSos, SosCodec.decode(sosBytes))

        val claimBytes = goldenClaimHex.hexToBytes() + byteArrayOf(1, 2, 3, 4, 5)
        assertEquals(goldenClaim, SosCodec.decode(claimBytes))
    }

    @Test
    fun `encode rejects invalid payloads`() {
        assertEncodeRejects(goldenSos.copy(body = null))
        assertEncodeRejects(goldenClaim.copy(kind = SosKind.ACK, body = goldenSos.body))
        assertEncodeRejects(goldenSos.withBody { copy(severity = 4) })
        assertEncodeRejects(goldenSos.withBody { copy(severity = 0) })
        assertEncodeRejects(goldenSos.copy(seq = -1))
        assertEncodeRejects(goldenSos.copy(seq = 65_536))
        assertEncodeRejects(goldenSos.copy(timestampSeconds = -1L))
        assertEncodeRejects(goldenSos.copy(timestampSeconds = 4_294_967_296L))
        assertEncodeRejects(goldenSos.withBody { copy(location = location!!.copy(latE7 = 900_000_001)) })
        assertEncodeRejects(goldenSos.withBody { copy(location = location!!.copy(lonE7 = -1_800_000_001)) })
        assertEncodeRejects(goldenSos.withBody { copy(batteryPercent = 101) })
        assertEncodeRejects(goldenSos.withBody { copy(batteryPercent = -1) })
    }

    @Test
    fun `decode never throws on random input`() {
        val random = Random(0x4A415349L)
        repeat(5_000) {
            val bytes = ByteArray(random.nextInt(65)).also(random::nextBytes)
            val decoded = SosCodec.decode(bytes)
            if (decoded != null) {
                assertEquals(decoded, SosCodec.decode(SosCodec.encode(decoded)))
            }
        }
    }

    private fun assertRoundTrip(payload: SosPayload) {
        assertEquals(payload, SosCodec.decode(SosCodec.encode(payload)))
    }

    private fun assertEncodeRejects(payload: SosPayload) {
        try {
            SosCodec.encode(payload)
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException for $payload")
    }

    private fun goldenSosWith(offset: Int, value: Int): ByteArray =
        goldenSosHex.hexToBytes().also { it[offset] = value.toByte() }

    private fun SosPayload.withBody(change: SosBody.() -> SosBody): SosPayload =
        copy(body = body!!.change())

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
