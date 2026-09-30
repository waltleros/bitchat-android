package com.jasiri.alerts

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bitchat.android.BuildConfig
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import com.bitchat.android.service.MeshServiceHolder
import com.jasiri.quick.JasiriQuick
import com.jasiri.quick.QuickCatalog
import com.jasiri.sos.Compass8
import com.jasiri.sos.JasiriSos
import com.jasiri.sos.SosCategory
import com.jasiri.sos.SosDistance
import com.jasiri.sos.SosLocation
import com.jasiri.sos.location.SosLocationSource
import com.jasiri.sos.location.toSosLocationOrNull
import com.jasiri.sos.sosDistance
import com.jasiri.sos.ui.geoUri
import com.jasiri.sos.ui.peerLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Notifications + vibration for received SOS and WARNING quick messages, app open or closed. */
object JasiriAlerts {
    const val EXTRA_OPEN_SOS = "com.jasiri.extra.OPEN_SOS"
    const val EXTRA_OPEN_QUICK = "com.jasiri.extra.OPEN_QUICK"

    /** UI consumes these: set true by handleIntent; the button opens its page and sets it back to false. */
    val openSosRequest = MutableStateFlow(false)
    val openQuickRequest = MutableStateFlow(false)

    private const val CHANNEL_SOS = "jasiri_sos_alerts"
    private const val CHANNEL_WARN = "jasiri_quick_warnings"
    private const val TAG_SOS = "jasiri_sos"
    private const val TAG_QUICK = "jasiri_quick"
    private const val QUICK_NOTIFICATION_ID = 41_0001

    /** Distinct actions keep these PendingIntents from matching upstream's MainActivity intents (extras are ignored in matching). */
    private const val ACTION_OPEN_SOS = "com.jasiri.action.OPEN_SOS"
    private const val ACTION_OPEN_QUICK = "com.jasiri.action.OPEN_QUICK"

    private const val DEBUG_SENDER = "Test phone"

    private val SOS_PATTERN = longArrayOf(0, 800, 300, 800, 300, 800)
    private val WARN_PATTERN = longArrayOf(0, 300, 150, 300)

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sosPlanner = SosAlertPlanner()
    private val quickPlanner = QuickAlertPlanner()
    private val quickLock = Any()

    private data class WarningLine(val presetId: Int, val senderLabel: String)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val ctx = context.applicationContext
        ensureChannels(ctx)

