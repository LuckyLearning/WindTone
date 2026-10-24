package com.luckylearning.windtone.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.R

/**
 * 设置子页：实验室功能（v1.1.80 新增）。
 *
 * 口径（用户）：**还没定型的功能先放进来跑一段时间**，确认可用、不会误伤老用户之后，
 * 再升成主页分类卡或并进对应分类。首批收纳：
 *  - 在线播放（缓存上限 / 收听报告仍在打磨，且依赖云盘接口的稳定性）
 *  - 调整菜单顺序（纯个性化排序，用得少）
 *
 * 与正式分类的区别只体现在文案上，不搞另一套交互 —— 用户不该为了「实验」多学一遍。
 */
@Composable
internal fun SettingsLabPage(
    onOpenOnline: () -> Unit,
    onOpenMenuOrder: () -> Unit,
) {
    SectionTitle(stringResource(R.string.set_lab_title))
    Text(
        stringResource(R.string.set_lab_desc),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )

    ActionRow(
        title = stringResource(R.string.set_lab_online_title),
        subtitle = stringResource(R.string.set_lab_online_subtitle),
        onClick = onOpenOnline,
        trailing = stringResource(R.string.set_tag_experimental),
        chevron = true,
    )

    ActionRow(
        title = stringResource(R.string.set_lab_menu_order_title),
        subtitle = stringResource(R.string.set_lab_menu_order_subtitle),
        onClick = onOpenMenuOrder,
        trailing = stringResource(R.string.set_tag_experimental),
        chevron = true,
    )
}
