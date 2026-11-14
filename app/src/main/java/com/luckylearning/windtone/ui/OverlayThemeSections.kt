package com.luckylearning.windtone.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.OverlayStyleScope
import com.luckylearning.windtone.data.Prefs
import com.luckylearning.windtone.data.ThemeMode

/**
 * 桌面悬浮歌词里「输出屏幕」与「白天 / 夜间两套外观」两小节（v1.2.52）。
 *
 * 为什么单独一个文件：这两节都不是「一个滑块对应一个值」的常规行 ——
 * 一个是**枚举系统里真实存在的屏幕**（数量随硬件变化），一个是**当前编辑对象的状态说明**
 * 加两条整组操作。塞进已经 1400 多行的 `SettingsOverlayPage` 只会让它更难读；
 * 而它们又必须紧挨着「样式预设」出现（用户改外观时先要知道自己改的是哪一套），
 * 所以做成两个自包含小节，由页面按顺序摆上去。
 *
 * 两节都自己读写 [Prefs]（与 `BtAutoCarSettings` / `QueueSnapshotSection` 同一套做法），
 * 只有「需要播放域配合」的两件事（换屏、重读外观）走回调 —— 那两件事要经
 * `PlayerActions` 才能同时落盘、更新界面状态并叫醒悬浮窗服务。
 */

/**
 * 悬浮歌词输出到哪块屏幕。
 *
 * **只有一块屏时整节不出现**：单屏设备上多一行「输出屏幕：内置屏幕」，用户只会多一个
 * 疑问（「还能选别的？」），点开发现只有一个选项。
 *
 * 当前值自己从 [Prefs] 读、自己记：它只在这一处被改动，没必要为它再往
 * `PlayerUiState` 里加一个字段（那是「界面各处都要跟着变」的东西才需要的管道）。
 */
@Composable
internal fun OverlayDisplaySection(onSelect: (Int) -> Unit) {
    val context = LocalContext.current
    val displays = remember(context) { connectedDisplays(context) }
    if (displays.size <= 1) return
    val prefs = remember(context) { Prefs(context) }
    var current by remember(context) { mutableStateOf(prefs.lyricOverlayDisplayId) }
    val labels = displays.map { display ->
        val name = display.name?.takeIf { it.isNotBlank() }
        name ?: stringResource(R.string.ov_display_unnamed, display.displayId)
    }
    val index = displays.indexOfFirst { it.displayId == current }.coerceAtLeast(0)
    ChoiceRow(
        title = stringResource(R.string.ov_display_title),
        subtitle = stringResource(R.string.ov_display_sub),
        options = labels,
        selectedIndex = index,
        onSelect = { i ->
            displays.getOrNull(i)?.let { target ->
                current = target.displayId
                onSelect(target.displayId)
            }
        },
    )
}

/**
 * 「白天 / 夜间两套」的状态说明 + 两条整组操作。
 *
 * 刻意**不提供「现在编辑哪一套」的切换开关**：编辑对象由主题决定，
 * 再给一个开关就会出现「界面是深色、却在编辑白天那套」的分裂状态，
 * 用户改完看不出效果，只会判定为「设置没用」。想看另一套的表现，就切主题。
 */
@Composable
internal fun OverlayStyleScopeSection(onCopyToDark: () -> Unit, onClearDark: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { Prefs(context) }
    val config = LocalConfiguration.current
    // 明暗按**悬浮窗实际输出屏**算（副屏的 Configuration 与这里看到的不一定同步），
    // 所以判定交给 Prefs；读一下 config 只是让本组合在系统明暗变化时重组。
    val dark = remember(config) { prefs.overlayStyleIsDark() }
    // 「夜间那套有没有自有值」不是响应式数据：复制 / 清除之后手动改，避免多一个状态源
    var hasOwnDark by remember { mutableStateOf(prefs.overlayStyleHasOwnDark()) }

    GroupLabel(stringResource(R.string.ov_scope_title))
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = if (dark) {
                stringResource(R.string.ov_scope_now_dark)
            } else {
                stringResource(R.string.ov_scope_now_light)
            },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.ov_scope_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        // 主题被钉死时提前说清楚：不然用户会以为「跟随系统」坏了
        if (prefs.themeMode != ThemeMode.SYSTEM) {
            Text(
                text = stringResource(R.string.ov_scope_mode_fixed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
    ActionRow(
        title = stringResource(R.string.ov_scope_copy_to_dark),
        subtitle = stringResource(R.string.ov_scope_copy_to_dark_sub),
        onClick = {
            onCopyToDark()
            hasOwnDark = true
        },
    )
    if (hasOwnDark) {
        ActionRow(
            title = stringResource(R.string.ov_scope_reset_dark),
            subtitle = stringResource(R.string.ov_scope_reset_dark_sub),
            onClick = {
                onClearDark()
                hasOwnDark = false
            },
        )
    }
}

/**
 * 系统里当前可用的屏幕，按 displayId 升序（主屏 0 恒在最前）。
 *
 * 不过滤 `STATE_OFF`：头枕屏 / 副驾屏常常在无人看时关闭，按状态过滤会让「明明接着屏，
 * 设置里却选不到」。选中一块暂时不亮的屏也能挂窗，亮起来就有内容。
 */
private fun connectedDisplays(context: Context): List<Display> {
    val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return emptyList()
    return runCatching { manager.displays.toList() }
        .getOrDefault(emptyList())
        .sortedBy { it.displayId }
}
