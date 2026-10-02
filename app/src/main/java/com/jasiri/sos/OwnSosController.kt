package com.jasiri.sos

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

/** Hands an encoded SOS payload to the transport; returns true if it was queued for broadcast. */
fun interface SosSender {
    fun send(payload: ByteArray): Boolean
}

enum class OwnSosState { IDLE, ACTIVE, CANCELLING, CANCELLED, EXPIRED }

data class OwnSosStatus(
    val state: OwnSosState,
    /** Null when IDLE. */
    val sosId: Long?,
    /** Current content version; while CANCELLING or CANCELLED this is the CANCEL seq. */
    val seq: Int,
    /** Current SOS content; null when IDLE. */
    val body: SosBody?,
    val startedAtMillis: Long?,
    val lastAttemptAtMillis: Long?,
    val lastSuccessAtMillis: Long?,
    val successfulSends: Int,
    val consecutiveFailures: Int,
    /** True when the most recent send attempt returned false or threw. */
    val notSentWarning: Boolean
) {
    companion object {
        val IDLE = OwnSosStatus(
            state = OwnSosState.IDLE,
            sosId = null,
            seq = 0,
            body = null,
            startedAtMillis = null,
            lastAttemptAtMillis = null,
            lastSuccessAtMillis = null,
            successfulSends = 0,
            consecutiveFailures = 0,
            notSentWarning = false
        )
    }
}

data class OwnSosConfig(
    val fastIntervalMillis: Long = 30_000,
    val fastPhaseMillis: Long = 10 * 60_000,
    val slowIntervalMillis: Long = 120_000,
    val lifetimeMillis: Long = 6 * 60 * 60_000L,
    val retryAfterFailureMillis: Long = 10_000,
    val cancelRepeats: Int = 3,
    val cancelIntervalMillis: Long = 30_000
)

/**
 * Manages this phone's own SOS: start, periodic re-broadcast, update, cancel and expiry.
 *
 * All state changes and send attempts happen under one lock. At most one scheduler job
 * exists; each job carries a generation number and stops acting as soon as it is superseded.
 * [SosSender.send] is called while holding the lock, so it must not block.
 */
