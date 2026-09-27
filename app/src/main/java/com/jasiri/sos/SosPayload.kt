package com.jasiri.sos

import java.nio.ByteBuffer

/**
 * JASIRI SOS payload, format v1. See docs/JASIRI_SOS_V1.md for the byte layout.
 *
 * The payload carries no signature: it travels inside a signed mesh packet.
 */
enum class SosKind(val code: Int) {
    SOS(1),
    ACK(2),
    CLAIM(3),
    CANCEL(4),
    RESOLVE(5)
}

enum class SosCategory(val code: Int) {
    GENERAL(0),
    MEDICAL(1),
    TRAPPED(2),
    FIRE(3),
    VIOLENCE(4),
    DETAINED(5),
    MISSING_PERSON(6),
    OTHER(255)
}

data class SosLocation(
    val latE7: Int,
    val lonE7: Int,
    val accuracyMeters: Int,
    val fixAgeSeconds: Int,
    val approximate: Boolean
)

/** A null [batteryPercent] is sent as 255 (unknown); a null [peopleCount] is sent as 0 (unknown). */
data class SosBody(
    val category: SosCategory,
    val severity: Int,
    val location: SosLocation?,
    val batteryPercent: Int?,
    val peopleCount: Int?
)

/** [body] is present if and only if [kind] is [SosKind.SOS]. [sosId] is an unsigned 64-bit value. */
data class SosPayload(
    val kind: SosKind,
    val sosId: Long,
    val seq: Int,
    val timestampSeconds: Long,
    val body: SosBody?
)

object SosCodec {
    const val VERSION = 1
    const val HEADER_SIZE = 16
    const val SOS_SIZE = 33

    private const val MAX_U8 = 0xFF
    private const val MAX_U16 = 0xFFFF
    private const val MAX_U32 = 0xFFFF_FFFFL
    private const val MAX_LAT_E7 = 900_000_000
    private const val MAX_LON_E7 = 1_800_000_000
    private const val BATTERY_UNKNOWN = 255
    private const val PEOPLE_UNKNOWN = 0
    private const val FLAG_HAS_LOCATION = 0x01
    private const val FLAG_LOCATION_APPROXIMATE = 0x02

    private val LAT_RANGE = -MAX_LAT_E7..MAX_LAT_E7
    private val LON_RANGE = -MAX_LON_E7..MAX_LON_E7

    /** @throws IllegalArgumentException if [p] cannot be represented in format v1. */
    fun encode(p: SosPayload): ByteArray {
        require((p.kind == SosKind.SOS) == (p.body != null)) {
            "body must be present if and only if kind is SOS (kind=${p.kind})"
        }
        require(p.seq in 0..MAX_U16) { "seq out of range: ${p.seq}" }
        require(p.timestampSeconds in 0..MAX_U32) { "timestamp out of range: ${p.timestampSeconds}" }

        val body = p.body
        val buffer = ByteBuffer.allocate(if (body != null) SOS_SIZE else HEADER_SIZE)
        buffer.put(VERSION.toByte())
        buffer.put(p.kind.code.toByte())
        buffer.putLong(p.sosId)
        buffer.putShort(p.seq.toShort())
        buffer.putInt(p.timestampSeconds.toInt())
        if (body != null) {
            writeBody(buffer, body)
        }
        return buffer.array()
    }

