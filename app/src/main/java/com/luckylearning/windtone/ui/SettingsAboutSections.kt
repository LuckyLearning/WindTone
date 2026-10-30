package com.luckylearning.windtone.ui

import android.widget.Toast
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
import com.luckylearning.windtone.BuildConfig
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.UpdateChecker
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

// ================================================================ ③ 隐私说明

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
