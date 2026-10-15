package com.luckylearning.windtone.ui.library

import com.luckylearning.windtone.data.db.PlaylistEntity
import com.luckylearning.windtone.data.db.PlaylistSongEntity
import com.luckylearning.windtone.data.findDuplicateGroups
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.PlaylistInfo
import com.luckylearning.windtone.state.VirtualPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 歌单域的**实现**（含三个虚拟歌单：最近播放 / 最常播放 / 我的收藏）。
 *
 * 依赖与共享状态从 [LibrarySession] 取；刷新歌单后顺手刷一次统计与内存索引，
 * 与拆分前一致。
 */
internal class PlaylistStore(private val session: LibrarySession) : PlaylistActions {

    // ---------------------------------------------------------- 歌单

    private val db = session.db
    private val index = session.index
    private val _library = session._library

    private val scope get() = session.scope

    internal fun refreshPlaylists() {
        scope.launch { refreshPlaylistsNow() }
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
        // 「我的收藏」虚拟歌单：有收藏就列出全部，最近听过的排前面
        val favorites = index.allStates().asSequence()
            .filter { it.favorite }
            .sortedByDescending { it.lastPlayedAt }
            .mapNotNull { songsById[it.songId] }
            .toList()
        _library.update {
            it.copy(
                playlists = infos,
                extraRecent = recent,
                extraMostPlayed = most,
                extraFavorites = favorites,
            )
        }
        session.core.refreshStats()
    }

    override fun openPlaylist(playlist: PlaylistInfo) {
        scope.launch {
            val songs: List<Song> = when {
                playlist.virtual && playlist.id == VirtualPlaylist.RECENT -> _library.value.extraRecent
                playlist.virtual && playlist.id == VirtualPlaylist.MOST_PLAYED -> _library.value.extraMostPlayed
                playlist.virtual && playlist.id == VirtualPlaylist.FAVORITES -> _library.value.extraFavorites
                else -> {
                    val rows = withContext(Dispatchers.IO) { db.playlistDao().songsOf(playlist.id) }
                    val byId = _library.value.songs.associateBy { it.dbId }
                    rows.mapNotNull { byId[it.songId] }
                }
            }
            _library.update { it.copy(selectedPlaylist = playlist, playlistSongs = songs) }
        }
    }

    override fun closePlaylist() = _library.update { it.copy(selectedPlaylist = null, playlistSongs = emptyList()) }

    override fun createPlaylist(name: String, initialSongs: List<Song>, onCreated: (Long) -> Unit) {
        scope.launch {
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

    override fun renamePlaylist(id: Long, name: String) {
        scope.launch {
            withContext(Dispatchers.IO) { db.playlistDao().rename(id, name.trim()) }
            refreshPlaylists()
            _library.value.selectedPlaylist?.takeIf { it.id == id }?.let {
                _library.update { s -> s.copy(selectedPlaylist = it.copy(name = name.trim())) }
            }
        }
    }

    override fun deletePlaylist(id: Long) {
        scope.launch {
            withContext(Dispatchers.IO) { db.playlistDao().deletePlaylist(id) }
            closePlaylist()
            refreshPlaylists()
            pushMessage("已删除歌单")
        }
    }

    override fun addToPlaylist(playlistId: Long, song: Song) {
        scope.launch {
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

    override fun removeFromPlaylist(playlistId: Long, song: Song) {
        scope.launch {
            withContext(Dispatchers.IO) {
                idOf(song)?.let { db.playlistDao().removeSong(playlistId, it) }
            }
            refreshPlaylists()
            _library.value.selectedPlaylist?.takeIf { it.id == playlistId }?.let { openPlaylist(it) }
        }
    }

    /**
     * 歌单内拖动排序落盘。
     *
     * 只写 `playlist_songs.position`，一次事务逐行更新——不重建行，因此
     * `addedAt` 与「同一首歌只出现一次」的主键约束都不会被破坏。
     */
    override fun reorderPlaylist(playlistId: Long, orderedSongIds: List<Long>) {
        if (orderedSongIds.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) { db.playlistDao().reorder(playlistId, orderedSongIds) }
            // 内存里先按新顺序排好，界面立刻跟上（避免等一次完整重查）
            val byId = _library.value.playlistSongs.associateBy { it.dbId }
            val reordered = orderedSongIds.mapNotNull { byId[it] }
            if (reordered.size == _library.value.playlistSongs.size) {
                _library.update { it.copy(playlistSongs = reordered) }
            }
            refreshPlaylists()
        }
    }

    /**
     * 歌单一键去重：同标题 + 同歌手 + 同时长视为重复，只保留最值得留的那一首。
     *
     * 只影响**这个歌单的关联行**，不动曲库、不动文件——用户想清理曲库级的重复
     * 应该去「查找重复歌曲」（那个才会移出曲库）。
     */
    override fun dedupePlaylist(playlistId: Long) {
        scope.launch {
            val current = _library.value.playlistSongs
            if (current.size < 2) {
                pushMessage("歌单里没有重复歌曲")
                return@launch
            }
            val remove = findDuplicateGroups(current).flatMap { it.duplicates }
            if (remove.isEmpty()) {
                pushMessage("歌单里没有重复歌曲")
                return@launch
            }
            val ids = remove.mapNotNull { idOf(it) }
            withContext(Dispatchers.IO) {
                if (ids.isNotEmpty()) db.playlistDao().removeSongs(playlistId, ids)
            }
            val removedKeys = remove.map { it.stableKey }.toSet()
            _library.update {
                it.copy(playlistSongs = it.playlistSongs.filterNot { s -> s.stableKey in removedKeys })
            }
            refreshPlaylists()
            pushMessage("已从歌单移除 ${remove.size} 首重复歌曲")
        }
    }

    /**
     * 把当前播放队列存成一个新歌单（队列抽屉里的入口）。
     *
     * 调用方传的是队列里的 stableKey（`QueueEntry.mediaId`）而不是 Song：
     * 队列可能包含**已不在曲库**的条目（刚被移出 / 删掉的歌），
     * 这里按曲库现况过滤一遍，保证存出来的是一个能正常播放的歌单。
     */
    override fun createPlaylistFromQueue(name: String, queueKeys: List<String>) {
        if (queueKeys.isEmpty()) {
            pushMessage("播放队列是空的")
            return
        }
        val byKey = _library.value.songs.associateBy { it.stableKey }
        val songs = queueKeys.mapNotNull { byKey[it] }
        if (songs.isEmpty()) {
            pushMessage("队列里的歌曲已不在曲库中")
            return
        }
        createPlaylist(name, songs, {})
        val skipped = queueKeys.size - songs.size
        if (skipped > 0) pushMessage("（已跳过 $skipped 首不在曲库中的条目）")
    }

    private fun pushMessage(text: String) = session.pushMessage(text)

    /** 取歌曲的曲库主键；索引里没有（理论不该发生）返回 null */
    private fun idOf(song: Song): Long? = session.idOf(song)
}
