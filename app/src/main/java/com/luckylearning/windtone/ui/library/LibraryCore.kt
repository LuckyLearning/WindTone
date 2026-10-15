package com.luckylearning.windtone.ui.library

import android.util.Log
import androidx.room.withTransaction
import com.luckylearning.windtone.data.CoverRepository
import com.luckylearning.windtone.data.PinyinIndex
import com.luckylearning.windtone.data.ScanScope
import com.luckylearning.windtone.data.SongDeleter
import com.luckylearning.windtone.data.SortOrder
import com.luckylearning.windtone.data.db.toEntity
import com.luckylearning.windtone.data.db.toSong
import com.luckylearning.windtone.data.derivedFolderPath
import com.luckylearning.windtone.data.folderDisplayName
import com.luckylearning.windtone.data.folderParentHint
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource
import com.luckylearning.windtone.state.GroupInfo
import com.luckylearning.windtone.state.LibraryView
import com.luckylearning.windtone.state.PlayStats
import com.luckylearning.windtone.ui.defaultSort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 曲库域的**实现**（扫描 / 视图切换 / 排序 / 搜索 / 收藏 / 删除歌曲文件 / 统计 / 搜索历史）。
 *
 * 依赖与共享状态从 [LibrarySession] 取；扫描/删除/视图都要读「导入目录」的内存副本，
 * 那一份状态与围绕它的辅助方法现在住在 [FolderController]（[LibrarySession.folders]），
 * 这里按需 `session.folders.xxx()`；跨域动作直接调对应的兄弟实现
 * （如 [PlaylistStore.refreshPlaylists] / [SelectionController.exitSelection]）。
 *
 * 「文件夹 / SAF / 歌词 / 权限」这一摊见 [FolderController]，
 * 「M3U 与歌曲信息编辑」这一摊见 [M3uStore]——它们原先是本文件的分节，
 * 因为内容仍然过长被按同一边界再拆出去。
 */
internal class LibraryCore(private val session: LibrarySession) : LibraryActions {

    private val app = session.app
    private val prefs = session.prefs
    private val repository = session.repository
    private val db = session.db
    private val index = session.index
    private val playback = session.playback
    private val _library = session._library

    private val scope get() = session.scope

    /** 当前正在跑的音乐库扫描任务；范围/目录变化时先取消它，避免过期结果回写 */
    private var scanJob: Job? = null

    /** 搜索防抖任务 */
    private var searchJob: Job? = null

    /**
     * 重新装载曲库与用户数据。
     *
     * 界面在「清空用户数据 / 清理失效歌曲」之后调一次，让内存里的红心、歌单、
     * 统计与列表重新跟数据库对齐。
     */
    override fun reloadAll() {
        scope.launch {
            session.folders.reloadFolders()
            reloadSearchHistory()
            loadFromCacheThenScan()
        }
    }

    /**
     * 「清空曲库」之后的对账（设置页 → 关于与诊断 → 数据维护）。
     *
     * 为什么不能只调 [reloadAll]：曲库清了但扫描范围还是「全盘」的话，
     * `loadFromCacheThenScan()` 会立刻 `scan(auto = true)` 把整个磁盘再扫一遍 ——
     * 用户刚清空的曲库转眼又满上，看起来就像那个按钮根本没生效。
     * 所以这里必须把范围一并退回「未选择」，让界面停在「请选择扫描范围」。
     *
     * 收藏 / 播放统计 / 歌单**故意保留**：它们按 stableKey 与歌关联，
     * 重扫到同一批文件时红心与播放次数会自动对上，不用用户再点一遍。
     */
    override fun onLibraryCleared() {
        cancelScan()
        prefs.scanScope = ScanScope.UNSET
        prefs.scanScopeChosen = true
        session.folders.folders = emptyList()
        _library.update { it.copy(folderCount = 0, folderUris = emptyList(), folders = emptyList()) }
        clearLibraryForScopeReset()
        refreshGroups()
        session.playlists.refreshPlaylists()
        refreshStats()
    }

    // ================================================================ 音乐库

