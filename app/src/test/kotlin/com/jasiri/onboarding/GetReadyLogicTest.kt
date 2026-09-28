package com.jasiri.onboarding

import com.jasiri.onboarding.ItemStatus.NOT_AVAILABLE
import com.jasiri.onboarding.ItemStatus.OK
import com.jasiri.onboarding.ItemStatus.TODO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GetReadyLogicTest {

    private fun snapshot(
        permissions: ItemStatus,
        bluetooth: ItemStatus,
        locationServices: ItemStatus,
        backgroundLocation: ItemStatus,
        battery: ItemStatus
    ) = ReadinessSnapshot(permissions, bluetooth, locationServices, backgroundLocation, battery)

    @Test
    fun `all five OK starts and is all ready`() {
        val s = snapshot(OK, OK, OK, OK, OK)
        assertEquals(PrimaryAction.START, primaryAction(s))
        assertEquals(5 to 5, readyCount(s))
        assertTrue(allReady(s))
    }

    @Test
    fun `permissions TODO asks for permissions and blocks background location`() {
        val s = snapshot(TODO, TODO, TODO, TODO, TODO)
        assertEquals(PrimaryAction.ALLOW_PERMISSIONS, primaryAction(s))
        assertFalse(canRequestBackgroundLocation(s))
    }

    @Test
    fun `permissions OK with the rest TODO can start`() {
        val s = snapshot(OK, TODO, TODO, TODO, TODO)
        assertEquals(PrimaryAction.START, primaryAction(s))
        assertEquals(1 to 5, readyCount(s))
        assertFalse(allReady(s))
        assertTrue(canRequestBackgroundLocation(s))
    }

    @Test
    fun `NOT_AVAILABLE items are excluded from the count`() {
        val s = snapshot(OK, NOT_AVAILABLE, OK, NOT_AVAILABLE, TODO)
        assertEquals(2 to 3, readyCount(s))
    }

    @Test
    fun `only permissions applicable and OK is all ready`() {
        val s = snapshot(OK, NOT_AVAILABLE, NOT_AVAILABLE, NOT_AVAILABLE, NOT_AVAILABLE)
        assertEquals(1 to 1, readyCount(s))
        assertTrue(allReady(s))
    }

    @Test
    fun `permissions TODO with everything else OK is not ready`() {
        val s = snapshot(TODO, OK, OK, OK, OK)
        assertFalse(allReady(s))
        assertEquals(PrimaryAction.ALLOW_PERMISSIONS, primaryAction(s))
    }
}