class OwnSosController(
    private val sender: SosSender,
    private val scope: CoroutineScope,
    private val clockMillis: () -> Long,
    private val newSosId: () -> Long = { secureRandomNonZeroLong() },
    private val config: OwnSosConfig = OwnSosConfig()
) {
    private val lock = Any()
    private val _status = MutableStateFlow(OwnSosStatus.IDLE)
    val status: StateFlow<OwnSosStatus> = _status.asStateFlow()

    private var job: Job? = null
    private var generation = 0L

    /**
     * From IDLE, CANCELLED or EXPIRED: starts a new SOS (new sosId, seq 0). While ACTIVE: acts like [update].
     * While CANCELLING: stops the remaining CANCEL repeats of the old SOS and starts a new one.
     *
     * @throws IllegalArgumentException if [body] cannot be encoded; state is left unchanged.
     */
    fun start(body: SosBody) {
        validate(body)
        synchronized(lock) {
            when (_status.value.state) {
                OwnSosState.CANCELLING -> stopJob()
                OwnSosState.ACTIVE -> {
                    if (!expireIfDue(clockMillis())) {
                        updateLocked(body)
                        return
                    }
                }
                OwnSosState.IDLE, OwnSosState.CANCELLED, OwnSosState.EXPIRED -> Unit
            }
            val now = clockMillis()
            _status.value = OwnSosStatus.IDLE.copy(
                state = OwnSosState.ACTIVE,
                sosId = newSosId(),
                seq = 0,
                body = body,
                startedAtMillis = now
            )
            sendSosAndReschedule(now)
        }
    }

    /** @throws IllegalArgumentException if [body] cannot be encoded; state is left unchanged. */
    fun update(body: SosBody) {
        validate(body)
        synchronized(lock) {
            if (_status.value.state != OwnSosState.ACTIVE) return
            if (expireIfDue(clockMillis())) return
            updateLocked(body)
        }
    }

    fun cancel() {
        synchronized(lock) {
            val current = _status.value
            if (current.state != OwnSosState.ACTIVE) return
            val sosId = current.sosId ?: return
            stopJob()
            val cancelSeq = (current.seq + 1) and 0xFFFF
            _status.value = current.copy(state = OwnSosState.CANCELLING, seq = cancelSeq)

            val repeats = config.cancelRepeats.coerceAtLeast(1)
            attempt { now -> encodeCancel(sosId, cancelSeq, now) }
            if (repeats == 1) {
                _status.value = _status.value.copy(state = OwnSosState.CANCELLED)
                return
            }

            val gen = ++generation
            job = scope.launch {
                for (sent in 2..repeats) {
                    delay(config.cancelIntervalMillis)
                    synchronized(lock) {
                        if (gen != generation) return@launch
                        attempt { now -> encodeCancel(sosId, cancelSeq, now) }
                        if (sent == repeats) {
                            _status.value = _status.value.copy(state = OwnSosState.CANCELLED)
                        }
                    }
                }
            }
        }
    }

    /** Only from CANCELLED or EXPIRED; otherwise a no-op. */
    fun resetToIdle() {
        synchronized(lock) {
            val state = _status.value.state
            if (state != OwnSosState.CANCELLED && state != OwnSosState.EXPIRED) return
            stopJob()
            _status.value = OwnSosStatus.IDLE
        }
    }

    /**
     * Stops everything immediately WITHOUT sending anything (no CANCEL) and returns to IDLE.
     * Works from any state. Used when this phone's identity changes (panic wipe): the old SOS
     * can no longer be signed by its origin, so re-broadcasts or CANCELs would be rejected.
     */
    fun abandon() {
        synchronized(lock) {
            stopJob()
            _status.value = OwnSosStatus.IDLE
        }
    }

    private fun validate(body: SosBody) {
        SosCodec.encode(SosPayload(SosKind.SOS, sosId = 0L, seq = 0, timestampSeconds = 0L, body = body))
    }

    private fun updateLocked(body: SosBody) {
        val current = _status.value
        _status.value = current.copy(seq = (current.seq + 1) and 0xFFFF, body = body)
        sendSosAndReschedule(clockMillis())
    }

    /** Sends the current SOS now and restarts the schedule from [now]. Caller holds the lock. */
    private fun sendSosAndReschedule(now: Long) {
        stopJob()
        val firstWait = nextDelay(attemptSos(), now)
        val gen = ++generation
        job = scope.launch {
            var wait = firstWait
            while (true) {
                delay(wait)
                wait = synchronized(lock) {
                    if (gen != generation) null else sosTick()
                } ?: break
            }
        }
    }

    /** Returns the delay until the next tick, or null when the SOS is no longer active. */
    private fun sosTick(): Long? {
        if (_status.value.state != OwnSosState.ACTIVE) return null
        val now = clockMillis()
        if (expireIfDue(now)) return null
        return try {
            nextDelay(attemptSos(), now)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            config.retryAfterFailureMillis
        }
    }

    private fun attemptSos(): Boolean {
        val current = _status.value
        val sosId = current.sosId ?: return false
        val body = current.body ?: return false
        return attempt { now ->
            SosCodec.encode(SosPayload(SosKind.SOS, sosId, current.seq, now / 1000, body))
        }
    }

    /** One send attempt, recorded in [status]. Never throws. Caller holds the lock. */
    private fun attempt(encode: (nowMillis: Long) -> ByteArray): Boolean {
        val now = clockMillis()
        val ok = try {
            sender.send(encode(now))
        } catch (_: Exception) {
            false
        }
        val current = _status.value
        _status.value = if (ok) {
            current.copy(
                lastAttemptAtMillis = now,
                lastSuccessAtMillis = now,
                successfulSends = current.successfulSends + 1,
                consecutiveFailures = 0,
                notSentWarning = false
            )
        } else {
            current.copy(
                lastAttemptAtMillis = now,
                consecutiveFailures = current.consecutiveFailures + 1,
                notSentWarning = true
            )
        }
        return ok
    }

    private fun nextDelay(lastSendOk: Boolean, now: Long): Long {
        val startedAt = _status.value.startedAtMillis ?: now
        val interval = when {
            !lastSendOk -> config.retryAfterFailureMillis
            now - startedAt < config.fastPhaseMillis -> config.fastIntervalMillis
            else -> config.slowIntervalMillis
        }
        val untilExpiry = startedAt + config.lifetimeMillis - now
        return minOf(interval, untilExpiry).coerceAtLeast(0L)
    }

    /** Moves an ACTIVE SOS past its lifetime to EXPIRED. Caller holds the lock. */
    private fun expireIfDue(now: Long): Boolean {
        val current = _status.value
        val startedAt = current.startedAtMillis ?: return false
        if (current.state != OwnSosState.ACTIVE || now - startedAt < config.lifetimeMillis) return false
        stopJob()
        _status.value = current.copy(state = OwnSosState.EXPIRED)
        return true
    }

    private fun stopJob() {
        generation++
        job?.cancel()
        job = null
    }

    private fun encodeCancel(sosId: Long, seq: Int, nowMillis: Long): ByteArray =
        SosCodec.encode(SosPayload(SosKind.CANCEL, sosId, seq, nowMillis / 1000, body = null))
}

private val secureRandom by lazy { SecureRandom() }

internal fun secureRandomNonZeroLong(): Long {
    while (true) {
        val value = secureRandom.nextLong()
        if (value != 0L) return value
    }
}