    /** 冷启动：先展示曲库缓存（秒开），再按已选范围后台刷新；未选范围则完全不扫描 */
    private fun loadFromCacheThenScan() {
        scope.launch {
            val cached = withContext(Dispatchers.IO) {
                val states = db.songStateDao().all().associateBy { it.songId }
                db.songDao().visible().mapNotNull { it.toSong(states[it.id]) }
            }
            // 老版本升级上来的用户：已有扫描缓存但没有范围记录，视作「全盘」，
            // 保持原有行为；只有全新安装（无缓存）才会停在「等待选择范围」。
            // 注意必须用 scanScopeChosen 判定：用户「主动清空目录」后范围也是 UNSET，
            // 那种情况绝不能被这条兼容逻辑拽回全盘扫描。
            if (prefs.scanScope == ScanScope.UNSET && !prefs.scanScopeChosen && cached.isNotEmpty()) {
                prefs.scanScope = ScanScope.FULL
                prefs.scanScopeChosen = true
                _library.update { it.copy(scanScope = ScanScope.FULL) }
            }
            if (cached.isNotEmpty()) {
                applyLibrary(cached, skipped = 0, fromCache = true)
                playback.restoreQueueIfNeeded()
                // 缓存展示后延迟少许再扫描，避免抢启动帧
                delay(600)
            }
            if (prefs.scanScope == ScanScope.UNSET) {
                // 首次安装：不自动扫描，界面会提示用户选择范围
                _library.update { it.copy(scanning = false, statusText = "") }
                return@launch
            }
            scan(auto = true)
        }
    }

    /**
     * 选择「扫描全盘」。
     * 媒体权限的申请由界面层负责，授权成功后调用本方法。
     */
    override fun startFullScan() {
        prefs.scanScope = ScanScope.FULL
        prefs.scanScopeChosen = true
        _library.update { it.copy(scanScope = ScanScope.FULL) }
        scan(false)
    }

    /**
     * 切换扫描范围。
     * [ScanScope.FOLDERS] 需要至少有一个已导入目录，否则界面应先去选目录。
     */
    override fun setScanScope(scope: ScanScope) {
        if (scope == ScanScope.FOLDERS && session.folders.folderUriList().isEmpty()) return
        prefs.scanScope = scope
        prefs.scanScopeChosen = true
        cancelScan()
        _library.update {
            it.copy(
                scanScope = scope,
                folderCount = session.folders.folders.size,
                folderUris = session.folders.folderUriList(),
                folders = session.folders.folderItemList(),
            )
        }
        if (scope != ScanScope.UNSET) scan(false) else clearLibraryForScopeReset()
    }

    /** 最短时长阈值（秒），0 表示不过滤 */
    override fun minSongDurationSec(): Int = prefs.minSongDurationSec

    /**
     * 修改短音频过滤阈值并立即重新扫描生效。
     * 时长未知（0）的歌曲不会被过滤，播放拿到真实时长后由下次扫描接管。
     */
    override fun setMinSongDurationSec(sec: Int) {
        prefs.minSongDurationSec = sec
        if (prefs.scanScope != ScanScope.UNSET) scan(false)
    }

