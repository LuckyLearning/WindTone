package com.luckylearning.windtone.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.data.aliyun.AliyunAuth
import com.luckylearning.windtone.data.aliyun.AliyunDrive
import com.luckylearning.windtone.data.aliyun.AliyunEndpoints
import com.luckylearning.windtone.data.aliyun.AliyunEntry
import com.luckylearning.windtone.data.aliyun.AliyunExchange
import com.luckylearning.windtone.data.formatByteSize
import com.luckylearning.windtone.data.model.Song
import kotlinx.coroutines.launch

/**
 * 阿里云盘（在线播放）—— 授权 + 目录浏览 + 播放。
 *
 * 授权走 **OpenList 方案**：授权本身在 OpenList 的网页工具（api.oplist.org）里完成 ——
 * 用户扫码登录、页面直接给出「刷新令牌（Refresh Token）」，复制后粘贴到本应用即可。
 * 这条路不要求应用注册回调地址、也不要求车机有浏览器（可以在手机上粘贴完再同步感受
 * 同一份登录态），刷新令牌由 [AliyunAuth] 负责换取 access_token 并在过期前自动续期。
 *
 * 与百度网盘页的差异都来自开放平台本身：
 *  · 登录只有一个动作（粘贴令牌），没有设备码/二维码弹层；
 *  · 目录没有路径概念，全靠 file_id，导航用**栈**（进目录压栈、返回弹栈）；
 *  · 空间分「备份盘 / 资源库」两块，可切换（见 AliyunRules.pickDrive 的兜底顺序）；
 *  · 直链没有批量接口，播放一个取一个；「播放本目录全部」按顺序现取，
 *    进度给到第几首，避免大目录下像卡死。
 */
