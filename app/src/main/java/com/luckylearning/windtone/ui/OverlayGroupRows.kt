package com.luckylearning.windtone.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.overlay.A11yRuntime
import com.luckylearning.windtone.overlay.OverlayCollapseHelper
import com.luckylearning.windtone.state.PlayerUiState
import com.luckylearning.windtone.ui.theme.AppVisual
import com.luckylearning.windtone.ui.theme.windShadow

/**
 * 悬浮窗设置页的**可折叠分组**（v1.2.54）。
 *
 * 为什么不再用一个「高级参数」大开关兜住三十多项：那样一旦展开就是一屏滑杆，
 * 找「标题颜色」得从头翻到尾；而且用户改完一项往往还要回看同类项，
 * 展开范围越大越容易滚丢位置。按语义拆成若干组、各自收展之后，
 * 每组只占一行高度，展开的也只是一小段。
 *
 * 收起时副标题仍然报出该组的关键当前值（例如「上方 · 居中 · 12 磅」），
 * 这样**不展开也能确认设置是不是自己想要的那档**。
 *
 * **一张组 = 一张卡**（v1.2.54 第二版）：整页只剩「主开关 + 几张折叠卡」，
 * 不再是一长串行首尾相接的通铺列表。卡内的行由 [LocalSettingsRowFlat]
 * 切成透明底 —— 否则 [ActionRow] 自带的卡面会在组卡里再套一层小卡，
 * 边框与阴影叠起来比平铺更花。
 */
@Composable
internal fun OverlayGroup(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    // 展开 / 收起的箭头转 180°，给一次明确的状态反馈（180ms，与全站转场同速）
    val arrowAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(AppVisual.Motion.FastMs),
        label = "overlayGroupArrow",
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .windShadow(level = 1, shape = shape),
    ) {
        Column(Modifier.padding(horizontal = 14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onToggle)
                    .padding(top = 12.dp, bottom = if (expanded) 4.dp else 12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.collapse else R.string.expand,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = arrowAngle },
                )
            }
            if (expanded) {
                CompositionLocalProvider(LocalSettingsRowFlat provides true) {
                    Column(Modifier.padding(bottom = 10.dp)) { content() }
                }
            }
        }
    }
}

/** 设置页侧钮顺序列表的显示名（与单颗显隐开关文案对齐）。 */
@Composable
private fun overlayHandleOrderTitle(kind: OverlayCollapseHelper.OverlayHandleKind): String = when (kind) {
    OverlayCollapseHelper.OverlayHandleKind.COLLAPSE -> stringResource(R.string.oh_name_collapse)
    OverlayCollapseHelper.OverlayHandleKind.PLAY_PAUSE -> stringResource(R.string.oh_name_play_pause)
    OverlayCollapseHelper.OverlayHandleKind.PLAYER -> stringResource(R.string.oh_name_player)
    OverlayCollapseHelper.OverlayHandleKind.A11Y -> stringResource(R.string.oh_name_a11y)
}

/**
 * 侧钮的四颗开关 + 无障碍三档 + 上下顺序（v1.2.0 / v1.3.0，v1.2.54 抽成组件）。
 *
 * 从 `SettingsOverlayPage` 里搬出来的原因很直接：它自己就有 80 行，
 * 塞在页面主体里让「侧钮」和「歌词样式」两件事的代码缠在一起。
 * 调用的前置条件由调用方保证：**车载模式 + 侧钮总开关已打开**。
 */
