package com.jasiri.sos.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.button.CloseButton
import com.bitchat.android.service.MeshServiceHolder
import com.jasiri.sos.JasiriSos
import com.jasiri.sos.OwnSosController
import com.jasiri.sos.OwnSosState
import com.jasiri.sos.OwnSosStatus
import com.jasiri.sos.SOS_ACCURACY_UNKNOWN
import com.jasiri.sos.SosBody
import com.jasiri.sos.SosCategory
import com.jasiri.sos.SosEntry
import com.jasiri.sos.SosEntryState
import com.jasiri.sos.SosLocation
import com.jasiri.sos.SosRuntime
import com.jasiri.sos.location.SosLocationSource
import com.jasiri.sos.location.toSosLocationOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SosRed = Color(0xFFD32F2F)
private val SosAmber = Color(0xFFFFA000)

/** Red SOS pill for the main chat header; opens the full-screen SOS page. */
@Composable
fun SosHeaderButton(modifier: Modifier = Modifier) {
    val runtime = remember { JasiriSos.runtime }
    val status by runtime.own.status.collectAsStateWithLifecycle()
    val entries by runtime.entries.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }

    val ownLive = status.state == OwnSosState.ACTIVE || status.state == OwnSosState.CANCELLING
    val badge = alertBadgeCount(entries)
    val description = stringResource(R.string.jasiri_sos_cd_open)
    val pillShape = RoundedCornerShape(50)

    val pillAlpha = if (ownLive) {
        val transition = rememberInfiniteTransition(label = "sosPulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200),
                repeatMode = RepeatMode.Reverse
            ),
            label = "sosPulseAlpha"
        ).value
    } else {
        1f
    }

    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = description, role = Role.Button) { open = true }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer { alpha = pillAlpha }
                .clip(pillShape)
                .then(
                    if (ownLive) Modifier.background(SosRed)
                    else Modifier.border(1.5.dp, SosRed, pillShape)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .clearAndSetSemantics { }
        ) {
            Text(
                text = stringResource(R.string.jasiri_sos_title),
                color = if (ownLive) Color.White else SosRed,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
        if (badge > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 2.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(SosRed)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (badge > 9) "9+" else badge.toString(),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    if (open) {
        SosPage(runtime = runtime, onDismiss = { open = false })
    }
}

@Composable
private fun SosPage(runtime: SosRuntime, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            SosPageContent(runtime = runtime, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun SosPageContent(runtime: SosRuntime, onDismiss: () -> Unit) {
    val status by runtime.own.status.collectAsStateWithLifecycle()
    val entries by runtime.entries.collectAsStateWithLifecycle()
    val myPeerID by runtime.myPeerID.collectAsStateWithLifecycle()
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    val nicknames = remember(now) {
        try {
            MeshServiceHolder.unifiedMeshService?.getPeerNicknames()
        } catch (_: Exception) {
            null
        } ?: emptyMap()
    }

    val context = LocalContext.current
    val own = runtime.own
    val source = remember { SosLocationSource(context) }
    DisposableEffect(source) {
        onDispose { source.cancel() }
    }
    var shareOn by rememberSaveable { mutableStateOf(initialShareLocation(own.status.value)) }
    var hasPermission by remember { mutableStateOf(source.hasPermission()) }
    var locStatus by remember {
        mutableStateOf(initialLocStatus(own.status.value, shareOn, hasPermission))
    }

    fun fetchAndUpdate() {
        val started = own.status.value
        if (started.state != OwnSosState.ACTIVE) return
        val sosId = started.sosId
        locStatus = LocStatus.Getting
        if (!source.isLocationEnabled()) {
            locStatus = LocStatus.Unavailable
            return
        }
        source.requestFresh { fix ->
            val current = own.status.value
            if (current.state != OwnSosState.ACTIVE || current.sosId != sosId || !shareOn) {
                return@requestFresh
            }
            val body = current.body ?: return@requestFresh
            val mapped = fix?.toSosLocationOrNull(
                nowMillis = System.currentTimeMillis(),
                approximate = !source.hasFinePermission()
            )
            if (mapped == null) {
                locStatus = LocStatus.Unavailable
                return@requestFresh
            }
            try {
                own.update(body.copy(location = mapped))
            } catch (_: IllegalArgumentException) {
            }
            locStatus = LocStatus.Shared(mapped.accuracyMeters)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it } || source.hasPermission()
        hasPermission = granted
        if (granted) {
            fetchAndUpdate()
        } else {
            locStatus = LocStatus.NoPermission
        }
    }

    fun requestLocationPermission() {
        locStatus = LocStatus.NoPermission
        try {
            permissionLauncher.launch(LOCATION_PERMISSIONS)
        } catch (_: Exception) {
        }
    }

    fun fire(category: SosCategory) {
        val permitted = source.hasPermission()
        val location = if (shareOn && permitted) {
            source.lastKnown()?.toSosLocationOrNull(
                nowMillis = System.currentTimeMillis(),
                approximate = !source.hasFinePermission()
            )
        } else {
            null
        }
        val body = SosBody(
            category = category,
            severity = SOS_DEFAULT_SEVERITY,
            location = location,
            batteryPercent = readBattery(context),
            peopleCount = null
        )
        try {
            own.start(body)
        } catch (_: IllegalArgumentException) {
        }
        when {
            !shareOn -> {
                locStatus = LocStatus.Off
                own.status.value.sosId?.let { locationOptOutSosIds.add(it) }
            }
            permitted -> {
                hasPermission = true
                fetchAndUpdate()
            }
            else -> requestLocationPermission()
        }
    }

    fun onShareChanged(on: Boolean) {
        shareOn = on
        val current = own.status.value
        val active = current.state == OwnSosState.ACTIVE
        val sosId = current.sosId
        if (active && sosId != null) {
            if (on) locationOptOutSosIds.remove(sosId) else locationOptOutSosIds.add(sosId)
        }
        if (!on) {
            source.cancel()
            locStatus = LocStatus.Off
            val body = current.body
            if (active && body?.location != null) {
                try {
                    own.update(body.copy(location = null))
                } catch (_: IllegalArgumentException) {
                }
            }
        } else if (active) {
            if (source.hasPermission()) {
                hasPermission = true
                fetchAndUpdate()
            } else {
                requestLocationPermission()
            }
        }
    }

    val activeSosId = status.sosId.takeIf { status.state == OwnSosState.ACTIVE }
    LaunchedEffect(activeSosId, shareOn, hasPermission) {
        if (activeSosId == null || !shareOn || !hasPermission) return@LaunchedEffect
        if (locStatus != LocStatus.Getting) fetchAndUpdate()
        while (true) {
            delay(LOCATION_REFRESH_MILLIS)
            fetchAndUpdate()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.jasiri_sos_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = SosRed,
                modifier = Modifier.weight(1f)
            )
            CloseButton(onClick = onDismiss)
        }

        com.jasiri.onboarding.BluetoothBanner()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "own") {
                OwnPanel(
                    status = status,
                    now = now,
                    own = own,
                    shareLocation = shareOn,
                    onShareLocationChange = { onShareChanged(it) },
                    locStatus = locStatus,
                    onRequestLocationPermission = { requestLocationPermission() },
                    onFire = { fire(it) }
                )
            }
            item(key = "header") {
                Text(
                    text = stringResource(R.string.jasiri_sos_nearby_header, entries.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            if (entries.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.jasiri_sos_nearby_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(entries, key = { it.sosId }) { entry ->
                    ReceivedSosCard(
                        entry = entry,
                        now = now,
                        myPeerID = myPeerID,
                        nicknames = nicknames,
                        runtime = runtime
                    )
                }
            }
        }
    }
}

@Composable
private fun OwnPanel(
    status: OwnSosStatus,
    now: Long,
    own: OwnSosController,
    shareLocation: Boolean,
    onShareLocationChange: (Boolean) -> Unit,
    locStatus: LocStatus,
    onRequestLocationPermission: () -> Unit,
    onFire: (SosCategory) -> Unit
) {
    var selected by rememberSaveable { mutableStateOf(SosCategory.GENERAL) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val display = ownSosDisplay(status, now)) {
            OwnSosDisplay.Idle -> {
                Text(
                    text = stringResource(R.string.jasiri_sos_choose_category),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                CategoryChips(selected = selected, onSelect = { selected = it })
                ShareLocationRow(checked = shareLocation, onCheckedChange = onShareLocationChange)
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    HoldToSendButton(onFire = { onFire(selected) })
                }
            }

            is OwnSosDisplay.Sending, OwnSosDisplay.NotSent -> {
                ActiveStatusCard(
                    display = display,
                    locStatus = locStatus,
                    onRequestLocationPermission = onRequestLocationPermission
                )
                CategoryChips(
                    selected = status.body?.category ?: selected,
                    onSelect = { category ->
                        val current = status.body
                        if (current != null && current.category != category) {
                            selected = category
                            try {
                                own.update(current.copy(category = category))
                            } catch (_: IllegalArgumentException) {
                            }
                        }
                    }
                )
                ShareLocationRow(checked = shareLocation, onCheckedChange = onShareLocationChange)
                CancelOwnSosButton(onConfirm = { own.cancel() })
            }

            OwnSosDisplay.Cancelling -> {
                Text(
                    text = stringResource(R.string.jasiri_sos_cancelling),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            OwnSosDisplay.Cancelled, OwnSosDisplay.Expired -> {
                Text(
                    text = stringResource(
                        if (display == OwnSosDisplay.Cancelled) R.string.jasiri_sos_cancelled
                        else R.string.jasiri_sos_expired
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Button(onClick = { own.resetToIdle() }) {
                    Text(stringResource(R.string.jasiri_sos_ok))
                }
            }
        }
    }
}

@Composable
private fun ActiveStatusCard(
    display: OwnSosDisplay,
    locStatus: LocStatus,
    onRequestLocationPermission: () -> Unit
) {
    val notSent = display == OwnSosDisplay.NotSent
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (notSent) SosAmber else SosRed,
            contentColor = if (notSent) Color.Black else Color.White
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (display is OwnSosDisplay.Sending) {
                Text(
                    text = pluralStringResource(
                        R.plurals.jasiri_sos_active_sent,
                        display.successfulSends,
                        display.successfulSends
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                display.lastSent?.let { lastSent ->
                    Text(
                        text = stringResource(R.string.jasiri_sos_last_sent, heardAgoText(lastSent)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.jasiri_sos_not_sent),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            val noPermission = locStatus == LocStatus.NoPermission
            Text(
                text = locStatusText(locStatus),
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (noPermission) TextDecoration.Underline else null,
                modifier = if (noPermission) {
                    Modifier.clickable(role = Role.Button, onClick = onRequestLocationPermission)
                } else {
                    Modifier
                }
            )
        }
    }
}

@Composable
private fun ShareLocationRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.jasiri_sos_share_location),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = checked, onCheckedChange = null)
        }
        Text(
            text = stringResource(R.string.jasiri_sos_share_location_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun locStatusText(status: LocStatus): String = when (status) {
    LocStatus.Off -> stringResource(R.string.jasiri_sos_loc_off)
    LocStatus.Getting -> stringResource(R.string.jasiri_sos_loc_getting)
    is LocStatus.Shared ->
        if (status.accuracyM == SOS_ACCURACY_UNKNOWN) stringResource(R.string.jasiri_sos_loc_shared_unknown_acc)
        else stringResource(R.string.jasiri_sos_loc_shared, status.accuracyM)
    LocStatus.NoPermission -> stringResource(R.string.jasiri_sos_loc_no_permission)
    LocStatus.Unavailable -> stringResource(R.string.jasiri_sos_loc_unavailable)
}

@Composable
private fun CancelOwnSosButton(onConfirm: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.jasiri_sos_cancel_button))
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.jasiri_sos_cancel_confirm_title)) },
            text = { Text(stringResource(R.string.jasiri_sos_cancel_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onConfirm()
                }) { Text(stringResource(R.string.jasiri_sos_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.jasiri_sos_confirm_no))
                }
            }
        )
    }
}

/**
 * Press and hold for [SOS_HOLD_MILLIS] to fire [onFire] exactly once; a ring fills while held.
 * Releasing early resets the ring and fires nothing.
 */
@Composable
private fun HoldToSendButton(onFire: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val currentOnFire by rememberUpdatedState(onFire)
    val description = stringResource(R.string.jasiri_sos_cd_hold)

    Box(
        modifier = Modifier
            .size(180.dp)
            .semantics { contentDescription = description }
            .clip(CircleShape)
            .background(SosRed)
            .drawWithContent {
                drawContent()
                val strokeWidth = 8.dp.toPx()
                val inset = strokeWidth / 2 + 6.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                drawArc(
                    color = Color.White.copy(alpha = 0.25f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke
                )
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.value,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val hold = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = SOS_HOLD_MILLIS.toInt(),
                                easing = LinearEasing
                            )
                        )
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnFire()
                    }
                    tryAwaitRelease()
                    hold.cancel()
                    scope.launch { progress.snapTo(0f) }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.jasiri_sos_hold_label),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryChips(selected: SosCategory, onSelect: (SosCategory) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SosCategory.entries.forEach { category ->
            FilterChip(
                selected = category == selected,
                onClick = { onSelect(category) },
                label = { Text(categoryLabel(category)) }
            )
        }
    }
}

@Composable
private fun ReceivedSosCard(
    entry: SosEntry,
    now: Long,
    myPeerID: String?,
    nicknames: Map<String, String>,
    runtime: SosRuntime
) {
    val context = LocalContext.current
    val notSentMessage = stringResource(R.string.jasiri_sos_not_sent_toast)
    val closed = entry.state != SosEntryState.ACTIVE
    var confirmResolve by rememberSaveable(entry.sosId) { mutableStateOf(false) }
    val report: (Boolean) -> Unit = { sent ->
        if (!sent) Toast.makeText(context, notSentMessage, Toast.LENGTH_SHORT).show()
    }
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (closed) 0.6f else 1f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = categoryLabel(entry.body.category),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = " · " + peerLabel(entry.originPeerID, nicknames),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.jasiri_sos_heard,
                        heardAgoText(heardAgo(entry.lastHeardAtMillis, now))
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )
                if (entry.stale) {
                    Surface(
                        color = colorScheme.surfaceVariant,
                        contentColor = colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = stringResource(R.string.jasiri_sos_stale),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            entry.body.location?.let { location ->
                ReceivedLocation(location = location, now = now)
            }

            val acknowledged = entry.ackedBy.size
            val responding = entry.claimedBy.size
            if (acknowledged > 0 || responding > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.jasiri_sos_counts, acknowledged, responding),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (myPeerID != null && myPeerID in entry.claimedBy) {
                        Text(
                            text = stringResource(R.string.jasiri_sos_you_responding),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (closed) {
                Text(
                    text = stringResource(
                        if (entry.state == SosEntryState.CANCELLED) R.string.jasiri_sos_closed_cancelled
                        else R.string.jasiri_sos_closed_resolved
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )
            }

            val actions = availableActions(entry, myPeerID)
            if (actions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEach { action ->
                        when (action) {
                            SosAction.ACKNOWLEDGE -> OutlinedButton(
                                onClick = { report(runtime.acknowledge(entry.sosId)) }
                            ) { Text(stringResource(R.string.jasiri_sos_action_seen)) }

                            SosAction.CLAIM -> Button(
                                onClick = { report(runtime.claim(entry.sosId)) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SosRed,
                                    contentColor = Color.White
                                )
                            ) { Text(stringResource(R.string.jasiri_sos_action_claim)) }

                            SosAction.RESOLVE -> OutlinedButton(
                                onClick = { confirmResolve = true }
                            ) { Text(stringResource(R.string.jasiri_sos_action_resolve)) }
                        }
                    }
                }
            }
        }
    }

    if (confirmResolve) {
        AlertDialog(
            onDismissRequest = { confirmResolve = false },
            title = { Text(stringResource(R.string.jasiri_sos_resolve_confirm_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmResolve = false
                    report(runtime.resolve(entry.sosId))
                }) { Text(stringResource(R.string.jasiri_sos_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmResolve = false }) {
                    Text(stringResource(R.string.jasiri_sos_confirm_no))
                }
            }
        )
    }
}

@Composable
private fun ReceivedLocation(location: SosLocation, now: Long) {
    val context = LocalContext.current
    val noMapMessage = stringResource(R.string.jasiri_sos_no_map_app)
    val coords = formatCoords(location)
    val fixAge = fixAgeText(heardAgo(now - location.fixAgeSeconds * 1000L, now))
    val accuracy = accuracyOrNull(location)
    val line = if (accuracy != null) {
        stringResource(R.string.jasiri_sos_loc_line, coords, accuracy, fixAge)
    } else {
        stringResource(R.string.jasiri_sos_loc_line_unknown_acc, coords, fixAge)
    }
    val approximate = if (location.approximate) stringResource(R.string.jasiri_sos_loc_approximate) else ""

    Column {
        Text(
            text = line + approximate,
            style = MaterialTheme.typography.bodyMedium
        )
        TextButton(onClick = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(geoUri(location))))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, noMapMessage, Toast.LENGTH_SHORT).show()
            } catch (_: SecurityException) {
                Toast.makeText(context, noMapMessage, Toast.LENGTH_SHORT).show()
            }
        }) {
            Text(stringResource(R.string.jasiri_sos_open_map))
        }
    }
}