@Composable
internal fun AliyunPanPage(
    onPlay: (List<Song>, Int) -> Unit,
) {
    val context = LocalContext.current
    val auth = remember { AliyunAuth(context) }
    val drive = remember(auth) { AliyunDrive(auth) }
    val scope = rememberCoroutineScope()

    var loggedIn by remember { mutableStateOf(auth.isLoggedIn()) }
    var nickname by remember { mutableStateOf("") }
    /** 当前使用的盘（drive_id + 显示名）；取不到时置空并在界面提示 */
    var driveId by remember { mutableStateOf("") }
    var driveName by remember { mutableStateOf("") }
    /** 可切换的盘：Pair(显示名, drive_id) */
    var drives by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    /** 目录栈：最后一个元素是当前目录的 file_id（根目录固定叫 `root`） */
    var stack by remember { mutableStateOf(listOf(AliyunDrive.ROOT_ID)) }
    var entries by remember { mutableStateOf<List<AliyunEntry>>(emptyList()) }
    var nextMarker by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showPasteToken by remember { mutableStateOf(false) }

    fun refreshAccount() {
        scope.launch {
            progress = "正在读取账号信息…"
            drive.driveInfo()
                .onSuccess { info ->
                    nickname = info.nickname
                    drives = info.availableDrives()
                    val preferred = info.preferredDriveId().orEmpty()
                    driveId = preferred
                    driveName = drives.firstOrNull { it.second == preferred }?.first.orEmpty()
                }
                .onFailure { error = it.message ?: "读取账号信息失败" }
            progress = ""
        }
    }

    fun load(dirId: String, marker: String = "", append: Boolean = false) {
        if (driveId.isBlank()) return
        loading = true
        error = null
        scope.launch {
            drive.list(driveId, dirId, marker)
                .onSuccess { page ->
                    entries = if (append) entries + page.entries else page.entries
                    nextMarker = page.nextMarker
                }
                .onFailure { error = it.message ?: "列目录失败" }
            loading = false
        }
    }

    LaunchedEffect(loggedIn) {
        if (!loggedIn) return@LaunchedEffect
        refreshAccount()
    }

    // 拿到盘 id 后自动加载根目录（只加载一次）
    LaunchedEffect(driveId) {
        if (loggedIn && driveId.isNotBlank() && entries.isEmpty()) {
            load(AliyunDrive.ROOT_ID)
        }
    }

    SectionTitle("阿里云盘")
    Text(
        if (auth.hasClientCredentials()) {
            "已配置应用凭据：刷新令牌直连官方接口。授权在浏览器里完成，令牌只保存在本机。"
        } else {
            "未配置应用凭据：刷新令牌走 OpenList 在线接口（无需自备密钥）。令牌只保存在本机。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )

    if (!loggedIn) {
        Text(
            "获取令牌的步骤：① 打开获取令牌页面 → ② 选择「阿里云盘 OAuth2 扫码登录」→ " +
                "③ 勾选「使用 OpenList 提供的参数」→ ④ 点「获取 Token」并用手机扫码授权 → " +
                "⑤ 复制页面给出的「刷新令牌（Refresh Token）」回来粘贴。" +
                "应用对网盘只读，不会上传、修改或删除任何文件。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        ActionRow(
            title = "打开获取令牌页面",
            subtitle = "在浏览器里打开 ${AliyunEndpoints.TOKEN_TOOL_URL}，扫码授权后复制刷新令牌",
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(AliyunEndpoints.TOKEN_TOOL_URL))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { error = it.message ?: "无法打开浏览器" }
            },
            chevron = true,
        )
        ActionRow(
            title = "粘贴刷新令牌",
            subtitle = "把上一步拿到的 Refresh Token 粘贴进来；应用会先校验再保存",
            onClick = { showPasteToken = true },
            chevron = true,
        )
        ActionRow(
            title = "打开阿里云盘 App",
            subtitle = "整理音乐文件时可以直接在网盘 App 里操作",
            onClick = { openAliyunPan(context) },
            chevron = true,
        )
        if (progress.isNotBlank()) {
            Text(
                progress,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        if (showPasteToken) {
            PasteTokenDialog(
                onConfirm = { pasted ->
                    showPasteToken = false
                    progress = "正在校验令牌…"
                    scope.launch {
                        when (val result = auth.applyRefreshToken(pasted)) {
                            is AliyunExchange.Ready -> {
                                loggedIn = true
                                refreshAccount()
                            }
                            is AliyunExchange.Failed -> error = result.reason
                        }
                        progress = ""
                    }
                },
                onDismiss = { showPasteToken = false },
            )
        }
        error?.let { msg ->
            AlertDialog(
                onDismissRequest = { error = null },
                title = { Text("没能完成") },
                text = { Text(msg) },
                confirmButton = {
                    TextButton(onClick = { error = null }) { Text("好") }
                },
            )
        }
        return
    }

    // ---------------------------------------------------------------- 已登录
    Text(
        text = "已登录" + if (nickname.isNotBlank()) "：$nickname" else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { load(stack.last()) }) { Text("刷新") }
        if (stack.size > 1) {
            TextButton(onClick = {
                stack = stack.dropLast(1)
                entries = emptyList()
                nextMarker = ""
                load(stack.last())
            }) { Text("返回上级") }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { openAliyunPan(context) }) { Text("打开网盘 App") }
        TextButton(onClick = {
            auth.signOut()
            loggedIn = false
            nickname = ""
            driveId = ""
            driveName = ""
            drives = emptyList()
            entries = emptyList()
            stack = listOf(AliyunDrive.ROOT_ID)
            nextMarker = ""
        }) { Text("退出登录") }
    }

    // 盘切换：只有账号确实有两个盘时才出现
    if (drives.size > 1) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "当前盘：${driveName.ifBlank { "未知" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            drives.forEach { (name, id) ->
                if (id != driveId) {
                    TextButton(onClick = {
                        driveId = id
                        driveName = name
                        entries = emptyList()
                        nextMarker = ""
                        stack = listOf(AliyunDrive.ROOT_ID)
                        load(AliyunDrive.ROOT_ID)
                    }) { Text("切到$name") }
                }
            }
        }
    }

    if (driveId.isBlank()) {
        Text(
            "正在读取账号信息…",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }

    val audioEntries = entries.filter { it.isPlayableAudio }

    /**
     * 播放：队列始终是「本目录全部音频」，[startId] 指定从哪一首开始（null = 第一首）。
     *
     * 直链没有批量接口，按队列顺序逐个现取；没取到地址的条目直接跳过，
     * 起始位置按 fileId 反查（不能按 audioEntries 下标，那会在有失败时播错歌）。
     */
    fun playFrom(startId: String?) {
        if (audioEntries.isEmpty()) return
        loading = true
        error = null
        scope.launch {
            val songs = mutableListOf<Song>()
            audioEntries.forEachIndexed { index, entry ->
                progress = "正在取播放地址（${index + 1}/${audioEntries.size}）…"
                drive.downloadUrl(driveId, entry.fileId)
                    .onSuccess { songs += drive.toSong(entry, it) }
            }
            progress = ""
            loading = false
            if (songs.isEmpty()) {
                error = "没有取到可播放的地址（文件可能已被删除、移入回收站或被屏蔽）"
            } else {
                val idx = startId
                    ?.let { id -> songs.indexOfFirst { it.uri.fragment == "wt=$id" } }
                    ?.takeIf { it >= 0 } ?: 0
                onPlay(songs, idx)
            }
        }
    }

    if (audioEntries.isNotEmpty()) {
        TextButton(
            enabled = !loading,
            onClick = { playFrom(null) },
        ) { Text("播放本目录全部音频（${audioEntries.size}）") }
    }

    if (loading || progress.isNotBlank()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(progress.ifBlank { "正在读取网盘…" }, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (!loading && entries.isEmpty() && driveId.isNotBlank()) {
        Text(
            "这个目录里没有内容。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }

    entries.forEach { entry ->
        AliyunEntryRow(
            entry = entry,
            onOpenDir = {
                stack = stack + entry.fileId
                entries = emptyList()
                nextMarker = ""
                load(entry.fileId)
            },
            onPlayFile = { playFrom(entry.fileId) },
        )
    }

    if (nextMarker.isNotBlank() && !loading) {
        TextButton(onClick = { load(stack.last(), nextMarker, append = true) }) {
            Text("加载更多")
        }
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("没能完成") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { error = null }) { Text("好") }
            },
        )
    }
}

@Composable
private fun AliyunEntryRow(
    entry: AliyunEntry,
    onOpenDir: () -> Unit,
    onPlayFile: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (entry.isDir) "📁 ${entry.name}" else entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.isDir) {
                Text(
                    formatByteSize(entry.sizeBytes) +
                        if (!entry.isPlayableAudio) " · 格式暂不支持播放" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (entry.isDir) {
            TextButton(onClick = onOpenDir) { Text("进入") }
        } else if (entry.isPlayableAudio) {
            TextButton(onClick = onPlayFile) { Text("播放") }
        }
    }
}

/** 粘贴 refresh_token 的弹层（OpenList 方案的唯一登录动作） */
@Composable
private fun PasteTokenDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴刷新令牌") },
        text = {
            Column {
                Text(
                    "把获取令牌页面上显示的「刷新令牌（Refresh Token）」整段粘贴进来。" +
                        "应用会先换一次令牌做校验，成功才保存。令牌是敏感凭据，只保存在本机，" +
                        "退出登录即清除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.trim() },
                    label = { Text("refresh_token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank(),
                onClick = { onConfirm(input) },
            ) { Text("登录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 调起本机阿里云盘 App。
 *
 * 先按包名拉起（`com.alicloud.databox`），失败再退回 `aliyunpan://` scheme ——
 * 与百度页同一套双保险。
 */
private fun openAliyunPan(context: Context) {
    val byPackage = Intent().apply {
        setClassName("com.alicloud.databox", "com.alicloud.databox.main.MainActivity")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val started = runCatching { context.startActivity(byPackage) }.isSuccess ||
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("aliyunpan://")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.isSuccess
    if (!started) {
        android.widget.Toast.makeText(context, "没有找到阿里云盘 App", android.widget.Toast.LENGTH_SHORT).show()
    }
}
