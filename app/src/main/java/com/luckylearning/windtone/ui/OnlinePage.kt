package com.luckylearning.windtone.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.luckylearning.windtone.MusicApp
import com.luckylearning.windtone.R
import com.luckylearning.windtone.data.db.MusicDb
import com.luckylearning.windtone.data.db.PlaylistEntity
import com.luckylearning.windtone.data.db.PlaylistSongEntity
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.online.BuiltInSearch
import com.luckylearning.windtone.data.online.OnlineBoards
import com.luckylearning.windtone.data.online.OnlineCover
import com.luckylearning.windtone.data.online.OnlineLibrary
import com.luckylearning.windtone.data.online.OnlineSonglists
import com.luckylearning.windtone.data.online.source.OnlineSourceRegistry
import com.luckylearning.windtone.data.online.source.OnlineTrack
import com.luckylearning.windtone.data.online.script.LxLoadReport
import com.luckylearning.windtone.data.online.script.LxScript
import com.luckylearning.windtone.data.online.script.LxScriptStore
import com.luckylearning.windtone.data.online.script.LxSourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 洛雪源页面：搜索 + 排行榜 + 脚本管理 + 点播。
 *
 * 与云盘页互斥显示（同为「在线内容」的一级页），但**不共用**它的数据层：
 * 云盘走 AliyunDrive，这里走 BuiltInSearch / OnlineBoards（发现歌曲）+ 脚本（换直链），
 * 两条直链最后都汇到同一个 `lx-pending` / `aliyun-pending` 滑窗补链。
 *
 * ## 两个入口的分工（对齐洛雪首页）
 * - **搜索**：知道歌名时用；
 * - **排行榜**：不知道听什么时用。洛雪首页的主力其实是榜单，只给搜索框会显得「空」。
 * 两者都走内置接口（脚本没有 search/list 能力，见 `supportActions`），
 * 所以「有结果」不等于「能播」—— 点播前必须确认已经加载音源脚本。
 *
 * ## 交互约定
 * - **点一首 = 整组入队**：同一组结果一起进队列，从点中的那首开始。
 * - **长按 = 收藏**：收藏时把该曲入库（`songs` 表），之后与本地歌一样出现在「我的收藏」。
 * - **每组可分页**：四家结果混排时，单组的「加载更多」只追加该源。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnlinePage(
    onPlay: (List<Song>, Int) -> Unit,
    onClose: () -> Unit,
    onOpenDrawer: () -> Unit,
    /** 播放页是否展开：展开时本页的返回键必须让位（播放页在最上层） */
    playerExpanded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { LxScriptStore(context) }
    val app = context.applicationContext as? MusicApp

    var keyword by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<BuiltInSearch.Result>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var selectedSource by remember { mutableStateOf("all") }
    var manage by remember { mutableStateOf(false) }
    var scripts by remember { mutableStateOf(store.all()) }
    var report by remember { mutableStateOf(LxSourceManager.report) }
    /** 每个源已请求到第几页（1 = 只搜过第一页） */
    val pages = remember { mutableStateMapOf<String, Int>() }
    val loadingMore = remember { mutableStateMapOf<String, Boolean>() }

    /** 0 = 搜索，1 = 歌单，2 = 排行榜 */
    var tab by remember { mutableIntStateOf(0) }
    var openBoard by remember { mutableStateOf<OnlineBoards.Board?>(null) }
    var boardTracks by remember { mutableStateOf<List<OnlineTrack>>(emptyList()) }
    var boardLoading by remember { mutableStateOf(false) }
    var boardError by remember { mutableStateOf<String?>(null) }

    // 歌单详情（洛雪 `nav_songlist` → 点进一个歌单）
    var openSheet by remember { mutableStateOf<OnlineSonglists.Sheet?>(null) }
    var sheetTracks by remember { mutableStateOf<List<OnlineTrack>>(emptyList()) }
    var sheetLoading by remember { mutableStateOf(false) }
    var sheetError by remember { mutableStateOf<String?>(null) }
    var sheetSaving by remember { mutableStateOf(false) }

    // 详情页是子页：本页自己收返回，不让它穿透到 MainScreen 去关整个在线页。
    // 注册在本组件内 = 比 MainScreen 的 BackHandler 晚注册 = 优先级更高，正好符合层级。
    BackHandler(enabled = !playerExpanded && (openSheet != null || openBoard != null)) {
        if (openSheet != null) openSheet = null else openBoard = null
    }

    // 首次进入：等启动时的后台脚本加载跑完再刷新报告。
    // 不做这一步的竞态：冷启动后立刻进本页点播，注册表可能还是空的 → 取址失败，
    // 用户会看到「搜得到但播不了」，且不会主动重试。awaitReady 内部有 Mutex 串行化。
    LaunchedEffect(Unit) { report = LxSourceManager.awaitReady(context.applicationContext) }

    fun doSearch() {
        val q = keyword.trim()
        if (q.isBlank() || searching) return
        searching = true
        searched = true
        pages.clear()
        scope.launch {
            val r = BuiltInSearch.search(
                q,
                sources = if (selectedSource == "all") BuiltInSearch.SOURCES else listOf(selectedSource),
            )
            results = r
            r.forEach { pages[it.source] = 1 }
            searching = false
        }
    }

    /** 单组翻页：只请求这一家，合并去重后写回该组 */
    fun loadMore(group: BuiltInSearch.Result) {
        if (loadingMore[group.source] == true) return
        val next = (pages[group.source] ?: 1) + 1
        loadingMore[group.source] = true
        scope.launch {
            val more = runCatching {
                BuiltInSearch.search(keyword.trim(), listOf(group.source), page = next)
            }.getOrNull()
            val fresh = more?.firstOrNull()?.tracks.orEmpty()
            if (fresh.isNotEmpty()) {
                results = results.map { g ->
                    if (g.source != group.source) g
                    else g.copy(tracks = (g.tracks + fresh).distinctBy { it.stableKey })
                }
                pages[group.source] = next
            }
            loadingMore[group.source] = false
        }
    }

    /**
     * 整组入队：先把这一组的曲目批量入库（拿到 dbId，收藏/统计才有锚点），再起播。
     * 批量走 `upsertAll`，一次写盘 —— 30 首逐条 upsert 会做 30 次全量 JSON 序列化。
     *
     * 搜索与榜单都走这一条，保证「点一首 = 从这里连续播下去」只有一份实现。
     */
    fun playTracks(tracks: List<OnlineTrack>, index: Int) {
        if (tracks.isEmpty()) return
        // 搜索/榜单走内置接口、不需要脚本，所以「有结果」不代表「能播」：
        // 没有音源时直链根本换不到。这里挡在入队之前，并把音源管理直接弹出来，
        // 否则用户点一首歌只会看到「什么都没发生」（v1.2.61 实机反馈）。
        if (OnlineSourceRegistry.isEmpty) {
            Toast.makeText(context, AppStrings.s(R.string.online_need_source_toast), Toast.LENGTH_LONG).show()
            manage = true
            return
        }
        scope.launch {
            val songs = withContext(Dispatchers.IO) {
                runCatching { OnlineLibrary(context).upsertAll(tracks) }.getOrDefault(emptyList())
            }
            if (songs.isEmpty()) {
                Toast.makeText(context, AppStrings.s(R.string.online_error_title), Toast.LENGTH_SHORT).show()
                return@launch
            }
            onPlay(songs, index.coerceIn(0, songs.size - 1))
        }
    }

    fun loadBoard(board: OnlineBoards.Board) {
        openBoard = board
        boardTracks = emptyList()
        boardError = null
        boardLoading = true
        scope.launch {
            val r = runCatching { OnlineBoards.fetch(board) }.getOrNull()
            boardTracks = r?.tracks.orEmpty()
            boardError = r?.error ?: AppStrings.s(R.string.online_board_empty)
            boardLoading = false
        }
    }

    fun loadSheet(sheet: OnlineSonglists.Sheet) {
        openSheet = sheet
        sheetTracks = emptyList()
        sheetError = null
        sheetLoading = true
        scope.launch {
            val r = OnlineSonglists.detail(sheet.source, sheet.id)
            sheetTracks = r.tracks
            sheetError = if (r.tracks.isEmpty()) {
                AppStrings.s(R.string.online_sheet_empty)
            } else null
            sheetLoading = false
        }
    }

    /**
     * 把当前歌单收进「我的列表」：先把每首歌入库（拿主键），再建一份本地列表。
     *
     * 只建列表、不改收藏：用户在洛雪里收藏歌单，期望的是「这个歌单归我了」，
     * 不是「这 60 首都进了我喜欢的音乐」。
     */
    fun saveSheetToList(sheet: OnlineSonglists.Sheet) {
        if (sheetTracks.isEmpty() || sheetSaving) return
        sheetSaving = true
        scope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching {
                    val songs = OnlineLibrary(context).upsertAll(sheetTracks)
                    val dao = MusicDb.get(context).playlistDao()
                    val now = System.currentTimeMillis()
                    val pid = dao.insert(
                        PlaylistEntity(
                            name = sheet.name.ifBlank { AppStrings.s(R.string.online_sheet_default_name) }
                                .take(80),
                            createdAt = now,
                        ),
                    )
                    var position = 0
                    songs.forEach { song ->
                        val dbId = song.dbId ?: return@forEach
                        dao.insertSong(
                            PlaylistSongEntity(
                                playlistId = pid,
                                songId = dbId,
                                position = position++,
                                addedAt = now,
                            ),
                        )
                    }
                    sheet.name
                }.getOrNull()
            }
            sheetSaving = false
            if (name == null) {
                Toast.makeText(context, AppStrings.s(R.string.online_save_failed), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    context,
                    AppStrings.s(R.string.online_saved_to_list, name),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun toggleFavorite(track: OnlineTrack) {        scope.launch {
            val fav = withContext(Dispatchers.IO) {
                runCatching {
                    val song = OnlineLibrary(context).upsert(track)
                    app?.container?.favorites?.toggleSong(song)
                }.getOrNull()
            }
            if (fav == null) {
                Toast.makeText(context, AppStrings.s(R.string.online_favorite_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(openSheet?.name ?: openBoard?.name ?: stringResource(R.string.online_page_title))
            },
            navigationIcon = {
                IconButton(onClick = {
                    // 详情是子页：返回先退回上层列表，再退出在线页
                    when {
                        openSheet != null -> openSheet = null
                        openBoard != null -> openBoard = null
                        else -> onClose()
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                }
            },
            actions = {
                // 抽屉入口保留：在线页是一级页，用户在这里也应该能直接跳到云盘/收藏
                IconButton(onClick = onOpenDrawer) {
                    Icon(AppIcons.Menu, contentDescription = null)
                }
                TextButton(onClick = { manage = true }) {
                    Text(stringResource(R.string.online_manage_sources))
                }
            },
        )

        val sheet = openSheet
        if (sheet != null) {
            SheetDetailPane(
                sheet = sheet,
                tracks = sheetTracks,
                loading = sheetLoading,
                error = sheetError,
                saving = sheetSaving,
                onPlay = { playTracks(sheetTracks, it) },
                onFavorite = { toggleFavorite(it) },
                onSaveToList = { saveSheetToList(sheet) },
            )
            SourceStatusLine(report = report, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            return@Column
        }

        val board = openBoard
        if (board != null) {
            BoardDetail(
                tracks = boardTracks,
                loading = boardLoading,
                error = boardError,
                onPlay = { playTracks(boardTracks, it) },
                onFavorite = { toggleFavorite(it) },
            )
            SourceStatusLine(report = report, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            return@Column
        }

        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text(stringResource(R.string.online_tab_search)) },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text(stringResource(R.string.online_tab_sheets)) },
            )
            Tab(
                selected = tab == 2,
                onClick = { tab = 2 },
                text = { Text(stringResource(R.string.online_tab_boards)) },
            )
        }

        SourceStatusLine(report = report, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))

        if (tab == 2) {
            BoardsList(onOpen = { loadBoard(it) })
            return@Column
        }
        if (tab == 1) {
            SonglistPane(onOpen = { loadSheet(it) })
            return@Column
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                label = { Text(stringResource(R.string.online_search_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { doSearch() }, enabled = !searching) {
                Text(if (searching) stringResource(R.string.online_searching) else stringResource(R.string.online_search))
            }
        }

        SourceFilterRow(
            selected = selectedSource,
            onSelect = { selectedSource = it },
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        when {
            searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            // 三种空态必须分开：「没搜过」「搜了没结果」「搜了但每个源都报错」——
            // 统一显示「暂无结果」会让用户以为是关键词的问题，而实际是接口挂了 / 没网
            !searched -> EmptyHint(stringResource(R.string.online_idle_hint))
            results.all { it.tracks.isEmpty() } -> {
                val errors = results.mapNotNull { it.error }
                if (errors.isNotEmpty() && errors.size == results.size) {
                    EmptyHint(
                        stringResource(R.string.online_all_sources_failed) + "\n" + errors.joinToString("\n"),
                    )
                } else {
                    EmptyHint(stringResource(R.string.online_no_result))
                }
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                results.forEach { group ->
                    if (group.tracks.isEmpty()) return@forEach
                    item(key = "head_${group.source}") {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp, start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                BuiltInSearch.SOURCE_NAMES[group.source] ?: group.source,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.online_group_count, group.tracks.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(group.tracks, key = { it.stableKey }) { track ->
                        val index = group.tracks.indexOfFirst { it.stableKey == track.stableKey }
                        TrackRow(
                            track = track,
                            onPlay = { playTracks(group.tracks, index) },
                            onFavorite = { toggleFavorite(track) },
                        )
                    }
                    item(key = "more_${group.source}") {
                        val loading = loadingMore[group.source] == true
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            TextButton(onClick = { loadMore(group) }, enabled = !loading) {
                                Text(
                                    if (loading) stringResource(R.string.online_searching)
                                    else stringResource(R.string.online_load_more),
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (manage) {
        SourceManagerDialog(
            scripts = scripts,
            report = report,
            onDismiss = { manage = false },
            onImport = { text, isUrl ->
                scope.launch {
                    val imported = withContext(Dispatchers.IO) {
                        val code = if (isUrl) downloadScript(text) else text
                        if (code.isNullOrBlank() || code.length < 200) {
                            return@withContext null
                        }
                        val (name, version) = LxScript.parseMeta(code)
                        val script = LxScript(
                            id = "custom:" + UUID.randomUUID().toString(),
                            name = name.ifBlank { text.takeLast(40) },
                            code = code,
                            version = version,
                            addedAt = System.currentTimeMillis(),
                        )
                        store.upsert(script)
                        script
                    }
                    if (imported == null) {
                        Toast.makeText(context, AppStrings.s(R.string.online_import_failed), Toast.LENGTH_SHORT).show()
                    } else {
                        report = LxSourceManager.reload(context)
                        scripts = store.all()
                        Toast.makeText(
                            context,
                            AppStrings.s(R.string.online_source_loaded, report.sources.size),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            onToggle = { script ->
                scope.launch {
                    store.setEnabled(script.id, !script.enabled)
                    scripts = store.all()
                    report = LxSourceManager.reload(context)
                }
            },
            onDelete = { script ->
                scope.launch {
                    store.remove(script.id)
                    scripts = store.all()
                    report = LxSourceManager.reload(context)
                }
            },
            onReload = {
                scope.launch { report = LxSourceManager.reload(context) }
            },
        )
    }
}

/** 榜单列表：按平台分组，每组 3 个一行（不依赖 FlowRow 的实验 API） */
@Composable
private fun BoardsList(onOpen: (OnlineBoards.Board) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OnlineBoards.GROUPS.forEach { group ->
            item(key = "g_${group.source}") {
                Text(
                    group.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp, start = 4.dp),
                )
            }
            group.boards.chunked(3).forEachIndexed { rowIndex, row ->
                item(key = "r_${group.source}_$rowIndex") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { board ->
                            OutlinedButton(
                                onClick = { onOpen(board) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    board.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        // 补空位，避免最后一行按钮被拉宽
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** 榜单详情：与搜索结果同一套行样式，点一首从该首开始连续播放整个榜单 */
@Composable
private fun BoardDetail(
    tracks: List<OnlineTrack>,
    loading: Boolean,
    error: String?,
    onPlay: (Int) -> Unit,
    onFavorite: (OnlineTrack) -> Unit,
) {
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        tracks.isEmpty() -> EmptyHint(error ?: AppStrings.s(R.string.online_board_empty))
        else -> LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(tracks, key = { it.stableKey }) { track ->
                val index = tracks.indexOfFirst { it.stableKey == track.stableKey }
                TrackRow(
                    track = track,
                    onPlay = { onPlay(index) },
                    onFavorite = { onFavorite(track) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** 源筛选：全部 + 四家。切换只影响**下一次搜索**，不重跑当前结果（避免误触打乱列表） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceFilterRow(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val options = listOf("all") + BuiltInSearch.SOURCES
        options.forEach { id ->
            FilterChip(
                selected = selected == id,
                onClick = { onSelect(id) },
                label = {
                    Text(
                        if (id == "all") stringResource(R.string.online_filter_all)
                        else BuiltInSearch.SOURCE_NAMES[id]?.removeSuffix("音乐") ?: id,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}

@Composable
private fun SourceStatusLine(report: LxLoadReport, modifier: Modifier = Modifier) {
    val text = if (report.sources.isEmpty()) {
        stringResource(R.string.online_no_script_hint)
    } else {
        stringResource(R.string.online_loaded_sources, report.sources.size)
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (report.sources.isEmpty()) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
internal fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TrackRow(track: OnlineTrack, onPlay: () -> Unit, onFavorite: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = onPlay, onLongClick = { menu = true })
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackCover(track)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // 时长未知（0）时公共 formatDuration 给 "--:--"，这里当空处理，
                    // 免得每条搜索结果的副行都挂一个占位时长
                    listOf(track.displayArtist, track.album.orEmpty(), formatDuration(track.durationMs))
                        .filter { it.isNotBlank() && it != "--:--" }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (track.qualities.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    track.qualities.maxByOrNull { it.ordinal }?.shortLabel ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.online_play_this)) },
                onClick = { menu = false; onPlay() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.online_favorite)) },
                onClick = { menu = false; onFavorite() },
            )
        }
    }
}

/** 40dp 封面，取不到就是一块纯色占位 —— 不阻塞行高，也不留白洞 */
@Composable
private fun TrackCover(track: OnlineTrack) {
    val context = LocalContext.current
    var bitmap by remember(track.stableKey) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(track.stableKey) {
        bitmap = runCatching {
            OnlineCover.thumb(context, track, COVER_PX)
        }.getOrNull()
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                AppIcons.MusicNote, null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceManagerDialog(
    scripts: List<LxScript>,
    report: LxLoadReport,
    onDismiss: () -> Unit,
    onImport: (String, Boolean) -> Unit,
    onToggle: (LxScript) -> Unit,
    onDelete: (LxScript) -> Unit,
    onReload: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.online_manage_sources)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.online_source_loaded, report.sources.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (report.errors.isNotEmpty()) {
                    Text(
                        stringResource(R.string.online_source_errors, report.errors.joinToString("; ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.online_source_privacy),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.online_source_url_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Row {
                    TextButton(onClick = { onImport(text, true) }) {
                        Text(stringResource(R.string.online_import_url))
                    }
                    TextButton(onClick = { onImport(text, false) }) {
                        Text(stringResource(R.string.online_import_code))
                    }
                    TextButton(onClick = onReload) { Text(stringResource(R.string.online_reload)) }
                }
                scripts.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (s.enabled) stringResource(R.string.online_source_enabled)
                                else stringResource(R.string.online_source_disabled),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        TextButton(onClick = { onToggle(s) }) {
                            Text(if (s.enabled) stringResource(R.string.off) else stringResource(R.string.on))
                        }
                        IconButton(onClick = { onDelete(s) }) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.delete))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** 40dp @ xxhdpi 的物理像素，交给解码器按需降采样 */
private const val COVER_PX = 120

private suspend fun downloadScript(url: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use {
            if (!it.isSuccessful) return@withContext null
            it.body?.string()
        }
    }.getOrNull()
}