@Composable
fun categoryLabel(c: SosCategory): String = stringResource(
    when (c) {
        SosCategory.GENERAL -> R.string.jasiri_sos_cat_general
        SosCategory.MEDICAL -> R.string.jasiri_sos_cat_medical
        SosCategory.TRAPPED -> R.string.jasiri_sos_cat_trapped
        SosCategory.FIRE -> R.string.jasiri_sos_cat_fire
        SosCategory.VIOLENCE -> R.string.jasiri_sos_cat_violence
        SosCategory.DETAINED -> R.string.jasiri_sos_cat_detained
        SosCategory.MISSING_PERSON -> R.string.jasiri_sos_cat_missing
        SosCategory.OTHER -> R.string.jasiri_sos_cat_other
    }
)

@Composable
private fun heardAgoText(h: HeardAgo): String = when (h) {
    HeardAgo.JustNow -> stringResource(R.string.jasiri_sos_just_now)
    is HeardAgo.Minutes -> stringResource(R.string.jasiri_sos_minutes_ago, h.n)
    is HeardAgo.Hours -> stringResource(R.string.jasiri_sos_hours_ago, h.n)
}

@Composable
private fun fixAgeText(h: HeardAgo): String = when (h) {
    HeardAgo.JustNow -> stringResource(R.string.jasiri_sos_age_under_minute)
    is HeardAgo.Minutes -> stringResource(R.string.jasiri_sos_age_minutes, h.n)
    is HeardAgo.Hours -> stringResource(R.string.jasiri_sos_age_hours, h.n)
}

private const val LOCATION_REFRESH_MILLIS = 2 * 60_000L

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
)

/**
 * SOS ids for which the person switched location sharing off. Kept for the process lifetime, which
 * is also the lifetime of the own SOS, so reopening the page does not silently turn sharing back on.
 * Main thread only.
 */
private val locationOptOutSosIds = mutableSetOf<Long>()

private fun initialShareLocation(status: OwnSosStatus): Boolean {
    val sosId = status.sosId
    return status.state != OwnSosState.ACTIVE || sosId == null || sosId !in locationOptOutSosIds
}

private fun initialLocStatus(status: OwnSosStatus, shareOn: Boolean, hasPermission: Boolean): LocStatus = when {
    !shareOn -> LocStatus.Off
    !hasPermission -> LocStatus.NoPermission
    else -> status.body?.location?.let { LocStatus.Shared(it.accuracyMeters) } ?: LocStatus.Unavailable
}

private fun readBattery(context: Context): Int? = try {
    val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.let(::batteryPercentOrNull)
} catch (_: Exception) {
    null
}
