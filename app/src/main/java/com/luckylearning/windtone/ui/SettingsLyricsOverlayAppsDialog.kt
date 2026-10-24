package com.luckylearning.windtone.ui

import androidx.compose.ui.res.stringResource
import com.luckylearning.windtone.R

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「不显示悬浮窗的应用」选择弹窗（v1.1.29，设计见 docs/设计-悬浮窗按应用隐藏.md）。
 *
 * 为什么是弹窗而不是跳页：用户的核心动作是「打开 360 → 勾一下」，一两步的事；
 * 而且这是在车机上用，弹窗的大行高 + 大开关比二级页更好按。
 *
 * 数据来源：`PackageManager` 查全部**可启动应用**（MAIN/LAUNCHER）——
 * 系统应用（电话 / 地图 / 影像…）与用户应用一视同仁，因为要回避的恰恰
 * 多数是系统应用。零权限，本机查询。
 *
 * 交互：顶部搜索框（按应用名或包名过滤，车机输入法可语音听写），
 * 列表每行 = 图标 + 应用名 + 包名 + 开关；底部「清空」= 回到出厂默认（空名单）。
 */

/** 一行应用的数据（图标现取，反正一行只画一次） */
private data class LaunchableApp(val pkg: String, val label: String)

@Composable
internal fun OverlayHiddenAppsDialog(
    hiddenApps: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val apps = remember {
        val pm = context.packageManager
        val main = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(
            android.content.Intent.CATEGORY_LAUNCHER,
        )
        pm.queryIntentActivities(main, 0)
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .map { LaunchableApp(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label }
            .toList()
    }
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        if (query.isBlank()) {
            apps
        } else {
            val q = query.trim()
            apps.filter { it.label.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true) }
        }
    }
    // ① 已在名单里、但**不在可启动列表**的包：系统里有些「影像 / 桌面挂件」类应用
    //    不注册 LAUNCHER 入口，从列表里根本点不到。名单里留着的必须能被看见、能被取消，
    //    否则用户勾错了改不回来。
    val hiddenUnlisted = remember(hiddenApps, apps) {
        val launchable = apps.mapTo(HashSet()) { it.pkg }
        hiddenApps.filter { it.isNotBlank() && it !in launchable }.sorted()
    }
    // ② 允许手动输入包名（配合诊断页「前台应用记录」里抄到的包名）：
    //    搜索框里像包名的串且列表里没有 → 直接把这一条加进名单。
    val manualCandidate = remember(query, apps, hiddenApps) {
        val q = query.trim()
        val looksLikePkg = q.length >= 5 && q.count { it == '.' } >= 2 && q.none { it.isWhitespace() } &&
            q.all { it.isLetterOrDigit() || it == '.' || it == '_' }
        if (!looksLikePkg) null
        else if (apps.any { it.pkg.equals(q, ignoreCase = true) } || hiddenApps.any { it.equals(q, ignoreCase = true) }) null
        else q
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apps_without_the_overlay)) },
        text = {
            Column {
                Text(
                    if (hiddenApps.isEmpty()) {
                        stringResource(R.string.nothing_configured_yet_floating_lyrics)
                    } else {
                        stringResource(R.string.configured_floating_lyrics_hide_while, hiddenApps.size)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_app_name_or_package)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // 手动包名（搜索框里输入完整包名时出现）
                    manualCandidate?.let { pkg ->
                        item(key = "manual") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .clickable { onToggle(pkg, true) }
                                    .padding(horizontal = 4.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.add_package), fontSize = 16.sp, maxLines = 1)
                                    Text(
                                        text = pkg,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Switch(checked = false, onCheckedChange = { onToggle(pkg, true) })
                            }
                        }
                    }
                    // 已在名单但不在可启动列表的（系统里没有 LAUNCHER 入口的应用）
                    if (hiddenUnlisted.isNotEmpty()) {
                        item(key = "hdr-unlisted") {
                            Text(
                                stringResource(R.string.added_these_apps_have_no),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                            )
                        }
                        items(hiddenUnlisted, key = { "u-$it" }) { pkg ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .clickable { onToggle(pkg, false) }
                                    .padding(horizontal = 4.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = appLabel(context, pkg),
                                        fontSize = 16.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = pkg,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Switch(checked = true, onCheckedChange = { onToggle(pkg, false) })
                            }
                        }
                    }
                    items(filtered, key = { it.pkg }) { app ->
                        val checked = app.pkg in hiddenApps
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .clickable { onToggle(app.pkg, !checked) }
                                .padding(horizontal = 4.dp),
                        ) {
                            AppIcon(app.pkg)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = app.label,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = app.pkg,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Switch(
                                checked = checked,
                                onCheckedChange = { onToggle(app.pkg, it) },
                            )
                        }
                    }
                    if (filtered.isEmpty() && manualCandidate == null) {
                        item {
                            Text(
                                stringResource(R.string.no_matching_apps),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
        dismissButton = {
            // 不可撤销 → 二次确认（与诊断页「清空记录」同一条规矩）
            var confirmClear by remember { mutableStateOf(false) }
            TextButton(
                onClick = { confirmClear = true },
                enabled = hiddenApps.isNotEmpty(),
            ) { Text(stringResource(R.string.common_clear)) }
            if (confirmClear) {
                AlertDialog(
                    onDismissRequest = { confirmClear = false },
                    title = { Text(stringResource(R.string.clear_the_list)) },
                    text = { Text(stringResource(R.string.after_clearing_no_app_hides)) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmClear = false
                            onClear()
                        }) { Text(stringResource(R.string.common_clear)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
                    },
                )
            }
        },
    )
}

/** 应用图标：Drawable → Bitmap → ImageBitmap（跨 Compose 版本都稳的做法） */
@Composable
private fun AppIcon(pkg: String) {
    // Context 先在 composable 作用域里取一次 —— remember 的 lambda 不是 composable 上下文，
    // 在里面调 LocalContext.current 会直接编译不过。
    val context = LocalContext.current
    val bitmap = remember(pkg, context) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(pkg)
            val w = drawable.intrinsicWidth.coerceIn(1, 256)
            val h = drawable.intrinsicHeight.coerceIn(1, 256)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            bmp.asImageBitmap()
        }.getOrNull()
    }
    Box(
        modifier = Modifier.size(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(40.dp),
            ) {}
        }
    }
}

/**
 * 任意包名的显示名（名单里可能有不在「可启动应用」列表里的条目）。
 *
 * 取不到就回落成包名本身 —— 宁可难看，也不能让这一行变成空白、点不到取消。
 */
private fun appLabel(context: android.content.Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)
