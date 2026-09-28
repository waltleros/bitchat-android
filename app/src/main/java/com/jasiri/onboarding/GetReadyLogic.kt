package com.jasiri.onboarding

/** NOT_AVAILABLE = the device can't do it, or it isn't needed on this Android version. */
enum class ItemStatus { OK, TODO, NOT_AVAILABLE }

data class ReadinessSnapshot(
    /** OK or TODO only. */
    val permissions: ItemStatus,
    /** OK, TODO (off), NOT_AVAILABLE (no adapter). */
    val bluetooth: ItemStatus,
    /** OK, TODO (off), NOT_AVAILABLE. */
    val locationServices: ItemStatus,
    /** OK, TODO, NOT_AVAILABLE (not needed on this Android). */
    val backgroundLocation: ItemStatus,
    /** OK (unrestricted), TODO (optimized), NOT_AVAILABLE (unsupported). */
    val battery: ItemStatus
)

enum class PrimaryAction { ALLOW_PERMISSIONS, START }

private fun ReadinessSnapshot.items(): List<ItemStatus> =
    listOf(permissions, bluetooth, locationServices, backgroundLocation, battery)

/** TODO permissions -> ALLOW_PERMISSIONS, else START. */
fun primaryAction(s: ReadinessSnapshot): PrimaryAction =
    if (s.permissions == ItemStatus.TODO) PrimaryAction.ALLOW_PERMISSIONS else PrimaryAction.START

/** (done, total) over applicable items; NOT_AVAILABLE items are excluded from both. */
fun readyCount(s: ReadinessSnapshot): Pair<Int, Int> {
    val applicable = s.items().filter { it != ItemStatus.NOT_AVAILABLE }
    return applicable.count { it == ItemStatus.OK } to applicable.size
}

/** Background location can only be requested after permissions are OK. */
fun canRequestBackgroundLocation(s: ReadinessSnapshot): Boolean = s.permissions == ItemStatus.OK

/** True when nothing applicable is TODO (the screen may show "All set"). */
fun allReady(s: ReadinessSnapshot): Boolean = s.items().none { it == ItemStatus.TODO }
