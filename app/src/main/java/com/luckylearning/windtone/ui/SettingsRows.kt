package com.luckylearning.windtone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.BuildConfig
import com.luckylearning.windtone.data.DataMaintenance
import com.luckylearning.windtone.data.DownloadRoute
import com.luckylearning.windtone.data.SettingsDefaults
import com.luckylearning.windtone.data.UpdateChecker
import com.luckylearning.windtone.playback.Pitch
import kotlin.math.abs

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

/**
 * 「恢复默认」按钮的判据包装。
 *
 * `needsReset` 为真时给出动作，否则返回 `null` —— 行组件见到 `null` 就不画 ↺。
 * 把「当前值是否偏离出厂默认」和「点下去写回什么」放在同一个表达式里，
 * 是为了让两处不可能对不上：判据和动作相隔二十行、改一处忘一处，是这类
 * 「重置按钮把值写歪了」的典型来路。
 */
internal fun resetAction(needsReset: Boolean, action: () -> Unit): (() -> Unit)? =
    if (needsReset) action else null

@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onInfo: (() -> Unit)? = null,
    /** 非空时在开关左侧画一个 ↺（只在当前值偏离出厂默认时由调用方传入） */
    onReset: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                // 副标题放不下的细节（如车载模式的收起规则 / 开机自启）挂在这里，点开是弹窗
                if (onInfo != null) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        AppIcons.Info,
                        contentDescription = "查看详细说明",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(15.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onInfo),
                    )
                }
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onReset != null) {
            ResetButton(onReset)
            Spacer(Modifier.width(4.dp))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    subtitle: String? = null,
    /** 非空时在标题行右侧画一个 ↺（只在当前值偏离出厂默认时由调用方传入） */
    onReset: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (onReset != null) ResetButton(onReset)
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
        )
    }
}

/**
 * 设置主页的**二级分类入口卡**（v1.1.3 新增，效果图 ㉙ 的 `.cat-row`）。
 *
 * 与 [ActionRow] 的分工（效果图原话：「子页入口行与分类卡的区别」）：
 *  - [CategoryRow] = **一整类设置的门面**，带左侧语义图标 + 摘要，只出现在设置主页；
 *  - [ActionRow] = **某一类设置里的一条**，无图标，出现在子页里。
 *
 * 规格：40dp 图标容器（圆角 12、secondaryContainer 底）+ 标题 15sp/600 +
 * 摘要 11sp + 可选右侧值（11sp、primary）+ 18dp chevron；卡片圆角 14dp、
 * 内边距 11×14、卡间距 8dp。chevron 规范自 v1.1.3 起放宽一档 ——
 * **只有 CategoryRow 与 ActionRow** 可带箭头，`OptionRow`（弹层行）不变。
 */
@Composable
internal fun CategoryRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            // .cat-row{margin-top:8px} —— 卡与卡之间靠它分隔，不加 SectionTitle
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(14.dp))
                Text(
                    text = trailing,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    modifier = Modifier.widthIn(max = 96.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 设置页的可点行。
 *
 * @param trailing 右侧的当前值摘要（如「过滤 < 60 秒」「已是最新」）——把状态前置到首页，
 *   用户不用进子页就知道里面是什么状况。
 * @param chevron 是否显示右箭头。**只给「点开会进入另一个界面」的行开**（子页入口、
 *   打开弹层/弹窗的行）；就地执行动作（如「检查更新」）或就地展开（如「查看日志」）的行不加。
 * @param swatch 右侧的当前颜色预览圆点（颜色类设置行专用；`null` 表示不画）。
 *   「跟随主题」没有固定色，调用方传 `null`，改由 [trailing] 的文字表达。
 * @param onReset 非空时在右侧画一个 ↺（只在当前值偏离出厂默认时由调用方传入）。
 */
@Composable
internal fun ActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: String? = null,
    chevron: Boolean = false,
    swatch: Int? = null,
    onReset: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // ↺ 也是 clickable，且嵌在本行的 clickable 里。Compose 的点击手势由**最内层**
            // 拿到并消费，所以点 ↺ 只复位、不会顺带打开本行该进的那个界面。
            if (onReset != null) {
                Spacer(Modifier.width(6.dp))
                ResetButton(onReset)
            }
            if (swatch != null) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color(swatch))
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    trailing,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    modifier = Modifier.widthIn(max = 96.dp),
                )
            }
            if (chevron) {
                Spacer(Modifier.width(2.dp))
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
internal fun StatCard(rows: List<Pair<String, String>>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            rows.forEach { (k, v) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text(
                        k,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(112.dp),
                    )
                    Text(
                        v,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 歌词颜色（v1.1.4）

/**
 * 三处可配的歌词颜色。
 *
 * 三处共用同一个选择弹层 —— 它们的界面完全一样，只有「写回哪个字段」不同，
 * 复制三份 `ModalBottomSheet` 只会让样式迟早走样。
 */
