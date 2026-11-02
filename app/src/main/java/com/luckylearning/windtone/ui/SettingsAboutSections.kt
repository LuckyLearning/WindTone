package com.luckylearning.windtone.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.luckylearning.windtone.BuildConfig
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.UpdateChecker
import com.luckylearning.windtone.overlay.ForegroundAppDetector
import com.luckylearning.windtone.state.UpdateUiState

/**
 * 「关于与更新」页的分区（2026-10-30）。
 *
 * 与 `SettingsLibrarySections.kt` 同一套写法：**功能代码独立，页面只做组合**。
 * 每个分区自带标题、行与状态，外部只给「数据 + 回调」；页面里只留一串顺序调用，
 * 所以加一条信息 / 换个位置都不用动别的分区。
 */

// ================================================================ ① 版本信息卡

/**
 * 版本信息卡 + 连点 5 次解锁调试（v1.1.31）。
 *
 * 解锁入口放在这张卡上是有意的：它就在这里，普通用户不会去找，
 * 出问题的用户照着提示连点 5 次也不难。计数用 `remember` 不落盘 ——
 * 必须「连续」点，退出本页重新数，免得零散误触攒够 5 次把排障区翻出来。
 */
@Composable
internal fun AboutVersionSection(
    debugUnlocked: Boolean,
    onDebugUnlocked: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }
    var aboutTaps by remember { mutableStateOf(0) }
    val onAboutTap: () -> Unit = {
        aboutTaps += 1
        if (aboutTaps >= DEBUG_UNLOCK_TAPS) {
            aboutTaps = 0
            onDebugUnlocked(true)
            Toast.makeText(
                context,
                AppStrings.s(R.string.debug_tools_are_now_visible),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    SectionTitle(stringResource(R.string.about))
    StatCard(
        onClick = if (debugUnlocked) null else onAboutTap,
        rows = listOf(
            stringResource(R.string.app) to stringResource(R.string.windtone),
            stringResource(R.string.version) to version,
            stringResource(R.string.package_label) to context.packageName,
            stringResource(R.string.minimum_os) to "Android 7.0（API 24）",
            // v1.0.7 起 Release 按 ABI 分包，这里显示本机架构，方便对照下载
            stringResource(R.string.abis) to UpdateChecker.primaryAbiLabel().ifBlank { "—" },
            stringResource(R.string.update_source) to "${BuildConfig.RELEASE_OWNER}/${BuildConfig.RELEASE_REPO}",
        )
    )
}

// ================================================================ ② 检查更新

/**
 * 检查更新入口（点开是 [SettingsUpdatePage] 子页）。
 *
 * v1.1.80 起更新整体收进「关于」——版本号与「有没有新版本」本来就该在同一屏。
 */
@Composable
internal fun UpdateEntrySection(
    update: UpdateUiState,
    autoCheckUpdate: Boolean,
    onOpenUpdate: () -> Unit,
) {
    ActionRow(
        title = stringResource(R.string.check_for_updates),
        subtitle = when {
            update.info != null -> stringResource(R.string.version_is_available, update.info.version)
            autoCheckUpdate -> stringResource(R.string.check_for_updates_auto_check)
            else -> stringResource(R.string.check_for_updates_auto_check_2)
        },
        onClick = onOpenUpdate,
        trailing = if (update.info != null) stringResource(R.string.update_available) else "",
        chevron = true,
    )
}

// ================================================================ ③ 后台运行（电池白名单）

/**
 * 后台运行是否会被系统冻结（2026-11-01 从「诊断与排障」提到可见位置）。
 *
 * ## 为什么要提出来
 *
 * 这一项以前只存在于「诊断与排障」页，而那个页要**连点版本号 5 次**才解锁 ——
 * 出问题的用户找不到，正常用户更不会去点。但车机上「电池优化未加入白名单」
 * 的后果很具体：Doze / 后台冻结一旦生效，播放与悬浮窗会在息屏后被掐掉，
 * 表现为「放着放着没声了」「悬浮歌词自己没了」，而且**没有任何报错**。
 *
 * 两次车机诊断包（v1.2.40 / v1.2.44）里这一项都是「否」，说明它是常态而非个例。
 *
 * ## 状态怎么刷新
 *
 * 用户点进去勾选后是**离开本页**去系统设置，回来时 Composable 不会重建。
 * 所以挂一个 `ON_RESUME` 观察者重新判定一次 —— 否则用户明明已经加进去了，
 * 界面上还写着「未加入」，只会让人反复去点。
 */
@Composable
internal fun BackgroundRunSection() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var ignoring by remember {
        mutableStateOf(ForegroundAppDetector.ignoringBatteryOptimizations(context))
    }
    // 2026-11-02：跳去系统页后回来仍未被收录 → 说明这条路走不通，改给路径向导。
    // 以前只要 startActivity 不抛异常就当作"成功"，车机上落到「多媒体不支持」的死页，
    // 用户回来还是「未加入」，却拿不到任何可操作的提示。
    var showHelp by remember { mutableStateOf(false) }
    var awaitingReturn by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoring = ForegroundAppDetector.ignoringBatteryOptimizations(context)
                if (!ignoring && awaitingReturn) {
                    awaitingReturn = false
                    showHelp = true
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    SectionTitle(stringResource(R.string.background_run))
    ActionRow(
        title = stringResource(R.string.battery_optimization),
        subtitle = if (ignoring) {
            stringResource(R.string.battery_opt_joined)
        } else {
            stringResource(R.string.battery_opt_not_joined)
        },
        // 未加入时给一个感叹号提示；已加入就没有可做的了，连箭头都不给
        trailing = if (ignoring) null else "!",
        chevron = !ignoring,
        onClick = {
            if (!ignoring) {
                val jumped = ForegroundAppDetector.requestIgnoreBatteryOptimizations(context)
                if (jumped) {
                    // 上车机时这一步已改为落到「应用信息」页（见 batteryOptimizationIntents），
                    // 回来后仍未收录再兜底提示，避免把用户丢在没法操作的地方。
                    awaitingReturn = true
                } else {
                    showHelp = true
                }
            }
        },
    )
    if (showHelp) BatteryHelpDialog(onDismiss = { showHelp = false })
}

/**
 * 「允许后台运行」路径向导（2026-11-02 抽出，关于页与排障页复用）。
 *
 * 车机上标准「忽略电池优化」申请可能被系统直接拒绝（DiLink5 提示「多媒体不支持」），
 * 所以这里不是可选项，而是**兜底出口**：给 3 条手动路径 + 一条「找不到也不影响」的说明。
 */
@Composable
internal fun BatteryHelpDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val steps = stringResource(R.string.battery_help_body)
    val copiedMsg = stringResource(R.string.battery_help_copied)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.battery_help_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(steps, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(steps))
                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.battery_help_copy)) }
        },
        dismissButton = {
            TextButton(onClick = {
                ForegroundAppDetector.openSystemSettings(context)
                onDismiss()
            }) { Text(stringResource(R.string.fg_help_open_settings)) }
        },
    )
}

// ================================================================ ④ 隐私说明
/**
 * 隐私说明长文。
 *
 * 这是产品信任核心：写明「所有数据只在本机」以及「只有一处联网、具体是哪个仓库」。
 * 不要因为「太长占地方」删掉它 —— 一个本地播放器的可信度几乎全靠这一段。
 */
@Composable
internal fun PrivacyNoticeSection() {
    Text(
        stringResource(R.string.playlists_favorites_playback_stats_and),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

// ================================================================ ④ 诊断与排障入口

/**
 * 排障工具入口（点开是 [SettingsDiagnosisPage] 子页）。
 *
 * 放在本页最后一行：它是最不需要被普通用户看见的入口，但出问题时必须找得到 ——
 * 藏在设置里三级以外的地方，等于让用户去猜。
 */
@Composable
internal fun DiagnosticsEntrySection(onOpenDiagnostics: () -> Unit) {
    ActionRow(
        title = stringResource(R.string.diagnostics_troubleshooting),
        subtitle = stringResource(R.string.self_checks_and_toolkit),
        onClick = onOpenDiagnostics,
        chevron = true,
    )
}
