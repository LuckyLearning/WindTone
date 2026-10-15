package com.luckylearning.windtone.ui.library

import com.luckylearning.windtone.data.db.PlaylistSongEntity
import com.luckylearning.windtone.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 批量多选域的**实现**（多选态、全选 / 反选、以及底部操作栏的批量动作）。
 *
 * 依赖与共享状态从 [LibrarySession] 取；批量动作用完统一退出多选态，
 * 与拆分前一致。
 */
internal class SelectionController(private val session: LibrarySession) : SelectionActions {

    // ---------------------------------------------------------- 批量操作（多选）

    private val db = session.db
    private val index = session.index
    private val playback = session.playback
    private val _library = session._library

    private val scope get() = session.scope

    /** 进入多选态并选中这一首（长按歌曲行触发） */
    override fun enterSelection(song: Song) {
        _library.update { it.copy(selecting = true, selection = it.selection + song.stableKey) }
    }

    /** 多选态下点行 = 勾选 / 取消勾选 */
    override fun toggleSelected(song: Song) {
        val key = song.stableKey
        _library.update {
            it.copy(selection = if (key in it.selection) it.selection - key else it.selection + key)
        }
    }

    /** 全选**当前列表**（搜索过滤后看到的那批，而不是整库） */
    override fun selectAllVisible() {
        _library.update {
            it.copy(selecting = true, selection = it.visible.map { s -> s.stableKey }.toSet())
        }
    }

    /** 反选当前列表 */
    override fun invertSelection() {
        _library.update {
            val visibleKeys = it.visible.map { s -> s.stableKey }.toSet()
            it.copy(selecting = true, selection = visibleKeys - it.selection)
        }
    }

    /** 退出多选态（清空勾选） */
    override fun exitSelection() = _library.update { it.copy(selecting = false, selection = emptySet()) }

    /** 勾选集合对应的 Song（按曲库顺序返回，顺序稳定） */
    internal fun selectedSongs(): List<Song> {
        val keys = _library.value.selection
        if (keys.isEmpty()) return emptyList()
        return _library.value.songs.filter { it.stableKey in keys }
    }

    /** 批量加入歌单 */
    override fun bulkAddToPlaylist(playlistId: Long, playlistName: String) {
        val songs = selectedSongs()
        if (songs.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) {
                val dao = db.playlistDao()
                var pos = dao.maxPosition(playlistId) + 1
                val now = System.currentTimeMillis()
                songs.forEach { s ->
                    idOf(s)?.let { dao.insertSong(PlaylistSongEntity(playlistId, it, pos++, now)) }
                }
            }
            session.playlists.refreshPlaylists()
            _library.value.selectedPlaylist?.takeIf { it.id == playlistId }?.let { session.playlists.openPlaylist(it) }
            pushMessage("已把 ${songs.size} 首加入「$playlistName」")
            exitSelection()
        }
    }

    /** 批量收藏 / 批量取消收藏 */
    override fun bulkSetFavorite(favorite: Boolean) {
        val songs = selectedSongs()
        if (songs.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) {
                val dao = db.songStateDao()
                val now = System.currentTimeMillis()
                songs.forEach { s ->
                    idOf(s)?.let { id ->
                        dao.ensure(id, now)
                        dao.setFavorite(id, favorite, now)
                        index.bump(id) { it.copy(favorite = favorite) }
                    }
                }
            }
            _library.update {
                it.copy(
                    favoriteUris = if (favorite) it.favoriteUris + songs.map { s -> s.stableKey }
                    else it.favoriteUris - songs.map { s -> s.stableKey }.toSet(),
                )
            }
            session.playlists.refreshPlaylists()
            pushMessage(if (favorite) "已收藏 ${songs.size} 首" else "已取消收藏 ${songs.size} 首")
            exitSelection()
        }
    }

    /** 批量加入播放队列（追加到队尾；已在队列里的会跳过，避免重复条目） */
    override fun bulkAddToQueue() {
        val songs = selectedSongs()
        if (songs.isEmpty()) return
        playback.enqueue(songs)
        pushMessage("已把 ${songs.size} 首加入播放队列")
        exitSelection()
    }

    /**
     * 批量「移出曲库」：只打排除标记，**不删文件、不删收藏与统计**。
     * 重扫也不会再收进来，直到用户在设置里恢复。
     *
     * 注意：本方法**刻意不在 [SelectionActions] 接口上**，而是由 `LibraryViewModel`
     * 直接声明——它的默认值 `selectedSongs()` 依赖实例状态，接口默认值无法表达。
     */
    internal fun bulkRemoveFromLibrary(songs: List<Song>) {
        if (songs.isEmpty()) return
        scope.launch {
            val ids = songs.mapNotNull { idOf(it) }
            withContext(Dispatchers.IO) {
                if (ids.isNotEmpty()) db.songDao().setExcluded(ids, true)
            }
            playback.removeFromQueue(songs)
            session.core.applyRemovedFromMemory(songs)
            pushMessage("已把 ${songs.size} 首移出曲库（文件与收藏都还在，可在设置里恢复）")
            exitSelection()
        }
    }

    /** 恢复全部「移出曲库」的歌并重扫一次 */
    override fun restoreExcludedSongs() {
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                val dao = db.songDao()
                val ids = dao.all().filter { it.excluded }.map { it.id }
                if (ids.isEmpty()) 0 else {
                    dao.setExcluded(ids, false)
                    ids.size
                }
            }
            if (n == 0) {
                pushMessage("没有被移出曲库的歌曲")
                return@launch
            }
            pushMessage("已恢复 $n 首，正在重新扫描…")
            session.core.scan(false)
        }
    }

    private fun pushMessage(text: String) = session.pushMessage(text)

    /** 取歌曲的曲库主键；索引里没有（理论不该发生）返回 null */
    private fun idOf(song: Song): Long? = session.idOf(song)
}
