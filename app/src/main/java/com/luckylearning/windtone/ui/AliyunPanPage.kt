package com.luckylearning.windtone.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.data.aliyun.AliyunAuth
import com.luckylearning.windtone.data.aliyun.AliyunDrive
import com.luckylearning.windtone.data.aliyun.AliyunEndpoints
import com.luckylearning.windtone.data.aliyun.AliyunEntry
import com.luckylearning.windtone.data.aliyun.AliyunExchange
import com.luckylearning.windtone.data.aliyun.AliyunPlaybackWindow
import com.luckylearning.windtone.data.aliyun.AliyunProbe
import com.luckylearning.windtone.data.aliyun.AliyunRules
import com.luckylearning.windtone.data.aliyun.AliyunSavedPlaylists
import com.luckylearning.windtone.data.aliyun.AliyunScanRules
import com.luckylearning.windtone.playback.CloudUrlMemo
import com.luckylearning.windtone.data.aliyun.AliyunTrackStore
import com.luckylearning.windtone.data.formatByteSize
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.ui.theme.AppVisual
import com.luckylearning.windtone.ui.theme.windShadow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 阿里云盘（在线播放）—— **一级公民页**（v1.1.75 起：从「设置 → 在线播放」提升为
 * 抽屉里的独立入口，与歌曲 / 专辑 / 歌手同级）。
 *
 * 授权**在应用内完成**（[AliyunQrLoginDialog]）：点「扫码授权」弹出二维码，用阿里云盘
 * App 扫码确认后自动拿到 refresh_token 存进 [AliyunAuth]，全程不跳浏览器、不复制粘贴。
 * 走的是阿里官方 passport 接口（详见 [AliyunQrLogin]）；
 * 手动粘贴 refresh_token 保留为兜底入口（OpenList 网页工具改版时仍走得通）。
 *
 * 与本地曲库的差异都来自开放平台本身：
 *  · 目录没有路径概念，全靠 file_id，导航用**栈**（进目录压栈、返回弹栈）；
 *  · 空间分「备份盘 / 资源库」两块，可切换（见 AliyunRules.pickDrive 的兜底顺序）；
 *  · 直链没有批量接口，播放一个取一个；「播放本目录全部」按顺序现取，
 *    进度给到第几首，避免大目录下像卡死。
 *  · 支持把当前目录（含下 2 层子目录）递归扫描成歌单（[AliyunScanRules]），
 *    歌单存 fileId 引用，播放时现取直链（见 AliyunSavedPlaylists）。
 *
 * 版式（v1.1.75 重构）：固定顶栏（返回 / 标题 / 汉堡 / 溢出菜单）+ 可滚动正文；
 * 未登录是居中 hero（渐变云徽 + 授权引导），已登录是账号卡 + 目录浏览。
 * 扫描 / 自检这类低频操作收进顶栏溢出菜单，目录浏览区只留高频动作。
 */
