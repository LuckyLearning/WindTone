package com.luckylearning.windtone.ui.library

import android.net.Uri
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.state.PlaylistInfo
import com.luckylearning.windtone.state.VirtualPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * M3U 与歌曲信息编辑域的**实现**（M3U 导入导出 + `song_overrides` 本地标签覆盖）。
 *
 * 从 [LibraryCore] 里按分节边界拆出来的一摊：它只负责「文件 ↔ 本地库」的搬运与落盘，
 * 曲库列表本身的重排 / 字母索引 / 分组复用 [LibraryCore] 的 `internal` 辅助方法。
 */
internal class M3uStore(private val session: LibrarySession) : M3uActions {

    private val app = session.app
    private val repository = session.repository
    private val db = session.db
    private val index = session.index
    private val playback = session.playback
    private val _library = session._library

    private val scope get() = session.scope

    // ---------------------------------------------------------- 歌曲信息编辑（本地覆盖）

    override fun updateSongInfo(song: Song, title: String, artist: String, album: String) {
        scope.launch {
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
            val visible = session.core.searchSongs(sorted, _library.value.query)
            _library.update {
                it.copy(songs = sorted, visible = visible, letterIndex = session.core.buildLetterIndex(visible))
            }
            playback.setLibrarySongs(sorted)
            session.core.refreshGroups()
            playback.renameCurrentSong(uri, t.ifBlank { song.title })
            pushMessage("已更新歌曲信息（仅本应用内显示，不修改原文件）")
        }
    }

    // ---------------------------------------------------------- M3U 导入导出

    override fun exportPlaylistM3U(playlist: PlaylistInfo, target: Uri) {
        scope.launch {
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
                app.contentResolver.openOutputStream(target)?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                }
                pushMessage("已导出「${playlist.name}」（${songs.size} 首）")
            }.onFailure { pushMessage("导出失败：${it.message}") }
        }
    }

    override fun importM3U(target: Uri) {
        scope.launch {
            val text = runCatching {
                app.contentResolver.openInputStream(target)?.use {
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
            session.playlists.createPlaylist(name, matched, {})
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

    private fun pushMessage(text: String) = session.pushMessage(text)
}
