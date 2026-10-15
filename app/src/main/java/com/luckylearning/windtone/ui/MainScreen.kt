package com.luckylearning.windtone.ui
import com.luckylearning.windtone.state.LibraryView
import com.luckylearning.windtone.state.GroupInfo
import com.luckylearning.windtone.state.PlaylistInfo
import com.luckylearning.windtone.state.LibraryUiState
import com.luckylearning.windtone.state.PlayerUiState
import com.luckylearning.windtone.state.VirtualPlaylist

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luckylearning.windtone.data.ScanScope
import com.luckylearning.windtone.data.SortOrder
import com.luckylearning.windtone.data.model.Song
import kotlinx.coroutines.launch

fun requiredAudioPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** 底部导航用的视图图标 */
private val LibraryView.icon: ImageVector
    get() = when (this) {
        LibraryView.SONGS -> AppIcons.MusicNote
        LibraryView.ALBUM -> AppIcons.Album
        LibraryView.ARTIST -> AppIcons.Artist
        LibraryView.FOLDER -> AppIcons.Folder
        LibraryView.PLAYLIST -> AppIcons.LibraryMusic
    }

@Composable
fun MainScreen(
    lib: LibraryViewModel,
    playerVm: PlayerViewModel,
    updateVm: UpdateViewModel,
    settings: SettingsViewModel,
) {
    val library by lib.library.collectAsStateWithLifecycle()
    val player by playerVm.player.collectAsStateWithLifecycle()
    val update by updateVm.update.collectAsStateWithLifecycle()
    // 诊断页的「数据与存储」快照：null 表示尚未刷新过，进诊断子页时才拉一次
    val maintenanceReport by settings.maintenanceReport.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val audioPermission = remember { requiredAudioPermission() }
    var granted by remember { mutableStateOf(hasPermission(context, audioPermission)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val ok = result[audioPermission] == true || hasPermission(context, audioPermission)
        granted = ok
        lib.setAudioPermission(ok)
        // 只有在用户主动选择「扫描全盘」后才会走到这里
        if (ok) lib.startFullScan() else lib.notifyScanPermissionDenied()
    }

    // 用户是否正在通过「指定目录」路径挑文件夹（挑完要强制切换到目录范围）
    var pendingFolderScope by remember { mutableStateOf(false) }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            lib.addFolder(uri, folderScopeOnly = pendingFolderScope)
        } else {
                }
        pendingFolderScope = false
    }

    val m3uImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) lib.importM3U(uri) }

    // M3U 导出：先记录待导出的歌单，等用户选完保存位置再写文件
    var pendingExport by remember { mutableStateOf<PlaylistInfo?>(null) }
    val m3uExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri ->
        val pl = pendingExport
        if (uri != null && pl != null) lib.exportPlaylistM3U(pl, uri)
        pendingExport = null
    }

    // 崩溃日志导出
    val crashExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> if (uri != null) settings.exportCrashLog(uri) }

    // 删除歌曲：Android 11+ 媒体库文件由系统弹出确认框，结果在这里回来
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> lib.onSystemDeleteResult(result.resultCode == Activity.RESULT_OK) }

    // 长按 / 更多操作相关状态
    var actionSong by remember { mutableStateOf<Song?>(null) }
    var deleteConfirmSong by remember { mutableStateOf<Song?>(null) }
    var folderRemoveConfirmGroup by remember { mutableStateOf<GroupInfo?>(null) }
    var addToPlaylistSong by remember { mutableStateOf<Song?>(null) }
    var editSong by remember { mutableStateOf<Song?>(null) }
    var infoSong by remember { mutableStateOf<Song?>(null) }
    var newPlaylistDialog by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showScanScope by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var crashLogText by remember { mutableStateOf<String?>(null) }
    var crashLogCount by remember { mutableStateOf(0) }

    // 「出现在其他应用上」授权状态。用户在系统设置里授权后返回本应用时
    // 会重新读取一次（见下方 ON_RESUME），设置页据此决定开关能否打开。
    var canDrawOverlay by remember { mutableStateOf(playerVm.canDrawOverlay()) }

    // 桌面悬浮窗的运行状态：服务起没起来、失败在哪一步（见 LyricOverlayService 的上报）
    val overlayHealth by playerVm.overlayHealth.collectAsStateWithLifecycle()

    LaunchedEffect(showSettings) {
        if (showSettings) crashLogCount = settings.crashLogCount()
    }

    /**
     * 「扫描全盘」入口：先要媒体读取权限，授权成功后由 VM 启动全盘扫描。
     * 注意安装后不再自动申请任何权限，一律由用户点按钮触发。
     */
    val requestFullScan: () -> Unit = {
        if (granted) {
            lib.startFullScan()
        } else {
            val perms = mutableListOf(audioPermission)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    /** 选择目录并把扫描范围切成「指定目录」 */
    val pickFolderAsScope: () -> Unit = {
        pendingFolderScope = true
        folderLauncher.launch(null)
    }

    /** 单纯追加一个目录（当前是全盘扫描时保持全盘，目录内容合并进来） */
    val importFolder: () -> Unit = {
        pendingFolderScope = false
        folderLauncher.launch(null)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    lib.updateDeepScanFlag()
                    // 悬浮歌词授权是走系统设置页的，回来后必须重新读一次；
                    // 权限刚被授予时顺手把开关打开并起服务，省掉用户再点一次。
                    val nowCanDraw = playerVm.canDrawOverlay()
                    val changed = nowCanDraw != canDrawOverlay
                    canDrawOverlay = nowCanDraw
                    if (changed && nowCanDraw && !player.lyricOverlay) playerVm.setLyricOverlay(true)
                }

                // 悬浮窗只在 App 退到后台时显示：回到前台立刻隐藏，
                // 离开前台再放出来。用 START/STOP 而不是 RESUME/PAUSE，
                // 因为系统权限弹窗等短暂遮挡不该让字幕闪一下又消失。
                Lifecycle.Event.ON_START -> playerVm.setAppForeground(true)
                Lifecycle.Event.ON_STOP -> playerVm.setAppForeground(false)

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 全局一次性提示（曲库 / 播放 / 维护都会往这条总线上发）
    LaunchedEffect(lib) {
        lib.messages.collect { text ->
            snackbarHostState.showSnackbar(text)
        }
    }

    // 更新流程的提示（已是最新 / 检查失败 / 下载失败）复用同一条顶部 Snackbar
    LaunchedEffect(update.message) {
        update.message?.takeIf { it.isNotBlank() }?.let {
            snackbarHostState.showSnackbar(it)
            updateVm.consumeUpdateMessage()
        }
    }

    BackHandler(enabled = player.playerExpanded) { playerVm.closePlayer() }
    BackHandler(enabled = !player.playerExpanded && library.selectedPlaylist != null) { lib.closePlaylist() }
    BackHandler(
        enabled = !player.playerExpanded && library.selectedPlaylist == null && library.selectedGroup != null,
    ) { lib.closeGroup() }
    BackHandler(enabled = showSettings) {
        showSettings = false
        crashLogText = null
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            // 车载模式：整体换成两栏横屏布局（左导航 / 右内容 + 底部常驻播放条），
            // 手机的「单列 + 底部导航」在横屏大屏上会把列表压成窄条，故单独走一套。
            // 播放页是盖在最上层的独立全屏页（见下方 AnimatedVisibility），不进这两栏。
            if (player.carMode) {
                CarHomeScreen(
                    library = library,
                    player = player,
                    progressFlow = playerVm.progress,
                    onSelectView = lib::setView,
                    onOpenSettings = { showSettings = true },
                    onQuery = lib::setQuery,
                    onSort = { lib.setSort(library.sortOrder.next()) },
                    onRescan = { lib.scan() },
                    onPlay = playerVm::playQueue,
                    onSongLongPress = { actionSong = it },
                    onOpenGroup = lib::openGroup,
                    onOpenPlaylist = lib::openPlaylist,
                    onCloseDetail = {
                        if (library.selectedPlaylist != null) lib.closePlaylist() else lib.closeGroup()
                    },
                    onNewPlaylist = { newPlaylistDialog = true },
                    onImportM3U = {
                        m3uImportLauncher.launch(
                            arrayOf("audio/x-mpegurl", "audio/mpegurl", "text/plain", "*/*")
                        )
                    },
                    onOpenScanScope = { showScanScope = true },
                    onFullScan = requestFullScan,
                    onImportFolder = importFolder,
                    granted = granted,
                    onToggle = playerVm::togglePlay,
                    onNext = playerVm::next,
                    onPrevious = playerVm::previous,
                    onSeek = playerVm::seekTo,
                    onExpandPlayer = playerVm::openPlayer,
                    isFavorite = player.current?.let { it.stableKey in library.favoriteUris } == true,
                    onToggleFavorite = { player.current?.let(lib::toggleFavorite) },
                )
            } else Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                LibraryHeader(
                    library = library,
                    granted = granted,
                    onQuery = lib::setQuery,
                    onSort = lib::setSort,
                    onRequestPermission = { permissionLauncher.launch(arrayOf(audioPermission)) },
                    onImportFolder = importFolder,
                    onRescan = { lib.scan() },
                    onRefreshLyrics = lib::refreshLyrics,
                    onDeepScan = { lib.deepScanSettingsIntent()?.let { context.startActivity(it) } },
                    onImportM3U = {
                        m3uImportLauncher.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "text/plain", "*/*"))
                    },
                    onSubmitSearch = lib::submitSearch,
                    onClearSearchHistory = lib::clearSearchHistory,
                    onOpenSettings = { showSettings = true },
                    onCheckUpdate = {
                        updateVm.checkUpdate()
                        snackbarHostState.currentSnackbarData?.dismiss()
                    },
                    onOpenScanScope = { showScanScope = true },
                    onFullScan = requestFullScan,
                )

                Box(Modifier.weight(1f)) {
                    when {
                        // 首次进入 / 移除全部目录后：等待用户指定扫描范围，不自动扫任何位置
                        library.scanScope == ScanScope.UNSET && library.songs.isEmpty() ->
                            ScanSetupView(
                                scanning = library.scanning,
                                statusText = library.statusText,
                                onFullScan = requestFullScan,
                                onPickFolder = pickFolderAsScope,
                                modifier = Modifier.fillMaxSize(),
                            )

                        library.scanning && library.songs.isEmpty() -> ScanningState(library.statusText)

                        library.selectedPlaylist != null -> PlaylistDetail(
                            lib = lib,
                            playerVm = playerVm,
                            library = library,
                            player = player,
                            onBack = lib::closePlaylist,
                            onExport = {
                                pendingExport = library.selectedPlaylist
                                m3uExportLauncher.launch("${library.selectedPlaylist!!.name}.m3u")
                            },
                        )

                        library.selectedGroup != null -> GroupDetail(
                            playerVm = playerVm,
                            library = library,
                            player = player,
                            onBack = lib::closeGroup,
                            onSongLongPress = { actionSong = it },
                        )

                        library.view == LibraryView.SONGS -> SongsList(
                            playerVm = playerVm,
                            library = library,
                            player = player,
                            granted = granted,
                            onSongLongPress = { actionSong = it },
                            onImportFolder = importFolder,
                            onFullScan = requestFullScan,
                            onOpenScanScope = { showScanScope = true },
                        )

                        library.view == LibraryView.PLAYLIST -> PlaylistListView(
                            lib = lib,
                            library = library,
                            onImportM3U = {
                                m3uImportLauncher.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "text/plain", "*/*"))
                            },
                        )

                        else -> GroupListView(
                            library = library,
                            onOpen = lib::openGroup,
                            canRemove = library.view == LibraryView.FOLDER,
                            onRemove = { folderRemoveConfirmGroup = it },
                        )
                    }
                }

                // 迷你播放条：放进 Column 而不是浮在最上层，
                // 这样列表不会被它遮住，Snackbar 也不会和它打架。
                MiniPlayer(
                    song = player.current,
                    isPlaying = player.isPlaying,
                    progressFlow = playerVm.progress,
                    onExpand = playerVm::openPlayer,
                    onToggle = playerVm::togglePlay,
                    onNext = playerVm::next,
                    onPrevious = playerVm::previous,
                    onSeek = playerVm::seekTo,
                )

                // 五视图切换：从顶部一排 FilterChip 改为底部导航栏，单手可达
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    LibraryView.entries.forEach { view ->
                        NavigationBarItem(
                            selected = library.view == view,
                            onClick = { lib.setView(view) },
                            icon = {
                                Icon(
                                    imageVector = view.icon,
                                    contentDescription = view.label,
                                    modifier = Modifier.size(22.dp),
                                )
                            },
                            label = { Text(view.label, fontSize = 11.sp) },
                        )
                    }
                }
            }

            // 播放页 / 设置页覆盖在最上层
            AnimatedVisibility(
                visible = player.playerExpanded,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
                modifier = Modifier.fillMaxSize(),
            ) {
                PlayerScreen(
                    state = player,
                    progressFlow = playerVm.progress,
                    onClose = playerVm::closePlayer,
                    onToggle = playerVm::togglePlay,
                    onNext = playerVm::next,
                    onPrevious = playerVm::previous,
                    onSeekTo = playerVm::seekTo,
                    onCyclePlayMode = playerVm::cyclePlayMode,
                    onLyricOffset = playerVm::setLyricOffset,
                    onResetLyricOffset = playerVm::resetLyricOffset,
                    onLyricScale = playerVm::setLyricScale,
                    onShowQueue = { playerVm.refreshQueue(); showQueue = true },
                    onSleep = playerVm::sleepAfter,
                    onSleepEndOfSong = playerVm::sleepEndOfSong,
                    onCancelSleep = playerVm::cancelSleep,
                    onSpeed = playerVm::setSpeed,
                    onExportLyrics = playerVm::exportLyrics,
                    // 播放设置抽屉 → 音频信息：与长按菜单共用 infoSong + SongInfoSheet
                    onShowAudioInfo = {
                        player.current?.let { infoSong = it; playerVm.inspectAudio(it) }
                    },
                    // 车载沉浸态常驻歌名行的红心：收藏状态存在 LibraryUiState 里，
                    // 用 stableKey 做集合查找，和长按菜单保持一致。
                    isFavorite = player.current?.let { it.stableKey in library.favoriteUris } == true,
                    onToggleFavorite = lib::toggleFavorite,
                )
            }

            AnimatedVisibility(
                visible = showSettings,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
                modifier = Modifier.fillMaxSize(),
            ) {
                // 这两个偏好只写 SharedPreferences、不走 StateFlow，直接读方法
                // 拿到的是一次性快照，开关点了 UI 也不动（用户看起来就是"关不掉"）。
                // 用本地 state 兜住显示，回调里同步写回。
                var dynamicThemeOn by remember { mutableStateOf(playerVm.dynamicThemeEnabled()) }
                var autoCheckUpdateOn by remember { mutableStateOf(updateVm.autoCheckUpdateEnabled()) }
                // 下载线路同样只存在 SharedPreferences 里，用本地 state 兜住显示
                var downloadRouteOn by remember { mutableStateOf(updateVm.downloadRoute()) }
                SettingsScreen(
                    library = library,
                    player = player,
                    update = update,
                    dynamicTheme = dynamicThemeOn,
                    autoCheckUpdate = autoCheckUpdateOn,
                    crashLogCount = crashLogCount,
                    crashLogText = crashLogText,
                    onClose = {
                        showSettings = false
                        crashLogText = null
                    },
                    onDynamicTheme = { dynamicThemeOn = it; playerVm.setDynamicTheme(it) },
                    onCarMode = playerVm::setCarMode,
                    onLyricOverlay = playerVm::setLyricOverlay,
                    onOverlayEditing = playerVm::setOverlayEditing,
                    canDrawOverlay = canDrawOverlay,
                    overlayHealth = overlayHealth,
                    onLyricScale = playerVm::setLyricScale,
                    onLyricWordByWord = playerVm::setLyricWordByWord,
                    onNotifyLyrics = playerVm::setNotifyLyrics,
                    onSpeed = playerVm::setSpeed,
                    onSkipSilence = playerVm::setSkipSilence,
                    onClearStats = lib::clearPlayStats,
                    maintenance = maintenanceReport,
                    onRefreshMaintenance = settings::refreshMaintenanceReport,
                    onCleanupMissingSongs = {
                        settings.cleanupMissingSongs()
                        lib.reloadAll()
                    },
                    onClearCoverCache = settings::clearCoverCache,
                    onResetUserData = {
                        settings.resetAllUserData()
                        lib.reloadAll()
                    },
                    minSongDurationSec = lib.minSongDurationSec(),
                    onMinSongDurationSec = lib::setMinSongDurationSec,
                    onLoadCrashLog = { crashLogText = settings.crashLogText() },
                    onExportCrashLog = { crashExportLauncher.launch("music-player-crash-${System.currentTimeMillis()}.txt") },
                    onClearCrashLog = {
                        settings.clearCrashLog()
                        crashLogText = null
                        crashLogCount = settings.crashLogCount()
                    },
                    onCheckUpdate = updateVm::checkUpdate,
                    onDownloadUpdate = updateVm::downloadUpdate,
                    onInstallUpdate = updateVm::installUpdate,
                    onGrantInstallPermission = updateVm::grantInstallPermission,
                    onAutoCheckUpdate = { autoCheckUpdateOn = it; updateVm.setAutoCheckUpdate(it) },
                    downloadRoute = downloadRouteOn,
                    onDownloadRoute = { downloadRouteOn = it; updateVm.setDownloadRoute(it) },
                    onOpenReleasePage = updateVm::openReleasePage,
                    onIgnoreUpdate = updateVm::ignoreUpdate,
                    onDismissUpdateDialog = updateVm::dismissUpdateDialog,
                )
            }

            // Snackbar 移到顶部：底部已经被迷你条 + 导航栏占满
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 6.dp),
            )

            // ------------------------------------------------ 抽屉与对话框
            actionSong?.let { song ->
                SongActionsSheet(
                    song = song,
                    isFavorite = song.stableKey in library.favoriteUris,
                    // 删除文件入口只在文件夹视图开放（歌曲视图的操作以播放管理为主，避免误删）
                    canDelete = library.view == LibraryView.FOLDER,
                    onDismiss = { actionSong = null },
                    onToggleFavorite = { lib.toggleFavorite(song) },
                    onPlayNext = { playerVm.playNext(song) },
                    onAddToPlaylist = { addToPlaylistSong = song },
                    onEdit = { editSong = song },
                    onViewAlbum = {
                        lib.setView(LibraryView.ALBUM)
                        lib.openGroup(GroupInfo(song.albumOrUnknown, song.albumOrUnknown, "", 0, song))
                    },
                    onViewArtist = {
                        lib.setView(LibraryView.ARTIST)
                        lib.openGroup(GroupInfo(song.artistOrUnknown, song.artistOrUnknown, "", 0, song))
                    },
                    onAudioInfo = { infoSong = song; playerVm.inspectAudio(song) },
                    onDelete = {
                        actionSong = null
                        deleteConfirmSong = song
                    },
                )
            }

            deleteConfirmSong?.let { song ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { deleteConfirmSong = null },
                    title = { Text("删除歌曲文件") },
                    text = {
                        Text(
                            "将从存储中永久删除「${song.title}」（${song.displayName}），" +
                                "同目录的歌词文件会一并删除。\n此操作无法撤销，确定继续吗？",
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                deleteConfirmSong = null
                                lib.deleteSong(song) { sender ->
                                    deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                                }
                            },
                        ) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = { TextButton(onClick = { deleteConfirmSong = null }) { Text("取消") } },
                )
            }

            infoSong?.let { song ->
                SongInfoSheet(
                    song = song,
                    info = player.audioInfo,
                    loading = player.audioInfoLoading,
                    onDismiss = {
                        infoSong = null
                        playerVm.clearAudioInfo()
                    },
                )
            }

            addToPlaylistSong?.let { song ->
                AddToPlaylistSheet(
                    playlists = library.playlists,
                    song = song,
                    onDismiss = { addToPlaylistSong = null },
                    onPick = { lib.addToPlaylist(it.id, song) },
                    onCreateNew = { name -> lib.createPlaylist(name, listOf(song)) },
                )
            }

            folderRemoveConfirmGroup?.let { group ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { folderRemoveConfirmGroup = null },
                    title = { Text("移除文件夹扫描结果") },
                    text = {
                        Text(
                            "将从音乐库中移除「${group.title}」下的 ${group.count} 首歌曲，" +
                                "同时从歌单、播放队列、收藏和播放统计中清理。\n" +
                                "该组对应的导入目录也会从扫描列表中移除，重新扫描后这些歌曲不会再出现；" +
                                "原始音频文件不会被删除。",
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                folderRemoveConfirmGroup = null
                                lib.removeFolderScanResults(group)
                            },
                        ) {
                            Text("移除", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { folderRemoveConfirmGroup = null }) { Text("取消") }
                    },
                )
            }

            editSong?.let { song ->
                EditSongDialog(
                    song = song,
                    onDismiss = { editSong = null },
                    onSave = { t, a, al -> lib.updateSongInfo(song, t, a, al) },
                )
            }

            if (newPlaylistDialog) {
                NewPlaylistDialog(
                    onDismiss = { newPlaylistDialog = false },
                    onCreate = { lib.createPlaylist(it) },
                )
            }

            if (showScanScope) {
                ScanScopeSheet(
                    current = library.scanScope,
                    folders = library.folderUris,
                    onFullScan = { showScanScope = false; requestFullScan() },
                    onPickFolder = { showScanScope = false; pickFolderAsScope() },
                    onUseFolders = { showScanScope = false; lib.setScanScope(ScanScope.FOLDERS) },
                    onRemoveFolder = { lib.removeFolder(it) },
                    onDismiss = { showScanScope = false },
                )
            }

            if (showQueue) {
                QueueSheet(
                    queue = player.queue,
                    onClose = { showQueue = false },
                    onJump = { playerVm.jumpToQueue(it) },
                    onRemove = { playerVm.removeQueueItem(it) },
                    onMove = { from, to -> playerVm.moveQueueItem(from, to) },
                )
            }
        }
    }
}

