package com.jasiri.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SosLocationMathTest {

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val NAIROBI_LAT = -1.286389
        const val NAIROBI_LON = 36.817223
    }

    private fun at(accuracy: Int, lat: Int = -12863890, lon: Int = 368172230) =
        SosLocation(lat, lon, accuracy, 0, false)

    @Test
    fun `nairobi maps to E7`() {
        val loc = toSosLocation(NAIROBI_LAT, NAIROBI_LON, 5f, NOW, NOW)!!
        assertEquals(-12863890, loc.latE7)
        assertEquals(368172230, loc.lonE7)
    }

    @Test
    fun `out of range or NaN coordinates are rejected`() {
        assertNull(toSosLocation(90.0001, 0.0, 5f, NOW, NOW))
        assertNull(toSosLocation(0.0, -180.5, 5f, NOW, NOW))
        assertNull(toSosLocation(Double.NaN, 0.0, 5f, NOW, NOW))
        assertNull(toSosLocation(0.0, Double.NaN, 5f, NOW, NOW))
    }

    @Test
    fun `boundaries map without overflow`() {
        val northEast = toSosLocation(90.0, 180.0, 5f, NOW, NOW)
        val southWest = toSosLocation(-90.0, -180.0, 5f, NOW, NOW)
        assertNotNull(northEast)
        assertNotNull(southWest)
        assertEquals(900_000_000, northEast!!.latE7)
        assertEquals(1_800_000_000, northEast.lonE7)
        assertEquals(-900_000_000, southWest!!.latE7)
        assertEquals(-1_800_000_000, southWest.lonE7)
    }

    @Test
    fun `accuracy is rounded up and clamped`() {
        assertEquals(13, toSosLocation(0.0, 0.0, 12.2f, NOW, NOW)!!.accuracyMeters)
        assertEquals(65535, toSosLocation(0.0, 0.0, null, NOW, NOW)!!.accuracyMeters)
        assertEquals(65535, toSosLocation(0.0, 0.0, -1f, NOW, NOW)!!.accuracyMeters)
        assertEquals(65535, toSosLocation(0.0, 0.0, 1e6f, NOW, NOW)!!.accuracyMeters)
    }

    @Test
    fun `fix age is in whole seconds and clamped`() {
        assertEquals(90, toSosLocation(0.0, 0.0, 5f, NOW - 90_500, NOW)!!.fixAgeSeconds)
        assertEquals(0, toSosLocation(0.0, 0.0, 5f, NOW + 10_000, NOW)!!.fixAgeSeconds)
        val thirtyDays = 30L * 24 * 60 * 60 * 1000
        assertEquals(65535, toSosLocation(0.0, 0.0, 5f, NOW - thirtyDays, NOW)!!.fixAgeSeconds)
    }

    @Test
    fun `approximate passes through`() {
        assertTrue(toSosLocation(0.0, 0.0, 5f, NOW, NOW, approximate = true)!!.approximate)
        assertFalse(toSosLocation(0.0, 0.0, 5f, NOW, NOW, approximate = false)!!.approximate)
        assertFalse(toSosLocation(0.0, 0.0, 5f, NOW, NOW)!!.approximate)
    }

    @Test
    fun `isBetterFix prefers newer or more accurate`() {
        assertTrue(isBetterFix(at(50), NOW, null, null))
        assertTrue(isBetterFix(at(50), NOW + 10_000, at(10), NOW))
        assertTrue(isBetterFix(at(10), NOW, at(50), NOW))
        assertFalse(isBetterFix(at(50), NOW, at(10), NOW))
    }

    @Test
    fun `isBetterFix rejects an older candidate`() {
        assertFalse(isBetterFix(at(5), NOW - 10_000, at(50), NOW))
    }
}
