package com.jasiri.quick

import java.nio.ByteBuffer

/**
 * JASIRI quick message payload, format v1. See docs/JASIRI_QUICK_V1.md for the byte layout.
 *
 * The payload carries a preset code, not text; receivers render the label from [QuickCatalog].
 * It carries no signature: it will travel inside a signed mesh packet.
 */
data class QuickLocation(
    val latE7: Int,
    val lonE7: Int,
    /** 65535 = unknown. */
    val accuracyMeters: Int,
    val approximate: Boolean
)

/** [msgId] is an unsigned 64-bit value; [presetId] is not checked against the catalog. */
data class QuickPayload(
    val msgId: Long,
    val presetId: Int,
    val timestampSeconds: Long,
    val location: QuickLocation?
)

object QuickCodec {
    const val VERSION = 1
    const val KIND_QUICK = 1
    const val LEN_NO_LOCATION = 17
    const val LEN_WITH_LOCATION = 27

    private const val MAX_U16 = 0xFFFF
    private const val MAX_U32 = 0xFFFF_FFFFL
    private const val MAX_LAT_E7 = 900_000_000
    private const val MAX_LON_E7 = 1_800_000_000
    private const val FLAG_HAS_LOCATION = 0x01
    private const val FLAG_LOCATION_APPROXIMATE = 0x02

    private val LAT_RANGE = -MAX_LAT_E7..MAX_LAT_E7
    private val LON_RANGE = -MAX_LON_E7..MAX_LON_E7
    private val PRESET_RANGE = 1..MAX_U16

    /** @throws IllegalArgumentException on msgId 0, presetId !in 1..65535, timestamp !in 0..4294967295,
     *  lat/lon out of range, accuracy < 0 (accuracy > 65535 is clamped to 65535). */
    fun encode(p: QuickPayload): ByteArray {
        require(p.msgId != 0L) { "msgId must be non-zero" }
        require(p.presetId in PRESET_RANGE) { "presetId out of range: ${p.presetId}" }
        require(p.timestampSeconds in 0..MAX_U32) { "timestamp out of range: ${p.timestampSeconds}" }
        val location = p.location
        if (location != null) {
            require(location.latE7 in LAT_RANGE) { "latE7 out of range: ${location.latE7}" }
            require(location.lonE7 in LON_RANGE) { "lonE7 out of range: ${location.lonE7}" }
            require(location.accuracyMeters >= 0) { "accuracyMeters negative: ${location.accuracyMeters}" }
        }

        var flags = 0
        if (location != null) {
            flags = flags or FLAG_HAS_LOCATION
            if (location.approximate) flags = flags or FLAG_LOCATION_APPROXIMATE
        }

        val buffer = ByteBuffer.allocate(if (location != null) LEN_WITH_LOCATION else LEN_NO_LOCATION)
        buffer.put(VERSION.toByte())
        buffer.put(KIND_QUICK.toByte())
        buffer.putLong(p.msgId)
        buffer.putShort(p.presetId.toShort())
        buffer.putInt(p.timestampSeconds.toInt())
        buffer.put(flags.toByte())
        if (location != null) {
            buffer.putInt(location.latE7)
            buffer.putInt(location.lonE7)
            buffer.putShort(location.accuracyMeters.coerceAtMost(MAX_U16).toShort())
        }
        return buffer.array()
    }

    /** Never throws; returns null for anything invalid. */
    fun decode(bytes: ByteArray): QuickPayload? {
        if (bytes.size != LEN_NO_LOCATION && bytes.size != LEN_WITH_LOCATION) return null
        if (u8(bytes, 0) != VERSION) return null
        if (u8(bytes, 1) != KIND_QUICK) return null
        val msgId = i64(bytes, 2)
        if (msgId == 0L) return null
        val presetId = u16(bytes, 10)
        if (presetId == 0) return null
        val timestamp = u32(bytes, 12)

        val flags = u8(bytes, 16)
        val hasLocation = (flags and FLAG_HAS_LOCATION) != 0
        if (hasLocation != (bytes.size == LEN_WITH_LOCATION)) return null
        val location = if (hasLocation) {
            val latE7 = i32(bytes, 17)
            val lonE7 = i32(bytes, 21)
            if (latE7 !in LAT_RANGE || lonE7 !in LON_RANGE) return null
            QuickLocation(
                latE7 = latE7,
                lonE7 = lonE7,
                accuracyMeters = u16(bytes, 25),
                approximate = (flags and FLAG_LOCATION_APPROXIMATE) != 0
            )
        } else {
            null
        }
        return QuickPayload(msgId, presetId, timestamp, location)
    }

    private fun u8(b: ByteArray, offset: Int): Int = b[offset].toInt() and 0xFF

    private fun u16(b: ByteArray, offset: Int): Int = (u8(b, offset) shl 8) or u8(b, offset + 1)

    private fun i32(b: ByteArray, offset: Int): Int =
        (u8(b, offset) shl 24) or (u8(b, offset + 1) shl 16) or (u8(b, offset + 2) shl 8) or u8(b, offset + 3)

    private fun u32(b: ByteArray, offset: Int): Long = i32(b, offset).toLong() and MAX_U32

    private fun i64(b: ByteArray, offset: Int): Long =
        (i32(b, offset).toLong() shl 32) or (i32(b, offset + 4).toLong() and MAX_U32)
}
