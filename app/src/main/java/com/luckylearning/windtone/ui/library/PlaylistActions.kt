package com.luckylearning.windtone.ui.library

import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.PlaylistInfo

/**
 * 歌单域对外的**动作面**（含三个虚拟歌单的打开、以及歌单内的增删改与排序）。
 *
 * 抽成接口的目的见 [LibraryActions]；[createPlaylist] 的默认值留在接口上
 * （Kotlin 重写不能声明默认值）。
 */
interface PlaylistActions {
    fun openPlaylist(playlist: PlaylistInfo)
    fun closePlaylist()
    fun createPlaylist(name: String, initialSongs: List<Song> = emptyList(), onCreated: (Long) -> Unit = {})
    fun renamePlaylist(id: Long, name: String)
    fun deletePlaylist(id: Long)
    fun addToPlaylist(playlistId: Long, song: Song)
    fun removeFromPlaylist(playlistId: Long, song: Song)
    fun reorderPlaylist(playlistId: Long, orderedSongIds: List<Long>)
    fun dedupePlaylist(playlistId: Long)
    fun createPlaylistFromQueue(name: String, queueKeys: List<String>)
}
