package com.luckylearning.windtone.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.lyrics.LockScreenLyricPublisher

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

    // 车载蓝牙歌词（实验，竞品借鉴：Salt Player）：把歌词写进 AVRCP 的 title。
    // 自包含读写 Prefs；切换后 invalidate 让下一 tick 立刻重发一次元数据。
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var btLyric by remember { mutableStateOf(prefs.btCarLyric) }
    SwitchRow(
        title = stringResource(R.string.bt_car_lyric_title),
        subtitle = stringResource(R.string.bt_car_lyric_sub),
        checked = btLyric,
        onCheckedChange = {
            btLyric = it
            prefs.btCarLyric = it
            LockScreenLyricPublisher.invalidate()
        },
        onReset = resetAction(btLyric != SettingsDefaults.BT_CAR_LYRIC) {
            btLyric = SettingsDefaults.BT_CAR_LYRIC
            prefs.btCarLyric = SettingsDefaults.BT_CAR_LYRIC
            LockScreenLyricPublisher.invalidate()
        },
    )
}
