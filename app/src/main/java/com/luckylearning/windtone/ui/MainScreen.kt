package com.luckylearning.windtone.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luckylearning.windtone.data.ScanScope
import com.luckylearning.windtone.data.buildLibraryStats
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.GroupInfo
import com.luckylearning.windtone.state.LibraryView
import com.luckylearning.windtone.state.PlaylistInfo
import com.luckylearning.windtone.state.VirtualPlaylist
import com.luckylearning.windtone.state.virtualPlaylistInfo

fun requiredAudioPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * 大屏阈值：≥ 840dp 视为「车机 / 平板 / 折叠屏展开」。
 *
 * 只用来判断底部要不要给车机 Dock 让位，**不再**用来切双栏——v1.1.14 起双栏整体停用。
 */
private const val LARGE_SCREEN_WIDTH_DP = 840

/**
 * 车机底部固定按钮栏的保底高度。
 *
 * 比亚迪 DiLink 那条「Home / 应用 / 空调」的条子不是系统导航栏，不在 insets 里上报
 * （实测 navigationBars = 0），所以必须在布局上留死高度，否则「设置」和迷你播放条
 * 会整块压在按钮下面，点不到也看不见。
 */
private val CAR_DOCK_RESERVED_HEIGHT = 56.dp

/**
 * 主界面骨架：状态编排、权限申请、覆盖层与全部弹窗。
 *
 * 列表区 / 顶栏 / 空态已拆到 `LibraryLists.kt` 与 `LibraryHeader.kt`（批次 8.6）——
 * 这里只保留「谁在什么条件下显示什么」以及跨域回调的接线。
 */
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
    val widthDp = LocalConfiguration.current.screenWidthDp
    // 底部安全留白：系统导航栏 insets 与「车机 Dock 保底高度」取大值。
    // 车机把底部按钮栏当浮层、insets 报 0，光靠 navigationBarsPadding 会让左下角
    // 「设置」被按钮压住点不到（用户实测比亚迪宋 L DM-i）。
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomSafe = if (widthDp >= LARGE_SCREEN_WIDTH_DP) {
        maxOf(navigationBottom, CAR_DOCK_RESERVED_HEIGHT)
    } else {
        navigationBottom
    }

    // ---------------------------------------------------------------- 车载模式
    //
    // 开关一拨**立刻**生效：以前 `requestedOrientation` 只在 MainActivity.onCreate 里读一次，
    // 用户在设置里打开车载模式后界面毫无变化（除了几个要等 5 秒才出现的细节），
    // 看起来就像「这个开关没用」——这是本轮重设计的起点，见 docs/设计-车载模式.md。
    // 这里直接改 Activity 的方向；Activity 声明了 configChanges，不会重建、不会打断播放。
    val carMode = player.carMode
    val activity = remember(context) { context.findActivity() }
    LaunchedEffect(carMode) {
        activity?.requestedOrientation = if (carMode) {
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val audioPermission = remember { requiredAudioPermission() }
    var granted by remember { mutableStateOf(hasPermission(context, audioPermission)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val ok = result[audioPermission] == true || hasPermission(context, audioPermission)
        granted = ok
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

    // 批量删除：媒体库文件同样走一次系统确认（一次请求可带多个 URI）
    val bulkDeleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> lib.onSystemBulkDeleteResult(result.resultCode == Activity.RESULT_OK) }

    // 长按 / 更多操作相关状态
    var actionSong by remember { mutableStateOf<Song?>(null) }
    var deleteConfirmSong by remember { mutableStateOf<Song?>(null) }
    var bulkDeleteConfirm by remember { mutableStateOf(false) }
    var folderRemoveConfirmGroup by remember { mutableStateOf<GroupInfo?>(null) }
    var addToPlaylistSong by remember { mutableStateOf<Song?>(null) }
    var showBulkAddToPlaylist by remember { mutableStateOf(false) }
    var saveQueueAsPlaylist by remember { mutableStateOf(false) }
    var editSong by remember { mutableStateOf<Song?>(null) }
    var infoSong by remember { mutableStateOf<Song?>(null) }
    var newPlaylistDialog by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showScanScope by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var showDuplicates by remember { mutableStateOf(false) }
    // 曲库格式与容量（v1.1.17）：纯本地统计，进入时现算，不落库
    var showLibraryStats by remember { mutableStateOf(false) }
    // 侧边抽屉开合（v1.1.3：取代底部五视图导航）。只在曲库页出现，
    // 播放页 / 设置页这类全屏覆盖层在效果图里各有自己的返回按钮，没有汉堡入口。
    var drawerOpen by remember { mutableStateOf(false) }
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
                    // 安装权限同理：从「安装未知应用」授权页回来若已授权，直接续上装包，
                    // 不再要求用户重新点一次（旧版这里断掉，用户只能杀进程重来）。
                    updateVm.onAppForeground()
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

    // 桌面入口（9.4 启动器快捷方式 / 9.5 小组件深链）：播放全部 / 随机播放 / 我的收藏 /
    // 进播放页 / 进曲库 / 进歌单。
    //
    // 不能一收到就执行 —— 从桌面入口冷启动时，曲库还在从 Room 缓存异步装载，
    // 此刻 `songs` 是空的，而 `playQueue` 遇到空列表会**静默返回**（见
    // `PlaybackEngine.startQueue` 的 `if (queue.isEmpty()) return`），
    // 用户看到的就是"点了快捷方式、应用开了、但没播"，而且连句提示都没有。
    //
    // 所以等曲库自己报出结论再动手：判据在 [AppShortcut.canRun]（纯函数，有单测）。
    // 这里**绝不能**自己看 `scanning` / `scanScope` 判断就绪 —— 那三个信号在冷启动
    // 第一帧全都"看着像"已就绪，正是 v1.1.3 ~ v1.1.12 那个隐藏故障的成因。
    val shortcutAction by AppShortcut.pending.collectAsStateWithLifecycle()
    LaunchedEffect(
        shortcutAction,
        library.libraryLoaded,
        library.playlistsLoaded,
        library.songs.size,
        library.scanning,
    ) {
        val action = shortcutAction ?: return@LaunchedEffect
        val ready = AppShortcut.canRun(
            action = action,
            songsLoaded = library.libraryLoaded,
            hasSongs = library.songs.isNotEmpty(),
            scanning = library.scanning,
            playlistsLoaded = library.playlistsLoaded,
        )
        if (!ready) return@LaunchedEffect
        AppShortcut.consumePending()
        if (library.songs.isEmpty()) snackbarHostState.showSnackbar("曲库里还没有歌曲，先选好扫描范围并等扫描完成")
        when (action) {
            AppShortcut.PLAY_ALL -> playerVm.playQueue(library.songs, 0)

            AppShortcut.SHUFFLE -> playerVm.playShuffled(library.songs)

            AppShortcut.FAVORITES -> {
                lib.setView(LibraryView.PLAYLIST)
                // 用 virtualPlaylistInfo 拼条目，**不要**去 `library.playlists` 里按 id 找：
                // 那个列表只有用户自建歌单，三个虚拟歌单（我的收藏 / 最近播放 / 最常播放）
                // 是界面现拼的视图，从来没进过它 —— 早期这里就是这么写的，查找永远返回
                // null，于是只切到「歌单」列表页就走了，用户报的"打开收藏打开的是歌单"。
                // 同一个构造函数，列表页与这里共用，不会再各拼一份。
                lib.openPlaylist(virtualPlaylistInfo(VirtualPlaylist.FAVORITES, library))
            }

            // 小组件深链：不依赖曲库，随时都能跳
            AppShortcut.OPEN_PLAYER -> playerVm.openPlayer()
            AppShortcut.OPEN_LIBRARY -> {
                lib.closePlaylist()
                lib.closeGroup()
                lib.setView(LibraryView.SONGS)
            }
            AppShortcut.OPEN_PLAYLISTS -> {
                lib.closeGroup()
                lib.setView(LibraryView.PLAYLIST)
            }
        }
    }

    if (showReport) {
        // 进入报告页时按当前档位拉一次数据（切 7/30 天时由页面内部再拉）
        LaunchedEffect(Unit) { if (library.report == null) lib.loadReport() }
    }

    BackHandler(enabled = player.playerExpanded) { playerVm.closePlayer() }
    BackHandler(enabled = !player.playerExpanded && library.selecting) { lib.exitSelection() }
    BackHandler(enabled = !player.playerExpanded && !library.selecting && library.selectedPlaylist != null) { lib.closePlaylist() }
    BackHandler(
        enabled = !player.playerExpanded && !library.selecting && library.selectedPlaylist == null &&
            library.selectedGroup != null,
    ) { lib.closeGroup() }
    BackHandler(enabled = showSettings) {
        showSettings = false
        crashLogText = null
    }
    BackHandler(enabled = showReport) { showReport = false }
    BackHandler(enabled = showDuplicates) { showDuplicates = false }
    BackHandler(enabled = showLibraryStats) { showLibraryStats = false }
    // 抽屉最后注册 = 优先级最高：抽屉开着时返回键只收抽屉，不会顺带退出别的层。
    BackHandler(enabled = drawerOpen) { drawerOpen = false }

    /** 一级列表 + 详情：手机与宽屏左栏共用同一份内容 */
    val listArea: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxSize()
                // 右滑手势（v1.1.4）：在内容区**任意位置**向右滑动即可拉出侧边抽屉。
                //
                // 刻意不做成「从左边缘往右拖」：边缘手势的起手带只有十几 dp，
                // 车机上单手够不着；而且屏幕左缘正是系统「返回」手势区，抢不过它。
                //
                // 用 `detectHorizontalDragGestures` 而不是自己解 `awaitPointerEvent`：
                // 它自带水平方向的 touch-slop 判定，纵向滚动（LazyColumn）会自然赢下
                // 手势竞争，列表照常滚，不会被这里截胡。
                .pointerInput(drawerOpen, showSettings, player.playerExpanded, carMode) {
                    // 覆盖层（播放页 / 设置页）打开时不再接管；抽屉已开的收起交给遮罩。
                    // 车载模式下也不接管：车机触摸板误触率高，「右滑开抽屉」在车上只会添乱，
                    // 而车机版根本没有抽屉入口（视图切换走顶部横向 Tab）。
                    if (carMode || drawerOpen || showSettings || player.playerExpanded) return@pointerInput
                    var dragged = 0f
                    val threshold = 72.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragCancel = { dragged = 0f },
                        onDragEnd = {
                            // 只认「够长的一次右滑」，避免把普通水平抖动当成手势
                            if (dragged >= threshold) drawerOpen = true
                            dragged = 0f
                        },
                        onHorizontalDrag = { _, amount -> dragged += amount },
                    )
                }
        ) {
            // 一级列表的滚动位置托管：按视图各存一份，切视图（歌曲↔专辑↔歌手↔
            // 文件夹↔歌单）或进详情再返回时，列表都回到用户之前滚动到的位置。
            // 必须声明在 when 之外 —— 进详情时下面的 else 分支会离开组合，
            // holder 若随之被 forget，存下的位置会一起丢掉。
            val listStateHolder = rememberSaveableStateHolder()
            // P2-4：主内容区的分支收敛到纯函数（MainNavigation.kt），便于单测与扩展。
            when (
                mainRouteOf(
                    scanScope = library.scanScope,
                    songsEmpty = library.songs.isEmpty(),
                    scanning = library.scanning,
                    hasSelectedPlaylist = library.selectedPlaylist != null,
                    hasSelectedGroup = library.selectedGroup != null,
                )
            ) {
                // 首次进入 / 移除全部目录后：等待用户指定扫描范围，不自动扫任何位置
                MainRoute.SCAN_SETUP ->
                    ScanSetupView(
                        scanning = library.scanning,
                        statusText = library.statusText,
                        onFullScan = requestFullScan,
                        onPickFolder = pickFolderAsScope,
                        modifier = Modifier.fillMaxSize(),
                    )

                MainRoute.SCANNING -> ScanningState(library.statusText)

                MainRoute.PLAYLIST_DETAIL -> PlaylistDetail(
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

                MainRoute.GROUP_DETAIL -> GroupDetail(
                    playerVm = playerVm,
                    library = library,
                    player = player,
                    onBack = lib::closeGroup,
                    onSongLongPress = { actionSong = it },
                    carMode = carMode,
                )

                // 一级列表：用 view 作为 key，每个视图各记一份滚动位置，互不干扰。
                // 详情页（PlaylistDetail / GroupDetail）刻意留在这层之外 —— 每次进
                // 详情都从顶部开始，符合「打开一个新的分组 / 歌单」的预期。
                MainRoute.LIBRARY -> listStateHolder.SaveableStateProvider(library.view.name) {
                    when (library.view) {
                        LibraryView.SONGS -> SongsList(
                            playerVm = playerVm,
                            library = library,
                            player = player,
                            granted = granted,
                            onSongLongPress = { actionSong = it },
                            onEnterSelection = lib::enterSelection,
                            onToggleSelect = lib::toggleSelected,
                            onImportFolder = importFolder,
                            onFullScan = requestFullScan,
                            onOpenScanScope = { showScanScope = true },
                            // 车载：行高 / 字号 / 触控目标放大，且**禁用长按多选**（车机手势不可靠）
                            carMode = carMode,
                        )

                        LibraryView.PLAYLIST -> PlaylistListView(
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
                            carMode = carMode,
                        )
                    }
                }
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        // 整块内容抬到底部安全线之上：车机 Dock 会把它压住，也顺带修好手机三键导航
        // 盖住迷你播放条的老问题（surface 背景仍铺满全屏，不会露出黑边）。
        Box(Modifier.fillMaxSize().padding(bottom = bottomSafe)) {
            // v1.1.14：双栏（车载两栏 / 平板双栏）整体停用 —— 车机上底部固定按钮栏会
            // 压住左下角「设置」，且用户明确不要双栏；统一走单栏（抽屉导航 + 迷你播放条）。
            // v3（2026-10-20）：车机改「左常驻导航 + 右内容区」，播放条只铺内容区；
            // 手机无左导航，仍是单列（抽屉 + 迷你播放条）。
            Row(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                if (carMode) {
                    CarNavRail(
                        current = library.view,
                        onSelect = lib::setView,
                        onOpenSettings = { showSettings = true },
                    )
                }
                Column(Modifier.weight(1f)) {
                if (carMode) {
                    // 车机：标题行（搜索）；视图切换在左侧常驻导航
                    CarLibraryHeader(
                        library = library,
                        onQuery = lib::setQuery,
                        onSubmitSearch = lib::submitSearch,
                    )
                } else {
                    LibraryHeader(
                        library = library,
                        granted = granted,
                        onOpenDrawer = { drawerOpen = true },
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
                        onOpenScanScope = { showScanScope = true },
                        onFullScan = requestFullScan,
                        onOpenReport = { showReport = true },
                    )
                }

                Box(Modifier.weight(1f)) {
                    if (library.selecting) {
                        SelectionBar(
                            count = library.selection.size,
                            onExit = lib::exitSelection,
                            onSelectAll = lib::selectAllVisible,
                            onInvert = lib::invertSelection,
                            onAddToPlaylist = { showBulkAddToPlaylist = true },
                            onFavorite = { lib.bulkSetFavorite(true) },
                            onEnqueue = lib::bulkAddToQueue,
                            onRemoveFromLibrary = { lib.bulkRemoveFromLibrary() },
                            onDeleteFiles = { bulkDeleteConfirm = true },
                        )
                    } else {
                        listArea()
                    }
                }

                if (!library.selecting) {
                    if (carMode) {
                        // 车机大播放条：112dp、主键 88dp、收藏 + 上一首 / 播放 / 下一首 + 进度。
                        // 不放「歌词」快捷键（用户明确不要），进播放页点封面 / 歌名即可。
                        CarPlayerBar(
                            song = player.current,
                            isPlaying = player.isPlaying,
                            progressFlow = playerVm.progress,
                            isFavorite = player.current?.let { it.stableKey in library.favoriteUris } == true,
                            onToggleFavorite = {
                                player.current?.let { lib.toggleFavorite(it) }
                            },
                            onExpand = playerVm::openPlayer,
                            onToggle = playerVm::togglePlay,
                            onNext = playerVm::next,
                            onPrevious = playerVm::previous,
                            onSeek = playerVm::seekTo,
                        )
                    } else {
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
                    }

                    // v1.1.3：底部五视图导航整体移除，五个浏览视图收进左侧抽屉
                    // （顶栏汉堡按钮开合，见 LibraryDrawer.kt）。列表因此多出
                    // 一整条导航栏的纵向空间 —— 这是这次重构的直接收益。
                }
                }
            }

            // 侧边抽屉：盖住曲库内容与迷你播放条（遮罩吃掉下层触摸）。
            // 挂在主内容之上、且放在播放页与设置页**之前** —— 那两个是全屏覆盖层，
            // 各有自己的返回按钮，抽屉不该压在其上（效果图里它们也没有汉堡入口）。
            // statusBarsPadding：效果图的抽屉在状态栏**之下**（.app 的坐标系起点），
            // 不是盖住状态栏。
            LibraryDrawer(
                open = drawerOpen,
                currentView = library.view,
                onSelectView = lib::setView,
                onOpenScanScope = { showScanScope = true },
                onOpenSettings = { showSettings = true },
                onClose = { drawerOpen = false },
                modifier = Modifier.statusBarsPadding(),
            )

            // 播放页 / 设置页 / 报告 / 重复清理覆盖在最上层（v1.1.14 起没有双栏，播放页一律全屏覆盖）
            AnimatedVisibility(
                visible = player.playerExpanded,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
                modifier = Modifier.fillMaxSize(),
            ) {
                PlayerPane(
                    playerVm = playerVm,
                    player = player,
                    library = library,
                    lib = lib,
                    canDrawOverlay = canDrawOverlay,
                    onShowQueue = { playerVm.refreshQueue(); showQueue = true },
                    onShowAudioInfo = { player.current?.let { infoSong = it; playerVm.inspectAudio(it) } },
                    onExportLyrics = { uri -> playerVm.exportLyrics(uri) },
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
                    onTestBootPlayback = playerVm::startPlaybackInBackground,
                    onPlayQueue = playerVm::playQueue,
                    onLyricOverlayLines = playerVm::setLyricOverlayLines,
                    onLyricOverlayFullHeight = playerVm::setLyricOverlayFullHeight,
                    onLyricOverlayFullWidthPercent = playerVm::setLyricOverlayFullWidthPercent,
                    onLyricOverlayFullOffsetPercent = playerVm::setLyricOverlayFullOffsetPercent,
                    onLyricOverlayShowTitle = playerVm::setLyricOverlayShowTitle,
                    onLyricOverlayTitleScale = playerVm::setLyricOverlayTitleScale,
                    onLyricOverlayTextScale = playerVm::setLyricOverlayTextScale,
                    onLyricOverlayAlign = playerVm::setLyricOverlayAlign,
                    onLyricOverlayBackdrop = playerVm::setLyricOverlayBackdrop,
                    onLyricOverlayPauseMode = playerVm::setLyricOverlayPauseMode,
                    onLyricOverlayWidthPercent = playerVm::setLyricOverlayWidthPercent,
                    onLyricOverlayTextColor = playerVm::setLyricOverlayTextColor,
                    onLyricOverlayHiddenApps = playerVm::setLyricOverlayHiddenApps,
                    onForegroundLogEnabled = playerVm::setForegroundLogEnabled,
                    onDebugUnlocked = playerVm::setDebugUnlocked,
                    onOverlayEditing = playerVm::setOverlayEditing,
                    onOverlayPreview = playerVm::previewOverlay,
                    canDrawOverlay = canDrawOverlay,
                    overlayHealth = overlayHealth,
                    onLyricScale = playerVm::setLyricScale,
                    onLyricTapSeeks = playerVm::setLyricTapSeeks,
                    onLyricBilingual = playerVm::setLyricBilingual,
                    onSpeed = playerVm::setSpeed,
                    onPitch = playerVm::setPitchSemitones,
                    onSkipSilence = playerVm::setSkipSilence,
                    onAutoResume = playerVm::setAutoResume,
                    onAutoPlayOnBoot = playerVm::setAutoPlayOnBoot,
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
                    onResetLibrary = {
                        settings.resetLibrary()
                        // 注意不是 reloadAll()：范围还停在「全盘」时会立刻重扫，
                        // 刚清空的曲库会被填回来（见 LibraryViewModel.onLibraryCleared）
                        lib.onLibraryCleared()
                    },
                    onRestoreExcluded = lib::restoreExcludedSongs,
                    onOpenReport = { showReport = true },
                    onOpenDuplicates = { showDuplicates = true },
                    onOpenStats = { showLibraryStats = true },
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
                    // 车载设置页的一步回播放：先收起设置再展开播放页，
                    // 否则播放页会被设置覆盖层压在下面（层级顺序在 Box 里是固定的）。
                    onOpenPlayer = {
                        showSettings = false
                        crashLogText = null
                        playerVm.openPlayer()
                    },
                    onTogglePlay = playerVm::togglePlay,
                    onPrevious = playerVm::previous,
                    onNext = playerVm::next,
                    onRescan = { lib.scan() },
                    // 扫描范围是弹层：先关设置，弹层才不会浮在设置页上面关不掉
                    onOpenScanScope = {
                        showSettings = false
                        crashLogText = null
                        showScanScope = true
                    },
                )
            }

            AnimatedVisibility(
                visible = showReport,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
                modifier = Modifier.fillMaxSize(),
            ) {
                ListeningReportScreen(
                    state = library.report,
                    onClose = { showReport = false },
                    onSetDays = lib::setReportDays,
                    onPlayTop = { songs -> if (songs.isNotEmpty()) playerVm.playQueue(songs, 0) },
                )
            }

            AnimatedVisibility(
                visible = showDuplicates,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
                modifier = Modifier.fillMaxSize(),
            ) {
                DuplicateCleanupScreen(
                    state = library,
                    onClose = { showDuplicates = false },
                    onScan = lib::scanDuplicates,
                    onClean = lib::cleanDuplicates,
                    onClear = lib::clearDuplicateScan,
                )
            }

            // 曲库格式与容量：与收听报告 / 重复清理同级的全屏子页。
            // 统计在组合期现算（remember 只跟曲库变），没有异步、没有 loading 态 ——
            // 曲库本来就在内存里，几百到几万首的聚合是毫秒级的事。
            AnimatedVisibility(
                visible = showLibraryStats,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
                modifier = Modifier.fillMaxSize(),
            ) {
                LibraryStatsScreen(
                    stats = remember(library.songs) { buildLibraryStats(library.songs) },
                    onClose = { showLibraryStats = false },
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
                AlertDialog(
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

            if (bulkDeleteConfirm) {
                val count = library.selection.size
                AlertDialog(
                    onDismissRequest = { bulkDeleteConfirm = false },
                    title = { Text("删除 $count 个音频文件") },
                    text = {
                        Text(
                            "将从存储中永久删除所选的 $count 个文件（媒体库文件会再弹一次系统确认框），" +
                                "同目录的歌词文件会一并删除。\n" +
                                "此操作无法撤销。只想让它们不出现在曲库里，请改用「移出曲库」。",
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                bulkDeleteConfirm = false
                                lib.bulkDeleteFiles { sender ->
                                    bulkDeleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                                }
                            },
                        ) { Text("删除文件", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = { TextButton(onClick = { bulkDeleteConfirm = false }) { Text("取消") } },
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

            if (showBulkAddToPlaylist) {
                AddToPlaylistSheet(
                    playlists = library.playlists,
                    song = null,
                    count = library.selection.size,
                    onDismiss = { showBulkAddToPlaylist = false },
                    onPick = {
                        lib.bulkAddToPlaylist(it.id, it.name)
                        showBulkAddToPlaylist = false
                    },
                    onCreateNew = { name ->
                        lib.createPlaylist(name)
                        showBulkAddToPlaylist = false
                    },
                )
            }

            folderRemoveConfirmGroup?.let { group ->
                AlertDialog(
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

            if (saveQueueAsPlaylist) {
                SaveQueueAsPlaylistDialog(
                    count = player.queue.size,
                    onDismiss = { saveQueueAsPlaylist = false },
                    onSave = { name ->
                        lib.createPlaylistFromQueue(name, player.queue.map { it.mediaId })
                        saveQueueAsPlaylist = false
                    },
                )
            }

            if (showScanScope) {
                ScanScopeSheet(
                    current = library.scanScope,
                    folders = library.folders,
                    onFullScan = { showScanScope = false; requestFullScan() },
                    onPickFolder = { showScanScope = false; pickFolderAsScope() },
                    onUseFolders = { showScanScope = false; lib.setScanScope(ScanScope.FOLDERS) },
                    onRemoveFolder = { lib.removeFolder(it) },
                    onToggleExcluded = { uri, excluded -> lib.setFolderExcluded(uri, excluded) },
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
                    onSaveAsPlaylist = {
                        showQueue = false
                        saveQueueAsPlaylist = true
                    },
                )
            }
        }
    }
}

/**
 * 播放页在手机上是全屏覆盖层、在平板右栏里是一块面板，两处调用只差一个 `pane`。
 * 抽成一个薄封装，避免把 20 多个回调抄两遍（抄两遍就一定会有一处漏改）。
 */
@Composable
private fun PlayerPane(
    playerVm: PlayerViewModel,
    player: com.luckylearning.windtone.state.PlayerUiState,
    library: com.luckylearning.windtone.state.LibraryUiState,
    lib: LibraryViewModel,
    canDrawOverlay: Boolean,
    onShowQueue: () -> Unit,
    onShowAudioInfo: () -> Unit,
    onExportLyrics: ((android.net.Uri) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
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
            onLyricOffsetAbsolute = playerVm::setLyricOffsetAbsolute,
            onLyricScale = playerVm::setLyricScale,
            onShowQueue = onShowQueue,
            onSleep = playerVm::sleepAfter,
            onSleepEndOfSong = playerVm::sleepEndOfSong,
            onSleepEndOfAlbum = playerVm::sleepEndOfAlbum,
            onCancelSleep = playerVm::cancelSleep,
            onSpeed = playerVm::setSpeed,
            onPitch = playerVm::setPitchSemitones,
            onExportLyrics = onExportLyrics ?: { playerVm.exportLyrics(it) },
            onShowAudioInfo = onShowAudioInfo,
            // 车载沉浸态常驻歌名行的红心：收藏状态存在 LibraryUiState 里，
            // 用 stableKey 做集合查找，和长按菜单保持一致。
            isFavorite = player.current?.let { it.stableKey in library.favoriteUris } == true,
            onToggleFavorite = lib::toggleFavorite,
            // 播放设置弹层里的悬浮歌词快捷开关（与设置页同一个开关、同一套授权逻辑）
            canDrawOverlay = canDrawOverlay,
            onLyricOverlay = playerVm::setLyricOverlay,
            onLyricsPaneVisible = playerVm::setLyricsPaneVisible,
            pane = onExportLyrics == null,
        )
    }
}

/**
 * 多选态底部操作栏。
 *
 * 位置：**顶替**迷你播放条与底部导航栏（而不是叠在上面）——多选是一个短暂的、
 * 有明确开始与结束的状态，把它和"日常导航"混在一起只会让人误点。
 */
@Composable
private fun SelectionBar(
    count: Int,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onFavorite: () -> Unit,
    onEnqueue: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onDeleteFiles: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val hasSelection = count > 0
    Surface(color = scheme.surfaceContainerHigh, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            ) {
                IconButton(onClick = onExit) {
                    Icon(Icons.Filled.Close, contentDescription = "退出多选", tint = scheme.onSurface)
                }
                Text(
                    if (hasSelection) "已选 $count 首" else "请选择歌曲",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSelectAll) { Text("全选") }
                TextButton(onClick = onInvert) { Text("反选") }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 6.dp, end = 6.dp, bottom = 8.dp),
            ) {
                SelectionAction(AppIcons.PlaylistAdd, "加入歌单", hasSelection, onAddToPlaylist)
                SelectionAction(AppIcons.FavoriteBorder, "收藏", hasSelection, onFavorite)
                SelectionAction(AppIcons.QueueMusic, "加入队列", hasSelection, onEnqueue)
                SelectionAction(AppIcons.VisibilityOff, "移出曲库", hasSelection, onRemoveFromLibrary, danger = true)
                SelectionAction(Icons.Filled.Close, "删除文件", hasSelection, onDeleteFiles, danger = true)
            }
        }
    }
}

@Composable
private fun SelectionAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            else if (danger) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            fontSize = 13.sp,
            color = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            else if (danger) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 把当前队列存成歌单：给个预填名，省得每次手打 */
@Composable
private fun SaveQueueAsPlaylistDialog(
    count: Int,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf(defaultPlaylistName()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("把当前队列存为歌单") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "将把队列里的 $count 首歌按当前顺序保存成一个新歌单。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("歌单名称") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onSave(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 默认歌单名：`队列 10-15 14:30`（按时间命名，用户可改） */
private fun defaultPlaylistName(): String {
    val now = java.util.Calendar.getInstance()
    val md = "%02d-%02d".format(now.get(java.util.Calendar.MONTH) + 1, now.get(java.util.Calendar.DAY_OF_MONTH))
    val hm = "%02d:%02d".format(now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE))
    return "队列 $md $hm"
}