@Composable
internal fun AliyunPanPage(
    onPlay: (List<Song>, Int) -> Unit,
    onClose: () -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
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
    val tracks = remember { AliyunTrackStore(context) }
    var playlists by remember { mutableStateOf(savedPlaylists.all()) }
    var query by remember { mutableStateOf("") }
    var editingPlaylistId by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableStateOf("") }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    /** 播放自检：运行中标记 + 结果文本（null = 没跑过） */
    var probeRunning by remember { mutableStateOf(false) }
    var probeText by remember { mutableStateOf<String?>(null) }
    /** 扫描完成、待命名的结果（null = 无） */
    var namingScan by remember { mutableStateOf<AliyunScanRules.ScanOutcome?>(null) }
    /** 读取账号信息（driveInfo）最近一次失败的原因（成功即清空）；driveId 为空时显示在页面上 */
    var accountError by remember { mutableStateOf<String?>(null) }
    /** 顶栏溢出菜单 */
    var menuOpen by remember { mutableStateOf(false) }

    /**
     * 读账号信息：拿昵称与盘 id。返回失败原因（成功返回 null）。
     *
     * 之所以单独拆出来：这个失败**不能只弹一次窗就完事** —— driveId 拿不到，
     * 后面列目录、取直链、自检全都发不了（会被 BadRequest 挡住），用户看到
     * 的却是「正在读取账号信息…」永远挂着，真实的报错早被关掉了。
     * 失败原因要一直留在页面上 + 给「重试」，自检/播放前发现盘 id 为空也要能
     * 现场重新拉一次。
     */
    suspend fun fetchAccount(): String? {
        progress = "正在读取账号信息…"
        val err = drive.driveInfo().fold(
            onSuccess = { info ->
                nickname = info.nickname
                drives = info.availableDrives()
                val preferred = info.preferredDriveId().orEmpty()
                driveId = preferred
                driveName = drives.firstOrNull { it.second == preferred }?.first.orEmpty()
                null
            },
            onFailure = { it.message ?: "读取账号信息失败" },
        )
        accountError = err
        progress = ""
        return err
    }

    fun refreshAccount() {
        scope.launch { fetchAccount() }
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

    /** 退出登录：清凭据 + 清界面状态（不动本地歌单存档） */
    fun signOut() {
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
        query = ""
    }

    /** 回到上一级目录；备份盘根目录的上级是资源库「全部文件」（见 AliyunRules.backupRootParent） */
    fun goUp() {
        val fileDriveId = AliyunRules.backupRootParent(driveId, drives, stack.size <= 1)
        if (stack.size > 1) {
            stack = stack.dropLast(1)
            dirNames = dirNames.dropLast(1)
            entries = emptyList()
            nextMarker = ""
            load(stack.last())
        } else if (fileDriveId != null) {
            driveId = fileDriveId
            driveName = drives.firstOrNull { it.second == fileDriveId }?.first.orEmpty()
            entries = emptyList()
            nextMarker = ""
            stack = listOf(AliyunDrive.ROOT_ID)
            dirNames = listOf("")
            load(AliyunDrive.ROOT_ID)
        }
    }

    /** 切换到另一块盘（备份盘 / 资源库），回到它的根目录 */
    fun switchDrive(id: String, name: String) {
        driveId = id
        driveName = name
        entries = emptyList()
        nextMarker = ""
        stack = listOf(AliyunDrive.ROOT_ID)
        dirNames = listOf("")
        load(AliyunDrive.ROOT_ID)
    }

    val audioEntries = entries.filter { it.isPlayableAudio }

    /** 整表进队列，只给窗口里的歌取直链。当前这首失败就改播窗口里后面那首。 */
    fun openQueue(songs: List<Song>, start: Int) {
        if (songs.isEmpty()) return
        loading = true
        error = null
        progress = "正在准备附近几首的播放地址…"
        scope.launch {
            val filled = drive.fillWindow(songs, start)
            // 预填好的直链记进备忘录：onPlay 的 setMediaItems 过 resolve 时直接取用，
            // 起播那一首不再二次现取
            filled.filter { !AliyunPlaybackWindow.isPending(it.uri.scheme) }
                .forEach { CloudUrlMemo.remember(it.stableKey, it.uri.toString()) }
            val playAt = AliyunPlaybackWindow.firstReady(
                filled.map { AliyunPlaybackWindow.isPending(it.uri.scheme) },
                start,
            )
            progress = ""
            loading = false
            if (playAt == null) {
                error = "没有取到可播放的地址（文件可能已被删除、移入回收站或被屏蔽）"
            } else {
                onPlay(filled, playAt)
            }
        }
    }

    /**
     * 播放：队列是本目录全部音频，直链只取当前曲附近的一个窗口。
     * [startId] 指定从哪一首开始（null = 第一首）。
     */
    fun playFrom(startId: String?) {
        if (audioEntries.isEmpty()) return
        if (driveId.isBlank()) {
            error = "还没读到账号信息（盘 id 为空），等账号信息加载完再播放"
            return
        }
        val folder = dirNames.lastOrNull()?.takeIf { it.isNotBlank() }
        val parent = stack.last()
        val songs = audioEntries.map { entry ->
            tracks.remember(driveId, entry.fileId, entry.name, folder, parent)
            drive.pendingSong(entry, driveId, folder, tracks.get(driveId, entry.fileId))
        }
        val start = startId
            ?.let { id -> audioEntries.indexOfFirst { it.fileId == id } }
            ?.takeIf { it >= 0 } ?: 0
        openQueue(songs, start)
    }

    /** 递归扫描当前目录（含下 2 层子目录）成歌单，完成后弹命名框 */
    fun startScan() {
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
    }

    /** 播放自检：测当前目录（或云盘歌单）第一首歌的直链能不能下载 */
    fun startProbe() {
        if (playlists.isEmpty() && audioEntries.isEmpty()) {
            error = "这个目录里没有音频文件，先进入一个有歌的目录再自检"
            return
        }
        probeRunning = true
        probeText = null
        scope.launch {
            // 盘 id 没读到，后面 everything 都是 BadRequest —— 先补一次账号信息，
            // 顺便把真实失败原因带出来（以前这里只会看到干巴巴的 BadRequest）
            if (driveId.isBlank()) {
                probeText = "还没读到账号信息，正在重新获取…"
                fetchAccount()?.let { err ->
                    probeRunning = false
                    probeText = "取不到账号的盘 id：$err\n\n「读取账号信息」这一步失败了，直链无从谈起；" +
                        "先解决它（页面上有重试按钮），再回来测直链。"
                    return@launch
                }
            }
            val target = audioEntries.firstOrNull()?.fileId
                ?: playlists.firstOrNull()?.files?.firstOrNull()?.fileId
            if (target == null) {
                probeRunning = false
                probeText = "没有可测的文件"
                return@launch
            }
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
                        "可以到「设置 → 在线播放」清一次在线缓存再试。"
                } else {
                    "\n\n主机：${result.host}"
                }
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

    // ================================================================ 页面骨架

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ------------------------------------------------ 固定顶栏
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 2.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "阿里云盘",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                Text(
                    when {
                        !loggedIn -> "在线音乐 · 未连接"
                        nickname.isNotBlank() -> "已登录 · $nickname"
                        else -> "已登录"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loggedIn) {
                IconButton(onClick = { onOpenDrawer() }) {
                    Icon(
                        AppIcons.Menu,
                        contentDescription = "打开菜单",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "更多操作",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("打开网盘 App") },
                            onClick = {
                                menuOpen = false
                                openAliyunPan(context)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("扫描为歌单（含 2 层子目录）") },
                            enabled = !loading && !scanning,
                            onClick = {
                                menuOpen = false
                                startScan()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("播放自检（测直链）") },
                            enabled = !loading && !scanning,
                            onClick = {
                                menuOpen = false
                                startProbe()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("退出登录") },
                            onClick = {
                                menuOpen = false
                                signOut()
                            },
                        )
                    }
                }
            }
        }

        // ------------------------------------------------ 可滚动正文
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (!loggedIn) {
                NotLoggedInBody(
                    auth = auth,
                    progress = progress,
                    onScan = { showQrLogin = true },
                    onPasteToken = { showPasteToken = true },
                    onOpenApp = { openAliyunPan(context) },
                )
            } else {
                LoggedInBody(
                    nickname = nickname,
                    driveId = driveId,
                    driveName = driveName,
                    drives = drives,
                    accountError = accountError,
                    loading = loading,
                    progress = progress,
                    scanning = scanning,
                    scanProgress = scanProgress,
                    scanJob = scanJob,
                    stackSize = stack.size,
                    playlists = playlists,
                    query = query,
                    onQuery = { query = it },
                    onRefreshAccount = {
                        accountError = null
                        refreshAccount()
                    },
                    onSwitchDrive = ::switchDrive,
                    onGoUp = ::goUp,
                    onRefresh = { load(stack.last()) },
                    onPlayAll = { playFrom(null) },
                    onStopScan = { scanJob?.cancel() },
                    onPlayPlaylist = { p ->
                        val songs = p.files.map { hit ->
                            val folder = hit.parentPath.substringAfterLast('/').ifBlank { hit.parentPath }
                            tracks.remember(p.driveId, hit.fileId, hit.name, folder, hit.parentFileId)
                            drive.pendingSong(
                                AliyunEntry(hit.fileId, hit.name, isDir = false, sizeBytes = 0L),
                                p.driveId,
                                folder,
                                tracks.get(p.driveId, hit.fileId),
                            )
                        }
                        openQueue(songs, 0)
                    },
                    onManagePlaylist = { editingPlaylistId = it },
                    onDeletePlaylist = {
                        savedPlaylists.remove(it)
                        playlists = savedPlaylists.all()
                    },
                    onLoadMore = { load(stack.last(), nextMarker, append = true) },
                    canLoadMore = nextMarker.isNotBlank() && !loading,
                    entries = entries,
                    onOpenDir = { entry ->
                        stack = stack + entry.fileId
                        dirNames = dirNames + entry.name
                        entries = emptyList()
                        nextMarker = ""
                        load(entry.fileId)
                    },
                    onPlayFile = { playFrom(it.fileId) },
                )
            }
        }
    }

    // ================================================================ 弹层（不随正文滚动）

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
    editingPlaylistId?.let { id ->
        playlists.firstOrNull { it.id == id }?.let { playlist ->
            PlaylistFilesDialog(
                playlist = playlist,
                query = query,
                onMove = { from, to ->
                    val ids = playlist.files.map { it.fileId }.toMutableList()
                    if (to !in ids.indices) return@PlaylistFilesDialog
                    val moved = ids.removeAt(from)
                    ids.add(to, moved)
                    savedPlaylists.reorder(playlist.id, ids)
                    playlists = savedPlaylists.all()
                },
                onRemove = { fileId ->
                    savedPlaylists.removeFile(playlist.id, fileId)
                    playlists = savedPlaylists.all()
                },
                onDismiss = { editingPlaylistId = null },
            )
        }
    }
}

