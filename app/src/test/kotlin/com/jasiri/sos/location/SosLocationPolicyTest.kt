package com.jasiri.sos.location

import com.jasiri.sos.OwnSosState
import com.jasiri.sos.OwnSosStatus
import com.jasiri.sos.SosLocation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SosLocationPolicyTest {

    private val active = OwnSosStatus.IDLE.copy(state = OwnSosState.ACTIVE, sosId = 42L)

    private fun loc(accuracy: Int) = SosLocation(
        latE7 = -12_864_000,
        lonE7 = 368_172_000,
        accuracyMeters = accuracy,
        fixAgeSeconds = 0,
        approximate = false
    )

    @Before
    fun resetOptOut() {
        SosLocationOptOut.clear()
    }

    @Test
    fun `refreshes when active, allowed, permitted and enabled`() {
        assertTrue(shouldRefreshLocation(active, optedOut = false, hasPermission = true, locationEnabled = true))
    }

    @Test
    fun `any single failing condition stops the refresh`() {
        assertFalse(shouldRefreshLocation(OwnSosStatus.IDLE, false, true, true))
        assertFalse(shouldRefreshLocation(active.copy(state = OwnSosState.CANCELLING), false, true, true))
        assertFalse(shouldRefreshLocation(active.copy(sosId = null), false, true, true))
        assertFalse(shouldRefreshLocation(active, optedOut = true, hasPermission = true, locationEnabled = true))
        assertFalse(shouldRefreshLocation(active, optedOut = false, hasPermission = false, locationEnabled = true))
        assertFalse(shouldRefreshLocation(active, optedOut = false, hasPermission = true, locationEnabled = false))
    }

    @Test
    fun `a failed fix never replaces or clears the location`() {
        assertFalse(shouldApplyFix(null, 1_000L, loc(10), 1_000L))
        assertFalse(shouldApplyFix(null, 1_000L, null, null))
    }

    @Test
    fun `first fix applies when the SOS has no location`() {
        assertTrue(shouldApplyFix(loc(50), 1_000L, null, null))
    }

    @Test
    fun `newer fix wins even if less accurate`() {
        assertTrue(shouldApplyFix(loc(100), 10_001L, loc(5), 1_000L))
    }

    @Test
    fun `same age fix wins only when more accurate`() {
        assertTrue(shouldApplyFix(loc(5), 4_000L, loc(20), 1_000L))
        assertFalse(shouldApplyFix(loc(30), 4_000L, loc(20), 1_000L))
    }

    @Test
    fun `unknown current fix time lets the fresh fix win`() {
        assertTrue(shouldApplyFix(loc(100), 1_000L, loc(5), null))
    }

    @Test
    fun `opt-out set adds and removes`() {
        SosLocationOptOut.add(7L)
        assertTrue(SosLocationOptOut.contains(7L))
        SosLocationOptOut.remove(7L)
        assertFalse(SosLocationOptOut.contains(7L))
    }
}
