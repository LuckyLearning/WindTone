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
import com.luckylearning.windtone.data.applyLibraryFormatFilter
import com.luckylearning.windtone.data.LibrarySearch
import com.luckylearning.windtone.data.buildLibraryStats
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.GroupInfo
import com.luckylearning.windtone.state.LibraryView
import com.luckylearning.windtone.state.PlaylistInfo
import com.luckylearning.windtone.state.VirtualPlaylist
import com.luckylearning.windtone.state.virtualPlaylistInfo

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
    // 明暗模式（v1.2.5）：设置页里改档要立刻反映到选中态，所以读同一条流，
    // 而不是像其它只写 SharedPreferences 的偏好那样用一次性快照 + 本地 state 兜。
    val themeMode by playerVm.themeMode.collectAsStateWithLifecycle()
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
    // v1.1.80：设置页打开时落在哪一页。默认主页；播放页「更多设置…」深链到悬浮歌词。
    var settingsEntryPage by remember { mutableStateOf(SettingsPage.ROOT) }
    var showReport by remember { mutableStateOf(false) }
    var showDuplicates by remember { mutableStateOf(false) }
    // 曲库格式与容量（v1.1.17）：纯本地统计，进入时现算，不落库
    var showLibraryStats by remember { mutableStateOf(false) }
    // 侧边抽屉开合（v1.1.3：取代底部五视图导航）。只在曲库页出现，
    // 播放页 / 设置页这类全屏覆盖层在效果图里各有自己的返回按钮，没有汉堡入口。
    var drawerOpen by remember { mutableStateOf(false) }
    // 阿里云盘页（v1.1.75 升为一级入口）：与曲库视图互斥显示，抽屉里切换。
    var showAliyun by remember { mutableStateOf(false) }
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
            AppShortcut.PLAY_ALL -> {
                val queue = applyLibraryFormatFilter(library.songs, library.formatFilter)
                playerVm.playQueue(queue, 0)
            }

            AppShortcut.SHUFFLE -> {
                val queue = applyLibraryFormatFilter(library.songs, library.formatFilter)
                playerVm.playShuffled(queue)
            }

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

    // ---------------------------------------------------------------- 外部播放入口
    //
    // 文件管理器点歌（ACTION_VIEW audio/*）与系统语音点歌（MEDIA_PLAY_FROM_SEARCH）。
    // 与快捷方式同一套「先登记、等曲库就绪再执行」的理由，但这里**不禁**在最开始执行：
    //  · 点歌（OpenUri）其实不依赖曲库 —— 不在库里就走临时曲，所以立刻可执行；
    //  · 语音搜索必须在曲库装载完之后才有得搜，否则搜出来是空，用户以为没这首歌。
    val externalRequest by ExternalMedia.pending.collectAsStateWithLifecycle()
    LaunchedEffect(externalRequest, library.libraryLoaded, library.songs.size) {
        val request = externalRequest ?: return@LaunchedEffect
        when (request) {
            is ExternalRequest.OpenUri -> {
                ExternalMedia.consumePending()
                // 曲库里已有同一首（同一个 uri）就用库里的那份 —— 它有封面缓存、
                // 歌词、收藏状态；找不到才退化成临时曲。
                val hit = library.songs.firstOrNull { it.uri.toString() == request.uri }
                    ?: ExternalMedia.transientSong(context, request.uri)
                playerVm.playQueue(listOf(hit), 0)
            }

            is ExternalRequest.SearchSong -> {
                // 曲库还没装载完就等（结论没出来之前搜索必然为空）
                if (!library.libraryLoaded) return@LaunchedEffect
                ExternalMedia.consumePending()
                val hits = LibrarySearch.search(library.songs, request.query)
                if (hits.isEmpty()) {
                    snackbarHostState.showSnackbar("没找到「${request.query}」，试试歌名或歌手")
                } else {
                    playerVm.playQueue(hits, 0)
                }
            }
        }
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
    // 云盘页先于抽屉注册：抽屉开着时返回键只收抽屉（抽屉的 BackHandler 在后面，优先级更高）。
    BackHandler(enabled = showAliyun) { showAliyun = false }
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
                            granted = granted,
                            onImportFolder = importFolder,
                            onFullScan = requestFullScan,
                            onOpenScanScope = { showScanScope = true },
                        )

                        else -> GroupListView(
                            library = library,
                            onOpen = lib::openGroup,
                            canRemove = library.view == LibraryView.FOLDER,
                            onRemove = { folderRemoveConfirmGroup = it },
                            carMode = carMode,
                            granted = granted,
                            onImportFolder = importFolder,
                            onFullScan = requestFullScan,
                            onOpenScanScope = { showScanScope = true },
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
                        // 选曲库视图时退出云盘页（与抽屉 onSelectView 同一口径）
                        onSelect = {
                            showAliyun = false
                            lib.setView(it)
                        },
                        onOpenSettings = { settingsEntryPage = SettingsPage.ROOT; showSettings = true },
                        aliyunActive = showAliyun,
                        onOpenAliyun = { showAliyun = true },
                        onOpenScanScope = { showScanScope = true },
                    )
                }
                Column(Modifier.weight(1f)) {
                if (showAliyun) {
                    // 阿里云盘一级页（v1.1.75）：自带头部（返回/标题/菜单），顶替
                    // LibraryHeader + 曲库列表的位置；迷你播放条保留在下面，播云盘歌
                    // 时照常显示。选曲库视图会自动退出云盘页（见抽屉的 onSelectView）。
                    AliyunPanPage(
                        onPlay = playerVm::playQueue,
                        onClose = { showAliyun = false },
                        onOpenDrawer = { drawerOpen = true },
                        // 抽屉开着时云盘页不拦返回键：返回键优先收抽屉（云盘页在子目录
                        // 里会拦返回逐级回上级，两个 BackHandler 后注册者优先，必须让位）
                        backEnabled = !drawerOpen,
                        modifier = Modifier.weight(1f),
                    )
                } else {
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
                        onFormatFilter = lib::setFormatFilter,
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
                } // else（曲库页）：阿里云盘一级页时不渲染曲库头部与列表

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
                // 选曲库视图时退出阿里云盘页：抽屉里的「音乐库」组与「在线音乐」组互斥显示
                onSelectView = {
                    showAliyun = false
                    lib.setView(it)
                },
                aliyunActive = showAliyun,
                onOpenAliyun = { showAliyun = true },
                onOpenScanScope = { showScanScope = true },
                onOpenSettings = { settingsEntryPage = SettingsPage.ROOT; showSettings = true },
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
                    // 悬浮窗样式速选面板的「更多设置…」：直接掀开设置覆盖层
                    onOpenSettings = { settingsEntryPage = SettingsPage.OVERLAY; showSettings = true },
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
                    themeMode = themeMode,
                    dynamicTheme = dynamicThemeOn,
                    autoCheckUpdate = autoCheckUpdateOn,
                    crashLogCount = crashLogCount,
                    crashLogText = crashLogText,
                    initialPage = settingsEntryPage,
                    onClose = {
                        showSettings = false
                        crashLogText = null
                    },
                    onDynamicTheme = { dynamicThemeOn = it; playerVm.setDynamicTheme(it) },
                    onThemeMode = playerVm::setThemeMode,
                    onCarMode = playerVm::setCarMode,
                    onLyricOverlay = playerVm::setLyricOverlay,
                    onTestBootPlayback = playerVm::startPlaybackInBackground,
                    onPlayQueue = playerVm::playQueue,
                    onLyricOverlayLines = playerVm::setLyricOverlayLines,
                    onLyricOverlayHandles = playerVm::setOverlayHandles,
                    onOverlayHandle = playerVm::setOverlayHandle,
                    onOverlayHandleOrder = playerVm::setOverlayHandleOrder,
                    onLyricOverlayFullHeight = playerVm::setLyricOverlayFullHeight,
                    onLyricOverlayFullWidthPercent = playerVm::setLyricOverlayFullWidthPercent,
                    onLyricOverlayFullOffsetPercent = playerVm::setLyricOverlayFullOffsetPercent,
                    onLyricOverlayShowTitle = playerVm::setLyricOverlayShowTitle,
                    onLyricOverlayTitlePercent = playerVm::setLyricOverlayTitlePercent,
                    onLyricOverlayTextSp = playerVm::setLyricOverlayTextSp,
                    onLyricOverlayAlign = playerVm::setLyricOverlayAlign,
                    onLyricOverlayPauseMode = playerVm::setLyricOverlayPauseMode,
                    onLyricOverlayWidthPercent = playerVm::setLyricOverlayWidthPercent,
                    onLyricOverlayTextColor = playerVm::setLyricOverlayTextColor,
                    onLyricOverlayBoxHeightPercent = playerVm::setLyricOverlayBoxHeightPercent,
                    onLyricOverlayFullHeightPercent = playerVm::setLyricOverlayFullHeightPercent,
                    onLyricOverlayXPercent = playerVm::setLyricOverlayXPercent,
                    onLyricOverlayYPercent = playerVm::setLyricOverlayYPercent,
                    onLyricOverlayFullYPercent = playerVm::setLyricOverlayFullYPercent,
                    onLyricOverlayBgColor = playerVm::setLyricOverlayBgColor,
                    onLyricOverlayBgAlphaPercent = playerVm::setLyricOverlayBgAlphaPercent,
                    onLyricOverlayCornerRadiusDp = playerVm::setLyricOverlayCornerRadiusDp,
                    onLyricOverlayBorderWidthDp = playerVm::setLyricOverlayBorderWidthDp,
                    onLyricOverlayBorderColor = playerVm::setLyricOverlayBorderColor,
                    onLyricOverlayLineSpacingDp = playerVm::setLyricOverlayLineSpacingDp,
                    onLyricOverlayCurrentColor = playerVm::setLyricOverlayCurrentColor,
                    onLyricOverlayDimPercent = playerVm::setLyricOverlayDimPercent,
                    onLyricOverlayStrokeWidthDp = playerVm::setLyricOverlayStrokeWidthDp,
                    onLyricOverlayStrokeColor = playerVm::setLyricOverlayStrokeColor,
                    onLyricOverlayFadeMs = playerVm::setLyricOverlayFadeMs,
                    onLyricOverlayTitleMode = playerVm::setLyricOverlayTitleMode,
                    onLyricOverlayHiddenApps = playerVm::setLyricOverlayHiddenApps,
                    onApplyOverlayPreset = playerVm::applyOverlayPreset,
                    onForegroundLogEnabled = playerVm::setForegroundLogEnabled,
                    onDebugUnlocked = playerVm::setDebugUnlocked,
                    onOverlayEditing = playerVm::setOverlayEditing,
                    onOverlayPreview = playerVm::previewOverlay,
                    canDrawOverlay = canDrawOverlay,
                    overlayHealth = overlayHealth,
                    onLyricScale = playerVm::setLyricScale,
                    onLyricTapSeeks = playerVm::setLyricTapSeeks,
                    onPlayerA11yAction = playerVm::setPlayerA11yAction,
                    onSpeed = playerVm::setSpeed,
                    onPitch = playerVm::setPitchSemitones,
                    onSkipSilence = playerVm::setSkipSilence,
                    onSkipIntroSeconds = playerVm::setSkipIntroSeconds,
                    onSkipOutroSeconds = playerVm::setSkipOutroSeconds,
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
                    onSwitchDownloadRoute = updateVm::switchDownloadRoute,
                    onPingRoutes = updateVm::pingRoutes,
                    onOpenReleasePage = updateVm::openReleasePage,
                    onIgnoreUpdate = updateVm::ignoreUpdate,
                    onDismissUpdateDialog = updateVm::dismissUpdateDialog,
                    // 车载设置页（与手机同一套）顶栏的一步回播放：先收起设置再展开播放页，
                    // 否则播放页会被设置覆盖层压在下面（层级顺序在 Box 里是固定的）。
                    onOpenPlayer = {
                        showSettings = false
                        crashLogText = null
                        playerVm.openPlayer()
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
                    onClear = { playerVm.clearQueue() },
                    onShuffleOrder = { playerVm.shuffleQueueOrder() },
                )
            }
        }
    }
}
