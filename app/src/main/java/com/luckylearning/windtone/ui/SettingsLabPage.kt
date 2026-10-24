package com.luckylearning.windtone.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
    SectionTitle("实验室功能")
    Text(
        "这里放还没有定型的功能：先跑一段时间，确认稳定、不会误伤日常使用之后，" +
            "再移到正式位置。它们依然是正式实现，只是入口先放在这里。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )

    ActionRow(
        title = "在线播放",
        subtitle = "在线歌单 · 播放缓存 · 收听报告（阿里云盘已升为抽屉一级入口）",
        onClick = onOpenOnline,
        trailing = "实验",
        chevron = true,
    )

    ActionRow(
        title = "调整菜单顺序",
        subtitle = "拖动不顺手时用上移 / 下移排列主页分类卡",
        onClick = onOpenMenuOrder,
        trailing = "实验",
        chevron = true,
    )
}
