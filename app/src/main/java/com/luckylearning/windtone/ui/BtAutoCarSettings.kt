package com.luckylearning.windtone.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.luckylearning.windtone.MusicApp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.playback.BtAutoCarPolicy

/**
 * G7：外观 → 车载分区下的「蓝牙自动车机」块。
 * 直接读写 Prefs + [PlaybackEngine] 的同步方法，避免把一串回调穿过 SettingsScreen。
 */
@Composable
internal fun BtAutoCarSettingsSection() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val engine = remember(context) {
        (context.applicationContext as MusicApp).container.playback
    }

    var enabled by remember { mutableStateOf(prefs.btAutoCarEnabled) }
    var anyDevice by remember { mutableStateOf(prefs.btAutoCarAnyDevice) }
    var restore by remember { mutableStateOf(prefs.btAutoCarRestore) }
    var devicesRaw by remember { mutableStateOf(prefs.btAutoCarDevices) }
    var showInfo by remember { mutableStateOf(false) }
    var permTick by remember { mutableStateOf(0) }

    val hasConnectPerm = remember(permTick) { hasBluetoothConnectPermission(context) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permTick++ }

    SwitchRow(
        title = stringResource(R.string.bt_auto_car_enabled),
        subtitle = stringResource(R.string.bt_auto_car_enabled_sub),
        checked = enabled,
        onCheckedChange = {
            enabled = it
            engine.setBtAutoCarEnabled(it)
        },
        onInfo = { showInfo = true },
        onReset = resetAction(enabled != SettingsDefaults.BT_AUTO_CAR_ENABLED) {
            enabled = SettingsDefaults.BT_AUTO_CAR_ENABLED
            engine.setBtAutoCarEnabled(SettingsDefaults.BT_AUTO_CAR_ENABLED)
        },
    )

    if (!enabled) {
        if (showInfo) {
            BtAutoCarInfoDialog(onDismiss = { showInfo = false })
        }
        return
    }

    SwitchRow(
        title = stringResource(R.string.bt_auto_car_restore),
        subtitle = stringResource(R.string.bt_auto_car_restore_sub),
        checked = restore,
        onCheckedChange = {
            restore = it
            engine.setBtAutoCarRestore(it)
        },
        onReset = resetAction(restore != SettingsDefaults.BT_AUTO_CAR_RESTORE) {
            restore = SettingsDefaults.BT_AUTO_CAR_RESTORE
            engine.setBtAutoCarRestore(SettingsDefaults.BT_AUTO_CAR_RESTORE)
        },
    )

    SwitchRow(
        title = stringResource(R.string.bt_auto_car_any),
        subtitle = if (anyDevice) {
            stringResource(R.string.bt_auto_car_any_sub)
        } else {
            stringResource(R.string.bt_auto_car_remembered_only)
        },
        checked = anyDevice,
        onCheckedChange = {
            anyDevice = it
            engine.setBtAutoCarAnyDevice(it)
        },
        onReset = resetAction(anyDevice != SettingsDefaults.BT_AUTO_CAR_ANY_DEVICE) {
            anyDevice = SettingsDefaults.BT_AUTO_CAR_ANY_DEVICE
            engine.setBtAutoCarAnyDevice(SettingsDefaults.BT_AUTO_CAR_ANY_DEVICE)
        },
    )

    if (!anyDevice) {
        Text(
            text = stringResource(R.string.bt_auto_car_devices_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )

        if (!hasConnectPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Text(
                text = stringResource(R.string.bt_auto_car_need_permission),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            TextButton(onClick = {
                permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }) {
                Text(stringResource(R.string.bt_auto_car_grant_permission))
            }
        }

        val devices = remember(devicesRaw) { BtAutoCarPolicy.decodeDevices(devicesRaw) }
        if (devices.isEmpty()) {
            Text(
                text = stringResource(R.string.bt_auto_car_empty_devices),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                for (d in devices) {
                    RowSetting(
                        title = d.displayName,
                        subtitle = d.id,
                        onRemove = {
                            engine.removeBtAutoCarDevice(d.id)
                            devicesRaw = prefs.btAutoCarDevices
                        },
                    )
                }
            }
        }

        TextButton(onClick = {
            val before = prefs.btAutoCarDevices
            engine.addConnectedBtDevicesToRemembered()
            devicesRaw = prefs.btAutoCarDevices
            val msg = if (devicesRaw == before) {
                context.getString(R.string.bt_auto_car_added_none)
            } else {
                context.getString(R.string.bt_auto_car_added_ok)
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }) {
            Text(stringResource(R.string.bt_auto_car_add_connected))
        }
    }

    if (showInfo) {
        BtAutoCarInfoDialog(onDismiss = { showInfo = false })
    }
}

@Composable
private fun RowSetting(
    title: String,
    subtitle: String,
    onRemove: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRemove) {
            Text(stringResource(R.string.bt_auto_car_remove))
        }
    }
}

@Composable
private fun BtAutoCarInfoDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bt_auto_car_enabled)) },
        text = {
            Text(
                stringResource(R.string.bt_auto_car_info),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.got_it))
            }
        },
    )
}

private fun hasBluetoothConnectPermission(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.BLUETOOTH_CONNECT,
    ) == PackageManager.PERMISSION_GRANTED
}