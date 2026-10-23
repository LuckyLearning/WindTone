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
import com.luckylearning.windtone.data.aliyun.AliyunProbe
import com.luckylearning.windtone.data.aliyun.AliyunSavedPlaylists
import com.luckylearning.windtone.data.aliyun.AliyunScanRules
import com.luckylearning.windtone.data.formatByteSize
import com.luckylearning.windtone.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 阿里云盘（在线播放）—— 授权 + 目录浏览 + 播放。
 *
 * 授权**在应用内完成**（[AliyunQrLoginDialog]）：点「扫码授权」弹出二维码，用阿里云盘
 * App 扫码确认后自动拿到 refresh_token 存进 [AliyunAuth]，全程不跳浏览器、不复制粘贴。
 * 复刻的是 OpenList-APIPages（api.oplist.org）网页工具的同一套接口（详见 [AliyunQrLogin]）；
 * 手动粘贴 refresh_token 保留为兜底入口（网页工具改版时仍走得通）。
 * 这条路不要求应用注册回调地址、也不要求车机有浏览器（手机上授权完，车机登录态可用）。
 *
 * 与百度网盘页的差异都来自开放平台本身：
 *  · 登录后只有目录浏览一个主界面，没有设备码/二维码弹层；
 *  · 目录没有路径概念，全靠 file_id，导航用**栈**（进目录压栈、返回弹栈）；
 *  · 空间分「备份盘 / 资源库」两块，可切换（见 AliyunRules.pickDrive 的兜底顺序）；
 *  · 直链没有批量接口，播放一个取一个；「播放本目录全部」按顺序现取，
 *    进度给到第几首，避免大目录下像卡死。
 *  · 支持把当前目录（含下 2 层子目录）递归扫描成歌单（[AliyunScanRules]），
 *    歌单存 fileId 引用，播放时现取直链（见 AliyunSavedPlaylists）。
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
    var showQrLogin by remember { mutableStateOf(false) }
    /** 目录名栈（与 [stack] 同步：根目录为空串），扫描歌单取名字用 */
    var dirNames by remember { mutableStateOf(listOf("")) }
    /** 保存云盘歌单的本地存档（改名 / 删除也走它） */
    val savedPlaylists = remember { AliyunSavedPlaylists(context) }
    var playlists by remember { mutableStateOf(savedPlaylists.all()) }
    var scanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableStateOf("") }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    /** 播放自检：运行中标记 + 结果文本（null = 没跑过） */
    var probeRunning by remember { mutableStateOf(false) }
    var probeText by remember { mutableStateOf<String?>(null) }
    /** 扫描完成、待命名的结果（null = 无） */
    var namingScan by remember { mutableStateOf<AliyunScanRules.ScanOutcome?>(null) }

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
            "已配置应用凭据：扫码授权在应用内完成，令牌刷新直连官方接口，只保存在本机。"
        } else {
            "未配置应用凭据：扫码授权在应用内完成（无需自备密钥），令牌刷新走 OpenList 在线接口，只保存在本机。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )

    if (!loggedIn) {
        Text(
            "扫码授权在应用内完成：点「扫码授权」用阿里云盘 App 扫弹出的二维码即可，" +
                "无需跳转浏览器、无需复制粘贴。应用对网盘只读，不会上传、修改或删除任何文件。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        ActionRow(
            title = "扫码授权",
            subtitle = "用阿里云盘 App 扫码，授权在应用内自动完成",
            onClick = { showQrLogin = true },
            chevron = true,
        )
        ActionRow(
            title = "手动粘贴令牌",
            subtitle = "备用：在 ${AliyunEndpoints.TOKEN_TOOL_URL} 扫码拿到 Refresh Token 后粘贴进来，先校验再保存",
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
        if (showQrLogin) {
            AliyunQrLoginDialog(
                auth = auth,
                onLoggedIn = {
                    showQrLogin = false
                    loggedIn = true
                    refreshAccount()
                },
                onError = { msg ->
                    showQrLogin = false
                    error = msg
                },
                onDismiss = { showQrLogin = false },
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
                dirNames = dirNames.dropLast(1)
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
            dirNames = listOf("")
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
                        dirNames = listOf("")
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
            enabled = !loading && !scanning,
            onClick = { playFrom(null) },
        ) { Text("播放本目录全部音频（${audioEntries.size}）") }
    }

    // ---------------------------------------------- 播放自检（直链能不能下载）
    // 播不了时第一件事就是分清「直链取不到/下不动」还是「播放器侧的问题」，
    // 否则只能看到一个 ERROR_CODE_IO_UNSPECIFIED。
    TextButton(
        enabled = !loading && !scanning,
        onClick = {
            val target = audioEntries.firstOrNull()?.fileId
                ?: playlists.firstOrNull()?.files?.firstOrNull()?.fileId
            if (target == null) {
                error = "这个目录里没有音频文件，先进入一个有歌的目录再自检"
                return@TextButton
            }
            probeRunning = true
            probeText = null
            scope.launch {
                val url = drive.downloadUrl(driveId, target)
                    .getOrElse {
                        probeRunning = false
                        probeText = "取直链失败：${it.message ?: "未知原因"}"
                        return@launch
                    }
                val result = AliyunProbe.probe(url)
                probeRunning = false
                probeText = result.summary() +
                    if (result.ok) {
                        "\n\n直链本身没问题，若仍播不了，问题在播放器侧（本地缓存或文件格式）。" +
                            "可以到「设置 → 关于与诊断」清一次在线缓存再试。"
                    } else {
                        "\n\n主机：${result.host}"
                    }
            }
        },
    ) { Text(if (probeRunning) "正在自检…" else "播放自检（测直链）") }

    // ---------------------------------------------- 递归扫描成歌单（含下 2 层子目录）
    TextButton(
        enabled = !loading && !scanning,
        onClick = {
            val rootId = stack.last()
            val rootName = dirNames.lastOrNull().orEmpty()
            scanning = true
            scanProgress = "正在扫描…"
            error = null
            scanJob = scope.launch {
                try {
                    val outcome = drive.scanPlaylist(driveId, rootId) { dirs, files ->
                        scanProgress = "已扫 $dirs 个目录，收到 $files 首…"
                    }
                    if (outcome.files.isEmpty()) {
                        error = if (outcome.skipped.isNotEmpty()) {
                            "没有扫到音频文件。" + outcome.skipped.take(3).joinToString("；")
                        } else {
                            "这个目录（含 2 层子目录）里没有音频文件"
                        }
                    } else {
                        namingScan = outcome
                    }
                } catch (e: CancellationException) {
                    // 用户取消：不算错误
                } catch (e: Exception) {
                    error = e.message ?: "扫描失败"
                }
                scanning = false
                scanProgress = ""
            }
        },
    ) { Text("扫描为歌单（含 2 层子目录）") }

    if (scanning) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(scanProgress, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { scanJob?.cancel() }) { Text("停止") }
        }
    }

    // 扫描完成 → 命名入库
    namingScan?.let { outcome ->
        NameScanDialog(
            defaultName = dirNames.lastOrNull().orEmpty().ifBlank { "云盘歌单" },
            onConfirm = { name ->
                namingScan = null
                savedPlaylists.add(name, driveId, dirNames.lastOrNull().orEmpty(), outcome.files)
                playlists = savedPlaylists.all()
            },
            onDismiss = { namingScan = null },
        )
    }

    // 已保存的云盘歌单（存 fileId 引用，播放时现取直链）
    if (playlists.isNotEmpty()) {
        Text(
            "云盘歌单（${playlists.size}）",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
        playlists.forEach { p ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${p.fileCount} 首 · 来源：${p.rootName.ifBlank { "根目录" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    enabled = !loading && !scanning,
                    onClick = {
                        loading = true
                        error = null
                        scope.launch {
                            val songs = mutableListOf<Song>()
                            p.files.forEachIndexed { index, hit ->
                                progress = "正在取播放地址（${index + 1}/${p.files.size}）…"
                                drive.downloadUrl(p.driveId, hit.fileId)
                                    .onSuccess {
                                        songs += drive.toSong(
                                            AliyunEntry(hit.fileId, hit.name, isDir = false, sizeBytes = 0L),
                                            it,
                                        )
                                    }
                            }
                            progress = ""
                            loading = false
                            if (songs.isEmpty()) {
                                error = "没有取到可播放的地址（文件可能已被删除或被屏蔽）"
                            } else {
                                onPlay(songs, 0)
                            }
                        }
                    },
                ) { Text("播放") }
                TextButton(onClick = {
                    savedPlaylists.remove(p.id)
                    playlists = savedPlaylists.all()
                }) { Text("删除") }
            }
        }
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
                dirNames = dirNames + entry.name
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

    probeText?.let { msg ->
        AlertDialog(
            onDismissRequest = { probeText = null },
            title = { Text("播放自检") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { probeText = null }) { Text("知道了") }
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

/** 扫描完成后给歌单命名（默认用扫描根目录名） */
@Composable
private fun NameScanDialog(
    defaultName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存为歌单") },
        text = {
            Column {
                Text(
                    "扫描结果已就绪，起个名字存下来。歌单只保存文件引用，播放时现取直链，" +
                        "文件被删除或移走后播放会自动跳过。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("歌单名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank(),
                onClick = { onConfirm(input) },
            ) { Text("保存") }
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
