package com.jasiri.quick.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.R
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.ui.ComposerActionSurface
import com.bitchat.android.ui.ComposerIconSize
import com.jasiri.quick.JasiriQuick
import com.jasiri.quick.QuickCatalog
import com.jasiri.quick.QuickFeedEntry
import com.jasiri.quick.QuickPreset
import com.jasiri.quick.QuickRuntime
import com.jasiri.quick.QuickSendStatus
import com.jasiri.quick.QuickTone
import com.jasiri.sos.location.SosLocationSource
import com.jasiri.sos.location.toSosLocationOrNull
import com.jasiri.sos.ui.HeardAgo
import com.jasiri.sos.ui.accuracyOrNull
import com.jasiri.sos.ui.formatCoords
import com.jasiri.sos.ui.geoUri
import com.jasiri.sos.ui.heardAgo
import com.jasiri.sos.ui.peerLabel
import kotlinx.coroutines.delay
import java.util.Locale

private val QuickRed = Color(0xFFD32F2F)
private val QuickAmber = Color(0xFFFFA000)

/** Composer button that opens the quick message sheet. Public mesh chat only (decided by the caller). */
@Composable
fun QuickGridButton(modifier: Modifier = Modifier) {
    val runtime = remember { JasiriQuick.runtime }
    val unread by runtime.unreadCount.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    var includeLocation by rememberSaveable { mutableStateOf(true) }
    val description = stringResource(R.string.jasiri_quick_cd_open)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(modifier = modifier) {
        ComposerActionSurface(
            isActive = unread > 0,
            isPressed = isPressed,
            contentDescription = description,
            modifier = Modifier.clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button
            ) {
                open = true
                runtime.markAllRead()
            }
        ) { tint ->
            Canvas(
                modifier = Modifier
                    .size(ComposerIconSize)
                    .padding(2.dp)
            ) {
                val gap = size.minDimension * 0.16f
                val cell = (size.minDimension - gap) / 2f
                val corner = CornerRadius(cell * 0.3f)
                for (row in 0..1) {
                    for (col in 0..1) {
                        drawRoundRect(
                            color = tint,
                            topLeft = Offset(col * (cell + gap), row * (cell + gap)),
                            size = Size(cell, cell),
                            cornerRadius = corner
                        )
                    }
                }
            }
        }
        if (unread > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(QuickRed)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeText(unread),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    if (open) {
        QuickSheet(
            runtime = runtime,
            includeLocation = includeLocation,
            onIncludeLocationChange = { includeLocation = it },
            onDismiss = { open = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickSheet(
    runtime: QuickRuntime,
    includeLocation: Boolean,
    onIncludeLocationChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        QuickSheetContent(
            runtime = runtime,
            includeLocation = includeLocation,
            onIncludeLocationChange = onIncludeLocationChange
        )
    }
}

@Composable
private fun QuickSheetContent(
    runtime: QuickRuntime,
    includeLocation: Boolean,
    onIncludeLocationChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val feed by runtime.feed.collectAsStateWithLifecycle()
    val unread by runtime.unreadCount.collectAsStateWithLifecycle()
    val secondaryLang = remember {
        secondaryLanguage(Locale.getDefault().language, QuickCatalog.completeLanguages)
    }
    val source = remember { SosLocationSource(context) }
    val nicknames = remember(feed) {
        try {
            MeshServiceHolder.unifiedMeshService?.getPeerNicknames()
        } catch (_: Exception) {
            null
        } ?: emptyMap()
    }
    val rateLimitedMessage = stringResource(R.string.jasiri_quick_rate_limited)

    LaunchedEffect(unread) {
        if (unread > 0) runtime.markAllRead()
    }

    val pending = nextPending(feed)
    val ticking = pending != null
    val now by produceState(initialValue = System.currentTimeMillis(), ticking) {
        while (true) {
            value = System.currentTimeMillis()
            delay(if (ticking) 250L else 15_000L)
        }
    }

    fun onTileTap(preset: QuickPreset) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val location = if (includeLocation && preset.wantsLocation && source.hasPermission()) {
            source.lastKnown()
                ?.toSosLocationOrNull(
                    nowMillis = System.currentTimeMillis(),
                    approximate = !source.hasFinePermission()
                )
                ?.toQuickLocation()
        } else {
            null
        }
        if (runtime.queue(preset.id, location) == null) {
            showToast(context, rateLimitedMessage)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (pending != null) {
            UndoBar(
                entry = pending,
                now = now,
                onUndo = { runtime.undo(pending.key) }
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.jasiri_quick_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.jasiri_quick_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item(key = "location", span = { GridItemSpan(maxLineSpan) }) {
                IncludeLocationRow(checked = includeLocation, onCheckedChange = onIncludeLocationChange)
            }

            items(QuickCatalog.builtIn, key = { "preset-${it.id}" }) { preset ->
                val text = tileText(preset.id, secondaryLang) ?: TileText(preset.en, null)
                QuickTile(preset = preset, text = text, onTap = { onTileTap(preset) })
            }

            item(key = "feed-header", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(R.string.jasiri_quick_feed_header),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            if (feed.isEmpty()) {
                item(key = "feed-empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.jasiri_quick_feed_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(feed, key = { "feed-${it.key}" }, span = { GridItemSpan(maxLineSpan) }) { entry ->
                    FeedRow(
                        entry = entry,
                        now = now,
                        secondaryLang = secondaryLang,
                        nicknames = nicknames,
                        runtime = runtime
                    )
                }
            }
        }
    }
}

@Composable
private fun UndoBar(entry: QuickFeedEntry, now: Long, onUndo: () -> Unit) {
    val label = tileText(entry.presetId, null)?.primary ?: unknownPresetText(entry.presetId)
    val secondsLeft = undoSecondsLeft(entry.undoDeadlineMillis ?: now, now)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(QuickAmber)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.jasiri_quick_sending_in, label, secondsLeft),
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = onUndo,
            colors = ButtonDefaults.textButtonColors(contentColor = Color.Black)
        ) {
            Text(
                text = stringResource(R.string.jasiri_quick_undo),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
    }
}

@Composable
private fun IncludeLocationRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.jasiri_quick_include_location),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = checked, onCheckedChange = null)
        }
        Text(
            text = stringResource(R.string.jasiri_quick_include_location_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun QuickTile(preset: QuickPreset, text: TileText, onTap: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val background: Color
    val primaryColor: Color
    val secondaryColor: Color
    val borderColor: Color?
    when (preset.tone) {
        QuickTone.INFO -> {
            background = MaterialTheme.colorScheme.surfaceVariant
            primaryColor = onSurface
            secondaryColor = onSurface.copy(alpha = 0.8f)
            borderColor = onSurface.copy(alpha = 0.25f)
        }
        QuickTone.NEED -> {
            background = QuickAmber
            primaryColor = Color.Black
            secondaryColor = Color.Black
            borderColor = null
        }
        QuickTone.WARNING -> {
            background = QuickRed
            primaryColor = Color.White
            secondaryColor = Color.White
            borderColor = null
        }
    }
    val shape = RoundedCornerShape(16.dp)
    val description = listOfNotNull(text.primary, text.secondary).joinToString(". ")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .clip(shape)
            .background(background)
            .then(if (borderColor != null) Modifier.border(1.dp, borderColor, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onTap)
            .clearAndSetSemantics { contentDescription = description }
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = text.primary,
                color = primaryColor,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            text.secondary?.let {
                Text(
                    text = it,
                    color = secondaryColor,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun FeedRow(
    entry: QuickFeedEntry,
    now: Long,
    secondaryLang: String?,
    nicknames: Map<String, String>,
    runtime: QuickRuntime
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val preset = QuickCatalog.byId(entry.presetId)
    val text = tileText(entry.presetId, secondaryLang)
    val barColor = when (preset?.tone) {
        QuickTone.INFO -> colorScheme.primary
        QuickTone.NEED -> QuickAmber
        QuickTone.WARNING -> QuickRed
        null -> colorScheme.outline
    }
    val retryFailedMessage = stringResource(R.string.jasiri_quick_retry_failed)
    val noMapMessage = stringResource(R.string.jasiri_quick_no_map_app)
    val who = if (entry.mine) {
        stringResource(R.string.jasiri_quick_you)
    } else {
        peerLabel(entry.senderPeerID ?: "", nicknames)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(12.dp))
            .background(colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Box(
            modifier = Modifier
                .width(6.dp)
                .fillMaxHeight()
                .background(barColor)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = text?.primary ?: unknownPresetText(entry.presetId),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            text?.secondary?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = stringResource(
                    R.string.jasiri_quick_heard,
                    who,
                    heardAgoText(heardAgo(entry.receivedAtMillis, now))
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )

            entry.location?.let { quickLocation ->
                val location = quickLocation.toSosLocation()
                val accuracy = accuracyOrNull(location)
                val line = buildString {
                    append(formatCoords(location))
                    if (accuracy != null) append(" · ±").append(accuracy).append(" m")
                }
                val approximate = if (location.approximate) stringResource(R.string.jasiri_sos_loc_approximate) else ""
                Text(
                    text = line + approximate,
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(geoUri(location))))
                    } catch (_: ActivityNotFoundException) {
                        showToast(context, noMapMessage)
                    } catch (_: SecurityException) {
                        showToast(context, noMapMessage)
                    }
                }) {
                    Text(stringResource(R.string.jasiri_quick_open_map))
                }
            }

            if (entry.mine) {
                when (entry.status) {
                    QuickSendStatus.PENDING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(
                                R.string.jasiri_quick_status_pending,
                                undoSecondsLeft(entry.undoDeadlineMillis ?: now, now)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { runtime.undo(entry.key) }) {
                            Text(stringResource(R.string.jasiri_quick_undo))
                        }
                    }

                    QuickSendStatus.SENT -> Text(
                        text = stringResource(R.string.jasiri_quick_status_sent),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )

                    QuickSendStatus.NOT_SENT -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.jasiri_quick_status_not_sent),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            if (!runtime.retry(entry.key)) showToast(context, retryFailedMessage)
                        }) {
                            Text(stringResource(R.string.jasiri_quick_retry))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun heardAgoText(h: HeardAgo): String = when (h) {
    HeardAgo.JustNow -> stringResource(R.string.jasiri_sos_just_now)
    is HeardAgo.Minutes -> stringResource(R.string.jasiri_sos_minutes_ago, h.n)
    is HeardAgo.Hours -> stringResource(R.string.jasiri_sos_hours_ago, h.n)
}

private fun showToast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
