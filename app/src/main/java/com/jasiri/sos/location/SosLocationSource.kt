package com.jasiri.sos.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.jasiri.sos.SosLocation
import com.jasiri.sos.isBetterFix
import com.jasiri.sos.toSosLocation

/** Maps a platform fix to the wire type; null if its coordinates are invalid. */
fun Location.toSosLocationOrNull(nowMillis: Long, approximate: Boolean = false): SosLocation? =
    toSosLocation(
        latDeg = latitude,
        lonDeg = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        fixTimeMillis = time,
        nowMillis = nowMillis,
        approximate = approximate
    )

/**
 * Plain [LocationManager] access for the SOS screen. Independent of the geohash location
 * providers and their privacy gate. No method throws.
 */
class SosLocationSource(context: Context) {

    private val appContext = context.applicationContext
    private val locationManager: LocationManager? = try {
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    } catch (_: Exception) {
        null
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Main thread only. */
    private var pending: FreshRequest? = null

    /** FINE or COARSE granted. */
    fun hasPermission(): Boolean =
        isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** FINE granted; without it Android only hands out approximate fixes. */
    fun hasFinePermission(): Boolean = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)

    fun isLocationEnabled(): Boolean = try {
        locationManager?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false
    } catch (_: Exception) {
        false
    }

    /** Newest last-known fix across enabled providers (GPS, NETWORK, PASSIVE), or null. Never throws. */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? {
        if (!hasPermission()) return null
        val manager = locationManager ?: return null
        val now = System.currentTimeMillis()
        var best: Location? = null
        var bestMapped: SosLocation? = null
        for (provider in LAST_KNOWN_PROVIDERS) {
            val location = try {
                if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null
            } catch (_: Exception) {
                null
            } ?: continue
            val mapped = location.toSosLocationOrNull(now) ?: continue
            if (isBetterFix(mapped, location.time, bestMapped, best?.time)) {
                best = location
                bestMapped = mapped
            }
        }
        return best
    }

    /**
     * One fresh fix. Callback on the main thread exactly once: a Location, or null after
     * timeoutMillis / on error / without permission. Prefer GPS, fall back to NETWORK.
     * API 30+: LocationManager.getCurrentLocation with a CancellationSignal; below 30:
     * requestSingleUpdate (deprecated is fine) with a main-thread timeout that removes the listener.
     *
     * GPS and NETWORK are asked in parallel. A GPS fix wins immediately; a NETWORK fix is held
     * for up to [GPS_GRACE_MILLIS] in case GPS answers, and used if it doesn't. A new request,
     * or [cancel], supersedes a pending one, whose callback is then never invoked.
     */
    fun requestFresh(timeoutMillis: Long = 30_000, callback: (Location?) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { requestFresh(timeoutMillis, callback) }
            return
        }
        cancel()
        val request = FreshRequest(callback)
        pending = request
        request.start(timeoutMillis)
    }

    /** Cancels any pending fresh request, idempotent. */
    fun cancel() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { cancel() }
            return
        }
        pending?.abandon()
        pending = null
    }

    private fun isGranted(permission: String): Boolean = try {
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    private fun isProviderEnabled(manager: LocationManager, provider: String): Boolean = try {
        manager.isProviderEnabled(provider)
    } catch (_: Exception) {
        false
    }

    /** All state is touched on the main thread only. */
    private inner class FreshRequest(private val callback: (Location?) -> Unit) {
        private var done = false
        private var outstanding = 0
        private var gpsOutstanding = false
        private var fallback: Location? = null
        private var deadlineUptime = 0L
        private val signals = mutableListOf<CancellationSignal>()
        private val listeners = mutableListOf<LocationListener>()
        private val timeout = Runnable { finish(fallback) }

        fun start(timeoutMillis: Long) {
            val manager = locationManager
            if (manager == null || !hasPermission()) {
                mainHandler.post { finish(null) }
                return
            }
            deadlineUptime = SystemClock.uptimeMillis() + timeoutMillis
            mainHandler.postAtTime(timeout, deadlineUptime)
            for (provider in FRESH_PROVIDERS) {
                if (isProviderEnabled(manager, provider) && requestFrom(manager, provider)) {
                    outstanding++
                    if (provider == LocationManager.GPS_PROVIDER) gpsOutstanding = true
                }
            }
            if (outstanding == 0) mainHandler.post { finish(null) }
        }

        @SuppressLint("MissingPermission")
        private fun requestFrom(manager: LocationManager, providerName: String): Boolean {
            if (!hasPermission()) return false
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    signals += signal
                    manager.getCurrentLocation(providerName, signal, appContext.mainExecutor) { location ->
                        onResult(providerName, location)
                    }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            onResult(providerName, location)
                        }
                        @Deprecated("Required by LocationListener on API < 29")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {
                            onResult(providerName, null)
                        }
                    }
                    listeners += listener
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(providerName, listener, Looper.getMainLooper())
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun onResult(provider: String, location: Location?) {
            if (done) return
            outstanding--
            val isGps = provider == LocationManager.GPS_PROVIDER
            if (isGps) gpsOutstanding = false
            when {
                location != null && isGps -> finish(location)
                location != null -> {
                    fallback = location
                    if (!gpsOutstanding) {
                        finish(location)
                    } else {
                        val graceDeadline = SystemClock.uptimeMillis() + GPS_GRACE_MILLIS
                        mainHandler.removeCallbacks(timeout)
                        mainHandler.postAtTime(timeout, minOf(deadlineUptime, graceDeadline))
                    }
                }
                outstanding <= 0 || (isGps && fallback != null) -> finish(fallback)
            }
        }

        private fun finish(location: Location?) {
            if (done) return
            abandon()
            if (pending === this) pending = null
            callback(location)
        }

        /** Stops all platform requests without invoking the callback. */
        fun abandon() {
            if (done) return
            done = true
            mainHandler.removeCallbacks(timeout)
            signals.forEach { signal ->
                try { signal.cancel() } catch (_: Exception) { }
            }
            val manager = locationManager
            if (manager != null) {
                listeners.forEach { listener ->
                    try { manager.removeUpdates(listener) } catch (_: Exception) { }
                }
            }
            signals.clear()
            listeners.clear()
        }
    }

    private companion object {
        val LAST_KNOWN_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        val FRESH_PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        const val GPS_GRACE_MILLIS = 10_000L
    }
}
