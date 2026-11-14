package com.luckylearning.windtone.ui

import androidx.compose.ui.res.stringResource
import com.luckylearning.windtone.R

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.luckylearning.windtone.ui.theme.windShadow
import com.luckylearning.windtone.ui.theme.AppVisual
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
 * 可折叠的分区标题（2026-10-30）。
 *
 * 存在的理由：用得很少、又处在页面顶部的成组滑块（典型是「速度与变调」）常驻展开，
 * 等于把两块「一碰就改值」的区域永久摆在最容易被误触的位置。收起后标题行变成
 * 「标题 + 当前值摘要 + 展开箭头」，要看要改再点开。
 *
 * 为什么不做「记住上次展开状态」：这类参数一年改一两次，记住展开等于把误触面积
 * 永久留在页面上；而摘要行已经能回答「现在是多少」，不展开也知道值。
 *
 * 收起态**不移动**恢复默认（↺）按钮 —— 它仍在展开后的每一行行尾，与其它设置项一致。
 */
@Composable
internal fun CollapsibleSectionTitle(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    /** 弹层里用一档更轻的标题（与 `SectionLabel` 同规格），设置页用默认值 */
    compact: Boolean = false,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(AppVisual.Motion.FastMs),
        label = "collapseChevron",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(
                top = if (compact) 16.dp else 18.dp,
                bottom = if (compact) 2.dp else 6.dp,
            ),
    ) {
        Text(
            title,
            style = if (compact) {
                MaterialTheme.typography.labelMedium
            } else {
                MaterialTheme.typography.labelLarge
            },
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = rotation },
        )
    }
}

/**
 * 分区内的**次级小标题**（v1.1.30）。
 *
 * 「调试」分区下面挂着四块排障工具（前台应用记录 / 悬浮歌词自检 / 崩溃日志 / 开机自启），
 * 它们需要各自的标题，但不该再各占一个 [SectionTitle] —— 那会看起来像四个并列分区，
 * 与「集中放一起」的初衷相反。所以次级标题比 SectionTitle 轻一档（left 对齐、无上间距加倍）。
 */
@Composable
internal fun DebugLabel(text: String) {
    GroupLabel(text)
}

/**
 * 分区内的**次级小标题**（v1.1.51 从 [DebugLabel] 提出来复用）。
 *
 * 一个分区里塞了太多并列项时（悬浮歌词的尺寸 / 字体 / 背景 / 行为），
 * 需要一层比 [SectionTitle] 轻的小标题把它们分开，否则一整屏滑块看着像一锅粥。
 */
@Composable
internal fun GroupLabel(text: String) {
    // 组内（折叠卡里）比整页少一层呼吸：卡本身已经有内边距，
    // 再按整页那套 14dp 打头会显得空。
    val flat = LocalSettingsRowFlat.current
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = if (flat) 10.dp else 14.dp, bottom = 2.dp),
    )
}

/**
 * 「这一行位于折叠组卡内部」的视觉开关（v1.2.54）。
 *
 * 为什么需要：悬浮歌词子页把三十多项收进几张折叠卡之后，[ActionRow] 原本那张
 * `surfaceVariant` 小卡就变成了**卡中卡** —— 一张组卡里再嵌七八张带阴影的小卡，
 * 边框层层套着，比平铺更花。这里让组把行切成「透明底 + 无阴影」的列表行，
 * 卡面只由组本身承担一次。
 *
 * 用 CompositionLocal 而不是给每个行组件加参数：行组件被全项目上百处复用，
 * 加参数会让「组内 / 组外」这个纯视觉决策散落到每个调用点；默认值 false
 * 保证组外一处像素都不动。
 */
internal val LocalSettingsRowFlat = staticCompositionLocalOf { false }

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
                        contentDescription = stringResource(R.string.view_details),
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
        // contentDescription 用标题（v1.1.29 补）：读屏软件对没有描述的开关
        // 只会念「开关，已关闭」，用户不知道这是哪一个。加上标题后念
        // 「前台应用记录，开关，已关闭」。顺带给界面测试一个稳定抓手 ——
        // 合并语义树里用「父级含标题」定位开关会连滚动容器一起命中。
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

