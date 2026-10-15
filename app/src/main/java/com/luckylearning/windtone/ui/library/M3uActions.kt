package com.luckylearning.windtone.ui.library

import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.PlaylistInfo

/**
 * M3U 与歌曲信息编辑域对外的**动作面**（M3U 导入导出 + `song_overrides` 本地标签覆盖）。
 *
 * 从 [LibraryActions] 里按分节边界拆出来：这一摊只做「文件 ↔ 本地库」的搬运与落盘
 * （M3U 文本、`song_overrides` 表），不碰扫描/视图/搜索。抽成接口的目的与可见性取舍
 * 见 [LibraryActions]。
 */
interface M3uActions {
    fun updateSongInfo(song: Song, title: String, artist: String, album: String)
    fun exportPlaylistM3U(playlist: PlaylistInfo, target: Uri)
    fun importM3U(target: Uri)
}
