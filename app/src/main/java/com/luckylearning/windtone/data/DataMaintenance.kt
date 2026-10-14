package com.luckylearning.windtone.data

import android.content.Context
import com.luckylearning.windtone.data.db.MusicDb

/**
 * 数据维护与自检。
 *
 * 为什么单独成类：这些操作**不属于日常业务流**，只在设置页的诊断页被用户手动触发，
 * 或者将来被排障工具调用。集中在一处比散在 ViewModel 里几十个方法更容易看清
 * 「哪些操作会破坏性地删数据」。
 *
 * 全部方法都在 IO 上执行，调用方负责切线程。
 */
class DataMaintenance(
    private val context: Context,
    private val db: MusicDb,
) {

    /** 自检报告（诊断页展示） */
    data class Report(
        /** 曲库里可用的歌 */
        val songs: Int,
        /** 文件在本机暂不可见（软删）的歌 */
        val missing: Int,
        val folders: Int,
        val playlists: Int,
        val favorites: Int,
        val plays: Long,
        val listenedMs: Long,
        val searchHistory: Int,
        /** 封面磁盘缓存占用 */
        val coverBytes: Long,
        /** 数据库文件占用 */
        val dbBytes: Long,
    ) {
        val dbSizeText: String get() = formatBytes(dbBytes)
        val coverSizeText: String get() = formatBytes(coverBytes)

        private fun formatBytes(v: Long): String = when {
            v >= 1024 * 1024 -> "%.1f MB".format(v / 1024.0 / 1024.0)
            v >= 1024 -> "%.0f KB".format(v / 1024.0)
            else -> "$v B"
        }
    }

    suspend fun report(): Report {
        val songDao = db.songDao()
        val summary = runCatching { db.songStateDao().summary() }.getOrNull()
        return Report(
            songs = runCatching { songDao.countVisible() }.getOrDefault(0),
            missing = runCatching { songDao.countMissing() }.getOrDefault(0),
            folders = runCatching { db.folderDao().all().size }.getOrDefault(0),
            playlists = runCatching { db.playlistDao().all().size }.getOrDefault(0),
            favorites = summary?.favorites ?: 0,
            plays = summary?.totalPlays ?: 0L,
            listenedMs = summary?.totalListenedMs ?: 0L,
            searchHistory = runCatching { db.searchHistoryDao().recent(100).size }.getOrDefault(0),
            coverBytes = CoverRepository.diskUsageBytes(),
            dbBytes = databaseBytes(),
        )
    }

    /**
     * 清理「文件已不在本机」的歌曲行，返回清理条数。
     *
     * 连带效果（外键级联）：这些歌的收藏、播放统计、歌词偏移、标签编辑、
     * 歌单关联、队列条目一并消失——这正是"这条歌已经不存在了"的应有结果。
     * 想临时保留它们就什么都不做（列表本来就不展示软删的歌）。
     */
    suspend fun cleanupMissingSongs(): Int = runCatching { db.songDao().deleteMissing() }.getOrDefault(0)

    /**
     * 清空全部用户数据（收藏 / 播放统计 / 歌词偏移 / 标签编辑 / 歌单 / 队列 / 搜索历史），
     * **保留曲库与导入目录**。危险操作，调用方必须二次确认。
     */
    suspend fun resetUserData() {
        db.songStateDao().resetAll()
        db.queueDao().clear()
        db.playerStateDao().clear()
        db.searchHistoryDao().clear()
        db.playlistDao().clearAll()
        CoverRepository.clear()
    }

    /** 清空曲库与导入目录（等同于"重新开始"），保留用户数据（收藏等仍在库里，重扫到同一文件即可复用） */
    suspend fun resetLibrary() {
        db.songDao().clear()
        db.folderDao().clear()
    }

    /** 清掉封面磁盘缓存 */
    fun clearCoverCache() = CoverRepository.clearAll()

    private fun databaseBytes(): Long = runCatching {
        context.getDatabasePath(MusicDb.DB_NAME).let { f ->
            // WAL 模式下数据可能还在 -wal 文件里，一起算上才是真实占用
            listOf(f, java.io.File("${f.path}-wal"), java.io.File("${f.path}-shm"))
                .filter { it.exists() }
                .sumOf { it.length() }
        }
    }.getOrDefault(0L)
}
