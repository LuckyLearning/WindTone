package com.luckylearning.windtone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.playback.SceneMode
import com.luckylearning.windtone.playback.SceneProfiles

/**
 * 场景化模式芯片行：驾车 / 通勤 / 助眠 + 关闭。
 *
 * [large] 给车机用：更高触控目标（约 56dp）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SceneChipsRow(
    large: Boolean = false,
    showHint: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val activeId by SceneMode.activeId.collectAsState()
    val chipHeight = if (large) 56.dp else 36.dp
    val textStyle = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge

    Column(modifier.fillMaxWidth()) {
        if (showHint) {
            Text(
                text = stringResource(R.string.scene_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SceneProfiles.chipIds().forEach { id ->
                val labelRes = when (id) {
                    SceneProfiles.DRIVE.id -> R.string.scene_drive
                    SceneProfiles.COMMUTE.id -> R.string.scene_commute
                    SceneProfiles.SLEEP.id -> R.string.scene_sleep
                    else -> return@forEach
                }
                FilterChip(
                    selected = activeId == id,
                    onClick = { SceneMode.select(id) },
                    label = { Text(stringResource(labelRes), style = textStyle) },
                    modifier = Modifier.heightIn(min = chipHeight),
                )
            }
            FilterChip(
                selected = activeId.isEmpty(),
                onClick = { SceneMode.select("") },
                label = { Text(stringResource(R.string.scene_off), style = textStyle) },
                modifier = Modifier.heightIn(min = chipHeight),
            )
        }
    }
}
