package com.luckylearning.windtone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.luckylearning.windtone.ui.theme.adaptiveDensity
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.CalendarDayCell
import com.luckylearning.windtone.data.ListenCalendarMonth
import com.luckylearning.windtone.data.formatCalendarDayTitle
import com.luckylearning.windtone.data.formatListenDuration
import com.luckylearning.windtone.data.formatYearMonth
import com.luckylearning.windtone.state.CalendarUiState
import com.luckylearning.windtone.ui.theme.windShadow
import java.util.Calendar
import java.util.TimeZone

/**
 * 听歌日历热力页（H7）。
 *
 * 数据来自既有 `listen_log` 小时桶，经 [com.luckylearning.windtone.data.buildListenCalendarMonth]
 * 聚合成月视图色块；**不另建统计管线**。点某一天看当日摘要。
 */
@Composable
fun ListenCalendarScreen(
    state: CalendarUiState?,
    onClose: () -> Unit,
    onLoadMonth: (year: Int, month: Int) -> Unit,
) {
    BackHandler { onClose() }
    val zone = remember { TimeZone.getDefault() }
    val nowCal = remember {
        Calendar.getInstance(zone).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
    val curYear = state?.year ?: nowCal.get(Calendar.YEAR)
    val curMonth = state?.month ?: (nowCal.get(Calendar.MONTH) + 1)
    var selected by remember(curYear, curMonth, state?.monthData) {
        mutableStateOf<CalendarDayCell?>(null)
    }
    // UI v2 阶段三：限宽改读统一密度表，不再用第 4 套 `>= 840` 局部判据。
    val density = adaptiveDensity()
    val monthData = state?.monthData
    val todayStart = nowCal.timeInMillis

    fun shiftMonth(delta: Int) {
        val c = Calendar.getInstance(zone)
        c.set(Calendar.YEAR, curYear)
        c.set(Calendar.MONTH, curMonth - 1)
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.add(Calendar.MONTH, delta)
        val y = c.get(Calendar.YEAR)
        val m = c.get(Calendar.MONTH) + 1
        val nowY = nowCal.get(Calendar.YEAR)
        val nowM = nowCal.get(Calendar.MONTH) + 1
        if (y > nowY || (y == nowY && m > nowM)) return
        selected = null
        onLoadMonth(y, m)
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    stringResource(R.string.cal_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .widthIn(max = density.contentMaxWidth)
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 18.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(onClick = { shiftMonth(-1) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.cal_cd_prev_month),
                        )
                    }
                    Text(
                        formatYearMonth(curYear, curMonth),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    val atCurrent = curYear == nowCal.get(Calendar.YEAR) &&
                        curMonth == nowCal.get(Calendar.MONTH) + 1
                    IconButton(onClick = { shiftMonth(1) }, enabled = !atCurrent) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.cal_cd_next_month),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (state?.loading == true || monthData == null) {
                    Text(
                        stringResource(R.string.cal_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(28.dp))
                    return@Column
                }

                MonthSummary(monthData)
                Spacer(Modifier.height(14.dp))

                Row(Modifier.fillMaxWidth()) {
                    stringArrayResource(R.array.cal_week_labels).forEach { w ->
                        Text(
                            w,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))

                HeatGrid(
                    month = monthData,
                    selected = selected,
                    todayStartMs = todayStart,
                    onSelect = { selected = it },
                )
                Spacer(Modifier.height(16.dp))

                DayDetail(selected = selected, zone = zone)
                Spacer(Modifier.height(24.dp))

                Text(
                    stringResource(R.string.cal_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun MonthSummary(month: ListenCalendarMonth) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().windShadow(level = 1, shape = RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SummaryCell(stringResource(R.string.cal_month_total), formatListenDuration(month.totalMs))
            SummaryCell(
                stringResource(R.string.cal_active_days),
                stringResource(R.string.cal_active_days_value, month.activeDays),
            )
            SummaryCell(
                stringResource(R.string.cal_max_day),
                if (month.maxDayMs > 0L) {
                    formatListenDuration(month.maxDayMs)
                } else {
                    stringResource(R.string.cal_em_dash)
                },
            )
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HeatGrid(
    month: ListenCalendarMonth,
    selected: CalendarDayCell?,
    todayStartMs: Long,
    onSelect: (CalendarDayCell) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val max = month.maxDayMs.coerceAtLeast(1L)
    val rows = month.cells.chunked(7)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { week ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                week.forEach { cell ->
                    val frac = if (!cell.inMonth || cell.ms <= 0L) 0f
                    else (cell.ms.toFloat() / max).coerceIn(0f, 1f)
                    val bg = when {
                        !cell.inMonth -> scheme.surfaceVariant.copy(alpha = 0.35f)
                        cell.ms <= 0L -> scheme.onSurfaceVariant.copy(alpha = 0.10f)
                        else -> scheme.primary.copy(alpha = 0.18f + 0.82f * frac)
                    }
                    val isSelected = selected?.dayStartMs == cell.dayStartMs
                    val isToday = cell.dayStartMs == todayStartMs
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(bg)
                            .then(
                                if (isSelected) Modifier.border(
                                    2.dp,
                                    scheme.primary,
                                    RoundedCornerShape(8.dp),
                                ) else if (isToday) Modifier.border(
                                    1.dp,
                                    scheme.primary.copy(alpha = 0.55f),
                                    RoundedCornerShape(8.dp),
                                ) else Modifier,
                            )
                            .clickable(enabled = cell.inMonth) { onSelect(cell) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (cell.inMonth) "${cell.dayOfMonth}" else "",
                            style = MaterialTheme.typography.labelMedium,
                            fontSize = 12.sp,
                            color = when {
                                !cell.inMonth -> Color.Transparent
                                frac >= 0.55f -> scheme.onPrimary
                                else -> scheme.onSurface
                            },
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayDetail(selected: CalendarDayCell?, zone: TimeZone) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            if (selected == null) {
                Text(
                    stringResource(R.string.cal_pick_day),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    formatCalendarDayTitle(selected.dayStartMs, zone),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (selected.ms > 0L) {
                        stringResource(
                            R.string.cal_day_listened,
                            formatListenDuration(selected.ms),
                        )
                    } else {
                        stringResource(R.string.cal_day_empty)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
