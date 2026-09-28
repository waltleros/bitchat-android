package com.jasiri.onboarding

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bitchat.android.R
import kotlinx.coroutines.delay

class GetReadyActions(
    val allowPermissions: () -> Unit,
    val turnOnBluetooth: () -> Unit,
    val turnOnLocation: () -> Unit,
    val allowBackgroundLocation: () -> Unit,
    val allowBattery: () -> Unit,
    val start: () -> Unit
)

private const val REFRESH_MILLIS = 1_000L

/** Used only if the very first snapshot read fails; the next successful read replaces it. */
private val FallbackSnapshot = ReadinessSnapshot(
    permissions = ItemStatus.TODO,
    bluetooth = ItemStatus.TODO,
    locationServices = ItemStatus.TODO,
    backgroundLocation = ItemStatus.TODO,
    battery = ItemStatus.TODO
)

/** Single setup checklist shown instead of upstream's five onboarding screens. */
@Composable
fun GetReadyScreen(
    snapshot: () -> ReadinessSnapshot,
    actions: GetReadyActions,
    modifier: Modifier = Modifier
) {
    val latestSnapshot by rememberUpdatedState(snapshot)
    var current by remember {
        mutableStateOf(
            try {
                snapshot()
            } catch (_: Exception) {
                FallbackSnapshot
            }
        )
    }

    fun refresh() {
        try {
            current = latestSnapshot()
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(REFRESH_MILLIS)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val s = current
    val (done, total) = readyCount(s)
    val primary = primaryAction(s)

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = stringResource(R.string.jasiri_ready_subtitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = stringResource(R.string.jasiri_ready_progress, done, total),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.size(4.dp))

            ReadyRow(
                icon = Icons.Filled.Security,
                title = stringResource(R.string.jasiri_ready_perm_title),
                why = stringResource(R.string.jasiri_ready_perm_why),
                required = true,
                status = s.permissions,
                actionLabel = stringResource(R.string.jasiri_ready_allow),
                onAction = actions.allowPermissions
            )
            ReadyRow(
                icon = Icons.Filled.Bluetooth,
                title = stringResource(R.string.jasiri_ready_bt_title),
                why = stringResource(R.string.jasiri_ready_bt_why),
                required = false,
                status = s.bluetooth,
                actionLabel = stringResource(R.string.jasiri_ready_turn_on),
                onAction = actions.turnOnBluetooth
            )
            ReadyRow(
                icon = Icons.Filled.LocationOn,
                title = stringResource(R.string.jasiri_ready_loc_title),
                why = stringResource(R.string.jasiri_ready_loc_why),
                required = false,
                status = s.locationServices,
                actionLabel = stringResource(R.string.jasiri_ready_turn_on),
                onAction = actions.turnOnLocation
            )
            val canRequestBackground = canRequestBackgroundLocation(s)
            ReadyRow(
                icon = Icons.Filled.Sync,
                title = stringResource(R.string.jasiri_ready_bg_title),
                why = stringResource(R.string.jasiri_ready_bg_why),
                required = false,
                status = s.backgroundLocation,
                actionLabel = stringResource(
                    if (canRequestBackground) R.string.jasiri_ready_allow else R.string.jasiri_ready_after_permissions
                ),
                actionEnabled = canRequestBackground,
                onAction = actions.allowBackgroundLocation
            )
            ReadyRow(
                icon = Icons.Filled.BatteryStd,
                title = stringResource(R.string.jasiri_ready_battery_title),
                why = stringResource(R.string.jasiri_ready_battery_why),
                required = false,
                status = s.battery,
                actionLabel = stringResource(R.string.jasiri_ready_allow),
                onAction = actions.allowBattery
            )
        }

        HorizontalDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = if (primary == PrimaryAction.START) actions.start else actions.allowPermissions,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = stringResource(
                        if (primary == PrimaryAction.START) R.string.jasiri_ready_start else R.string.jasiri_ready_allow_permissions
                    ),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (primary == PrimaryAction.START && !allReady(s)) {
                Text(
                    text = stringResource(R.string.jasiri_ready_later_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ReadyRow(
    icon: ImageVector,
    title: String,
    why: String,
    required: Boolean,
    status: ItemStatus,
    actionLabel: String,
    onAction: () -> Unit,
    actionEnabled: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (status == ItemStatus.OK) Icons.Filled.CheckCircle else icon,
                contentDescription = null,
                tint = if (status == ItemStatus.OK) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                Text(
                    text = why,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                RowTag(required = required)
            }
            Spacer(Modifier.width(8.dp))
            when (status) {
                ItemStatus.OK -> Text(
                    text = stringResource(R.string.jasiri_ready_ok),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )

                ItemStatus.NOT_AVAILABLE -> Text(
                    text = stringResource(R.string.jasiri_ready_not_available),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface.copy(alpha = 0.5f)
                )

                ItemStatus.TODO -> if (actionEnabled) {
                    FilledTonalButton(onClick = onAction) { Text(actionLabel) }
                } else {
                    OutlinedButton(onClick = onAction, enabled = false) { Text(actionLabel) }
                }
            }
        }
    }
}

@Composable
private fun RowTag(required: Boolean) {
    val colors = MaterialTheme.colorScheme
    val tagColor = if (required) colors.primary else colors.onSurfaceVariant
    Box(
        modifier = Modifier
            .padding(top = 2.dp)
            .border(1.dp, tagColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            text = stringResource(if (required) R.string.jasiri_ready_required else R.string.jasiri_ready_recommended),
            style = MaterialTheme.typography.labelSmall,
            color = tagColor
        )
    }
}
