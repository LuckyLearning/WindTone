package com.luckylearning.windtone.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.R

// ================================================================ 弹层（保持原有逻辑）

@Composable
internal fun PlaylistFilesDialog(
    playlist: com.luckylearning.windtone.data.aliyun.AliyunPlaylist,
    query: String,
    onMove: (Int, Int) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val files = playlist.files.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(playlist.name) },
        text = {
            Column {
                files.forEachIndexed { index, hit ->
                    val realIndex = playlist.files.indexOfFirst { it.fileId == hit.fileId }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(hit.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(enabled = realIndex > 0, onClick = { onMove(realIndex, realIndex - 1) }) { Text(stringResource(R.string.aliyun_move_up)) }
                        TextButton(
                            enabled = realIndex in 0 until playlist.files.lastIndex,
                            onClick = { onMove(realIndex, realIndex + 1) },
                        ) { Text(stringResource(R.string.aliyun_move_down)) }
                        TextButton(onClick = { onRemove(hit.fileId) }) { Text(stringResource(R.string.aliyun_remove)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}

/** 粘贴 refresh_token 的弹层（OpenList 方案的唯一登录动作） */
@Composable
internal fun PasteTokenDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.aliyun_paste_token_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.aliyun_paste_token_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.trim() },
                    label = { Text("refresh_token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank(),
                onClick = { onConfirm(input) },
            ) { Text(stringResource(R.string.aliyun_sign_in)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 扫描完成后给歌单命名（默认用扫描根目录名） */
@Composable
internal fun NameScanDialog(
    defaultName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.aliyun_save_as_playlist)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.aliyun_save_playlist_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.aliyun_playlist_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank(),
                onClick = { onConfirm(input) },
            ) { Text(stringResource(R.string.aliyun_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * 调起本机阿里云盘 App。
 *
 * 先按包名拉起（`com.alicloud.databox`），失败再退回 `aliyunpan://` scheme ——
 * 双保险（App 未装则走系统浏览器）。
 */
internal fun openAliyunPan(context: Context) {
    val byPackage = Intent().apply {
        setClassName("com.alicloud.databox", "com.alicloud.databox.main.MainActivity")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val started = runCatching { context.startActivity(byPackage) }.isSuccess ||
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("aliyunpan://")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.isSuccess
    if (!started) {
        android.widget.Toast.makeText(context, context.getString(R.string.aliyun_toast_app_missing), android.widget.Toast.LENGTH_SHORT).show()
    }
}
