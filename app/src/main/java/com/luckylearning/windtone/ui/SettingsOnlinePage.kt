package com.luckylearning.windtone.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.data.formatByteSize
import com.luckylearning.windtone.playback.OnlineMediaCache

@Composable
internal fun SettingsOnlinePage(
    onOpenAliyun: () -> Unit,
    onOpenPlaylists: () -> Unit,
) {
    val context = LocalContext.current
    var limit by remember { mutableLongStateOf(OnlineMediaCache.limitBytes(context)) }
    var used by remember { mutableLongStateOf(OnlineMediaCache.sizeBytes(context)) }

    SectionTitle("来源")
    ActionRow(
        title = "阿里云盘",
        subtitle = "扫码登录后播放云盘里的音乐。只读，收藏和统计与本地曲库分开",
        onClick = onOpenAliyun,
        chevron = true,
    )
    ActionRow(
        title = "在线歌单（M3U）",
        subtitle = "添加 m3u / m3u8 / pls 直链并播放",
        onClick = onOpenPlaylists,
        chevron = true,
    )

    SectionTitle("播放缓存")
    Text(
        "已用 ${formatByteSize(used)}，上限 ${formatByteSize(limit)}。同一首再次播放时直接读已下载的部分。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    OnlineMediaCache.LIMIT_STEPS.forEach { step ->
        val selected = step == limit
        ActionRow(
            title = formatByteSize(step),
            subtitle = if (selected) "当前上限" else "点这一行改成这个上限",
            onClick = {
                OnlineMediaCache.setLimitBytes(context, step)
                limit = OnlineMediaCache.limitBytes(context)
                used = OnlineMediaCache.sizeBytes(context)
            },
        )
    }
    ActionRow(
        title = "清空缓存",
        subtitle = "清空后，听过的歌需要重新下载",
        onClick = {
            val ok = OnlineMediaCache.clear(context)
            used = OnlineMediaCache.sizeBytes(context)
            Toast.makeText(context, if (ok) "已清除在线缓存" else "当前没有可清除的缓存", Toast.LENGTH_SHORT).show()
        },
    )
}

@Composable
internal fun SettingsMenuOrderPage(
    order: List<String>,
    onChange: (List<String>) -> Unit,
) {
    SectionTitle("分类顺序")
    Text(
        "上移、下移调整设置主页的分类卡。子页里面的开关不参加排序。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val pages = SettingsMenuOrder.pages(order)
    Column(Modifier.fillMaxWidth()) {
        pages.forEachIndexed { index, page ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(page.title, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                TextButton(enabled = index > 0, onClick = {
                    val ids = pages.map { it.name }.toMutableList()
                    val moved = ids.removeAt(index)
                    ids.add(index - 1, moved)
                    onChange(ids)
                }) { Text("上移") }
                TextButton(enabled = index < pages.lastIndex, onClick = {
                    val ids = pages.map { it.name }.toMutableList()
                    val moved = ids.removeAt(index)
                    ids.add(index + 1, moved)
                    onChange(ids)
                }) { Text("下移") }
            }
        }
    }
}
