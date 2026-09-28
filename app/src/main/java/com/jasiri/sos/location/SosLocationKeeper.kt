package com.jasiri.sos.location

import android.content.Context
import com.jasiri.sos.JasiriSos
import com.jasiri.sos.OwnSosState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Refreshes the location of this phone's own ACTIVE SOS every [SOS_LOCATION_REFRESH_MILLIS],
 * whether or not the SOS page is open. Same privacy rules as the page: nothing while sharing is
 * off for that SOS or without permission, and it never asks for permission.
 */
object SosLocationKeeper {
    private val started = AtomicBoolean(false)

    /** Main: [SosLocationSource.requestFresh] registers LocationListeners on the main looper. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Main thread only. Fix time of the location this keeper last applied, per sosId. */
    private val lastAppliedFixTime = HashMap<Long, Long>()

    fun start(context: Context) {
        try {
            if (!started.compareAndSet(false, true)) return
            val appContext = context.applicationContext
            scope.launch {
                try {
                    val locationSource = SosLocationSource(appContext)
                    JasiriSos.runtime.own.status
                        .map { status -> status.sosId.takeIf { status.state == OwnSosState.ACTIVE } }
                        .distinctUntilChanged()
                        .collectLatest { sosId ->
                            if (sosId == null) {
                                try {
                                    locationSource.cancel()
                                } catch (_: Exception) {
                                }
                                lastAppliedFixTime.clear()
                                return@collectLatest
                            }
                            while (currentCoroutineContext().isActive) {
                                delay(SOS_LOCATION_REFRESH_MILLIS)
                                refreshOnce(locationSource, sosId)
                            }
                        }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun refreshOnce(source: SosLocationSource, sosId: Long) {
        try {
            val own = JasiriSos.runtime.own
            val status = own.status.value
            val allowed = shouldRefreshLocation(
                status = status,
                optedOut = SosLocationOptOut.contains(sosId),
                hasPermission = source.hasPermission(),
                locationEnabled = source.isLocationEnabled()
            )
            if (!allowed || status.sosId != sosId) return

            source.requestFresh { fix ->
                try {
                    val current = own.status.value
                    if (current.state != OwnSosState.ACTIVE || current.sosId != sosId) return@requestFresh
                    if (SosLocationOptOut.contains(sosId)) return@requestFresh
                    val body = current.body ?: return@requestFresh
                    if (fix == null) return@requestFresh
                    val mapped = fix.toSosLocationOrNull(
                        nowMillis = System.currentTimeMillis(),
                        approximate = !source.hasFinePermission()
                    )
                    if (!shouldApplyFix(mapped, fix.time, body.location, lastAppliedFixTime[sosId])) {
                        return@requestFresh
                    }
                    try {
                        own.update(body.copy(location = mapped))
                        lastAppliedFixTime[sosId] = fix.time
                    } catch (_: IllegalArgumentException) {
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }
}
