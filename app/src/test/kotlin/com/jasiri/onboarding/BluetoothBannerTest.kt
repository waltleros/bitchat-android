package com.jasiri.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothBannerTest {

    @Test
    fun `no adapter is unsupported whatever enabled says`() {
        assertEquals(BtBannerState.UNSUPPORTED, btBannerState(adapterPresent = false, enabled = false))
        assertEquals(BtBannerState.UNSUPPORTED, btBannerState(adapterPresent = false, enabled = true))
    }

    @Test
    fun `adapter present and enabled hides the banner`() {
        assertEquals(BtBannerState.HIDDEN, btBannerState(adapterPresent = true, enabled = true))
    }

    @Test
    fun `adapter present but disabled shows off`() {
        assertEquals(BtBannerState.OFF, btBannerState(adapterPresent = true, enabled = false))
    }
}