        scope.launch {
            val sos = JasiriSos.runtime
            combine(sos.entries, sos.myPeerID) { entries, me -> entries to me }.collect { (entries, me) ->
                try {
                    val plan = sosPlanner.plan(entries, me)
                    plan.cancel.forEach { cancelSos(ctx, it) }
                    if (plan.post.isNotEmpty()) {
                        val nicknames = nicknames()
                        plan.post.forEach { alert ->
                            postSos(ctx, alert.sosId, alert.category, peerLabel(alert.originPeerID, nicknames), alert.location)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        scope.launch {
            JasiriQuick.runtime.feed.collect { feed ->
                try {
                    val plan = synchronized(quickLock) {
                        quickPlanner.plan(feed, System.currentTimeMillis())
                    } ?: return@collect
                    val nicknames = nicknames()
                    postQuick(
                        ctx,
                        plan.recent.map { WarningLine(it.presetId, peerLabel(it.senderPeerID, nicknames)) },
                        plan.buzz
                    )
                } catch (_: Exception) {
                }
            }
        }

        scope.launch {
            JasiriQuick.runtime.unreadCount.collect { count ->
                try {
                    if (count == 0) {
                        synchronized(quickLock) { quickPlanner.onAllRead() }
                        cancelQuick(ctx)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun handleIntent(intent: Intent?) {
        try {
            if (intent == null) return
            if (intent.getBooleanExtra(EXTRA_OPEN_SOS, false)) {
                intent.removeExtra(EXTRA_OPEN_SOS)
                openSosRequest.value = true
            }
            if (intent.getBooleanExtra(EXTRA_OPEN_QUICK, false)) {
                intent.removeExtra(EXTRA_OPEN_QUICK)
                openQuickRequest.value = true
            }
        } catch (_: Exception) {
        }
    }

    /** Debug builds only: one sample SOS + one sample warning through the real builders. Runtimes and planners are untouched. */
    fun debugDemo(context: Context) {
        if (!BuildConfig.DEBUG) return
        try {
            val ctx = context.applicationContext
            ensureChannels(ctx)
            postSos(
                ctx,
                sosId = -1L,
                category = SosCategory.MEDICAL,
                senderLabel = DEBUG_SENDER,
                location = SosLocation(
                    latE7 = -12_864_000,
                    lonE7 = 368_172_000,
                    accuracyMeters = 10,
                    fixAgeSeconds = 0,
                    approximate = false
                )
            )
            postQuick(ctx, listOf(WarningLine(presetId = 6, senderLabel = DEBUG_SENDER)), buzz = true)
            Toast.makeText(context, R.string.jasiri_alert_debug_posted, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    private fun ensureChannels(ctx: Context) {
        try {
            val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
            val sos = NotificationChannel(
                CHANNEL_SOS,
                ctx.getString(R.string.jasiri_alert_channel_sos),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = ctx.getString(R.string.jasiri_alert_channel_sos_desc)
                enableVibration(true)
                vibrationPattern = SOS_PATTERN
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
            }
            val warn = NotificationChannel(
                CHANNEL_WARN,
                ctx.getString(R.string.jasiri_alert_channel_warn),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = ctx.getString(R.string.jasiri_alert_channel_warn_desc)
                enableVibration(true)
                vibrationPattern = WARN_PATTERN
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(sos)
            manager.createNotificationChannel(warn)
        } catch (_: Exception) {
        }
    }

    private fun sosNotificationId(sosId: Long): Int = (sosId xor (sosId ushr 32)).toInt()

    private fun postSos(ctx: Context, sosId: Long, category: SosCategory, senderLabel: String, location: SosLocation?) {
        if (!canNotify(ctx)) {
            vibrate(ctx, SOS_PATTERN)
            return
        }
        val id = sosNotificationId(sosId)
        val distanceText = location?.let { distanceTextOrNull(ctx, it) }
        val body = if (distanceText != null) {
            ctx.getString(R.string.jasiri_alert_sos_text_distance, senderLabel, distanceText)
        } else {
            ctx.getString(R.string.jasiri_alert_sos_text, senderLabel)
        }
        val builder = NotificationCompat.Builder(ctx, CHANNEL_SOS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.jasiri_alert_sos_title, ctx.getString(categoryLabelRes(category))))
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx, ACTION_OPEN_SOS, EXTRA_OPEN_SOS, id))
        if (location != null) {
            mapIntent(ctx, location, id + 1)?.let {
                builder.addAction(0, ctx.getString(R.string.jasiri_alert_open_map), it)
            }
        }
        if (!notifySafely(ctx, TAG_SOS, id, builder.build())) vibrate(ctx, SOS_PATTERN)
    }

    private fun cancelSos(ctx: Context, sosId: Long) {
        try {
            NotificationManagerCompat.from(ctx).cancel(TAG_SOS, sosNotificationId(sosId))
        } catch (_: Exception) {
        }
    }

    private fun postQuick(ctx: Context, recent: List<WarningLine>, buzz: Boolean) {
        if (recent.isEmpty()) return
        if (!canNotify(ctx)) {
            if (buzz) vibrate(ctx, WARN_PATTERN)
            return
        }
        val builder = NotificationCompat.Builder(ctx, CHANNEL_WARN)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx, ACTION_OPEN_QUICK, EXTRA_OPEN_QUICK, QUICK_NOTIFICATION_ID))
        if (!buzz) builder.setSilent(true)

        if (recent.size == 1) {
            val only = recent.first()
            val english = QuickCatalog.label(only.presetId, "en") ?: return
            val swahili = QuickCatalog.label(only.presetId, "sw") ?: english
            builder
                .setContentTitle(ctx.getString(R.string.jasiri_alert_warning_title, english))
                .setContentText(ctx.getString(R.string.jasiri_alert_warning_line, swahili, only.senderLabel))
        } else {
            val lines = recent.mapNotNull { line ->
                QuickCatalog.label(line.presetId, "en")?.let {
                    ctx.getString(R.string.jasiri_alert_warning_line, it, line.senderLabel)
                }
            }
            val title = ctx.getString(R.string.jasiri_alert_warnings_title, recent.size)
            val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
            lines.forEach { style.addLine(it) }
            builder
                .setContentTitle(title)
                .setContentText(lines.firstOrNull())
                .setNumber(recent.size)
                .setStyle(style)
        }
        if (!notifySafely(ctx, TAG_QUICK, QUICK_NOTIFICATION_ID, builder.build()) && buzz) {
            vibrate(ctx, WARN_PATTERN)
        }
    }

    private fun cancelQuick(ctx: Context) {
        try {
            NotificationManagerCompat.from(ctx).cancel(TAG_QUICK, QUICK_NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    private fun openAppIntent(ctx: Context, action: String, extra: String, requestCode: Int): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            this.action = action
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(extra, true)
        }
        return PendingIntent.getActivity(
            ctx,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * Direct geo: intent when it resolves. Without a manifest <queries> entry, resolveActivity returns
     * null on API 30+ even when a map app exists, so fall back to the system chooser, which always
     * resolves and tells the user when no app can open it.
     */
    private fun mapIntent(ctx: Context, location: SosLocation, requestCode: Int): PendingIntent? = try {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(geoUri(location)))
        val target = if (view.resolveActivity(ctx.packageManager) != null) {
            view
        } else {
            Intent.createChooser(view, ctx.getString(R.string.jasiri_alert_open_map))
        }
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        PendingIntent.getActivity(
            ctx,
            requestCode,
            target,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    } catch (_: Exception) {
        null
    }

    private fun canNotify(ctx: Context): Boolean = try {
        NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)
    } catch (_: Exception) {
        false
    }

    @SuppressLint("MissingPermission")
    private fun notifySafely(ctx: Context, tag: String, id: Int, notification: Notification): Boolean = try {
        NotificationManagerCompat.from(ctx).notify(tag, id, notification)
        true
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        false
    }

    private fun vibrate(ctx: Context, pattern: LongArray) {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
        }
    }

    private fun nicknames(): Map<String, String> = try {
        MeshServiceHolder.unifiedMeshService?.getPeerNicknames()
    } catch (_: Exception) {
        null
    } ?: emptyMap()

    /** From this phone's last-known fix only; null without permission or a fix. Never prompts, never leaves the phone. */
    private fun distanceTextOrNull(ctx: Context, them: SosLocation): String? {
        return try {
            val source = SosLocationSource(ctx)
            val me = source.lastKnown()
                ?.toSosLocationOrNull(System.currentTimeMillis(), approximate = !source.hasFinePermission())
                ?: return null
            when (val d = sosDistance(me, them)) {
                is SosDistance.Away ->
                    ctx.getString(R.string.jasiri_sos_distance_away, d.text, directionText(ctx, d.direction))
                is SosDistance.VeryClose ->
                    ctx.getString(R.string.jasiri_sos_distance_very_close, d.withinMeters)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun directionText(ctx: Context, direction: Compass8): String = ctx.getString(
        when (direction) {
            Compass8.N -> R.string.jasiri_dir_n
            Compass8.NE -> R.string.jasiri_dir_ne
            Compass8.E -> R.string.jasiri_dir_e
            Compass8.SE -> R.string.jasiri_dir_se
            Compass8.S -> R.string.jasiri_dir_s
            Compass8.SW -> R.string.jasiri_dir_sw
            Compass8.W -> R.string.jasiri_dir_w
            Compass8.NW -> R.string.jasiri_dir_nw
        }
    )

    private fun categoryLabelRes(category: SosCategory): Int = when (category) {
        SosCategory.GENERAL -> R.string.jasiri_sos_cat_general
        SosCategory.MEDICAL -> R.string.jasiri_sos_cat_medical
        SosCategory.TRAPPED -> R.string.jasiri_sos_cat_trapped
        SosCategory.FIRE -> R.string.jasiri_sos_cat_fire
        SosCategory.VIOLENCE -> R.string.jasiri_sos_cat_violence
        SosCategory.DETAINED -> R.string.jasiri_sos_cat_detained
        SosCategory.MISSING_PERSON -> R.string.jasiri_sos_cat_missing
        SosCategory.OTHER -> R.string.jasiri_sos_cat_other
    }
}
