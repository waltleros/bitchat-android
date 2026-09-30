package com.jasiri.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.sqrt

class SosGeoTest {

    /** Metres per degree of latitude (and of longitude at the equator) for R = 6_371_000 m. */
    private val metersPerDegree = 6_371_000.0 * Math.PI / 180.0

    private fun loc(lat: Double, lon: Double, acc: Int = 5) = SosLocation(
        latE7 = (lat * 1e7).roundToInt(),
        lonE7 = (lon * 1e7).roundToInt(),
        accuracyMeters = acc,
        fixAgeSeconds = 0,
        approximate = false
    )

    private fun degrees(meters: Double): Double = meters / metersPerDegree

    @Test
    fun `distance from a point to itself is zero`() {
        val p = loc(-1.28638, 36.81722)
        assertEquals(0.0, distanceMeters(p, p), 0.0)
    }

    @Test
    fun `one degree of latitude at the equator`() {
        assertEquals(111_195.0, distanceMeters(loc(0.0, 0.0), loc(1.0, 0.0)), 100.0)
    }

    @Test
    fun `Nairobi CBD to JKIA is about 12_8 km`() {
        val cbd = loc(-1.28638, 36.81722)
        val jkia = loc(-1.31923, 36.92780)
        assertEquals(12_800.0, distanceMeters(cbd, jkia), 300.0)
    }

    @Test
    fun `bearings to the four cardinal points`() {
        val origin = loc(0.0, 0.0)
        assertEquals(0.0, bearingDegrees(origin, loc(1.0, 0.0)), 0.5)
        assertEquals(90.0, bearingDegrees(origin, loc(0.0, 1.0)), 0.5)
        assertEquals(180.0, bearingDegrees(origin, loc(-1.0, 0.0)), 0.5)
        assertEquals(270.0, bearingDegrees(origin, loc(0.0, -1.0)), 0.5)
    }

    @Test
    fun `compass8 sector boundaries`() {
        assertEquals(Compass8.N, compass8(0.0))
        assertEquals(Compass8.N, compass8(22.4))
        assertEquals(Compass8.NE, compass8(22.5))
        assertEquals(Compass8.E, compass8(90.0))
        assertEquals(Compass8.S, compass8(180.0))
        assertEquals(Compass8.W, compass8(270.0))
        assertEquals(Compass8.NW, compass8(337.4))
        assertEquals(Compass8.N, compass8(337.5))
        assertEquals(Compass8.N, compass8(359.9))
    }

    @Test
    fun `formatDistance rounds and switches units`() {
        assertEquals("10 m", formatDistance(3.0))
        assertEquals("350 m", formatDistance(347.0))
        assertEquals("1.0 km", formatDistance(999.0))
        assertEquals("1.2 km", formatDistance(1234.0))
        assertEquals("9.9 km", formatDistance(9_949.0))
        assertEquals("14 km", formatDistance(14_400.0))
    }

    @Test
    fun `within combined accuracy is very close`() {
        val me = loc(0.0, 0.0, acc = 20)
        val them = loc(degrees(30.0), 0.0, acc = 20)
        assertEquals(SosDistance.VeryClose(40), sosDistance(me, them))
    }

    @Test
    fun `about 350 m north-east is away with direction`() {
        val leg = degrees(350.0 / sqrt(2.0))
        val d = sosDistance(loc(0.0, 0.0), loc(leg, leg))
        assertTrue("expected Away, was $d", d is SosDistance.Away)
        d as SosDistance.Away
        assertTrue("unexpected text ${d.text}", d.text in setOf("340 m", "350 m", "360 m"))
        assertEquals(Compass8.NE, d.direction)
    }

    @Test
    fun `unknown accuracy counts as 100 m`() {
        val me = loc(0.0, 0.0, acc = SOS_ACCURACY_UNKNOWN)
        assertEquals(SosDistance.VeryClose(110), sosDistance(me, loc(degrees(100.0), 0.0, acc = 10)))
        assertTrue(sosDistance(me, loc(degrees(150.0), 0.0, acc = 10)) is SosDistance.Away)
    }

    @Test
    fun `uncertainty is at least 20 m`() {
        val me = loc(0.0, 0.0, acc = 1)
        val them = loc(degrees(15.0), 0.0, acc = 1)
        assertEquals(SosDistance.VeryClose(20), sosDistance(me, them))
    }
}