// ================================================================ 未登录：hero 引导

@Composable
private fun NotLoggedInBody(
    auth: AliyunAuth,
    progress: String,
    onScan: () -> Unit,
    onPasteToken: () -> Unit,
    onOpenApp: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        // 渐变云徽（与抽屉品牌块同一套风之渐变）
        Box(
            modifier = Modifier
                .size(84.dp)
                .windShadow(level = 3, shape = CircleShape)
                .clip(CircleShape)
                .background(AppVisual.WindGradient),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.Cloud,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(42.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "连接你的阿里云盘",
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "扫码授权在应用内完成：用阿里云盘 App 扫二维码即可，无需跳转浏览器、无需复制粘贴。\n" +
                "应用对网盘只读，不会上传、修改或删除任何文件。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(24.dp))

        // 主操作：渐变胶囊「扫码授权」
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .windShadow(level = 2, shape = CircleShape)
                .clip(CircleShape)
                .background(AppVisual.WindGradient)
                .clickable(onClick = onScan),
        ) {
            Icon(AppIcons.Cloud, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("扫码授权", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Spacer(Modifier.height(12.dp))

        // 次操作：tonal 胶囊「手动粘贴令牌」
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onPasteToken),
        ) {
            Text(
                "手动粘贴令牌",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onOpenApp) { Text("打开阿里云盘 App") }

        Text(
            if (auth.hasClientCredentials()) {
                "已配置应用凭据：令牌刷新直连官方接口，只保存在本机。"
            } else {
                "未配置应用凭据：令牌刷新走 OpenList 在线接口，只保存在本机。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp),
        )
        if (progress.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(progress, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ================================================================ 已登录

@Composable
private fun LoggedInBody(
    nickname: String,
    driveId: String,
    driveName: String,
    drives: List<Pair<String, String>>,
    accountError: String?,
    loading: Boolean,
    progress: String,
    scanning: Boolean,
    scanProgress: String,
    scanJob: Job?,
    stackSize: Int,
    playlists: List<com.luckylearning.windtone.data.aliyun.AliyunPlaylist>,
    query: String,
    onQuery: (String) -> Unit,
    onRefreshAccount: () -> Unit,
    onSwitchDrive: (String, String) -> Unit,
    onGoUp: () -> Unit,
    onRefresh: () -> Unit,
    onPlayAll: () -> Unit,
    onStopScan: () -> Unit,
    onPlayPlaylist: (com.luckylearning.windtone.data.aliyun.AliyunPlaylist) -> Unit,
    onManagePlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onLoadMore: () -> Unit,
    canLoadMore: Boolean,
    entries: List<AliyunEntry>,
    onOpenDir: (AliyunEntry) -> Unit,
    onPlayFile: (AliyunEntry) -> Unit,
) {
    val audioEntries = entries.filter { it.isPlayableAudio }
    // ---------------------------------------------- 账号卡
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(AppVisual.WindGradient),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Cloud, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    nickname.ifBlank { "阿里云盘账号" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        driveName.isNotBlank() -> "当前盘：$driveName"
                        accountError != null -> "账号信息读取失败"
                        else -> "正在读取账号信息…"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "刷新当前目录",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 盘切换：只有账号确实有两个盘时才出现
        if (drives.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            ) {
                drives.forEach { (name, id) ->
                    val selected = id == driveId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                            )
                            .clickable(enabled = !selected) { onSwitchDrive(id, name) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            name,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    // 账号信息读取失败：常驻红字 + 重试（不能只弹一次窗，盘 id 拿不到后面全挂）
    if (driveId.isBlank()) {
        when {
            accountError != null -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        "读取账号信息失败：$accountError",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRefreshAccount) { Text("重试") }
                }
            }
            loading -> Unit // 目录加载指示器统一在下面画
        }
    }

    // ---------------------------------------------- 搜索
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = CircleShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (query.isBlank()) {
                    Text(
                        "搜索文件名",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 14.sp,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = "清空搜索",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }

    // ---------------------------------------------- 工具行：上级 / 播放全部
    val fileDriveId = AliyunRules.backupRootParent(driveId, drives, stackSize <= 1)
    if (stackSize > 1 || fileDriveId != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp),
        ) {
            TonalPill(
                icon = Icons.Filled.KeyboardArrowLeft,
                label = "上级",
                onClick = onGoUp,
            )
        }
    }
    if (audioEntries.isNotEmpty() && driveId.isNotBlank()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            val enabled = !loading && !scanning
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .alpha(if (enabled) 1f else 0.5f)
                    .windShadow(level = 2, shape = CircleShape)
                    .clip(CircleShape)
                    .background(AppVisual.WindGradient)
                    .clickable(enabled = enabled, onClick = onPlayAll)
                    .padding(horizontal = 16.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "播放本目录全部 · ${audioEntries.size} 首",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                )
            }
        }
    }

    // 扫描进度
    if (scanning) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(scanProgress, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { onStopScan() }) { Text("停止") }
        }
    }

    // ---------------------------------------------- 云盘歌单
    val shownPlaylists = if (query.isBlank()) playlists else playlists.filter { playlist ->
        playlist.name.contains(query, ignoreCase = true) ||
            playlist.files.any { it.name.contains(query, ignoreCase = true) }
    }
    if (shownPlaylists.isNotEmpty()) {
        Text(
            "云盘歌单（${shownPlaylists.size}）",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
        )
        shownPlaylists.forEach { p ->
            // 卡片样式与曲库分组卡一致（surfaceContainerLow + 圆角 + 图标位），
            // 次要操作收进溢出菜单，主操作（播放）常驻
            var cardMenuOpen by remember { mutableStateOf(false) }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                RoundedCornerShape(10.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            AppIcons.QueueMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${p.fileCount} 首 · 来源：${p.rootName.ifBlank { "根目录" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        enabled = !loading && !scanning,
                        onClick = { onPlayPlaylist(p) },
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "播放 ${p.name}",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Box {
                        IconButton(onClick = { cardMenuOpen = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "更多操作",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(expanded = cardMenuOpen, onDismissRequest = { cardMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("曲目管理") },
                                onClick = {
                                    cardMenuOpen = false
                                    onManagePlaylist(p.id)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除歌单") },
                                onClick = {
                                    cardMenuOpen = false
                                    onDeletePlaylist(p.id)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------- 目录内容
    if (loading || progress.isNotBlank()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(progress.ifBlank { "正在读取网盘…" }, style = MaterialTheme.typography.bodySmall)
        }
    }

    val shownEntries = if (query.isBlank()) entries else entries.filter { it.name.contains(query, ignoreCase = true) }
    if (!loading && shownEntries.isEmpty() && driveId.isNotBlank()) {
        Text(
            "这个目录里没有内容。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
    shownEntries.forEach { entry ->
        AliyunEntryRow(
            entry = entry,
            onOpenDir = { onOpenDir(entry) },
            onPlayFile = { onPlayFile(entry) },
        )
    }

    if (canLoadMore) {
        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        ) {
            TonalPill(label = "加载更多", onClick = onLoadMore)
        }
    }

    Spacer(Modifier.height(12.dp))
}

/** 小型 tonal 胶囊按钮（工具行用：上级 / 加载更多） */
@Composable
private fun TonalPill(
    label: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(2.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ================================================================ 目录条目行

@Composable
private fun AliyunEntryRow(
    entry: AliyunEntry,
    onOpenDir: () -> Unit,
    onPlayFile: () -> Unit,
) {
    val playable = entry.isPlayableAudio
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.isDir || playable) {
                if (entry.isDir) onOpenDir() else onPlayFile()
            }
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (entry.isDir) AppIcons.Folder else AppIcons.MusicNote,
                contentDescription = null,
                tint = if (entry.isDir) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.isDir) {
                Text(
                    formatByteSize(entry.sizeBytes) +
                        if (!playable) " · 格式暂不支持播放" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "文件夹",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (entry.isDir) {
            Icon(
                AppIcons.ChevronRight,
                contentDescription = "进入 ${entry.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        } else if (playable) {
            IconButton(onClick = onPlayFile) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "播放 ${entry.name}",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ================================================================ 弹层（保持原有逻辑）

@Composable
private fun PlaylistFilesDialog(
    playlist: com.luckylearning.windtone.data.aliyun.AliyunPlaylist,
    query: String,
    onMove: (Int, Int) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val files = playlist.files.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(playlist.name) },
        text = {
            Column {
                files.forEachIndexed { index, hit ->
                    val realIndex = playlist.files.indexOfFirst { it.fileId == hit.fileId }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(hit.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(enabled = realIndex > 0, onClick = { onMove(realIndex, realIndex - 1) }) { Text("上移") }
                        TextButton(
                            enabled = realIndex in 0 until playlist.files.lastIndex,
                            onClick = { onMove(realIndex, realIndex + 1) },
                        ) { Text("下移") }
                        TextButton(onClick = { onRemove(hit.fileId) }) { Text("移除") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
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