// ================================================================ 歌曲列表（含字母索引）

@Composable
private fun SongsList(

    playerVm: PlayerViewModel,
    library: LibraryUiState,
    player: PlayerUiState,
    granted: Boolean,
    onSongLongPress: (Song) -> Unit,
    onImportFolder: () -> Unit,
    onFullScan: () -> Unit,
    onOpenScanScope: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    if (library.visible.isEmpty()) {
        val emptyLibrary = library.songs.isEmpty()
        val folderScope = library.scanScope == ScanScope.FOLDERS
        EmptyHint(
            title = if (emptyLibrary) {
                if (folderScope) "所选目录里没有音频" else "全盘扫描没找到音频"
            } else {
                "没有匹配的结果"
            },
            subtitle = if (emptyLibrary) {
                when {
                    folderScope -> "确认目录里有 mp3 / flac 等音频，或换一个目录。\n同目录的 .lrc 歌词会被自动识别。"
                    !granted -> "全盘扫描需要「读取音频」权限，也可以改为只导入某个音乐目录。"
                    else -> "可授予「所有文件访问权限」后再扫描（能连带同目录歌词一起识别），\n或者改为只导入指定的音乐目录。"
                }
            } else {
                "换个关键词试试，支持歌名、歌手、专辑、文件名，也支持拼音首字母（如 zjl）"
            },
            actionLabel = if (emptyLibrary) {
                if (folderScope) "更换目录" else if (!granted) "授予权限并扫描全盘" else "重新扫描全盘"
            } else null,
            onAction = if (emptyLibrary) {
                if (folderScope) onImportFolder else onFullScan
            } else null,
            secondaryActionLabel = if (emptyLibrary) "扫描范围设置" else null,
            onSecondaryAction = if (emptyLibrary) onOpenScanScope else null,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    // 当前播放曲目在列表中的位置（缓存计算结果，避免每次重组都扫一遍列表）
    val currentIndex = remember(library.visible, player.current?.stableKey) {
        val key = player.current?.stableKey ?: return@remember -1
        library.visible.indexOfFirst { it.stableKey == key }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        ) {
            itemsIndexed(
                items = library.visible,
                key = { _, item -> item.stableKey },
                contentType = { _, _ -> "song" },
            ) { index, song ->
                SongRow(
                    song = song,
                    isCurrent = player.current?.stableKey == song.stableKey,
                    isPlaying = player.isPlaying,
                    onClick = { playerVm.playQueue(library.visible, index) },
                    onLongClick = { onSongLongPress(song) },
                    onMore = { onSongLongPress(song) },
                )
            }
        }

        // 字母索引导航条
        val letters = library.letterIndex.keys.toList()
        if (letters.size > 4) {
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 2.dp),
            ) {
                letters.forEach { letter ->
                    Text(
                        text = letter.toString(),
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                library.letterIndex[letter]?.let { idx ->
                                    scope.launch { listState.scrollToItem(idx.coerceIn(0, library.visible.size - 1)) }
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
        }

        // 正在播放的歌曲滚出屏幕后，一键跳回
        if (currentIndex > 0) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .clip(CircleShape)
                    .clickable {
                        scope.launch { listState.animateScrollToItem(currentIndex.firstVisibleSafe()) }
                    },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "回到当前播放",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/** 让「回到当前播放」上移一点，避免贴在最顶部 */
private fun Int.firstVisibleSafe(): Int = (this - 3).coerceAtLeast(0)

// ================================================================ 分组列表（专辑/歌手/文件夹）

@Composable
private fun GroupListView(
    library: LibraryUiState,
    onOpen: (GroupInfo) -> Unit,
    canRemove: Boolean = false,
    onRemove: (GroupInfo) -> Unit = {},
) {
    if (library.groups.isEmpty()) {
        EmptyHint("暂无分组", "扫描完成后即可按此维度浏览", modifier = Modifier.fillMaxSize())
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(library.groups, key = { it.key }, contentType = { "group" }) { group ->
            var showMenu by remember { mutableStateOf(false) }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onOpen(group) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
                    CoverArt(song = group.sample, size = 52.dp, cornerRadius = 12.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            group.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            group.subtitle.ifBlank { "${group.count} 首" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    CountPill("${group.count}")
                    if (canRemove) {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "更多操作",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("移除目录及扫描结果") },
                                    onClick = {
                                        showMenu = false
                                        onRemove(group)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ================================================================ 分组详情

@Composable
private fun GroupDetail(

    playerVm: PlayerViewModel,
    library: LibraryUiState,
    player: PlayerUiState,
    onBack: () -> Unit,
    onSongLongPress: (Song) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = library.selectedGroup?.title.orEmpty(),
            subtitle = "${library.detailSongs.size} 首",
            onBack = onBack,
            onPlayAll = { playerVm.playQueue(library.detailSongs, 0) },
        )
        LazyColumn(
            contentPadding = PaddingValues(top = 2.dp, bottom = 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(
                library.detailSongs,
                key = { _, s -> s.stableKey },
                contentType = { _, _ -> "song" },
            ) { index, song ->
                SongRow(
                    song = song,
                    isCurrent = player.current?.stableKey == song.stableKey,
                    isPlaying = player.isPlaying,
                    onClick = { playerVm.playQueue(library.detailSongs, index) },
                    onLongClick = { onSongLongPress(song) },
                    onMore = { onSongLongPress(song) },
                )
            }
        }
    }
}

// ================================================================ 歌单列表

@Composable
private fun PlaylistListView(
    lib: LibraryViewModel,
    library: LibraryUiState,
    onImportM3U: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    "我的歌单",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onImportM3U) { Text("导入 M3U") }
            }
        }
        item {
            PlaylistRowPlay(
                info = PlaylistInfo(
                    VirtualPlaylist.RECENT, "最近播放",
                    library.extraRecent.size, library.extraRecent.firstOrNull(), virtual = true,
                ),
                onOpen = lib::openPlaylist,
            )
        }
        item {
            PlaylistRowPlay(
                info = PlaylistInfo(
                    VirtualPlaylist.MOST_PLAYED, "最常播放",
                    library.extraMostPlayed.size, library.extraMostPlayed.firstOrNull(), virtual = true,
                ),
                onOpen = lib::openPlaylist,
            )
        }
        if (library.playlists.isEmpty()) {
            item {
                EmptyHint("还没有自建歌单", "在歌曲列表点行尾「⋮」即可加入或新建歌单", modifier = Modifier.padding(vertical = 30.dp))
            }
        }
        items(library.playlists, key = { it.id }, contentType = { "playlist" }) { pl ->
            PlaylistRowPlay(info = pl, onOpen = lib::openPlaylist)
        }
    }
}

@Composable
private fun PlaylistRowPlay(
    info: PlaylistInfo,
    onOpen: (PlaylistInfo) -> Unit,
) {
    val song = info.coverSong
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable { onOpen(info) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
            if (song != null) {
                CoverArt(song = song, size = 52.dp, cornerRadius = 12.dp)
            } else {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    info.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${info.songCount} 首",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            CountPill("${info.songCount}")
        }
    }
}

// ================================================================ 歌单详情

@Composable
private fun PlaylistDetail(

    lib: LibraryViewModel,
    playerVm: PlayerViewModel,
    library: LibraryUiState,
    player: PlayerUiState,
    onBack: () -> Unit,
    onExport: () -> Unit,
) {
    val info = library.selectedPlaylist ?: return
    var menuOpen by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(info.name) }

    val menuSlot: @Composable () -> Unit = {
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "歌单操作",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            PlaylistActionsMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                onRename = { renameDialog = true },
                onDelete = { lib.deletePlaylist(info.id) },
                onExport = onExport,
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(
            title = info.name,
            subtitle = "${library.playlistSongs.size} 首",
            onBack = onBack,
            onPlayAll = { playerVm.playQueue(library.playlistSongs, 0) },
            playAllEnabled = library.playlistSongs.isNotEmpty(),
            trailing = if (info.virtual) null else menuSlot,
        )

        if (library.playlistSongs.isEmpty()) {
            EmptyHint("歌单是空的", "回到歌曲列表点行尾「⋮」即可加入", modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(
                contentPadding = PaddingValues(top = 2.dp, bottom = 16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(
                    library.playlistSongs,
                    key = { _, s -> s.stableKey },
                    contentType = { _, _ -> "song" },
                ) { index, song ->
                    // 虚拟歌单（最近播放 / 最常播放）由统计结果生成，不允许从中移除
                    val removable = !info.virtual
                    SongRow(
                        song = song,
                        isCurrent = player.current?.stableKey == song.stableKey,
                        isPlaying = player.isPlaying,
                        onClick = { playerVm.playQueue(library.playlistSongs, index) },
                        onRemove = if (removable) ({ lib.removeFromPlaylist(info.id, song) }) else null,
                    )
                }
            }
        }
    }

    if (renameDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("重命名歌单") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    placeholder = { Text("歌单名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameText.isNotBlank()) lib.renamePlaylist(info.id, renameText)
                        renameDialog = false
                    },
                    enabled = renameText.isNotBlank(),
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("取消") } },
        )
    }
}

/** 详情页（分组 / 歌单）共用的标题栏 */
@Composable
private fun DetailHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    playAllEnabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onPlayAll, enabled = playAllEnabled) { Text("播放全部") }
        trailing?.invoke()
    }
}

// ================================================================ 顶栏

@Composable
private fun LibraryHeader(
    library: LibraryUiState,
    granted: Boolean,
    onQuery: (String) -> Unit,
    onSort: (SortOrder) -> Unit,
    onRequestPermission: () -> Unit,
    onImportFolder: () -> Unit,
    onRescan: () -> Unit,
    onRefreshLyrics: () -> Unit,
    onDeepScan: () -> Unit,
    onImportM3U: () -> Unit,
    onSubmitSearch: (String) -> Unit,
    onClearSearchHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onCheckUpdate: () -> Unit,
    onOpenScanScope: () -> Unit,
    onFullScan: () -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "本地音乐",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (library.songs.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                CountPill("${library.songs.size} 首")
            }
            Spacer(Modifier.weight(1f))

            IconButton(onClick = { searchMode = !searchMode }) {
                Icon(
                    imageVector = if (searchMode) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchMode) "关闭搜索" else "搜索",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }

            Box {
                IconButton(onClick = { sortMenu = true }) {
                    Icon(
                        imageVector = AppIcons.Sort,
                        contentDescription = "排序",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortOrder.entries.forEach { order ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = order.label,
                                    fontWeight = if (order == library.sortOrder) FontWeight.Bold
                                    else FontWeight.Normal,
                                    color = if (order == library.sortOrder) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = { onSort(order); sortMenu = false },
                        )
                    }
                }
            }

            Box {
                IconButton(onClick = { moreMenu = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                when (library.scanScope) {
                                    ScanScope.UNSET -> "扫描范围：未选择"
                                    ScanScope.FULL -> "扫描范围：全盘扫描"
                                    ScanScope.FOLDERS -> "扫描范围：指定目录 ${library.folderCount} 个"
                                }
                            )
                        },
                        onClick = { moreMenu = false; onOpenScanScope() },
                    )
                    if (library.scanScope != ScanScope.UNSET) {
                        DropdownMenuItem(
                            text = { Text("重新扫描当前范围") },
                            onClick = { moreMenu = false; onRescan() },
                        )
                    }
                    if (library.scanScope != ScanScope.FULL) {
                        DropdownMenuItem(
                            text = { Text(if (granted) "扫描全盘音乐…" else "授权并扫描全盘…") },
                            onClick = { moreMenu = false; onFullScan() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("导入音乐目录…") },
                        onClick = { moreMenu = false; onImportFolder() },
                    )
                    if (library.scanScope != ScanScope.FULL && !granted) {
                        DropdownMenuItem(
                            text = { Text("授予音频读取权限") },
                            onClick = { moreMenu = false; onRequestPermission() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("重新匹配 .lrc 歌词") },
                        onClick = { moreMenu = false; onRefreshLyrics() },
                    )
                    DropdownMenuItem(
                        text = { Text("导入 M3U 歌单…") },
                        onClick = { moreMenu = false; onImportM3U() },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (library.deepScan) "深度扫描：已开启（点击管理）"
                                else "所有文件访问权限（深度扫描）"
                            )
                        },
                        onClick = { moreMenu = false; onDeepScan() },
                    )
                    DropdownMenuItem(
                        text = { Text("检查更新") },
                        onClick = { moreMenu = false; onCheckUpdate() },
                    )
                    DropdownMenuItem(
                        text = { Text("设置") },
                        onClick = { moreMenu = false; onOpenSettings() },
                    )
                }
            }
        }

        if (searchMode) {
            OutlinedTextField(
                value = library.query,
                onValueChange = onQuery,
                placeholder = { Text("搜索歌名 / 歌手 / 专辑 / 文件名 / 拼音") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmitSearch(library.query) }),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            if (library.query.isBlank() && library.searchHistory.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) {
                    Text(
                        "历史",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        library.searchHistory.forEach { h ->
                            AssistChip(
                                onClick = {
                                    onQuery(h)
                                    onSubmitSearch(h)
                                },
                                label = { Text(h, fontSize = 12.sp) },
                            )
                        }
                    }
                    TextButton(onClick = onClearSearchHistory) {
                        Text("清空", fontSize = 12.sp)
                    }
                }
            }
        }

        val stat = buildString {
            append("共 ${library.songs.size} 首")
            if (library.lyricsCount > 0) append(" · 含歌词文件 ${library.lyricsCount} 首")
            if (library.skippedUnsupported > 0) append(" · 跳过 ${library.skippedUnsupported} 个不支持格式")
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
        ) {
            ScanScopeChip(
                scope = library.scanScope,
                folderCount = library.folderCount,
                onClick = onOpenScanScope,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stat,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ================================================================ 首次进入：选择扫描范围

/**
 * 首次进入的空态。
 *
 * 应用**默认不扫描整个手机**，由用户明确选择：
 *  - 扫描全盘（读系统媒体库，可配合「所有文件访问权限」做深度扫描）
 *  - 指定目录（用系统文件选择器挑文件夹）
 */
@Composable
private fun ScanSetupView(
    scanning: Boolean,
    statusText: String,
    onFullScan: () -> Unit,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = scheme.primaryContainer,
            modifier = Modifier.size(84.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = AppIcons.MusicNote,
                    contentDescription = null,
                    tint = scheme.onPrimaryContainer,
                    modifier = Modifier.size(38.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "选择音乐位置",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "本应用默认不会扫描整个手机存储。\n请选择一种方式建立你的音乐库：",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onFullScan,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Icon(AppIcons.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("扫描全盘音乐")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "读取系统媒体库里已有的音乐；授予「所有文件访问权限」后还能连带识别同目录 .lrc 歌词",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 16.sp,
        )

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = onPickFolder,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Icon(AppIcons.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("选择音乐目录")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "只扫描你指定的文件夹，可以选多个目录，之后也能随时追加",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 16.sp,
        )

        if (scanning) {
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = scheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    statusText.ifBlank { "正在扫描…" },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ================================================================ 扫描中

@Composable
private fun ScanningState(status: String) {
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = status.ifBlank { "正在扫描音乐库…" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LibrarySkeleton()
    }
}
