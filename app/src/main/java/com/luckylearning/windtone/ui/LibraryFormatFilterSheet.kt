package com.luckylearning.windtone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.LIBRARY_FORMAT_FILTER_EXT_CHIPS
import com.luckylearning.windtone.data.LibraryFormatFilter
import com.luckylearning.windtone.data.normalizeAudioExtension

/**
 * 曲库格式筛选底栏（H5）：仅无损开关 + 扩展名多选芯片，两维取交集。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun LibraryFormatFilterSheet(
    filter: LibraryFormatFilter,
    onChange: (LibraryFormatFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    AppSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.lib_format_filter_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.lib_format_filter_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilterChip(
                selected = filter.losslessOnly,
                onClick = { onChange(filter.copy(losslessOnly = !filter.losslessOnly)) },
                label = { Text(stringResource(R.string.lib_format_filter_lossless)) },
            )
            Text(
                text = stringResource(R.string.lib_format_filter_ext_section),
                style = MaterialTheme.typography.titleSmall,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LIBRARY_FORMAT_FILTER_EXT_CHIPS.forEach { raw ->
                    val ext = normalizeAudioExtension(raw)
                    val selected = ext in filter.extensions
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val next = if (selected) filter.extensions - ext else filter.extensions + ext
                            onChange(filter.copy(extensions = next))
                        },
                        label = { Text(ext.uppercase()) },
                    )
                }
            }
            if (filter.isActive) {
                TextButton(onClick = { onChange(LibraryFormatFilter.NONE) }) {
                    Text(stringResource(R.string.lib_format_filter_clear))
                }
            }
        }
    }
}
