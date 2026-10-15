package com.luckylearning.windtone.ui.library

import com.luckylearning.windtone.data.findDuplicateGroups
import com.luckylearning.windtone.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 重复歌曲清理域的**实现**（批次 10.2）。
 *
 * 依赖与共享状态从 [LibrarySession] 取。清理只给重复歌曲打「移出曲库」排除标记，
 * 不删文件、不删收藏与统计。
 */
internal class DuplicateController(private val session: LibrarySession) : DuplicateActions {

    // ---------------------------------------------------------- 重复歌曲清理（10.2）

    private val db = session.db
    private val playback = session.playback
    private val _library = session._library

    private val scope get() = session.scope

    /** 按「标题 + 歌手 + 时长」找重复；结果只放在内存里等用户确认，不自动动手 */
    override fun scanDuplicates() {
        _library.update { it.copy(duplicatesScanning = true) }
        scope.launch {
            val groups = withContext(Dispatchers.Default) { findDuplicateGroups(_library.value.songs) }
            _library.update {
                it.copy(duplicates = groups, duplicatesScanning = false, duplicatesScanned = true)
            }
            pushMessage(
                if (groups.isEmpty()) "没有发现重复歌曲"
                else "发现 ${groups.size} 组重复，共 ${groups.sumOf { g -> g.duplicates.size }} 首可移出曲库"
            )
        }
    }

    /**
     * 执行重复清理：每组留一首，其余**移出曲库**（排除标记，不删文件、不删收藏与统计）。
     * 这是不可逆感受很强的一步，所以界面上必须二次确认，且明确写出「不删文件」。
     */
    override fun cleanDuplicates() {
        val dupes = _library.value.duplicates.flatMap { it.duplicates }
        if (dupes.isEmpty()) return
        scope.launch {
            val ids = dupes.mapNotNull { idOf(it) }
            withContext(Dispatchers.IO) {
                if (ids.isNotEmpty()) db.songDao().setExcluded(ids, true)
            }
            playback.removeFromQueue(dupes)
            session.core.applyRemovedFromMemory(dupes)
            _library.update { it.copy(duplicates = emptyList(), duplicatesScanned = false) }
            pushMessage("已移出 ${dupes.size} 首重复歌曲（文件未删除，可在设置里恢复）")
        }
    }

    override fun clearDuplicateScan() = _library.update {
        it.copy(duplicates = emptyList(), duplicatesScanned = false, duplicatesScanning = false)
    }

    private fun pushMessage(text: String) = session.pushMessage(text)

    /** 取歌曲的曲库主键；索引里没有（理论不该发生）返回 null */
    private fun idOf(song: Song): Long? = session.idOf(song)
}
