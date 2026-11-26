package com.luckylearning.windtone.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luckylearning.windtone.ui.theme.AppVisual
import com.luckylearning.windtone.ui.theme.LocalUiFormFactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
import com.luckylearning.windtone.data.online.OnlineLists
import com.luckylearning.windtone.data.online.OnlineRecent
import com.luckylearning.windtone.data.online.OnlineResolver
import com.luckylearning.windtone.data.online.OnlineSonglists
import com.luckylearning.windtone.data.online.OnlineUrlMemo
import com.luckylearning.windtone.data.online.SavedSheetStore
import com.luckylearning.windtone.data.online.source.OnlineSourceRegistry
import com.luckylearning.windtone.data.online.source.OnlineTrack
import com.luckylearning.windtone.data.online.script.LxLoadReport
import com.luckylearning.windtone.data.online.script.LxScript
import com.luckylearning.windtone.data.online.script.LxScriptSource
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
 * 洛雪源页面：搜索 + 歌单 + 排行榜 + 我的列表 + 脚本管理 + 点播。
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
    place: LxFrame,
    canPop: Boolean,
    onBack: () -> Unit,
    onOpenDetail: (LxDetail) -> Unit,
    onSelectSection: (LxSection) -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    onOpenDrawer: () -> Unit,
    /** 播放页是否展开：展开时本页的返回键必须让位（播放页在最上层） */
    playerExpanded: Boolean = false,
    /** 抽屉 / 设置盖住本页时不拦返回，让上层先收自己 */
    backEnabled: Boolean = true,
    /** 已收藏的稳定键（与曲库共用一份）：行内心形据此显示实心 */
    favoriteKeys: Set<String> = emptySet(),
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
    var scripts by remember { mutableStateOf(store.all()) }
    var report by remember { mutableStateOf(LxSourceManager.report) }
    var sourcesReady by remember { mutableStateOf(LxSourceManager.isReady) }
    /** 用户切换「平台 → 脚本」后 +1，让音源管理弹窗里的候选重算 */
    var choiceRevision by remember { mutableIntStateOf(0) }
    /** 每个源已请求到第几页（1 = 只搜过第一页） */
    val pages = remember { mutableStateMapOf<String, Int>() }
    val loadingMore = remember { mutableStateMapOf<String, Boolean>() }

    var searchGen by remember { mutableIntStateOf(0) }
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
    /** 当前歌单是否已经收进我的列表（进详情时查一次，保存成功后置 true） */
    var sheetSaved by remember { mutableStateOf(false) }

    // 我的列表：只放从在线歌单收进来的列表。洛雪模式下「歌单」是平台歌单，
    // 自己攒下来的那一份在「我的列表」，与本地模式同名入口各看各的。
    var myLists by remember { mutableStateOf<List<OnlineLists.Summary>>(emptyList()) }
    var myListsLoading by remember { mutableStateOf(false) }
    var openMyList by remember { mutableStateOf<OnlineLists.Summary?>(null) }
    var myListSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var myListLoading by remember { mutableStateOf(false) }
    var confirmDeleteList by remember { mutableStateOf(false) }

    // 洛雪「最近播放」：只取在线曲，和本地「最近播放」按来源隔离
    var recentSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var recentLoading by remember { mutableStateOf(false) }
    var recentOpen by remember { mutableStateOf(false) }

    // 歌单页的数据与滚动位置必须放在这一层：AnimatedContent 打开歌单详情时会把
    // SonglistPane 移出组合，状态若留在它内部，返回时整页重建 —— 重新拉第一页、回到顶部。
    val songlistState = remember { SonglistUiState() }
    val sheetGridState = rememberLazyGridState()

    // 榜单列表 / 榜单详情 / 歌单详情的滚动位置同理：AnimatedContent 切子页会把内容移出组合，
    // 状态留在里面就等于每次都从第一行开始。三个都提到这一层。
    val boardListState = rememberLazyListState()
    val boardDetailState = rememberLazyListState()
    val sheetDetailState = rememberLazyListState()

    // 已经取过的榜单 / 歌单。返回列表再点同一项时复用结果，不重拉、不跳回顶部；
    // 换一项才清空并从头加载。
    var boardKey by remember { mutableStateOf<String?>(null) }
    var sheetKey by remember { mutableStateOf<String?>(null) }
    // 新开一个榜单/歌单才滚回顶部。记在页面外面：详情离开组合再回来时代数没变，不再滚。
    var boardScrollGen by remember { mutableIntStateOf(0) }
    var boardScrolledGen by remember { mutableIntStateOf(0) }
    var sheetScrollGen by remember { mutableIntStateOf(0) }
    var sheetScrolledGen by remember { mutableIntStateOf(0) }

    // 子页和上一个分区都由外面的栈决定。本页只在还能退的时候消费返回，
    // 并且让位给抽屉 / 设置 / 播放页（backEnabled）。根上不消费，Activity 回到桌面。
    BackHandler(enabled = backEnabled && !playerExpanded && canPop) { onBack() }
    // 最近播放是页面内的一层：后注册者优先，先关它，再退分区
    BackHandler(enabled = recentOpen) { recentOpen = false }

    // 栈把子页收起时，本地详情跟着收。切到别的分区时不擦掉底下那一帧的结果，
    // 返回还要看得到。
    LaunchedEffect(place.section, place.detail) {
        if (place.section != LxSection.SEARCH) {
            // 离开搜索后再回来的结果不能改当前栈顶（那已经是歌单 / 榜单了）
            searchGen++
        } else if (place.detail == LxDetail.NONE) {
            searchGen++
            searched = false
            results = emptyList()
            searching = false
        }
        // 歌单 / 榜单详情关掉时不擦掉 openSheet / openBoard。
        // 列表留在底层，详情只是盖上去再滑走；滑走的那一帧还要画刚才那个详情。
        // 离开这个分区再清，避免切回来时用过期的详情盖住列表。
        if (place.section != LxSection.SHEETS) openSheet = null
        if (place.section != LxSection.BOARDS) openBoard = null
        if (place.section == LxSection.MINE && place.detail != LxDetail.LIST) openMyList = null
        // 最近播放是 MINE 内的临时层，离开分区就收掉
        if (place.section != LxSection.MINE) recentOpen = false
    }

    // 「我的列表」每次进来重读一遍：保存发生在歌单详情，回到这里要立刻看到新列表。
    // 读之前先做一次同名合并 —— v1.3.2 之前重复点「收进我的列表」留下的两条同名列表，
    // 靠这一次幂等清理收拢成一条，不用用户自己删。
    LaunchedEffect(place.section) {
        if (place.section != LxSection.MINE) return@LaunchedEffect
        myListsLoading = true
        withContext(Dispatchers.IO) { OnlineLists.mergeDuplicates(context) }
        myLists = withContext(Dispatchers.IO) { OnlineLists.summaries(context) }
        // 最近播放只在 MINE 出现；进来先算一次，用于显示数量和封面
        recentSongs = withContext(Dispatchers.IO) { OnlineRecent.songs(context) }
        myListsLoading = false
    }

    // 首次进入：等启动时的后台脚本加载跑完再刷新报告。
    // 不做这一步的竞态：冷启动后立刻进本页点播，注册表可能还是空的 → 取址失败，
    // 用户会看到「搜得到但播不了」，且不会主动重试。awaitReady 内部有 Mutex 串行化。
    LaunchedEffect(Unit) {
        report = LxSourceManager.awaitReady(context.applicationContext)
        sourcesReady = true
    }

    fun doSearch() {
        val q = keyword.trim()
        if (q.isBlank() || searching) return
        val gen = ++searchGen
        searching = true
        searched = true
        pages.clear()
        // 旧搜索的在飞翻页不会再自己收尾，这里统一放掉，避免新结果第一页就点不动「加载更多」
        loadingMore.clear()
        scope.launch {
            val r = BuiltInSearch.search(
                q,
                sources = if (selectedSource == "all") BuiltInSearch.SOURCES else listOf(selectedSource),
            )
            if (gen != searchGen) return@launch
            results = r
            r.forEach { pages[it.source] = 1 }
            searching = false
            onOpenDetail(LxDetail.RESULTS)
        }
    }

    /** 单组翻页：只请求这一家，合并去重后写回该组 */
    fun loadMore(group: BuiltInSearch.Result) {
        if (loadingMore[group.source] == true) return
        val gen = searchGen
        val q = keyword.trim()
        val next = (pages[group.source] ?: 1) + 1
        loadingMore[group.source] = true
        scope.launch {
            try {
                val more = runCatching {
                    BuiltInSearch.search(q, listOf(group.source), page = next)
                }.getOrNull()
                // 用户已经发起了新搜索：旧翻页结果不能拼到新结果后面
                if (gen != searchGen) return@launch
                val fresh = more?.firstOrNull()?.tracks.orEmpty()
                if (fresh.isNotEmpty()) {
                    results = results.map { g ->
                        if (g.source != group.source) g
                        else g.copy(tracks = (g.tracks + fresh).distinctBy { it.stableKey })
                    }
                    pages[group.source] = next
                }
            } finally {
                // 新搜索已经接管这些组时，不要把它刚设上的 loading 清掉
                if (gen == searchGen) loadingMore[group.source] = false
            }
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
        // 没有音源时直链根本换不到。先等启动加载结束，避免「已加载 N 个音源」
        // 和「还没导入脚本」同时出现；确认仍然是空的，再打开左侧音源管理。
        scope.launch {
            if (OnlineSourceRegistry.isEmpty) {
                report = LxSourceManager.ensureLoaded(context.applicationContext)
                sourcesReady = true
            }
            if (OnlineSourceRegistry.isEmpty) {
                Toast.makeText(context, AppStrings.s(R.string.online_need_source_toast), Toast.LENGTH_LONG).show()
                onSelectSection(LxSection.SOURCES)
                return@launch
            }
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
        onOpenDetail(LxDetail.BOARD)
        val key = "${board.source}:${board.id}"
        // 刚看过同一榜单（返回列表后又点回来）：列表和滚动位置都还在，直接复用，
        // 不再重拉第一页、也不跳回顶部。
        if (boardKey == key && boardTracks.isNotEmpty()) return
        boardKey = key
        boardScrollGen++
        boardTracks = emptyList()
        boardError = null
        boardLoading = true
        scope.launch {
            try {
                val r = runCatching { OnlineBoards.fetch(board) }.getOrNull()
                // 用户已经点开别的榜单：这份结果作废，不能盖到新榜单的标题下
                if (boardKey != key) return@launch
                boardTracks = r?.tracks.orEmpty()
                boardError = r?.error ?: AppStrings.s(R.string.online_board_empty)
            } finally {
                if (boardKey == key) boardLoading = false
            }
        }
    }

    fun loadSheet(sheet: OnlineSonglists.Sheet) {
        openSheet = sheet
        onOpenDetail(LxDetail.SHEET)
        val key = "${sheet.source}:${sheet.id}"
        val cached = sheetKey == key && sheetTracks.isNotEmpty()
        if (!cached) {
            sheetKey = key
            sheetScrollGen++
            sheetTracks = emptyList()
            sheetError = null
            sheetLoading = true
        }
        scope.launch {
            // 收藏状态每次都重查：用户可能刚在「我的列表」里删掉了它
            val saved = withContext(Dispatchers.IO) { OnlineLists.isSaved(context, sheet) }
            // 用户已经点开别的歌单：收藏状态同样不能盖过去
            if (sheetKey != key) return@launch
            sheetSaved = saved
            if (cached) return@launch
            try {
                val r = OnlineSonglists.detail(sheet.source, sheet.id)
                if (sheetKey != key) return@launch
                sheetTracks = r.tracks
                sheetError = if (r.tracks.isEmpty()) {
                    AppStrings.s(R.string.online_sheet_empty)
                } else null
            } finally {
                if (sheetKey == key) sheetLoading = false
            }
        }
    }

    fun loadMyList(list: OnlineLists.Summary) {
        recentOpen = false
        openMyList = list
        onOpenDetail(LxDetail.LIST)
        myListSongs = emptyList()
        myListLoading = true
        scope.launch {
            myListSongs = withContext(Dispatchers.IO) { OnlineLists.songs(context, list.id) }
            myListLoading = false
        }
    }

    /** 打开洛雪「最近播放」：每次重查，刚播过的歌要立刻排在最前 */
    fun loadRecent() {
        recentOpen = true
        openMyList = null
        recentLoading = true
        scope.launch {
            recentSongs = withContext(Dispatchers.IO) { OnlineRecent.songs(context) }
            recentLoading = false
        }
    }

    /**
     * 把当前歌单收进「我的列表」。
     *
     * 只建列表、不改收藏：用户在洛雪里收藏歌单，期望的是「这个歌单归我了」，
     * 不是「这 60 首都进了我喜欢的音乐」。
     *
     * 幂等交给 [OnlineLists.saveSheet]：已经收过的歌单再点只补新曲目，不再新建 ——
     * 之前每次点都插一条新列表，用户看不到生效反馈就会再点一次，结果列表里两条一模一样的。
     */
    fun saveSheetToList(sheet: OnlineSonglists.Sheet) {
        if (sheetTracks.isEmpty() || sheetSaving) return
        val key = "${sheet.source}:${sheet.id}"
        sheetSaving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                OnlineLists.saveSheet(context, sheet, sheetTracks)
            }
            sheetSaving = false
            // 保存期间切到了别的歌单：这一份的收藏标记不能写到当前页上
            if (sheetKey != key) return@launch
            when (result) {
                is OnlineLists.SaveResult.Failed ->
                    Toast.makeText(context, AppStrings.s(R.string.online_save_failed), Toast.LENGTH_SHORT).show()
                is OnlineLists.SaveResult.Created -> {
                    sheetSaved = true
                    Toast.makeText(
                        context,
                        AppStrings.s(R.string.online_saved_to_list, sheet.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                is OnlineLists.SaveResult.Updated -> {
                    sheetSaved = true
                    Toast.makeText(
                        context,
                        AppStrings.s(R.string.online_list_updated, sheet.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    fun toggleFavorite(track: OnlineTrack) {
        scope.launch {
            val fav = withContext(Dispatchers.IO) {
                runCatching {
                    val song = OnlineLibrary(context).upsert(track)
                    app?.container?.favorites?.toggleSong(song)
                }.getOrNull()
            }
            // 成功也给一句反馈：行内心形会变，但列表滚动时用户未必盯着那一行
            val text = when (fav) {
                null -> AppStrings.s(R.string.online_favorite_failed)
                true -> AppStrings.s(R.string.online_fav_added)
                false -> AppStrings.s(R.string.online_fav_removed)
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    /** 我的列表里的曲目已经是入库的 [Song]，不需要再 upsert 一次 */
    fun toggleFavoriteSong(song: Song) {
        scope.launch {
            val fav = withContext(Dispatchers.IO) {
                runCatching { app?.container?.favorites?.toggleSong(song) }.getOrNull()
            }
            val text = when (fav) {
                null -> AppStrings.s(R.string.online_favorite_failed)
                true -> AppStrings.s(R.string.online_fav_added)
                false -> AppStrings.s(R.string.online_fav_removed)
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 删除一份在线列表。
     *
     * 在线列表不在本地模式出现，这里若没有删除入口，用户就只剩「新建」一种操作 ——
     * 收错了的歌单永远清不掉。
     */
    fun deleteMyList(list: OnlineLists.Summary) {
        scope.launch {
            withContext(Dispatchers.IO) {
                MusicDb.get(context).playlistDao().deletePlaylist(list.id)
                SavedSheetStore(context).repoint(list.id, null)
            }
            openMyList = null
            onBack()
            myLists = withContext(Dispatchers.IO) { OnlineLists.summaries(context) }
        }
    }


    val sideNav = LocalUiFormFactor.current.hasSideNav
    val title = when {
        place.section == LxSection.SHEETS && place.detail == LxDetail.SHEET && openSheet != null ->
            openSheet?.name.orEmpty()
        place.section == LxSection.BOARDS && place.detail == LxDetail.BOARD && openBoard != null ->
            openBoard?.name.orEmpty()
        place.section == LxSection.MINE && place.detail == LxDetail.LIST && openMyList != null ->
            openMyList?.name.orEmpty()
        else -> stringResource(place.section.labelRes)
    }

    // 稳定对象：播放进度重组时不能换成新 lambda，否则右滑手势的协程会重开，
    // 正在进行的纵向滑动被掐掉。
    val drawerOpener = remember { DrawerOpener() }
    drawerOpener.onOpen = onOpenDrawer

    Column(
        modifier
            .fillMaxSize()
            // 与曲库、云盘同一套：内容区右滑拉出抽屉。左缘留给系统返回。
            // 车机左导航常驻，不接管这套手势（车机触摸板误触率高）。
            // 设置 / 播放页盖住本页时也不接管，否则覆盖层上的横向滑动会穿透到底层开抽屉。
            .drawerSwipe(
                enabled = !sideNav && backEnabled && !playerExpanded,
                opener = drawerOpener,
            ),
    ) {
        if (confirmDeleteList) {
            AlertDialog(
                onDismissRequest = { confirmDeleteList = false },
                title = { Text(stringResource(R.string.pl_delete_playlist)) },
                text = { Text(stringResource(R.string.online_list_delete_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDeleteList = false
                        openMyList?.let { deleteMyList(it) }
                    }) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteList = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
        TopAppBar(
            title = {
                AnimatedContent(
                    targetState = title,
                    transitionSpec = { SettingsMotion.titleFade() },
                    label = "lx-title",
                ) { text ->
                    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            navigationIcon = {
                // 子页 / 上一个分区：返回。根上手机给汉堡，车机左导航已经常驻，不再放一个打不开的抽屉按钮。
                if (canPop) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                } else if (!sideNav) {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(AppIcons.Menu, contentDescription = stringResource(R.string.online_open_menu))
                    }
                }
            },
            actions = {
                // 我的列表详情：删除这份列表（在线列表不在本地模式出现，这里是唯一入口）
                val mine = openMyList
                if (place.section == LxSection.MINE && place.detail == LxDetail.LIST && mine != null) {
                    IconButton(onClick = { confirmDeleteList = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.pl_delete_playlist),
                        )
                    }
                }
                if (canPop && !sideNav) {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(AppIcons.Menu, contentDescription = stringResource(R.string.online_open_menu))
                    }
                }
            },
        )

        if (place.section != LxSection.SOURCES) {
            SourceStatusBanner(
                ready = sourcesReady,
                report = report,
                onManage = { onSelectSection(LxSection.SOURCES) },
            )
        }

        AnimatedContent(
            targetState = place,
            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
            transitionSpec = {
                SettingsMotion.slide(forward = LxBackStack.forward(initialState, targetState))
            },
            // 歌单 / 排行榜的「列表 ↔ 详情」不换内容槽。换槽会把列表移出组合，
            // 返回时滚动节点是新装上的，纵向滑动会停住。详情改由槽内覆盖。
            contentKey = { frame ->
                when (frame.section) {
                    LxSection.SHEETS, LxSection.BOARDS -> frame.section
                    else -> frame
                }
            },
            label = "lx-place",
        ) { target ->
            when (target.section) {
                LxSection.SOURCES -> {
                    val choices = remember(report, choiceRevision) { platformChoices() }
                    SourceManagerPage(
                        scripts = scripts,
                        report = report,
                        choices = choices,
                        onChooseSource = { platform, ownerId ->
                            scope.launch {
                                LxSourceManager.chooseSource(context, platform, ownerId)
                                choiceRevision++
                                report = LxSourceManager.report
                            }
                        },
                        onMove = { script, delta ->
                            scope.launch {
                                val list = scripts.toMutableList()
                                val from = list.indexOfFirst { it.id == script.id }
                                val to = from + delta
                                if (from >= 0 && to in list.indices) {
                                    list.removeAt(from)
                                    list.add(to, script)
                                    withContext(Dispatchers.IO) { store.save(list) }
                                    scripts = store.all()
                                    report = LxSourceManager.reload(context)
                                }
                            }
                        },
                        onImport = { text, isUrl ->
                            scope.launch {
                                val imported = withContext(Dispatchers.IO) {
                                    val code = if (isUrl) downloadScript(text) else text
                                    if (code.isNullOrBlank() || code.length < 200) return@withContext null
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
                                    choiceRevision++
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
                                choiceRevision++
                            }
                        },
                        onDelete = { script ->
                            scope.launch {
                                store.remove(script.id)
                                scripts = store.all()
                                report = LxSourceManager.reload(context)
                                choiceRevision++
                            }
                        },
                        onReload = {
                            scope.launch {
                                report = LxSourceManager.reload(context)
                                choiceRevision++
                            }
                        },
                    )
                }
                LxSection.SHEETS -> {
                    val sheet = openSheet
                    LxStackedDetail(
                        showDetail = target.detail == LxDetail.SHEET && sheet != null,
                        list = {
                            SonglistPane(
                                state = songlistState,
                                gridState = sheetGridState,
                                onOpen = { loadSheet(it) },
                            )
                        },
                        detail = {
                            if (sheet != null) SheetDetailPane(
                                sheet = sheet,
                                tracks = sheetTracks,
                                loading = sheetLoading,
                                error = sheetError,
                                saving = sheetSaving,
                                saved = sheetSaved,
                                favoriteKeys = favoriteKeys,
                                listState = sheetDetailState,
                                scrollGen = sheetScrollGen,
                                scrolledGen = sheetScrolledGen,
                                onScrolled = { sheetScrolledGen = it },
                                onPlay = { playTracks(sheetTracks, it) },
                                onFavorite = { toggleFavorite(it) },
                                onSaveToList = { saveSheetToList(sheet) },
                            )
                        },
                    )
                }
                LxSection.MINE -> {
                    val list = openMyList
                    when {
                        // 最近播放盖在「我的列表」上，返回先收它
                        recentOpen -> MyListDetailPane(
                            songs = recentSongs,
                            loading = recentLoading,
                            favoriteKeys = favoriteKeys,
                            onPlay = { index -> onPlay(recentSongs, index) },
                            onFavorite = { song -> toggleFavoriteSong(song) },
                        )
                        target.detail == LxDetail.LIST && list != null -> MyListDetailPane(
                            songs = myListSongs,
                            loading = myListLoading,
                            favoriteKeys = favoriteKeys,
                            onPlay = { index -> onPlay(myListSongs, index) },
                            onFavorite = { song -> toggleFavoriteSong(song) },
                        )
                        else -> MyListsPane(
                            lists = myLists,
                            loading = myListsLoading,
                            recentCover = recentSongs.take(4),
                            recentCount = recentSongs.size,
                            onOpen = { loadMyList(it) },
                            onOpenRecent = { loadRecent() },
                        )
                    }
                }
                LxSection.BOARDS -> {
                    val board = openBoard
                    LxStackedDetail(
                        showDetail = target.detail == LxDetail.BOARD && board != null,
                        list = {
                            BoardsList(listState = boardListState, onOpen = { loadBoard(it) })
                        },
                        detail = {
                            BoardDetail(
                                title = board?.name.orEmpty(),
                                tracks = boardTracks,
                                loading = boardLoading,
                                error = boardError,
                                favoriteKeys = favoriteKeys,
                                listState = boardDetailState,
                                scrollGen = boardScrollGen,
                                scrolledGen = boardScrolledGen,
                                onScrolled = { boardScrolledGen = it },
                                onPlay = { playTracks(boardTracks, it) },
                                onFavorite = { toggleFavorite(it) },
                            )
                        },
                    )
                }
                LxSection.SEARCH -> SearchBody(
                    keyword = keyword,
                    onKeyword = { keyword = it },
                    searching = searching,
                    onSearch = { doSearch() },
                    selectedSource = selectedSource,
                    onSelectSource = { selectedSource = it },
                    searched = searched && target.detail == LxDetail.RESULTS,
                    results = results,
                    loadingMore = loadingMore,
                    onLoadMore = { loadMore(it) },
                    favoriteKeys = favoriteKeys,
                    onPlay = { tracks, index -> playTracks(tracks, index) },
                    onFavorite = { toggleFavorite(it) },
                )
            }
        }
    }

}


@Composable
private fun SearchBody(
    keyword: String,
    onKeyword: (String) -> Unit,
    searching: Boolean,
    onSearch: () -> Unit,
    selectedSource: String,
    onSelectSource: (String) -> Unit,
    searched: Boolean,
    results: List<BuiltInSearch.Result>,
    loadingMore: Map<String, Boolean>,
    onLoadMore: (BuiltInSearch.Result) -> Unit,
    favoriteKeys: Set<String> = emptySet(),
    onPlay: (List<OnlineTrack>, Int) -> Unit,
    onFavorite: (OnlineTrack) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // 搜索区做成一张卡：输入框与按钮同属一个容器，避免两个控件各自为政
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = onKeyword,
                    placeholder = { Text(stringResource(R.string.online_search_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onSearch,
                    enabled = !searching,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text(if (searching) stringResource(R.string.online_searching) else stringResource(R.string.online_search))
                }
            }
        }
        SourceFilterRow(
            selected = selectedSource,
            onSelect = onSelectSource,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                // 搜索是「等一屏结果」，用骨架保持列表的形状，结果回来时不会整页跳一下
                searching -> LxListSkeleton(rows = 6)
                !searched -> LxEmptyPane(title = stringResource(R.string.online_idle_hint))
                results.all { it.tracks.isEmpty() } -> {
                    val errors = results.mapNotNull { it.error }
                    if (errors.isNotEmpty() && errors.size == results.size) {
                        LxEmptyPane(
                            title = stringResource(R.string.online_all_sources_failed),
                            hint = errors.joinToString("\n"),
                        )
                    } else {
                        LxEmptyPane(title = stringResource(R.string.online_no_result))
                    }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    results.forEach { group ->
                        if (group.tracks.isEmpty()) return@forEach
                        item(key = "head_${group.source}") {
                            LxSectionTitle(
                                text = BuiltInSearch.SOURCE_NAMES[group.source] ?: group.source,
                                count = group.tracks.size,
                            )
                        }
                        itemsIndexed(
                            group.tracks,
                            key = { _, track -> "${group.source}:${track.stableKey}" },
                        ) { index, track ->
                            TrackRow(
                                track = track,
                                favorite = track.stableKey in favoriteKeys,
                                onPlay = { onPlay(group.tracks, index) },
                                onFavorite = { onFavorite(track) },
                                staggerMs = (index % 8) * 30L,
                            )
                        }
                        item(key = "more_${group.source}") {
                            val loading = loadingMore[group.source] == true
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                TextButton(onClick = { onLoadMore(group) }, enabled = !loading) {
                                    if (loading) {
                                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        if (loading) stringResource(R.string.online_searching)
                                        else stringResource(R.string.online_load_more),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 详情盖在列表上，列表不离开组合。
 *
 * 歌单和排行榜以前在 [AnimatedContent] 里用 if/else 把列表换成详情。
 * 进详情时列表被移出组合，返回时用原来的滚动状态装回一个新节点。
 * 这一下之后两个列表都不再吃纵向滑动。列表留在底层，详情只盖住它。
 */
@Composable
internal fun LxStackedDetail(
    showDetail: Boolean,
    modifier: Modifier = Modifier,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        list()
        AnimatedVisibility(
            visible = showDetail,
            modifier = Modifier.fillMaxSize(),
            enter = slideInHorizontally(tween(SettingsMotion.SlideMs)) { width -> width } +
                fadeIn(tween(SettingsMotion.FadeMs)),
            exit = slideOutHorizontally(tween(SettingsMotion.SlideMs)) { width -> width } +
                fadeOut(tween(SettingsMotion.FadeMs)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                detail()
            }
        }
    }
}

/** 榜单列表：按平台分组。卡片带主色小标记，不再是三列纯文字按钮。 */
@Composable
private fun BoardsList(
    /** 由 `OnlinePage` 顶层持有：点进某个榜单再返回，停在上次看到的分组 */
    listState: LazyListState = rememberLazyListState(),
    onOpen: (OnlineBoards.Board) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("board-list"),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
    ) {
        OnlineBoards.GROUPS.forEach { group ->
            item(key = "g_${group.source}") {
                LxSectionTitle(group.name)
            }
            group.boards.chunked(3).forEachIndexed { rowIndex, row ->
                item(key = "r_${group.source}_$rowIndex") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { board ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 64.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { onOpen(board) },
                            ) {
                                Column(
                                    Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Box(
                                        Modifier
                                            .size(width = 18.dp, height = 3.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        board.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                        // 补空位，避免最后一行按钮被拉宽
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** 榜单详情：头部信息 + 与搜索结果同一套行样式，点一首从该首开始连续播放整个榜单 */
@Composable
private fun BoardDetail(
    title: String,
    tracks: List<OnlineTrack>,
    loading: Boolean,
    error: String?,
    favoriteKeys: Set<String> = emptySet(),
    listState: LazyListState = rememberLazyListState(),
    /** 换了一个榜才 +1。和 [scrolledGen] 一起避免返回时再滚回顶部。 */
    scrollGen: Int = 0,
    scrolledGen: Int = 0,
    onScrolled: (Int) -> Unit = {},
    onPlay: (Int) -> Unit,
    onFavorite: (OnlineTrack) -> Unit,
) {
    when {
        loading -> LxListSkeleton(rows = 8)
        tracks.isEmpty() -> LxEmptyPane(title = error ?: AppStrings.s(R.string.online_board_empty))
        else -> {
            // 列表已经在组合里再滚。骨架还显示时调用会停在「等第一次布局」，
            // 而且那次布局要等骨架撤掉才会发生。
            LaunchedEffect(scrollGen) {
                if (scrollGen == 0 || scrollGen == scrolledGen) return@LaunchedEffect
                listState.scrollToItem(0)
                onScrolled(scrollGen)
            }
            LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        ) {
            item(key = "head") {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            AppStrings.s(R.string.online_sheet_song_count, tracks.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            itemsIndexed(tracks, key = { _, track -> track.stableKey }) { index, track ->
                TrackRow(
                    track = track,
                    favorite = track.stableKey in favoriteKeys,
                    onPlay = { onPlay(index) },
                    onFavorite = { onFavorite(track) },
                    staggerMs = (index % 8) * 30L,
                )
            }
        }
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
    val options = listOf("all") + BuiltInSearch.SOURCES
    LazyRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(options) { id ->
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
private fun SourceStatusBanner(
    ready: Boolean,
    report: LxLoadReport,
    onManage: () -> Unit,
) {
    if (!ready) {
        Text(
            stringResource(R.string.online_sources_loading),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        return
    }
    if (report.sources.isEmpty()) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onManage),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    stringResource(R.string.online_no_script_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.online_open_sources),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        return
    }
    // 有音源时不再占一整行：一个小圆点 + 数量，长列表页顶部保持干净
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.online_loaded_sources, report.sources.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (report.errors.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.online_source_errors, report.errors.joinToString("; ")),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun EmptyHint(text: String) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(AppVisual.WindGradient),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TrackRow(
    track: OnlineTrack,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    /** 已收藏：心形实心 + 主色。以前只有在长按菜单里能收藏、行内没有任何标记，看起来「点了没变」 */
    favorite: Boolean = false,
    /** 首屏错峰（毫秒）：避免同一帧为整屏行同时发起封面请求 */
    staggerMs: Long = 0L,
) {
    var menu by remember { mutableStateOf(false) }
    // 列表标签以「这份音源真能给的最高档」为准；播过之后换成实际档位（补链完成会 +1 计数）
    val revision = OnlineUrlMemo.revision.intValue
    val quality = remember(track.stableKey, revision) { OnlineResolver.displayQuality(track) }
    Box {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(onClick = onPlay, onLongClick = { menu = true }),
        ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LxTrackCover(
                track = track,
                targetPx = COVER_PX,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(12.dp),
                initialSize = 16.sp,
                staggerMs = staggerMs,
            )
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
            if (quality != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    quality.shortLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onFavorite, modifier = Modifier.size(38.dp)) {
                Icon(
                    imageVector = if (favorite) AppIcons.Favorite else AppIcons.FavoriteBorder,
                    contentDescription = stringResource(
                        if (favorite) R.string.cd_unfavorite else R.string.cd_favorite,
                    ),
                    tint = if (favorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
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

/**
 * 音源管理：导入 / 启停 / 排序 / 「某平台用哪份脚本」。
 *
 * 为什么要有「排序」和「平台选择」两套东西：
 *  - **排序**决定默认顺序（自动模式下第一个能取到地址的脚本胜出）；
 *  - **平台选择**是洛雪里「音源」那个下拉的手动覆盖 —— 同一个平台装了两份脚本、
 *    其中一份对某类歌（比如无损）更全时，用户可以在不改顺序的前提下只给这个平台换人。
 * 两者叠加后的实际顺序由 `OnlineSourceRegistry.candidates` 给出，与本页展示一致。
 */
@Composable
private fun SourceManagerPage(
    scripts: List<LxScript>,
    report: LxLoadReport,
    choices: List<PlatformChoice>,
    onChooseSource: (platform: String, ownerId: String?) -> Unit,
    onMove: (LxScript, Int) -> Unit,
    onImport: (String, Boolean) -> Unit,
    onToggle: (LxScript) -> Unit,
    onDelete: (LxScript) -> Unit,
    onReload: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 概览：加载结果 + 隐私说明 + 重新加载
        LxCard(contentPadding = PaddingValues(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.online_source_loaded, report.sources.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.online_source_privacy),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onReload,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(stringResource(R.string.online_reload))
                }
            }
            if (report.errors.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.online_source_errors, report.errors.joinToString("; ")),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // 导入：输入框与两个入口合在一张卡里
        LxCard(contentPadding = PaddingValues(14.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.online_source_url_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { onImport(text, true) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(stringResource(R.string.online_import_url))
                }
                TextButton(
                    onClick = { onImport(text, false) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(stringResource(R.string.online_import_code))
                }
            }
        }

        if (choices.isNotEmpty()) {
            LxSectionTitle(stringResource(R.string.online_source_pick_title))
            LxCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    stringResource(R.string.online_source_pick_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                choices.forEach { choice ->
                    PlatformSourceRow(choice = choice, onChoose = onChooseSource)
                }
            }
        }

        LxSectionTitle(
            stringResource(R.string.online_scripts_title),
            count = scripts.size,
        )
        if (scripts.isEmpty()) {
            LxCard(contentPadding = PaddingValues(14.dp)) {
                Text(
                    stringResource(R.string.online_no_script_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LxCard(contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                scripts.forEachIndexed { index, script ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (script.enabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                script.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (script.enabled) stringResource(R.string.online_source_enabled)
                                else stringResource(R.string.online_source_disabled),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 顺序即优先级（自动模式下先试上面的）；只有停用的脚本也不影响顺序语义
                        TextButton(
                            enabled = index > 0,
                            onClick = { onMove(script, -1) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            Text(stringResource(R.string.move_up), style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(
                            enabled = index < scripts.lastIndex,
                            onClick = { onMove(script, 1) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            Text(stringResource(R.string.move_down), style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(
                            onClick = { onToggle(script) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            Text(
                                if (script.enabled) stringResource(R.string.off)
                                else stringResource(R.string.on),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        IconButton(onClick = { onDelete(script) }, modifier = Modifier.size(38.dp)) {
                            Icon(
                                Icons.Filled.Delete,
                                stringResource(R.string.delete),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    if (index != scripts.lastIndex) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp)
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「某个平台用哪份脚本」的一行。
 *
 * 只有一个候选时不摆下拉（选择没有意义，摆出来反而让人以为还有别的）；
 * 多个候选时点开是「自动 / 各脚本」，与洛雪的音源下拉同义。
 */
@Composable
private fun PlatformSourceRow(
    choice: PlatformChoice,
    onChoose: (platform: String, ownerId: String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = choice.options.firstOrNull { it.first == choice.selected }?.second
        ?: stringResource(R.string.online_source_auto)
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(choice.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            TextButton(
                enabled = choice.options.size > 1,
                onClick = { expanded = true },
            ) { Text(selectedLabel) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.online_source_auto)) },
                    onClick = {
                        expanded = false
                        onChoose(choice.platform, null)
                    },
                )
                choice.options.forEach { (ownerId, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            expanded = false
                            onChoose(choice.platform, ownerId)
                        },
                    )
                }
            }
        }
    }
}

/** 音源管理里「某个平台用哪份脚本」的一行 */
private data class PlatformChoice(
    val platform: String,
    /** 平台显示名（酷我音乐 / QQ 音乐 …） */
    val title: String,
    /** 用户手选的 ownerId；null = 自动（按脚本顺序） */
    val selected: String?,
    /** ownerId → 脚本名 */
    val options: List<Pair<String, String>>,
)

/**
 * 从注册表现算一份候选表。
 *
 * 不缓存：音源管理是低频弹窗，而注册表在导入 / 停用 / 删除 / 重排后都会整体重建，
 * 缓存反而要多写一套失效逻辑。列表最多几条，算一次是零成本。
 */
private fun platformChoices(): List<PlatformChoice> =
    OnlineSourceRegistry.platformIds.map { pid ->
        PlatformChoice(
            platform = pid,
            title = platformTitle(pid),
            selected = OnlineSourceRegistry.preferredFor(pid),
            options = OnlineSourceRegistry.sourcesFor(pid).map { it.ownerId to it.providerLabel },
        )
    }

/** 平台名：内置搜索认识的四家用它的表，其余（wy / xm）用脚本源的中文名 */
private fun platformTitle(id: String): String =
    BuiltInSearch.SOURCE_NAMES[id] ?: LxScriptSource.DISPLAY_NAMES[id] ?: id

/** 48dp @ xxhdpi 的物理像素，交给解码器按需降采样 */
private const val COVER_PX = 144

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
