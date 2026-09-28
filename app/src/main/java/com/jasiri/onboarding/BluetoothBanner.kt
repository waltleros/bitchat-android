package com.jasiri.onboarding

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bitchat.android.R

enum class BtBannerState { HIDDEN, OFF, UNSUPPORTED }

/** adapterPresent=false -> UNSUPPORTED; enabled=true -> HIDDEN; else OFF. */
fun btBannerState(adapterPresent: Boolean, enabled: Boolean): BtBannerState = when {
    !adapterPresent -> BtBannerState.UNSUPPORTED
    enabled -> BtBannerState.HIDDEN
    else -> BtBannerState.OFF
}

private val BannerAmber = Color(0xFFFFA000)
private val BannerGrey = Color(0xFF616161)

/** Non-blocking Bluetooth warning for the SOS page and the Quick sheet. Renders nothing while Bluetooth is on. */
@Composable
fun BluetoothBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(readBtBannerState(context)) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                state = readBtBannerState(context)
            }
        }
        val registered = try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            true
        } catch (_: Exception) {
            false
        }
        state = readBtBannerState(context)
        onDispose {
            if (registered) {
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: Exception) {
                }
            }
        }
    }

    LaunchedEffect(state) {
        if (state == BtBannerState.HIDDEN) BluetoothSkipStore.clear(context)
    }

    when (state) {
        BtBannerState.HIDDEN -> Unit

        BtBannerState.OFF -> Row(
            modifier = modifier
                .fillMaxWidth()
                .background(BannerAmber)
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.jasiri_bt_off),
                color = Color.Black,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { requestBluetoothOn(context) },
                colors = ButtonDefaults.textButtonColors(contentColor = Color.Black)
            ) {
                Text(stringResource(R.string.jasiri_bt_turn_on), fontWeight = FontWeight.Bold)
            }
        }

        BtBannerState.UNSUPPORTED -> Row(
            modifier = modifier
                .fillMaxWidth()
                .background(BannerGrey)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.jasiri_bt_unsupported),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun readBtBannerState(context: Context): BtBannerState {
    val adapter = try {
        context.getSystemService(BluetoothManager::class.java)?.adapter
    } catch (_: Exception) {
        null
    }
    val enabled = try {
        adapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        false
    }
    return btBannerState(adapterPresent = adapter != null, enabled = enabled)
}

private fun requestBluetoothOn(context: Context) {
    try {
        context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        return
    } catch (_: SecurityException) {
    } catch (_: ActivityNotFoundException) {
    }
    try {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    } catch (_: Exception) {
    }
}