    /** Returns null for any input that is not a valid v1 payload; never throws. */
    fun decode(bytes: ByteArray): SosPayload? {
        if (bytes.size < HEADER_SIZE) return null
        if (u8(bytes, 0) != VERSION) return null
        val kind = kindOf(u8(bytes, 1)) ?: return null
        val sosId = i64(bytes, 2)
        val seq = u16(bytes, 10)
        val timestamp = u32(bytes, 12)
        if (kind != SosKind.SOS) {
            return SosPayload(kind, sosId, seq, timestamp, body = null)
        }

        if (bytes.size < SOS_SIZE) return null
        val category = categoryOf(u8(bytes, 16))
        val severity = u8(bytes, 17)
        if (severity !in 1..3) return null
        val flags = u8(bytes, 18)
        val location = if ((flags and FLAG_HAS_LOCATION) != 0) {
            val latE7 = i32(bytes, 19)
            val lonE7 = i32(bytes, 23)
            if (latE7 !in LAT_RANGE || lonE7 !in LON_RANGE) return null
            SosLocation(
                latE7 = latE7,
                lonE7 = lonE7,
                accuracyMeters = u16(bytes, 27),
                fixAgeSeconds = u16(bytes, 29),
                approximate = (flags and FLAG_LOCATION_APPROXIMATE) != 0
            )
        } else {
            null
        }
        val battery = u8(bytes, 31)
        if (battery !in 0..100 && battery != BATTERY_UNKNOWN) return null
        val people = u8(bytes, 32)

        val body = SosBody(
            category = category,
            severity = severity,
            location = location,
            batteryPercent = battery.takeIf { it != BATTERY_UNKNOWN },
            peopleCount = people.takeIf { it != PEOPLE_UNKNOWN }
        )
        return SosPayload(kind, sosId, seq, timestamp, body)
    }

    private fun writeBody(buffer: ByteBuffer, body: SosBody) {
        require(body.severity in 1..3) { "severity out of range: ${body.severity}" }
        require(body.batteryPercent == null || body.batteryPercent in 0..100) {
            "batteryPercent out of range: ${body.batteryPercent}"
        }
        require(body.peopleCount == null || body.peopleCount in 1..MAX_U8) {
            "peopleCount out of range: ${body.peopleCount}"
        }
        val location = body.location
        if (location != null) {
            require(location.latE7 in LAT_RANGE) { "latE7 out of range: ${location.latE7}" }
            require(location.lonE7 in LON_RANGE) { "lonE7 out of range: ${location.lonE7}" }
            require(location.accuracyMeters >= 0) { "accuracyMeters negative: ${location.accuracyMeters}" }
            require(location.fixAgeSeconds >= 0) { "fixAgeSeconds negative: ${location.fixAgeSeconds}" }
        }

        var flags = 0
        if (location != null) {
            flags = flags or FLAG_HAS_LOCATION
            if (location.approximate) flags = flags or FLAG_LOCATION_APPROXIMATE
        }

        buffer.put(body.category.code.toByte())
        buffer.put(body.severity.toByte())
        buffer.put(flags.toByte())
        buffer.putInt(location?.latE7 ?: 0)
        buffer.putInt(location?.lonE7 ?: 0)
        buffer.putShort((location?.accuracyMeters ?: 0).coerceAtMost(MAX_U16).toShort())
        buffer.putShort((location?.fixAgeSeconds ?: 0).coerceAtMost(MAX_U16).toShort())
        buffer.put((body.batteryPercent ?: BATTERY_UNKNOWN).toByte())
        buffer.put((body.peopleCount ?: PEOPLE_UNKNOWN).toByte())
    }

    private fun kindOf(code: Int): SosKind? = SosKind.entries.firstOrNull { it.code == code }

    /** Unknown codes map to OTHER so newer senders stay readable. */
    private fun categoryOf(code: Int): SosCategory =
        SosCategory.entries.firstOrNull { it.code == code } ?: SosCategory.OTHER

    private fun u8(b: ByteArray, offset: Int): Int = b[offset].toInt() and 0xFF

    private fun u16(b: ByteArray, offset: Int): Int = (u8(b, offset) shl 8) or u8(b, offset + 1)

    private fun i32(b: ByteArray, offset: Int): Int =
        (u8(b, offset) shl 24) or (u8(b, offset + 1) shl 16) or (u8(b, offset + 2) shl 8) or u8(b, offset + 3)

    private fun u32(b: ByteArray, offset: Int): Long = i32(b, offset).toLong() and MAX_U32

    private fun i64(b: ByteArray, offset: Int): Long =
        (i32(b, offset).toLong() shl 32) or (i32(b, offset + 4).toLong() and MAX_U32)
}
