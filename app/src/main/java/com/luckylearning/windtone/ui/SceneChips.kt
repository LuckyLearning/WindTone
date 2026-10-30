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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.playback.SceneMode
import com.luckylearning.windtone.playback.SceneProfiles

/**
 * 场景芯片的 string 资源映射（纯函数，便于单测；与 [SceneChipsRow] 一致）。
 *
 * 标签 / 说明均走 values + values-zh；id 与 [SceneProfiles] 对齐。
 */
object SceneChipLabels {
    data class Chip(
        val id: String,
        val labelRes: Int,
        val descRes: Int,
    )

    /** 三档顺序与 [SceneProfiles.chipIds] 一致。 */
    fun chips(): List<Chip> = listOf(
        Chip(SceneProfiles.DRIVE.id, R.string.scene_drive, R.string.scene_drive_desc),
        Chip(SceneProfiles.COMMUTE.id, R.string.scene_commute, R.string.scene_commute_desc),
        Chip(SceneProfiles.SLEEP.id, R.string.scene_sleep, R.string.scene_sleep_desc),
    )

    fun labelResOf(id: String): Int? = chips().firstOrNull { it.id == id }?.labelRes

    fun descResOf(id: String): Int? = chips().firstOrNull { it.id == id }?.descRes
}

/**
 * 场景化模式芯片行：驾车 / 通勤 / 助眠 + 关闭。
 *
 * [large] 给车机用：更高触控目标（约 56dp）。
 * 说明文案挂在 contentDescription（读屏）；可见 hint 仍用 [R.string.scene_hint]。
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
            SceneChipLabels.chips().forEach { chip ->
                val label = stringResource(chip.labelRes)
                val desc = stringResource(chip.descRes)
                val a11y = stringResource(R.string.scene_chip_a11y, label, desc)
                FilterChip(
                    selected = activeId == chip.id,
                    onClick = { SceneMode.select(chip.id) },
                    label = { Text(label, style = textStyle) },
                    modifier = Modifier
                        .heightIn(min = chipHeight)
                        .semantics { contentDescription = a11y },
                )
            }
            val offLabel = stringResource(R.string.scene_off)
            val offDesc = stringResource(R.string.scene_off_desc)
            val offA11y = stringResource(R.string.scene_chip_a11y, offLabel, offDesc)
            FilterChip(
                selected = activeId.isEmpty(),
                onClick = { SceneMode.select("") },
                label = { Text(offLabel, style = textStyle) },
                modifier = Modifier
                    .heightIn(min = chipHeight)
                    .semantics { contentDescription = offA11y },
            )
        }
    }
}