    override fun scan(auto: Boolean) {
        if (_library.value.scanning) return
        if (_library.value.scanScope == ScanScope.UNSET) return
        // 同一时刻只保留最后一次扫描：范围/目录被改动后，旧扫描的结果必须作废，
        // 否则它会把「已经清空的曲库」重新写回内存与 Room 缓存（见 removeFolder）。
        scanJob?.cancel()
        scanJob = scope.launch {
            val scopeAtStart = prefs.scanScope
            val urisAtStart = session.folders.folderUriList()
            _library.update {
                it.copy(
                    scanning = true,
                    statusText = "准备扫描…",
                    folderCount = session.folders.folders.size,
                    folderUris = urisAtStart,
                    folders = session.folders.folderItemList(),
                )
            }
            // 本轮扫描的时间戳：写进每首歌的 lastSeenAt，扫完据此把"没见到的"标记为暂不可见
            val scanStart = System.currentTimeMillis()
            val result = try {
                repository.loadLibrary(urisAtStart) { text -> _library.update { it.copy(statusText = text) } }
            } catch (e: CancellationException) {
                // 扫描被新范围/移除目录打断：交给 cancelScan() 收尾，不报错
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "扫描失败", e)
                null
            }

            if (result == null) {
                _library.update { it.copy(scanning = false, statusText = "") }
                // 自动扫描失败不打扰用户（等同于原来的空串提示），手动扫描才弹 Snackbar
                if (!auto) pushMessage("扫描失败，请检查权限后重试")
                return@launch
            }

            // 扫描期间范围/目录又变了（例如用户把最后一个目录也移除了）：结果作废
            val scopeNow = prefs.scanScope
            if (scopeNow == ScanScope.UNSET || scopeNow != scopeAtStart) {
                Log.i(TAG, "扫描结果已过期（$scopeAtStart → $scopeNow），丢弃")
                return@launch
            }

            // 先增量落库（保住既有主键与用户数据），拿到稳定的曲库主键后再上界面
            val persisted = withContext(Dispatchers.IO) { persistSongs(result.songs, scanStart) }
            applyLibrary(persisted, result.skippedUnsupported, fromCache = false)
            session.folders.rememberScannedFolders()
            playback.restoreQueueIfNeeded()
            if (!auto && result.songs.isEmpty()) {
                pushMessage(
                    when (scopeNow) {
                        ScanScope.FOLDERS -> "所选目录里没有找到音频文件"
                        else -> "没有找到可播放的音频，可授予「所有文件访问权限」后重试，或改为导入指定的音乐目录"
                    }
                )
            }
        }
    }

    /**
     * 把本次扫描结果增量写入曲库，并返回带曲库主键的歌曲列表。
     *
     * 与旧实现的关键差别：**不再 `DELETE FROM` 再全量插入**。做法是
     *  1. 已存在的 uri 走 UPDATE（主键保持不变 → 收藏 / 歌单 / 队列 / 歌词偏移全部不受影响）；
     *  2. 新出现的 uri 走 INSERT；
     *  3. 本轮没见到的行置 `missingSince`（软删），而不是删行——文件被移走又放回来、
     *     或外置存储暂时挂载失败时，用户数据不会丢。
     */
    private suspend fun persistSongs(raw: List<Song>, scanStart: Long): List<Song> = withContext(Dispatchers.IO) {
        if (raw.isEmpty()) return@withContext raw
        val dao = db.songDao()
        val folderIdByDocId = session.folders.folders.associate { it.docId to it.id }
        val existing = dao.allIdUri().associate { it.uri to it.id }
        // 用户主动「移出曲库」的歌：整行跳过，既不更新也不重置排除标记，
        // 于是重扫之后它们仍然不会回到曲库（这正是与「软删」的区别所在）。
        val excluded = dao.excludedUris().toSet()
        val now = System.currentTimeMillis()
        val out = ArrayList<Song>(raw.size)

        db.withTransaction {
            for (song in raw) {
                if (song.stableKey in excluded) continue
                val folderId = song.folderPath?.let { folderIdByDocId[it] }
                val oldId = existing[song.stableKey]
                val row = song.toEntity(oldId ?: 0L, folderId).copy(lastSeenAt = scanStart, missingSince = 0L)
                val id = if (oldId == null) dao.insert(row) else {
                    dao.update(row)
                    oldId
                }
                out.add(song.copy(dbId = id))
            }
            dao.markMissing(scanStart, now)
            session.folders.refreshFolderCounts()
        }
        out
    }

    /**
     * 取消正在进行的扫描并复位状态。
     * 目录列表、扫描范围发生任何变化时都要先调用，避免旧扫描把过期结果写回曲库/缓存。
     */
    internal fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _library.update { if (!it.scanning) it else it.copy(scanning = false, statusText = "") }
    }

    /**
     * 回到「等待选择扫描范围」：清空曲库与扫描缓存。
     * 只清本地扫描结果，**不动**用户数据（收藏/歌单/统计）。
     */
    internal fun clearLibraryForScopeReset() {
        PinyinIndex.build(emptyList())
        playback.setLibrarySongs(emptyList())
        _library.update {
            it.copy(
                scanScope = ScanScope.UNSET,
                folderCount = session.folders.folders.size,
                folderUris = session.folders.folderUriList(),
                folders = session.folders.folderItemList(),
                songs = emptyList(),
                visible = emptyList(),
                letterIndex = emptyMap(),
                groups = emptyList(),
                selectedGroup = null,
                detailSongs = emptyList(),
                skippedUnsupported = 0,
                lyricsCount = 0,
                scanning = false,
                statusText = "",
            )
        }
        scope.launch {
            withContext(Dispatchers.IO) { db.songDao().clear() }
            index.clearIds()
            index.clearStates()
        }
        refreshGroups()
    }

    /** 应用歌曲列表：状态装载 → 信息覆盖 → 排序 → 拼音索引 → 分组 */
    private suspend fun applyLibrary(raw: List<Song>, skipped: Int, fromCache: Boolean) {
        // 每曲状态（收藏 / 标签覆盖 / 歌词偏移）一次读全，之后同步可用
        val states = withContext(Dispatchers.IO) { db.songStateDao().all() }
        index.replaceStates(states)
        val songs = raw.map { song ->
            val st = index.stateOf(song.dbId) ?: return@map song
            song.copy(
                title = st.overrideTitle?.takeIf { it.isNotBlank() } ?: song.title,
                artist = if (st.overrideArtist != null) st.overrideArtist else song.artist,
                album = if (st.overrideAlbum != null) st.overrideAlbum else song.album,
            )
        }
        // 排序方式按**当前视图**取（v1.1.4 排序下沉）；老用户新键缺失时会继承旧的全局值
        val view = _library.value.view
        val currentSort = prefs.sortOrder(view.name, view.defaultSort)
        val sorted = repository.sort(songs, currentSort)

        withContext(Dispatchers.Default) { PinyinIndex.build(sorted) }

        // uri → 曲库主键索引：持久化边界全靠它把界面上的 uri 换算成 songId
        index.rebuild(sorted)

        val favs = sorted.filter { index.stateOf(it.dbId)?.favorite == true }.map { it.stableKey }.toSet()

        // 重活全部在进入 update 之前算完：
        // update 的 lambda 走 CAS 重试，若在里面做全量搜索会被重复执行。
        val query = _library.value.query
        val visible = withContext(Dispatchers.Default) { searchSongs(sorted, query) }
        val letters = withContext(Dispatchers.Default) { buildLetterIndex(visible) }

        _library.update {
            it.copy(
                songs = sorted,
                sortOrder = currentSort,
                visible = visible,
                letterIndex = letters,
                scanning = false,
                skippedUnsupported = skipped,
                lyricsCount = sorted.count { s -> s.lrcUri != null },
                deepScan = repository.deepScanEnabled(),
                scanScope = prefs.scanScope,
                folderCount = session.folders.folders.size,
                folderUris = session.folders.folderUriList(),
                folders = session.folders.folderItemList(),
                statusText = "",
                favoriteUris = favs,
            )
        }
        playback.setLibrarySongs(sorted)
        refreshGroups()
        session.playlists.refreshPlaylists()
    }

    internal fun searchSongs(songs: List<Song>, query: String): List<Song> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return songs
        return songs.filter { song ->
            repository.matches(song, q) ||
                PinyinIndex.full(song.stableKey).contains(q) ||
                PinyinIndex.initials(song.stableKey).contains(q)
        }
    }

    /** 首字母 → 首个下标（A-Z 索引导航）；用 LinkedHashMap 保持字母顺序 */
    internal fun buildLetterIndex(list: List<Song>): Map<Char, Int> {
        if (list.isEmpty()) return emptyMap()
        val map = LinkedHashMap<Char, Int>()
        list.forEachIndexed { i, song ->
            val ch = PinyinIndex.initialOf(song.title)
            if (!map.containsKey(ch)) map[ch] = i
        }
        return map
    }

    /**
     * 搜索输入。
     * 输入框即时回显，但列表过滤延迟 140ms —— 原先每按一个键就要对全库做一次
     * O(n) 过滤（外加每首两次拼音查表）并重建字母索引，长库输入明显发涩。
     */
    override fun setQuery(q: String) {
        _library.update { it.copy(query = q) }
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(140L)
            val songs = _library.value.songs
            val (visible, letters) = withContext(Dispatchers.Default) {
                val v = searchSongs(songs, q)
                v to buildLetterIndex(v)
            }
            _library.update { it.copy(visible = visible, letterIndex = letters) }
        }
    }

    override fun setSort(order: SortOrder) {
        // 只改**当前视图**的排序（v1.1.4 排序下沉）：在专辑里选「按歌手」，
        // 歌曲列表的「按标题」不受影响。
        prefs.setSortOrder(_library.value.view.name, order)
        val sorted = repository.sort(_library.value.songs, order)
        val visible = searchSongs(sorted, _library.value.query)
        _library.update {
            it.copy(
                sortOrder = order,
                songs = sorted,
                visible = visible,
                letterIndex = buildLetterIndex(visible),
            )
        }
        playback.setLibrarySongs(sorted)
        refreshGroups()
    }

    // ---------------------------------------------------------- 视图切换

    override fun setView(view: LibraryView) {
        prefs.libraryView = view.name
        // 每个视图带着自己那份排序进来（v1.1.4 排序下沉）：从「专辑」切回「歌曲」时，
        // 歌曲列表要按歌曲自己的排序重排，而不是留着专辑刚选的那个。
        val order = prefs.sortOrder(view.name, view.defaultSort)
        val sorted = repository.sort(_library.value.songs, order)
        val visible = searchSongs(sorted, _library.value.query)
        _library.update {
            it.copy(
                view = view,
                sortOrder = order,
                songs = sorted,
                visible = visible,
                letterIndex = buildLetterIndex(visible),
                selectedGroup = null,
                selectedPlaylist = null,
                detailSongs = emptyList(),
            )
        }
        playback.setLibrarySongs(sorted)
        if (view == LibraryView.ALBUM || view == LibraryView.ARTIST || view == LibraryView.FOLDER) {
            refreshGroups()
        }
        if (view == LibraryView.PLAYLIST) session.playlists.refreshPlaylists()
    }

    internal fun refreshGroups() {
        val view = _library.value.view
        val songs = _library.value.songs
        if (songs.isEmpty()) {
            _library.update { if (it.groups.isEmpty()) it else it.copy(groups = emptyList()) }
            return
        }
        val groups: List<GroupInfo> = when (view) {
            LibraryView.ALBUM -> songs.groupBy { it.albumOrUnknown }.map { (k, list) ->
                GroupInfo(k, k, list.firstOrNull()?.artistOrUnknown.orEmpty(), list.size, list.first())
            }
            LibraryView.ARTIST -> songs.groupBy { it.artistOrUnknown }.map { (k, list) ->
                GroupInfo(k, k, "${list.size} 首", list.size, list.first())
            }
            // 文件夹视图只列真实存在的目录：拿不到目录路径的歌曲（媒体库没给 path）
            // 会被归到「未知目录」，这类分组没有可操作的对象，直接不展示。
            // 分组键用完整路径（两个同名目录不会被合并），标题只显示**最后一个文件夹名字**
            // ——全盘扫描下 path 是 /storage/emulated/0/Music/华语，页面上只显示「华语」。
            // 有多个目录的叶名相同时，副标题带父路径消歧（如「Music · 12 首」）。
            LibraryView.FOLDER -> {
                val byLabel = songs.groupBy { it.folderLabel }.filterKeys { it != UNKNOWN_FOLDER_LABEL }
                val dupNames = byLabel.keys
                    .groupingBy { folderDisplayName(it) }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
                byLabel.map { (k, list) ->
                    val name = folderDisplayName(k)
                    val sub = if (name in dupNames) "${folderParentHint(k)} · ${list.size} 首" else "${list.size} 首"
                    GroupInfo(k, name, sub, list.size, list.first())
                }
            }
            else -> emptyList()
        }
        // 分组列表的顺序也跟着当前视图走（v1.1.4 排序下沉）。
        // 「名称类」排序（TITLE / ALBUM / ARTIST）在各自视图下分别表示
        // 文件夹名 / 专辑名 / 歌手名，统一走拼音首字母序 —— 也正是原来的固定行为，
        // 所以默认值下这里与重构前完全一致。
        val order = prefs.sortOrder(view.name, view.defaultSort)
        val ordered = when (order) {
            // 文件夹视图：按完整路径排（同名目录靠它才稳定）
            SortOrder.FOLDER_PATH -> groups.sortedBy { it.key.lowercase() }
            // 以组内代表曲目的添加时间为准（组内最新一首大致反映这个分组的“新”度）
            SortOrder.DATE_ADDED -> groups.sortedByDescending { it.sample?.dateAdded ?: 0L }
            else -> groups.sortedWith(compareBy({ PinyinIndex.initialOf(it.title) }, { it.title.lowercase() }))
        }
        _library.update { if (it.groups == ordered) it else it.copy(groups = ordered) }
    }

    override fun openGroup(group: GroupInfo) {
        val songs = when (_library.value.view) {
            LibraryView.ALBUM -> _library.value.songs
                .filter { it.albumOrUnknown == group.key }
                .sortedWith(compareBy({ it.trackNo }, { it.title.lowercase() }))
            LibraryView.ARTIST -> _library.value.songs
                .filter { it.artistOrUnknown == group.key }
                .sortedWith(compareBy({ it.albumOrUnknown.lowercase() }, { it.trackNo }, { it.title.lowercase() }))
            else -> _library.value.songs
                .filter { it.folderLabel == group.key }
                .sortedWith(compareBy { it.displayName.lowercase() })
        }
        _library.update { it.copy(selectedGroup = group, detailSongs = songs) }
    }

    override fun closeGroup() = _library.update { it.copy(selectedGroup = null, detailSongs = emptyList()) }

    // ---------------------------------------------------------- 删除歌曲文件

    /** 等待系统删除确认（Android 11+ 媒体库文件）结果的挂起歌曲 */
    private var pendingDelete: Song? = null

    /**
     * 删除歌曲文件。
     *
     * Android 11+ 的媒体库文件会返回一个 [IntentSender]，需要 UI 层启动系统确认框，
     * 用户同意后系统代删，结果经 [onSystemDeleteResult] 回来；
     * 其余来源（SAF 文档 / 深度扫描路径 / 旧版媒体库）直接在 IO 线程删。
     */
    override fun deleteSong(song: Song, launchSystemConfirm: (android.content.IntentSender) -> Unit) {
        scope.launch {
            val sender = withContext(Dispatchers.IO) {
                runCatching { SongDeleter.prepareSystemConfirm(app, song) }.getOrNull()
            }
            if (sender != null) {
                pendingDelete = song
                launchSystemConfirm(sender)
            } else {
                val ok = withContext(Dispatchers.IO) { SongDeleter.deleteNow(app, song) }
                afterDelete(song, ok)
            }
        }
    }

    /** 系统确认框关闭后的回调：resultOk 为 true 表示系统已删除文件 */
    override fun onSystemDeleteResult(resultOk: Boolean) {
        val song = pendingDelete
        pendingDelete = null
        if (song != null) afterDelete(song, resultOk)
    }

    /** 等待系统**批量**删除确认（Android 11+ 媒体库文件）的挂起歌曲 */
    private var pendingBulkDelete: List<Song> = emptyList()

    /**
     * 批量删除文件（多选态的「删除文件」）。
     *
     * 与单选删除是同一条通道，区别在媒体库文件可以**一次请求带多个 URI**：
     * Android 11+ 只弹一个确认框，用户点一下就删完（见
     * [SongDeleter.prepareSystemConfirmMany]）。第一次实现里逐首调
     * `prepareSystemConfirm`，结果是要用户连点 N 次确认，中途取消还会留下"删了一半"。
     *
     * 混合来源（媒体库 + SAF + 深度扫描路径）时，系统只管媒体库那部分，
     * 剩下的在系统确认返回后自己删——见 [onSystemBulkDeleteResult]。
     *
     * 注意：本方法**刻意不在 [LibraryActions] 接口上**，而是由 `LibraryViewModel`
     * 直接声明——它的默认值 `selectedSongs()` 依赖实例状态，接口默认值无法表达。
     */
    internal fun bulkDeleteFiles(
        songs: List<Song>,
        launchSystemConfirm: (android.content.IntentSender) -> Unit,
    ) {
        if (songs.isEmpty()) return
        scope.launch {
            val sender = withContext(Dispatchers.IO) {
                runCatching { SongDeleter.prepareSystemConfirmMany(app, songs) }.getOrNull()
            }
            if (sender != null) {
                pendingBulkDelete = songs
                launchSystemConfirm(sender)
            } else {
                val deleted = withContext(Dispatchers.IO) { songs.filter { SongDeleter.deleteNow(app, it) } }
                afterBulkDelete(songs, deleted)
            }
        }
    }

    /** 批量系统确认框关闭后的回调 */
    override fun onSystemBulkDeleteResult(resultOk: Boolean) {
        val songs = pendingBulkDelete
        pendingBulkDelete = emptyList()
        if (songs.isEmpty()) return
        if (!resultOk) {
            pushMessage("已取消删除")
            session.selection.exitSelection()
            return
        }
        scope.launch {
            // 系统代删的只有媒体库文件；SAF 文档与深度扫描路径它管不到，这里补上。
            val rest = withContext(Dispatchers.IO) {
                songs.filter { it.source != SongSource.MEDIA_STORE }
                    .filter { SongDeleter.deleteNow(app, it) }
            }
            afterBulkDelete(songs, songs.filter { it.source == SongSource.MEDIA_STORE } + rest)
        }
    }

    /**
     * 批量文件删除收尾：已删掉的从库里连根清掉，未删成的保留（很可能就是权限被拒）。
     *
     * 无论成败都**退出多选态**——刚做完一次批量删除还留着勾选，很容易让人以为
     * 还得再点一次，或者顺手把已删的歌再操作一遍。
     */
    private fun afterBulkDelete(attempted: List<Song>, deleted: List<Song>) {
        scope.launch {
            if (deleted.isNotEmpty()) {
                playback.removeFromQueue(deleted)
                val ids = deleted.mapNotNull { idOf(it) }
                withContext(Dispatchers.IO) {
                    if (ids.isNotEmpty()) db.songDao().deleteByIds(ids)
                    deleted.forEach { CoverRepository.evict(it) }
                }
                index.removeUris(deleted.map { it.stableKey })
                index.dropStates(ids.toSet())
                applyRemovedFromMemory(deleted)
            }
            session.selection.exitSelection()
            pushMessage(
                when {
                    deleted.isEmpty() -> "删除失败：存储拒绝了该操作"
                    deleted.size < attempted.size ->
                        "已删除 ${deleted.size} 个文件，${attempted.size - deleted.size} 个被存储拒绝"
                    else -> "已删除 ${deleted.size} 个文件"
                }
            )
        }
    }

    /** 文件已删（或删除失败）后：清播放队列、清缓存与统计联动、更新界面 */
    private fun afterDelete(song: Song, ok: Boolean) {
        if (!ok) {
            pushMessage("删除「${song.title}」失败：存储拒绝了该操作")
            return
        }
        scope.launch {
            val key = song.stableKey
            // 1) 从播放队列里移除；若删的是当前曲目，ExoPlayer 会自动跳到下一首
            playback.removeFromQueue(listOf(song))
            // 2) 删曲库行：收藏 / 播放统计 / 歌单关联 / 队列条目全部由外键级联清除，
            //    不再逐个表手写 delete（旧实现漏掉任何一处都会留下脏数据）
            val songId = idOf(song)
            withContext(Dispatchers.IO) {
                songId?.let { db.songDao().deleteById(it) }
                CoverRepository.evict(song)
            }
            index.removeUri(key)
            if (songId != null) index.dropStates(listOf(songId))
            // 3) 更新内存状态（songs 本身已排序，过滤后顺序不变，无需重排）
            applyRemovedFromMemory(listOf(song))
            pushMessage("已删除「${song.title}」")
        }
    }

    // ---------------------------------------------------------- 收藏

    override fun toggleFavorite(song: Song) {
        scope.launch {
            val songId = idOf(song) ?: return@launch
            val newFav = withContext(Dispatchers.IO) {
                val dao = db.songStateDao()
                val now = System.currentTimeMillis()
                dao.ensure(songId, now)
                val fav = !(dao.favoriteOf(songId) ?: false)
                dao.setFavorite(songId, fav, now)
                fav
            }
            // 本地状态同步，避免为一次点击重查整张状态表
            index.bump(songId) { it.copy(favorite = newFav) }
            // 一次 update 完成增删，避免先加后减造成两次重组
            _library.update {
                val next = if (newFav) it.favoriteUris + song.stableKey else it.favoriteUris - song.stableKey
                it.copy(favoriteUris = next)
            }
            pushMessage(if (newFav) "已收藏「${song.title}」" else "已取消收藏")
        }
    }

    // ---------------------------------------------------------- 设置页

    /** 刷新播放统计概览（一条 SQL 出四项） */
    internal fun refreshStats() {
        scope.launch {
            val stats = withContext(Dispatchers.IO) {
                val s = runCatching { db.songStateDao().summary() }.getOrNull()
                PlayStats(
                    listenedSongs = s?.listenedSongs ?: 0,
                    totalPlays = s?.totalPlays ?: 0L,
                    totalListenedMs = s?.totalListenedMs ?: 0L,
                    favorites = s?.favorites ?: 0,
                )
            }
            _library.update { if (it.playStats == stats) it else it.copy(playStats = stats) }
        }
    }

    /** 清空播放次数与收听时长，但保留收藏与本地标签编辑 */
    override fun clearPlayStats() {
        scope.launch {
            // 先丢弃还没落库的累计时长，否则它会在重置之后又被写回去
            playback.resetListenAccumulator()
            withContext(Dispatchers.IO) {
                db.songStateDao().resetStats()
                // 收听报告的小时桶也必须一起清，否则"已清空统计"之后报告还在
                db.listenLogDao().clear()
            }
            index.transformStates { it.copy(playCount = 0, lastPlayedAt = 0L, playedMs = 0L) }
            session.playlists.refreshPlaylists()
            session.reports.loadReport()
            pushMessage("播放统计已清空（收藏保留）")
        }
    }

    // ---------------------------------------------------------- 搜索历史

    override fun submitSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    db.searchHistoryDao().add(q, System.currentTimeMillis())
                    db.searchHistoryDao().trim(SEARCH_HISTORY_LIMIT)
                }
            }
            reloadSearchHistory()
        }
    }

    override fun clearSearchHistory() {
        scope.launch {
            withContext(Dispatchers.IO) { db.searchHistoryDao().clear() }
            _library.update { it.copy(searchHistory = emptyList()) }
        }
    }

    /** 重新装载搜索历史 */
    private suspend fun reloadSearchHistory() {
        val history = withContext(Dispatchers.IO) { db.searchHistoryDao().recent(SEARCH_HISTORY_LIMIT) }
        _library.update { if (it.searchHistory == history) it else it.copy(searchHistory = history) }
    }

    /**
     * 把一批歌从**内存状态**里摘掉（曲库列表、详情、歌单、队列、最近/最常/收藏）。
     *
     * 三处调用者（删文件 / 移除目录 / 移出曲库）对数据库做的事不同，但对内存做的事
     * 完全一样——以前这段逻辑抄了三遍，任何一处漏了 "extraFavorites" 之类的新字段
     * 就会出现"删了歌但收藏页还挂着"的幽灵行。
     */
    internal fun applyRemovedFromMemory(removed: List<Song>) {
        val keys = removed.map { it.stableKey }.toSet()
        if (keys.isEmpty()) return
        playback.onSongsRemoved(removed)
        val songs = _library.value.songs.filterNot { it.stableKey in keys }
        val query = _library.value.query
        val visible = searchSongs(songs, query)
        _library.update {
            it.copy(
                songs = songs,
                visible = visible,
                letterIndex = buildLetterIndex(visible),
                lyricsCount = songs.count { s -> s.lrcUri != null },
                detailSongs = it.detailSongs.filterNot { s -> s.stableKey in keys },
                playlistSongs = it.playlistSongs.filterNot { s -> s.stableKey in keys },
                extraRecent = it.extraRecent.filterNot { s -> s.stableKey in keys },
                extraMostPlayed = it.extraMostPlayed.filterNot { s -> s.stableKey in keys },
                extraFavorites = it.extraFavorites.filterNot { s -> s.stableKey in keys },
                selection = it.selection - keys,
            )
        }
        refreshGroups()
        session.playlists.refreshPlaylists()
    }

    private fun pushMessage(text: String) = session.pushMessage(text)

    /** 取歌曲的曲库主键；索引里没有（理论不该发生）返回 null */
    private fun idOf(song: Song): Long? = session.idOf(song)

    // ---------------------------------------------------------------- 常量

    companion object {
        private const val TAG = "LibraryViewModel"
        /** 搜索历史保留条数 */
        private const val SEARCH_HISTORY_LIMIT = 10
    }
}

/** 文件夹维度缺失时的兜底标签，只用于内部分组，不在 UI 中展示 */
private const val UNKNOWN_FOLDER_LABEL = "未知目录"

// internal 而非 private：视图切换（本文件的 refreshGroups / openGroup）与「移除分组扫描结果」
// （FolderController.removeFolderScanResults）要用同一个口径分组，不能各写一份——两处口径一旦
// 漂移，界面上的分组键就对不上要移除的那批歌。
/** 文件夹维度标签（分组用） */
internal val Song.folderLabel: String
    get() = when {
        folderPath != null -> folderPath
        path != null -> java.io.File(path).parent
        source == com.luckylearning.windtone.data.model.SongSource.FOLDER -> uri.derivedFolderPath()
        else -> null
    } ?: UNKNOWN_FOLDER_LABEL
