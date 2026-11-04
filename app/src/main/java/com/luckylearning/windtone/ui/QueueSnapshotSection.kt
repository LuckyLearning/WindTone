package com.luckylearning.windtone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.luckylearning.windtone.playback.QueueSnapshots
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放队列弹层里的「队列快照」区（竞品借鉴：Musicolet 多队列 / Symfonium）。
 *
 * 自包含：直接读写 Prefs + 引擎（与 `BtAutoCarSettingsSection` /
 * `DeviceSchemeSettingsSection` 同一先例），队列弹层只多一行调用。
 *
 * 交互：保存当前队列（命名，同名覆盖）→ 列表里点一下恢复（含当前曲与进度），
 * 右侧删除。快照只存曲目主键，恢复时自动跳过已删除 / 丢失的歌。
 */
@Composable
internal fun QueueSnapshotsSection() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val engine = remember(context) { (context.applicationContext as MusicApp).container.playback }

    var raw by remember { mutableStateOf(prefs.queueSnapshots) }
    val snapshots = remember(raw) { QueueSnapshots.decode(raw) }
    var showSave by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        Text(
            stringResource(R.string.qs_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        TextButton(onClick = {
            nameInput = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).let { "队列 ${it.format(Date())}" }
            showSave = true
        }) { Text(stringResource(R.string.qs_save_current)) }
    }

    if (snapshots.isEmpty()) {
        Text(
            stringResource(R.string.qs_empty),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
        )
    } else {
        Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
            snapshots.forEach { snap ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            engine.restoreQueueSnapshot(snap.name)
                        }
                        .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(snap.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(
                            stringResource(R.string.qs_song_count, snap.songIds.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        engine.deleteQueueSnapshot(snap.name)
                        raw = prefs.queueSnapshots
                    }) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }

    if (showSave) {
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text(stringResource(R.string.qs_save_current)) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it.take(40) },
                    label = { Text(stringResource(R.string.qs_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (nameInput.isNotBlank()) {
                            engine.saveQueueSnapshot(nameInput)
                            raw = prefs.queueSnapshots
                        }
                        showSave = false
                    },
                    enabled = nameInput.isNotBlank(),
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showSave = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
