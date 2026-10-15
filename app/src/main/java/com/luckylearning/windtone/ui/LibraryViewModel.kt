package com.luckylearning.windtone.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.luckylearning.windtone.MusicApp
import com.luckylearning.windtone.data.CoverRepository
import com.luckylearning.windtone.data.PinyinIndex
import com.luckylearning.windtone.data.ScanScope
import com.luckylearning.windtone.data.SongDeleter
import com.luckylearning.windtone.data.SortOrder
import com.luckylearning.windtone.data.db.FolderEntity
import com.luckylearning.windtone.data.db.PlaylistEntity
import com.luckylearning.windtone.data.db.PlaylistSongEntity
import com.luckylearning.windtone.data.db.toEntity
import com.luckylearning.windtone.data.db.toSong
import com.luckylearning.windtone.data.derivedFolderPath
import com.luckylearning.windtone.data.docIdFullPath
import com.luckylearning.windtone.data.docIdOf
import com.luckylearning.windtone.data.folderDisplayName
import com.luckylearning.windtone.data.folderParentHint
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.treeDisplayName
import com.luckylearning.windtone.state.GroupInfo
import com.luckylearning.windtone.state.LibraryUiState
import com.luckylearning.windtone.state.LibraryView
import com.luckylearning.windtone.state.PlayStats
import com.luckylearning.windtone.state.PlaylistInfo
import com.luckylearning.windtone.state.VirtualPlaylist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as MusicApp).container
    private val prefs = container.prefs
    private val repository = container.libraryRepository
    private val db = container.db
    private val lyricsLoader = container.lyricsLoader
    private val index = container.songIndex
    private val bus = container.messages
    private val playback = container.playback

    /** 当前正在跑的音乐库扫描任务；范围/目录变化时先取消它，避免过期结果回写 */
    private var scanJob: Job? = null

    /** 搜索防抖任务 */
    private var searchJob: Job? = null

    /**
     * 导入目录的内存副本。
     *
     * 目录的唯一事实源是 Room 的 `folders` 表（顺序、叶名、完整路径、歌曲数都在那里），
     * 但界面要同步读（范围抽屉、扫描前判断），所以启动时装载一份、每次变更后刷新。
     */
    private var folders: List<FolderEntity> = emptyList()

    private val _library = MutableStateFlow(
        LibraryUiState(
            sortOrder = prefs.sortOrder,
            view = LibraryView.from(prefs.libraryView),
            deepScan = repository.deepScanEnabled(),
            scanScope = prefs.scanScope,
        )
    )
    val library: StateFlow<LibraryUiState> = _library.asStateFlow()

    /** 全局一次性提示（扫描完成 / 收藏成功 / 播放失败都走这里），界面收集后弹 Snackbar */
    val messages: SharedFlow<String> = bus.messages

    init {
        reloadAll()
    }

    /**
     * 重新装载曲库与用户数据。
     *
     * 界面在「清空用户数据 / 清理失效歌曲」之后调一次，让内存里的红心、歌单、
     * 统计与列表重新跟数据库对齐。
     */
    fun reloadAll() {
        viewModelScope.launch {
            reloadFolders()
            reloadSearchHistory()
            loadFromCacheThenScan()
        }
    }

    // ================================================================ 音乐库

    /** 冷启动：先展示曲库缓存（秒开），再按已选范围后台刷新；未选范围则完全不扫描 */
    private fun loadFromCacheThenScan() {
        viewModelScope.launch {
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
    fun startFullScan() {
        prefs.scanScope = ScanScope.FULL
        prefs.scanScopeChosen = true
        _library.update { it.copy(scanScope = ScanScope.FULL) }
        scan()
    }

    /**
     * 切换扫描范围。
     * [ScanScope.FOLDERS] 需要至少有一个已导入目录，否则界面应先去选目录。
     */
    fun setScanScope(scope: ScanScope) {
        if (scope == ScanScope.FOLDERS && folders.isEmpty()) return
        prefs.scanScope = scope
        prefs.scanScopeChosen = true
        cancelScan()
        _library.update {
            it.copy(scanScope = scope, folderCount = folders.size, folderUris = folderUriList())
        }
        if (scope != ScanScope.UNSET) scan() else clearLibraryForScopeReset()
    }

    private fun folderUriList(): List<String> = folders.map { it.treeUri }

    /** 最短时长阈值（秒），0 表示不过滤 */
    fun minSongDurationSec(): Int = prefs.minSongDurationSec

    /**
     * 修改短音频过滤阈值并立即重新扫描生效。
     * 时长未知（0）的歌曲不会被过滤，播放拿到真实时长后由下次扫描接管。
     */
    fun setMinSongDurationSec(sec: Int) {
        prefs.minSongDurationSec = sec
        if (prefs.scanScope != ScanScope.UNSET) scan()
    }

    fun scan(auto: Boolean = false) {
        if (_library.value.scanning) return
        if (_library.value.scanScope == ScanScope.UNSET) return
        // 同一时刻只保留最后一次扫描：范围/目录被改动后，旧扫描的结果必须作废，
        // 否则它会把「已经清空的曲库」重新写回内存与 Room 缓存（见 removeFolder）。
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            val scopeAtStart = prefs.scanScope
            val urisAtStart = folderUriList()
            _library.update {
                it.copy(
                    scanning = true,
                    statusText = "准备扫描…",
                    folderCount = urisAtStart.size,
                    folderUris = urisAtStart,
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
            rememberScannedFolders()
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
        val folderIdByDocId = folders.associate { it.docId to it.id }
        val existing = dao.allIdUri().associate { it.uri to it.id }
        val now = System.currentTimeMillis()
        val out = ArrayList<Song>(raw.size)

        db.withTransaction {
            for (song in raw) {
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
            refreshFolderCounts()
        }
        out
    }

    /** 目录歌曲数冗余列：扫描后重算一次，避免分组时再聚合 */
    private suspend fun refreshFolderCounts() {
        val dao = db.folderDao()
        val counts = db.songDao().countsByFolder().associate { it.folderId to it.songCount }
        for (folder in dao.all()) {
            val c = counts[folder.id] ?: 0
            if (folder.songCount != c) dao.setSongCount(folder.id, c)
        }
    }

    /**
     * 取消正在进行的扫描并复位状态。
     * 目录列表、扫描范围发生任何变化时都要先调用，避免旧扫描把过期结果写回曲库/缓存。
     */
    private fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _library.update { if (!it.scanning) it else it.copy(scanning = false, statusText = "") }
    }

    /**
     * 回到「等待选择扫描范围」：清空曲库与扫描缓存。
     * 只清本地扫描结果，**不动**用户数据（收藏/歌单/统计）。
     */
    private fun clearLibraryForScopeReset() {
        PinyinIndex.build(emptyList())
        playback.setLibrarySongs(emptyList())
        _library.update {
            it.copy(
                scanScope = ScanScope.UNSET,
                folderCount = folders.size,
                folderUris = folderUriList(),
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
        viewModelScope.launch {
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
        val sorted = repository.sort(songs, prefs.sortOrder)

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
                visible = visible,
                letterIndex = letters,
                scanning = false,
                skippedUnsupported = skipped,
                lyricsCount = sorted.count { s -> s.lrcUri != null },
                deepScan = repository.deepScanEnabled(),
                scanScope = prefs.scanScope,
                folderCount = folders.size,
                folderUris = folderUriList(),
                statusText = "",
                favoriteUris = favs,
            )
        }
        playback.setLibrarySongs(sorted)
        refreshGroups()
        refreshPlaylists()
    }

    private fun searchSongs(songs: List<Song>, query: String): List<Song> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return songs
        return songs.filter { song ->
            repository.matches(song, q) ||
                PinyinIndex.full(song.stableKey).contains(q) ||
                PinyinIndex.initials(song.stableKey).contains(q)
        }
    }

    /** 首字母 → 首个下标（A-Z 索引导航）；用 LinkedHashMap 保持字母顺序 */
    private fun buildLetterIndex(list: List<Song>): Map<Char, Int> {
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
    fun setQuery(q: String) {
        _library.update { it.copy(query = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(140L)
            val songs = _library.value.songs
            val (visible, letters) = withContext(Dispatchers.Default) {
                val v = searchSongs(songs, q)
                v to buildLetterIndex(v)
            }
            _library.update { it.copy(visible = visible, letterIndex = letters) }
        }
    }

    fun setSort(order: SortOrder) {
        prefs.sortOrder = order
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

    fun setView(view: LibraryView) {
        prefs.libraryView = view.name
        _library.update {
            it.copy(view = view, selectedGroup = null, selectedPlaylist = null, detailSongs = emptyList())
        }
        if (view == LibraryView.ALBUM || view == LibraryView.ARTIST || view == LibraryView.FOLDER) {
            refreshGroups()
        }
        if (view == LibraryView.PLAYLIST) refreshPlaylists()
    }

    private fun refreshGroups() {
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
        }.sortedWith(compareBy({ PinyinIndex.initialOf(it.title) }, { it.title.lowercase() }))
        _library.update { if (it.groups == groups) it else it.copy(groups = groups) }
    }

    fun openGroup(group: GroupInfo) {
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

    fun closeGroup() = _library.update { it.copy(selectedGroup = null, detailSongs = emptyList()) }

    // ---------------------------------------------------------- 歌单

    private fun refreshPlaylists() {
        viewModelScope.launch { refreshPlaylistsNow() }
    }

    /**
     * 重新计算歌单列表。
     *
     * 原实现对每个歌单单独查一次关联表（N+1），且重复构建了两次全库 Map。
     * 现在改成单条 SQL 取回全部关联行 + 内存分组，Map 只建一次。
     */
    private suspend fun refreshPlaylistsNow() {
        val entities = withContext(Dispatchers.IO) { db.playlistDao().all() }
        val links = withContext(Dispatchers.IO) { db.playlistDao().allSongs() }
        val songsById = _library.value.songs.associateBy { it.dbId }
        val byPlaylist = links.groupBy { it.playlistId }

        val infos = entities.map { p ->
            val songs = byPlaylist[p.id].orEmpty().mapNotNull { songsById[it.songId] }
            PlaylistInfo(p.id, p.name, songs.size, songs.firstOrNull())
        }
        // 统计直接用内存里的每曲状态，不再为了"最近播放/最常播放"再查一次库
        val recent = index.allStates().asSequence()
            .filter { it.lastPlayedAt > 0 }
            .sortedByDescending { it.lastPlayedAt }
            .take(100)
            .mapNotNull { songsById[it.songId] }
            .toList()
        val most = index.allStates().asSequence()
            .filter { it.playCount > 0 }
            .sortedByDescending { it.playCount }
            .take(100)
            .mapNotNull { songsById[it.songId] }
            .toList()
        _library.update {
            it.copy(
                playlists = infos,
                extraRecent = recent,
                extraMostPlayed = most,
            )
        }
        refreshStats()
    }

    fun openPlaylist(playlist: PlaylistInfo) {
        viewModelScope.launch {
            val songs: List<Song> = when {
                playlist.virtual && playlist.id == VirtualPlaylist.RECENT -> _library.value.extraRecent
                playlist.virtual && playlist.id == VirtualPlaylist.MOST_PLAYED -> _library.value.extraMostPlayed
                else -> {
                    val rows = withContext(Dispatchers.IO) { db.playlistDao().songsOf(playlist.id) }
                    val byId = _library.value.songs.associateBy { it.dbId }
                    rows.mapNotNull { byId[it.songId] }
                }
            }
            _library.update { it.copy(selectedPlaylist = playlist, playlistSongs = songs) }
        }
    }

    fun closePlaylist() = _library.update { it.copy(selectedPlaylist = null, playlistSongs = emptyList()) }

    fun createPlaylist(name: String, initialSongs: List<Song> = emptyList(), onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                val pid = db.playlistDao().insert(PlaylistEntity(name = name.trim(), createdAt = System.currentTimeMillis()))
                val now = System.currentTimeMillis()
                initialSongs.forEachIndexed { i, s ->
                    idOf(s)?.let { db.playlistDao().insertSong(PlaylistSongEntity(pid, it, i, now)) }
                }
                pid
            }
            refreshPlaylists()
            pushMessage("已创建歌单「$name」")
            onCreated(id)
        }
    }

    fun renamePlaylist(id: Long, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.playlistDao().rename(id, name.trim()) }
            refreshPlaylists()
            _library.value.selectedPlaylist?.takeIf { it.id == id }?.let {
                _library.update { s -> s.copy(selectedPlaylist = it.copy(name = name.trim())) }
            }
        }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.playlistDao().deletePlaylist(id) }
            closePlaylist()
            refreshPlaylists()
            pushMessage("已删除歌单")
        }
    }

    fun addToPlaylist(playlistId: Long, song: Song) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val songId = idOf(song) ?: return@withContext
                val pos = db.playlistDao().maxPosition(playlistId) + 1
                db.playlistDao().insertSong(PlaylistSongEntity(playlistId, songId, pos, System.currentTimeMillis()))
            }
            refreshPlaylists()
            _library.value.selectedPlaylist?.takeIf { it.id == playlistId }?.let { openPlaylist(it) }
            pushMessage("已加入歌单")
        }
    }

    fun removeFromPlaylist(playlistId: Long, song: Song) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                idOf(song)?.let { db.playlistDao().removeSong(playlistId, it) }
            }
            refreshPlaylists()
            _library.value.selectedPlaylist?.takeIf { it.id == playlistId }?.let { openPlaylist(it) }
        }
    }

    /** 取歌曲的曲库主键；索引里没有（理论不该发生）返回 null */
    private fun idOf(song: Song): Long? =
        index.idOf(song)

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
    fun deleteSong(song: Song, launchSystemConfirm: (android.content.IntentSender) -> Unit) {
        val app = getApplication<Application>()
        viewModelScope.launch {
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
    fun onSystemDeleteResult(resultOk: Boolean) {
        val song = pendingDelete
        pendingDelete = null
        if (song != null) afterDelete(song, resultOk)
    }

    /** 文件已删（或删除失败）后：清播放队列、清缓存与统计联动、更新界面 */
    private fun afterDelete(song: Song, ok: Boolean) {
        if (!ok) {
            pushMessage("删除「${song.title}」失败：存储拒绝了该操作")
            return
        }
        viewModelScope.launch {
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
            val songs = _library.value.songs.filterNot { it.stableKey == key }
            playback.onSongsRemoved(listOf(song))
            val query = _library.value.query
            val visible = searchSongs(songs, query)
            _library.update {
                it.copy(
                    songs = songs,
                    visible = visible,
                    letterIndex = buildLetterIndex(visible),
                    detailSongs = it.detailSongs.filterNot { s -> s.stableKey == key },
                    playlistSongs = it.playlistSongs.filterNot { s -> s.stableKey == key },
                    extraRecent = it.extraRecent.filterNot { s -> s.stableKey == key },
                    extraMostPlayed = it.extraMostPlayed.filterNot { s -> s.stableKey == key },
                )
            }
            refreshGroups()
            refreshPlaylists()
            pushMessage("已删除「${song.title}」")
        }
    }

    /**
     * 移除文件夹视图中某个分组下的全部扫描结果。
     *
     * 分组按「添加的目录」展示，所以移除时**同步把对应的导入目录从扫描列表移除**
     * （释放 SAF 持久化权限、更新 folderUris），否则下次扫描同样的歌又会回来。
     * 只从应用内部状态与数据库中清除，**不会删除存储中的原始音频文件**。
     * 会从播放队列、歌单、收藏、播放统计、歌词/封面缓存中一并清理。
     */
    fun removeFolderScanResults(group: GroupInfo) {
        if (_library.value.view != LibraryView.FOLDER) return
        val key = group.key
        val removed = _library.value.songs.filter { it.folderLabel == key }
        if (removed.isEmpty()) return
        viewModelScope.launch {
            // 1) 从播放器队列移除
            playback.removeFromQueue(removed)
            // 2) 批量删除曲库行：收藏 / 统计 / 歌单关联 / 队列条目由外键级联处理
            val removedIds = removed.mapNotNull { idOf(it) }
            withContext(Dispatchers.IO) {
                if (removedIds.isNotEmpty()) db.songDao().deleteByIds(removedIds)
                removed.forEach { CoverRepository.evict(it) }
            }
            index.removeUris(removed.map { it.stableKey })
            index.dropStates(removedIds.toSet())
            // 3) 更新内存状态
            val removedKeys = removed.map { it.stableKey }.toSet()
            val songs = _library.value.songs.filterNot { it.stableKey in removedKeys }
            playback.onSongsRemoved(removed)
            val query = _library.value.query
            val visible = searchSongs(songs, query)
            _library.update {
                it.copy(
                    songs = songs,
                    visible = visible,
                    letterIndex = buildLetterIndex(visible),
                    selectedGroup = if (it.selectedGroup?.key == key) null else it.selectedGroup,
                    detailSongs = if (it.selectedGroup?.key == key) emptyList() else it.detailSongs.filterNot { s -> s.stableKey in removedKeys },
                    playlistSongs = it.playlistSongs.filterNot { s -> s.stableKey in removedKeys },
                    extraRecent = it.extraRecent.filterNot { s -> s.stableKey in removedKeys },
                    extraMostPlayed = it.extraMostPlayed.filterNot { s -> s.stableKey in removedKeys },
                )
            }
            refreshGroups()
            refreshPlaylists()
            refreshStats()

            // 4) 分组按「添加的目录」展示：把这些歌所属的导入目录从扫描列表一并移除，
            //    否则下次扫描（或冷启动自动扫描）同样的歌又会回来。
            val matchedTrees = folders.map { it.treeUri }.filter { tree -> removed.any { it.belongsToTree(tree) } }
            if (matchedTrees.isNotEmpty()) {
                matchedTrees.forEach { tree ->
                    runCatching {
                        getApplication<Application>().contentResolver.releasePersistableUriPermission(
                            Uri.parse(tree),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
                withContext(Dispatchers.IO) {
                    db.folderDao().deleteByDocIds(matchedTrees.mapNotNull { docIdOf(it) })
                    reloadFolders()
                }
                // 目录配置变了：作废正在跑的扫描，避免它把刚删掉的结果又写回来
                cancelScan()
                if (folders.isEmpty() && prefs.scanScope == ScanScope.FOLDERS) {
                    // 最后一个目录被移除：与 removeFolder 相同的兜底 —— 范围回退「未选择」
                    prefs.scanScope = ScanScope.UNSET
                    prefs.scanScopeChosen = true
                    clearLibraryForScopeReset()
                    pushMessage("已移除「${group.title}」，导入目录已清空，请重新选择扫描范围")
                    return@launch
                }
            }
            val dirNote = if (matchedTrees.isEmpty()) ""
            else "，导入目录「${matchedTrees.joinToString("、") { treeDisplayName(it) }}」已一并移除"
            pushMessage("已移除「${group.title}」的 ${removed.size} 首歌曲$dirNote")
        }
    }

    // ---------------------------------------------------------- 收藏

    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
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

    // ---------------------------------------------------------- 文件夹 / 歌词 / 权限

    fun addFolder(uri: Uri, folderScopeOnly: Boolean = false) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { Log.w(TAG, "持久化文件夹权限失败", it) }
        // 写权限是「文件夹视图删除歌曲文件」的前提，拿不到也不影响读
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }

        viewModelScope.launch {
            withContext(Dispatchers.IO) { addFolderRow(uri.toString()) }
            // 首次导入目录即视为选定「指定目录」范围；用户明确选择「只看指定目录」时也强制切换
            if (folderScopeOnly || prefs.scanScope == ScanScope.UNSET) {
                prefs.scanScope = ScanScope.FOLDERS
                prefs.scanScopeChosen = true
            }
            _library.update {
                it.copy(
                    scanScope = prefs.scanScope,
                    folderCount = folders.size,
                    folderUris = folderUriList(),
                )
            }
            scan()
        }
    }

    /**
     * 把一个树 URI 登记成导入目录（已存在则只刷新内存副本）。
     * 目录表是唯一事实源，`sort` 取当前最大值 +1，保持"用户添加顺序"。
     */
    private suspend fun addFolderRow(uriString: String) {
        val docId = docIdOf(uriString)
        if (docId != null) {
            val dao = db.folderDao()
            if (dao.byDocId(docId) == null) {
                val fullPath = docIdFullPath(docId)
                dao.insert(
                    FolderEntity(
                        treeUri = uriString,
                        docId = docId,
                        name = folderDisplayName(fullPath),
                        fullPath = fullPath,
                        sort = dao.maxSort() + 1,
                        addedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        reloadFolders()
    }

    /** 重新装载目录列表（表 → 内存 → 界面状态） */
    private suspend fun reloadFolders() {
        folders = withContext(Dispatchers.IO) { db.folderDao().all() }
        _library.update { it.copy(folderCount = folders.size, folderUris = folderUriList()) }
    }

    /** 扫描后刷新目录的内存副本（目录的歌曲数在这一步重算） */
    private suspend fun rememberScannedFolders() {
        withContext(Dispatchers.IO) { refreshFolderCounts() }
        reloadFolders()
    }

    /** 重新装载搜索历史 */
    private suspend fun reloadSearchHistory() {
        val history = withContext(Dispatchers.IO) { db.searchHistoryDao().recent(SEARCH_HISTORY_LIMIT) }
        _library.update { if (it.searchHistory == history) it else it.copy(searchHistory = history) }
    }

    /**
     * 移除一个已导入目录。
     *
     * 两条硬规则：
     *  1. **绝不因为移除目录而自动扫全盘**。以往在「全盘扫描」范围下移除目录会走
     *     `scan()`，也就是把整块存储重扫一遍，又慢又没道理；现在只更新配置。
     *  2. 移除最后一个目录时，如果当前范围是「指定目录」，范围回退到「未选择」，
     *     清空曲库与扫描缓存并提示重新选择（同样不扫描任何位置）。
     */
    fun removeFolder(uriString: String) {
        if (folders.none { it.treeUri == uriString }) return
        runCatching {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(
                Uri.parse(uriString),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        // 目录配置变了：先作废正在跑的扫描，避免它把过期结果写回曲库/缓存
        cancelScan()

        // 立刻把「这个目录」带来的歌从曲库摘掉（只动应用内数据，不碰存储里的文件）
        val stale = songsFromTree(uriString)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                docIdOf(uriString)?.let { db.folderDao().deleteByDocIds(listOf(it)) }
                reloadFolders()
            }
            if (stale.isNotEmpty()) dropSongs(stale)

            if (folders.isEmpty()) {
                if (prefs.scanScope == ScanScope.FOLDERS) {
                    prefs.scanScope = ScanScope.UNSET
                    prefs.scanScopeChosen = true
                    clearLibraryForScopeReset()
                    pushMessage("已移除全部目录，请重新选择扫描范围")
                } else {
                    // 全盘扫描范围：曲库由系统媒体库决定，导入的目录只是附加来源，无需重扫
                    pushMessage("已移除全部目录")
                }
                return@launch
            }

            when (prefs.scanScope) {
                // 「指定目录」范围里目录就是唯一来源，剩下的目录要重扫（只扫这几个，很快）
                ScanScope.FOLDERS -> scan()
                // 「全盘扫描」范围：为了一个目录重扫整块盘不值得，等用户手动重扫
                else -> pushMessage("已移除目录「${treeDisplayName(uriString)}」")
            }
        }
    }

    /**
     * 找出曲库里来自某个 SAF 树目录的歌曲。
     * 用文档 ID 前缀比对（`primary:Music/a.mp3` 是否在 `primary:Music` 下），
     * 这样能精确到「这一棵导入的目录树」，不会误伤同名的其他目录。
     */
    private fun songsFromTree(treeUri: String): List<Song> =
        _library.value.songs.filter { it.belongsToTree(treeUri) }

    /** 歌曲是否来自某个 SAF 树目录（文档 ID 前缀比对，与 songsFromTree 同口径） */
    private fun Song.belongsToTree(treeUri: String): Boolean {
        val treeDocId = docIdOf(treeUri) ?: return false
        val docId = docIdOf(uri.toString()) ?: return false
        return docId == treeDocId || docId.startsWith("$treeDocId/")
    }

    /** 从内存曲库与数据库中摘掉指定歌曲（不删除存储中的文件；收藏/歌单/统计随曲库行级联清理） */
    private fun dropSongs(removed: List<Song>) {
        val keys = removed.map { it.stableKey }.toSet()
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
            )
        }
        viewModelScope.launch {
            val ids = removed.mapNotNull { idOf(it) }
            withContext(Dispatchers.IO) { if (ids.isNotEmpty()) db.songDao().deleteByIds(ids) }
            index.removeUris(removed.map { it.stableKey })
            index.dropStates(ids.toSet())
        }
        refreshGroups()
    }

    fun folderUris(): List<String> = folderUriList()

    /** 重新为缺少歌词文件的歌曲匹配同目录 .lrc */
    fun refreshLyrics() {
        viewModelScope.launch {
            _library.update { it.copy(statusText = "正在重新匹配歌词…") }
            val updated = repository.refreshSidecarLyrics(_library.value.songs)
            playback.setLibrarySongs(updated)
            val query = _library.value.query
            val visible = searchSongs(updated, query)
            _library.update {
                it.copy(
                    songs = updated,
                    visible = visible,
                    letterIndex = buildLetterIndex(visible),
                    lyricsCount = updated.count { s -> s.lrcUri != null },
                    statusText = "",
                )
            }
            pushMessage("已重新匹配歌词文件：${updated.count { it.lrcUri != null }} 首")
        }
    }

    fun deepScanSettingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.fromParts("package", getApplication<Application>().packageName, null)
            }
        } else null
    /** 用户拒绝音频读取权限时的提示（不影响「指定目录」方式） */
    fun notifyScanPermissionDenied() {
        pushMessage("没有读取音频的权限。可以改为「选择音乐目录」，用文件选择器授权指定文件夹。")
    }

    fun updateDeepScanFlag() {
        _library.update { it.copy(deepScan = repository.deepScanEnabled()) }
    }

        private fun pushMessage(text: String) = bus.post(text)

    // ---------------------------------------------------------- 设置页

    /** 刷新播放统计概览（一条 SQL 出四项） */
    private fun refreshStats() {
        viewModelScope.launch {
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
    fun clearPlayStats() {
        viewModelScope.launch {
            // 先丢弃还没落库的累计时长，否则它会在重置之后又被写回去
            playback.resetListenAccumulator()
            withContext(Dispatchers.IO) { db.songStateDao().resetStats() }
            index.transformStates { it.copy(playCount = 0, lastPlayedAt = 0L, playedMs = 0L) }
            refreshPlaylists()
            pushMessage("播放统计已清空（收藏保留）")
        }
    }

    // ---------------------------------------------------------- 搜索历史

    fun submitSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    db.searchHistoryDao().add(q, System.currentTimeMillis())
                    db.searchHistoryDao().trim(SEARCH_HISTORY_LIMIT)
                }
            }
            reloadSearchHistory()
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.searchHistoryDao().clear() }
            _library.update { it.copy(searchHistory = emptyList()) }
        }
    }

    // ---------------------------------------------------------- 歌曲信息编辑（本地覆盖）

    fun updateSongInfo(song: Song, title: String, artist: String, album: String) {
        viewModelScope.launch {
            val uri = song.stableKey
            val songId = song.dbId
            val t = title.trim()
            val a = artist.trim()
            val al = album.trim()
            // 三项都等于当前显示值 → 视为"恢复原始标签"，清空覆盖
            val restore = t == song.title && a == song.artistOrUnknown && al == song.albumOrUnknown
            val overrideTitle = if (restore) null else t.takeIf { it != song.title }
            val overrideArtist = if (restore) null else a.takeIf { it != song.artistOrUnknown }
            val overrideAlbum = if (restore) null else al.takeIf { it != song.albumOrUnknown }
            withContext(Dispatchers.IO) {
                val dao = db.songStateDao()
                val now = System.currentTimeMillis()
                dao.ensure(songId, now)
                if (restore) {
                    dao.clearOverride(songId)
                } else {
                    dao.setOverride(songId, overrideTitle, overrideArtist, overrideAlbum, now)
                }
            }
            // 内存状态同步，避免为一次编辑重查整张状态表
            index.bump(songId) {
                it.copy(
                    overrideTitle = overrideTitle,
                    overrideArtist = overrideArtist,
                    overrideAlbum = overrideAlbum,
                )
            }
            // 应用到当前列表
            val mapped = _library.value.songs.map {
                if (it.stableKey == uri) it.copy(
                    title = t.ifBlank { it.title },
                    artist = a.ifBlank { null },
                    album = al.ifBlank { null },
                ) else it
            }
            val sorted = repository.sort(mapped, _library.value.sortOrder)
            val visible = searchSongs(sorted, _library.value.query)
            _library.update {
                it.copy(songs = sorted, visible = visible, letterIndex = buildLetterIndex(visible))
            }
            playback.setLibrarySongs(sorted)
            refreshGroups()
            playback.renameCurrentSong(uri, t.ifBlank { song.title })
            pushMessage("已更新歌曲信息（仅本应用内显示，不修改原文件）")
        }
    }

    // ---------------------------------------------------------- M3U 导入导出

    fun exportPlaylistM3U(playlist: PlaylistInfo, target: Uri) {
        viewModelScope.launch {
            val songs: List<Song> = when {
                playlist.virtual && playlist.id == VirtualPlaylist.RECENT -> _library.value.extraRecent
                playlist.virtual && playlist.id == VirtualPlaylist.MOST_PLAYED -> _library.value.extraMostPlayed
                else -> {
                    val rows = withContext(Dispatchers.IO) { db.playlistDao().songsOf(playlist.id) }
                    val byId = _library.value.songs.associateBy { it.dbId }
                    rows.mapNotNull { byId[it.songId] }
                }
            }
            val text = buildM3U(playlist.name, songs)
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(target)?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                }
                pushMessage("已导出「${playlist.name}」（${songs.size} 首）")
            }.onFailure { pushMessage("导出失败：${it.message}") }
        }
    }

    fun importM3U(target: Uri) {
        viewModelScope.launch {
            val text = runCatching {
                getApplication<Application>().contentResolver.openInputStream(target)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                pushMessage("无法读取 M3U 文件")
                return@launch
            }
            val library = _library.value.songs
            val byPath = library.filter { it.path != null }.associateBy { it.path!! }
            val byUri = library.associateBy { it.stableKey.substringAfterLast('/') }
            val matched = ArrayList<Song>()
            var pendingTitle: String? = null
            for (line in text.lines()) {
                val l = line.trim()
                if (l.isEmpty()) continue
                if (l.startsWith("#EXTINF")) {
                    pendingTitle = l.substringAfter(':', "").trim()
                    continue
                }
                if (l.startsWith("#")) continue
                val clean = l.substringAfterLast("file://")
                val song = byPath[clean]
                    ?: library.firstOrNull { it.path != null && it.path.endsWith(clean, ignoreCase = true) }
                    ?: byUri[clean.substringAfterLast('/')]
                    ?: pendingTitle?.let { t ->
                        val name = t.substringAfter(" - ")
                        library.firstOrNull { it.title.equals(name, ignoreCase = true) }
                    }
                if (song != null) matched.add(song)
                pendingTitle = null
            }
            if (matched.isEmpty()) {
                pushMessage("M3U 中没有匹配到本地歌曲")
                return@launch
            }
            val name = "导入 " + (target.lastPathSegment?.substringBeforeLast('.')?.takeLast(20) ?: "歌单")
            createPlaylist(name, matched)
        }
    }

    private fun buildM3U(name: String, songs: List<Song>): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#PLAYLIST:$name")
        for (s in songs) {
            appendLine("#EXTINF:${s.durationMs / 1000},${s.artistOrUnknown} - ${s.title}")
            appendLine(s.path ?: s.uri.toString())
        }
    }

    // ---------------------------------------------------------------- 常量

    companion object {
        private const val TAG = "LibraryViewModel"
        /** 搜索历史保留条数 */
        private const val SEARCH_HISTORY_LIMIT = 10
    }
}

/** 文件夹维度缺失时的兜底标签，只用于内部分组，不在 UI 中展示 */
private const val UNKNOWN_FOLDER_LABEL = "未知目录"

/** 文件夹维度标签（分组用） */
private val Song.folderLabel: String
    get() = when {
        folderPath != null -> folderPath
        path != null -> java.io.File(path).parent
        source == com.luckylearning.windtone.data.model.SongSource.FOLDER -> uri.derivedFolderPath()
        else -> null
    } ?: UNKNOWN_FOLDER_LABEL