/**
 * 互斥多选项（v1.2.4，首个使用者是「主题模式」：跟随系统 / 浅色 / 深色）。
 *
 * 为什么不用开关：开关只能表达两态，三档要么拆成两个互斥开关（用户能点出自相矛盾
 * 的状态），要么塞进弹层（多一次点击，且看不到还有哪些档）。
 * 一排 chip 把**全部可选值**直接摆出来，点哪个就是哪个。
 *
 * @param options 与 [selectedIndex] 同序的展示文案；调用方负责本地化。
 * @param onReset 非空时在标题行右侧画一个 ↺（只在当前值偏离出厂默认时由调用方传入）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChoiceRow(
    title: String,
    subtitle: String? = null,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onReset: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onReset != null) ResetButton(onReset)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            options.forEachIndexed { index, label ->
                FilterChip(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    label = { Text(label) },
                )
            }
        }
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
 * 数值步进行（v1.2.54）。
 *
 * 为什么不用 [SliderRow]：滑杆必须给一个 `range`，而这一版用户的要求是
 * 「字号用磅、**不设区间**」—— 没有上下限就没有滑杆可言。步进器只提供 −/＋
 * 两个按钮，点一次变一档，能一直加、一直减，和 AnyLyric 的字号交互一致。
 *
 * @param valueLabel 右侧显示的可读值（如「20 磅」或「跟随正文」）——调用方负责本地化与单位
 * @param subtitle 副标题：说明这一档作用于什么、0 代表什么
 * @param onReset 非空时在最右画一个 ↺（只在当前值偏离出厂默认时由调用方传入）
 */
@Composable
internal fun StepperRow(
    title: String,
    valueLabel: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    subtitle: String? = null,
    onReset: (() -> Unit)? = null,
) {
    // 副标题放在控件行**下面**占满整宽：字号说明往往是一整句
    // （「手机 16–22 磅合适，车机离得远建议 24–32 磅…」），挤在左侧列里
    // 只剩十来个字宽，会折成一坨。
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (onReset != null) {
                ResetButton(onReset)
                Spacer(Modifier.width(4.dp))
            }
            // 读屏软件只念得出「按钮」，两个按钮会分不清加减 —— 描述里带标题。
            StepButton("−", stringResource(R.string.step_decrease_of, title), onDecrease)
            Text(
                valueLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                // 固定最小宽度：「20 磅」→「120 磅」变宽时按钮不许跳位
                modifier = Modifier.widthIn(min = 64.dp),
            )
            StepButton("＋", stringResource(R.string.step_increase_of, title), onIncrease)
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** [StepperRow] 的 −/＋ 按钮：38dp 方形，够车机上点中 */
@Composable
private fun StepButton(label: String, description: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    // 组内卡片本身就是 surfaceVariant 底，按钮再用同色会完全看不见 ——
    // 换成高一档的容器色，保持「按钮是可按的」这层视觉区分。
    val color = if (LocalSettingsRowFlat.current) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(
        color = color,
        shape = shape,
        modifier = Modifier
            .size(38.dp)
            .clip(shape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
            .windShadow(level = 1, shape = RoundedCornerShape(14.dp))
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
    // 组内扁平：不画独立卡面与阴影，只留可点区域（卡面由 OverlayGroup 提供）
    val flat = LocalSettingsRowFlat.current
    val shape = RoundedCornerShape(if (flat) 8.dp else 12.dp)
    Surface(
        color = if (flat) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (flat) 0.dp else 4.dp)
            .then(
                if (flat) {
                    Modifier
                } else {
                    Modifier.windShadow(level = 1, shape = RoundedCornerShape(12.dp))
                },
            )
            .clip(shape)
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

/**
 * 只读信息卡。
 *
 * [onClick] 非空时整张卡可点 —— 目前只有「关于」用它（连点 5 次解锁调试分区，v1.1.31）。
 * 刻意不做任何视觉提示（没有箭头、没有 ripple 之外的变化）：这个入口要的是「知道的人能找到」，
 * 而不是「所有人都想点一下看看」。
 */
@Composable
internal fun StatCard(rows: List<Pair<String, String>>, onClick: (() -> Unit)? = null) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().windShadow(level = 1, shape = RoundedCornerShape(12.dp)).then(
            if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
        ),
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