@Composable
internal fun SideHandleRows(
    player: PlayerUiState,
    onOverlayHandle: (Int, Boolean) -> Unit,
    onOverlayHandleA11yMode: (Int) -> Unit,
    onOverlayHandleOrder: (String) -> Unit,
) {
    val context = LocalContext.current
    SwitchRow(
        title = stringResource(R.string.oh_show_collapse),
        subtitle = stringResource(R.string.oh_show_collapse_sub),
        checked = player.overlayHandleCollapse,
        onCheckedChange = { onOverlayHandle(0, it) },
    )
    SwitchRow(
        title = stringResource(R.string.oh_show_play_pause),
        subtitle = stringResource(R.string.oh_show_play_pause_sub),
        checked = player.overlayHandlePlayPause,
        onCheckedChange = { onOverlayHandle(1, it) },
    )
    SwitchRow(
        title = stringResource(R.string.oh_show_player),
        subtitle = stringResource(R.string.oh_show_player_sub),
        checked = player.overlayHandlePlayer,
        onCheckedChange = { onOverlayHandle(2, it) },
    )
    // 无障碍钮三档（2026-10-31）：原来只有开/关，而"一直显示"在多一颗钮就少一块屏的
    // 车机上很快变成噪音，"一直不显示"又丢了入口。
    //
    // ⚠️ 顺序从"显示得最多"排到"最少"（常显 → 仅未运行 → 不显示），
    //    索引与 mode 值**不是**同序（mode: 2 / 1 / 0），换算在 a11yModes。
    val a11yModes = listOf(2, 1, 0)
    ChoiceRow(
        title = stringResource(R.string.oh_a11y_mode),
        subtitle = stringResource(R.string.oh_a11y_mode_sub),
        options = listOf(
            stringResource(R.string.oh_a11y_mode_always),
            stringResource(R.string.oh_a11y_mode_only_off),
            stringResource(R.string.oh_a11y_mode_off),
        ),
        selectedIndex = a11yModes.indexOf(player.overlayHandleA11yMode).coerceAtLeast(0),
        onSelect = { onOverlayHandleA11yMode(a11yModes[it]) },
        onReset = resetAction(
            player.overlayHandleA11yMode != SettingsDefaults.OVERLAY_HANDLE_A11Y_MODE,
        ) {
            onOverlayHandleA11yMode(SettingsDefaults.OVERLAY_HANDLE_A11Y_MODE)
        },
    )
    // 侧钮上下顺序 UI：上移 / 下移（车机大触控友好；四项固定项不必上 LazyColumn
    // 长按拖，避免与设置页滚动冲突）。
    Text(
        stringResource(R.string.oh_order_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 2.dp),
    )
    Text(
        stringResource(R.string.oh_order_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
    )
    val handleOrder = OverlayCollapseHelper.mergeHandleOrder(
        player.overlayHandleOrder.split(',').map { it.trim() }.filter { it.isNotEmpty() },
    )
    handleOrder.forEachIndexed { index, kind ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.oh_order_item,
                        index + 1,
                        overlayHandleOrderTitle(kind),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // 顺序里仍占位，但「仅未运行时显示」这一档当前可能正被状态藏起来。
                // 不标注的话，用户会以为设置没生效（2026-10-31）。
                if (kind == OverlayCollapseHelper.OverlayHandleKind.A11Y &&
                    !OverlayCollapseHelper.a11yHandleVisible(
                        mode = player.overlayHandleA11yMode,
                        running = A11yRuntime.running(context),
                    )
                ) {
                    Text(
                        text = stringResource(R.string.oh_order_item_hidden),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(
                enabled = index > 0,
                onClick = {
                    val ids = handleOrder.map { it.id }.toMutableList()
                    val moved = ids.removeAt(index)
                    ids.add(index - 1, moved)
                    onOverlayHandleOrder(ids.joinToString(","))
                },
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.move_up),
                )
            }
            IconButton(
                enabled = index < handleOrder.lastIndex,
                onClick = {
                    val ids = handleOrder.map { it.id }.toMutableList()
                    val moved = ids.removeAt(index)
                    ids.add(index + 1, moved)
                    onOverlayHandleOrder(ids.joinToString(","))
                },
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.move_down),
                )
            }
        }
    }
    if (OverlayCollapseHelper.handleOrderCsv(handleOrder) != SettingsDefaults.OVERLAY_HANDLE_ORDER) {
        TextButton(
            onClick = { onOverlayHandleOrder(SettingsDefaults.OVERLAY_HANDLE_ORDER) },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(stringResource(R.string.oh_restore_order))
        }
    }
}
