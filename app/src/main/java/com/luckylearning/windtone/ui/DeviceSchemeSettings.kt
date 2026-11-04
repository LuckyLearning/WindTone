package com.luckylearning.windtone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.MusicApp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.playback.DeviceSchemeMonitor
import com.luckylearning.windtone.playback.DeviceSchemePolicy
import com.luckylearning.windtone.playback.SoundSchemes

/**
 * 「音效按输出设备自动挂档」（竞品借鉴：Salt Player / Symfonium）。
 *
 * 形态沿用 [BtAutoCarSettingsSection]：自包含，直接读 Prefs + 引擎同步方法，
 * 不把一串回调穿过 SettingsScreen。
 *
 * 用户路径：打开总开关 → 点「设备绑定」→ 选当前接入的设备 → 选一条音效方案。
 * 之后插上该设备自动套用，拔掉还原。车载模式下不介入（车载有自己的音效档案）。
 */
@Composable
internal fun DeviceSchemeSettingsSection() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val engine = remember(context) {
        (context.applicationContext as MusicApp).container.playback
    }

    var enabled by remember { mutableStateOf(prefs.deviceSchemeEnabled) }
    var bindingsRaw by remember { mutableStateOf(prefs.deviceSchemeBindings) }
    var showDialog by remember { mutableStateOf(false) }
    // 正在为哪台设备挑方案（null = 不在挑）
    var pickingKey by remember { mutableStateOf<String?>(null) }

    val bindings = remember(bindingsRaw) { DeviceSchemePolicy.decode(bindingsRaw) }

    SwitchRow(
        title = stringResource(R.string.device_scheme_enabled),
        subtitle = stringResource(R.string.device_scheme_enabled_sub),
        checked = enabled,
        onCheckedChange = {
            enabled = it
            engine.setDeviceSchemeEnabled(it)
        },
        onReset = resetAction(enabled != SettingsDefaults.DEVICE_SCHEME_ENABLED) {
            enabled = SettingsDefaults.DEVICE_SCHEME_ENABLED
            engine.setDeviceSchemeEnabled(SettingsDefaults.DEVICE_SCHEME_ENABLED)
        },
    )

    ActionRow(
        title = stringResource(R.string.device_scheme_bindings),
        subtitle = if (bindings.isEmpty()) {
            stringResource(R.string.device_scheme_no_bindings)
        } else {
            stringResource(R.string.device_scheme_bound_count, bindings.size)
        },
        onClick = { showDialog = true },
        chevron = true,
        onReset = resetAction(bindingsRaw.isNotEmpty()) {
            bindingsRaw = SettingsDefaults.DEVICE_SCHEME_BINDINGS
            engine.setDeviceSchemeBindingsRaw(SettingsDefaults.DEVICE_SCHEME_BINDINGS)
        },
    )

    if (showDialog) {
        val connected = remember { DeviceSchemeMonitor.connectedOutputRefs(context) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.device_scheme_bindings)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.device_scheme_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    connected.forEach { (key, label) ->
                        BindingRow(
                            label = label,
                            schemeName = bindings[key].let { id ->
                                id?.let { SoundSchemes.byId(it)?.name } ?: stringResource(R.string.device_scheme_unbound)
                            },
                            onClick = { pickingKey = key },
                        )
                    }
                    // 已绑定但当前没接入的设备：仍可删除，避免「拔了就再也管不到」
                    val connectedKeys = connected.map { it.first }.toSet()
                    bindings.filterKeys { it !in connectedKeys }.forEach { (key, schemeId) ->
                        BindingRow(
                            label = key.removePrefix("bt:"),
                            schemeName = SoundSchemes.byId(schemeId)?.name ?: schemeId,
                            onClick = null,
                            onRemove = {
                                val next = bindings - key
                                bindingsRaw = DeviceSchemePolicy.encode(next)
                                engine.setDeviceSchemeBindingsRaw(bindingsRaw)
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.ok)) }
            },
        )
    }

    pickingKey?.let { key ->
        var selectedId by remember(key) { mutableStateOf(bindings[key].orEmpty()) }
        AlertDialog(
            onDismissRequest = { pickingKey = null },
            title = { Text(stringResource(R.string.device_scheme_choose)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { selectedId = "" }) {
                        Text(
                            stringResource(R.string.device_scheme_unbound) +
                                if (selectedId.isEmpty()) "  ✓" else "",
                        )
                    }
                    SoundSchemes.ALL.forEach { scheme ->
                        TextButton(onClick = { selectedId = scheme.id }) {
                            Text(scheme.name + if (selectedId == scheme.id) "  ✓" else "")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val next = if (selectedId.isEmpty()) bindings - key else bindings + (key to selectedId)
                        bindingsRaw = DeviceSchemePolicy.encode(next)
                        engine.setDeviceSchemeBindingsRaw(bindingsRaw)
                        pickingKey = null
                    },
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingKey = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun BindingRow(
    label: String,
    schemeName: String,
    onClick: (() -> Unit)?,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                schemeName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onRemove != null) {
            TextButton(onClick = onRemove) { Text(stringResource(R.string.delete)) }
        }
    }
}
