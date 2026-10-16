package com.luckylearning.windtone.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

/** `id ↔ uri` 轻量映射行：扫描落库与队列持久化时用来批量换算，避免逐条查询。 */
data class IdUri(val id: Long, val uri: String)

/** 目录歌曲数统计行 */
data class FolderCount(val folderId: Long, val songCount: Int)

/** 播放统计概览（一条 SQL 出全部四项，替代原来的 4 次查询） */
data class StatsSummary(
    val listenedSongs: Int,
    val totalPlays: Long,
    val totalListenedMs: Long,
    val favorites: Int,
)

// ---------------------------------------------------------------- 曲库

@Dao
interface SongDao {

    /** 曲库里当前可用的歌（软删的行与被排除的行都不返回） */
    @Query("SELECT * FROM songs WHERE missingSince = 0 AND excluded = 0")
    suspend fun visible(): List<SongEntity>

    /** 全量（含软删 / 已排除），维护与自检用 */
    @Query("SELECT * FROM songs")
    suspend fun all(): List<SongEntity>

    /** 一次性取回 id ↔ uri 映射，供扫描落库与队列保存批量换算 */
    @Query("SELECT id, uri FROM songs")
    suspend fun allIdUri(): List<IdUri>

    /** 被用户主动「移出曲库」的 uri：扫描时据此跳过，让排除在重扫后依然生效 */
    @Query("SELECT uri FROM songs WHERE excluded = 1")
    suspend fun excludedUris(): List<String>

    @Query("SELECT id FROM songs WHERE uri = :uri")
    suspend fun idOf(uri: String): Long?

    @Query("SELECT * FROM songs WHERE uri = :uri")
    suspend fun byUri(uri: String): SongEntity?

    /**
     * 按主键批量取行（一次 SQL），**不保证返回顺序**——顺序由调用方按自己的 id 列表还原。
     *
     * 车机浏览树用它一次取回一个歌单 / 一个虚拟歌单的全部曲目：
     * 逐首查（N+1）在这个场景下会随歌单长度线性变慢。
     * 软删与已排除的行照样过滤掉——它们不该出现在车机面板上。
     */
    @Query("SELECT * FROM songs WHERE id IN (:ids) AND missingSince = 0 AND excluded = 0")
    suspend fun byIds(ids: List<Long>): List<SongEntity>

    @Insert
    suspend fun insert(row: SongEntity): Long

    @Insert
    suspend fun insertAll(rows: List<SongEntity>)

    /** 按主键更新（落库时行已存在则走这里，ID 保持不变） */
    @Update
    suspend fun update(row: SongEntity)

    /**
     * 本轮扫描没有见到的歌：置为「暂不可见」而不是删除。
     * 收藏 / 歌单 / 播放统计因此全部保留，文件回来后自动恢复。
     * 已被用户排除（[SongEntity.excluded]）的行跳过——否则它们会被误标成「文件不见了」。
     */
    @Query("UPDATE songs SET missingSince = :now WHERE missingSince = 0 AND excluded = 0 AND lastSeenAt < :scanStart")
    suspend fun markMissing(scanStart: Long, now: Long): Int

    /** 用户主动「移出曲库 / 放回曲库」（批量，多选态与重复清理共用） */
    @Query("UPDATE songs SET excluded = :excluded WHERE id IN (:ids)")
    suspend fun setExcluded(ids: List<Long>, excluded: Boolean)

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM songs WHERE missingSince > 0")
    suspend fun deleteMissing(): Int

    @Query("DELETE FROM songs")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM songs WHERE missingSince = 0 AND excluded = 0")
    suspend fun countVisible(): Int

    @Query("SELECT COUNT(*) FROM songs WHERE missingSince > 0")
    suspend fun countMissing(): Int

    @Query("SELECT COUNT(*) FROM songs WHERE excluded = 1")
    suspend fun countExcluded(): Int

    @Query("SELECT folderId, COUNT(*) AS songCount FROM songs WHERE missingSince = 0 AND excluded = 0 AND folderId IS NOT NULL GROUP BY folderId")
    suspend fun countsByFolder(): List<FolderCount>
}

// ---------------------------------------------------------------- 导入目录

@Dao
interface FolderDao {

    @Query("SELECT * FROM folders ORDER BY sort ASC, addedAt ASC")
    suspend fun all(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE docId = :docId")
    suspend fun byDocId(docId: String): FolderEntity?

    @Query("SELECT COALESCE(MAX(sort), -1) FROM folders")
    suspend fun maxSort(): Int

    @Insert
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("UPDATE folders SET songCount = :count WHERE id = :id")
    suspend fun setSongCount(id: Long, count: Int)

    /** 排除 / 取消排除某目录（扫描时跳过它，但保留已导入的歌） */
    @Query("UPDATE folders SET excluded = :excluded WHERE docId = :docId")
    suspend fun setExcluded(docId: String, excluded: Boolean)

    @Query("DELETE FROM folders WHERE docId IN (:docIds)")
    suspend fun deleteByDocIds(docIds: List<String>)

    @Query("DELETE FROM folders")
    suspend fun clear()
}

// ---------------------------------------------------------------- 每曲本地状态

@Dao
interface SongStateDao {

    @Query("SELECT * FROM song_state")
    suspend fun all(): List<SongStateEntity>

    @Query("SELECT * FROM song_state WHERE songId = :songId")
    suspend fun bySongId(songId: Long): SongStateEntity?

    @Query("SELECT lyricOffsetMs FROM song_state WHERE songId = :songId")
    suspend fun lyricOffsetOf(songId: Long): Int?

    @Query("SELECT favorite FROM song_state WHERE songId = :songId")
    suspend fun favoriteOf(songId: Long): Boolean?

    @Query("SELECT songId FROM song_state WHERE favorite = 1")
    suspend fun favoriteIds(): List<Long>

    // ------------------------------------------------------------
    // 车机浏览树（`playback/LibraryBrowser.kt`）用的有序 id 列表。
    //
    // 三个虚拟歌单在 App 内的口径是「内存里排好序再取前 100」，
    // 车机面板没有那份内存索引，因此这里把排序下沉到 SQL（走 favorite /
    // lastPlayedAt 上的索引），语义与 `ui/library/PlaylistStore.kt` 保持一致。
    // ------------------------------------------------------------

    /** 「我的收藏」：最近听过的排前面（与 App 内虚拟歌单一一致） */
    @Query("SELECT songId FROM song_state WHERE favorite = 1 ORDER BY lastPlayedAt DESC")
    suspend fun favoriteIdsByRecent(): List<Long>

    /** 「最近播放」：按最后播放时间倒序 */
    @Query("SELECT songId FROM song_state WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    suspend fun recentIds(limit: Int): List<Long>

    /** 「最常播放」：按播放次数倒序 */
    @Query("SELECT songId FROM song_state WHERE playCount > 0 ORDER BY playCount DESC LIMIT :limit")
    suspend fun mostPlayedIds(limit: Int): List<Long>

    /** 按歌曲主键批量取状态行（车机浏览树合并标签覆盖用） */
    @Query("SELECT * FROM song_state WHERE songId IN (:songIds)")
    suspend fun bySongIds(songIds: List<Long>): List<SongStateEntity>

    /**
     * 确保行存在。
     * 历史 bug：早先只有 UPDATE 没有 INSERT，未收藏过的歌永远写不进统计表。
     * 这里显式列出全部列，不依赖 DDL 默认值。
     */
    @Query(
        "INSERT OR IGNORE INTO song_state " +
            "(songId, favorite, playCount, lastPlayedAt, playedMs, lyricOffsetMs, updatedAt) " +
            "VALUES (:songId, 0, 0, 0, 0, 0, :now)"
    )
    suspend fun ensure(songId: Long, now: Long)

    @Query("UPDATE song_state SET playCount = playCount + 1, lastPlayedAt = :now, updatedAt = :now WHERE songId = :songId")
    suspend fun bump(songId: Long, now: Long)

    @Query("UPDATE song_state SET playedMs = playedMs + :delta, updatedAt = :now WHERE songId = :songId")
    suspend fun addPlayedMs(songId: Long, delta: Long, now: Long)

    @Query("UPDATE song_state SET favorite = :fav, updatedAt = :now WHERE songId = :songId")
    suspend fun setFavorite(songId: Long, fav: Boolean, now: Long)

    @Query("UPDATE song_state SET lyricOffsetMs = :offsetMs, updatedAt = :now WHERE songId = :songId")
    suspend fun setLyricOffset(songId: Long, offsetMs: Int, now: Long)

    @Query(
        "UPDATE song_state SET overrideTitle = :title, overrideArtist = :artist, " +
            "overrideAlbum = :album, updatedAt = :now WHERE songId = :songId"
    )
    suspend fun setOverride(songId: Long, title: String?, artist: String?, album: String?, now: Long)

    @Query("UPDATE song_state SET overrideTitle = NULL, overrideArtist = NULL, overrideAlbum = NULL WHERE songId = :songId")
    suspend fun clearOverride(songId: Long)

    /** 清空播放次数与收听时长，但保留收藏与标签编辑 */
    @Query("UPDATE song_state SET playCount = 0, lastPlayedAt = 0, playedMs = 0")
    suspend fun resetStats()

    /** 一条 SQL 取得统计概览四项 */
    @Query(
        "SELECT " +
            "(SELECT COUNT(*) FROM song_state WHERE playCount > 0) AS listenedSongs, " +
            "(SELECT COALESCE(SUM(playCount), 0) FROM song_state) AS totalPlays, " +
            "(SELECT COALESCE(SUM(playedMs), 0) FROM song_state) AS totalListenedMs, " +
            "(SELECT COUNT(*) FROM song_state WHERE favorite = 1) AS favorites"
    )
    suspend fun summary(): StatsSummary

    @Query("DELETE FROM song_state WHERE songId = :songId")
    suspend fun delete(songId: Long)

    /** 清空所有用户状态（维护用；危险操作，调用方必须二次确认） */
    @Query("DELETE FROM song_state")
    suspend fun resetAll()
}

// ---------------------------------------------------------------- 歌单

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * 一次性取回所有歌单的关联行（单条 SQL + 内存分组）。
     * 原实现对每个歌单单独查一次（N+1），这里保持 O(1) 查询。
     */
    @Query("SELECT * FROM playlist_songs ORDER BY playlistId ASC, position ASC")
    suspend fun allSongs(): List<PlaylistSongEntity>

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :id ORDER BY position ASC")
    suspend fun songsOf(id: Long): List<PlaylistSongEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_songs WHERE playlistId = :id")
    suspend fun maxPosition(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(row: PlaylistSongEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSong(playlistId: Long, songId: Long)

    /** 一次移除多首（歌单去重 / 批量移除用） */
    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId IN (:songIds)")
    suspend fun removeSongs(playlistId: Long, songIds: List<Long>)

    @Query("UPDATE playlist_songs SET position = :position WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun setPosition(playlistId: Long, songId: Long, position: Int)

    /**
     * 按给定顺序重排歌单（拖动排序落盘）。
     *
     * 一次事务里逐行 UPDATE position：歌单是几十到几百首的量级，
     * 逐行写的开销远小于「清空重插」——后者会连带丢掉 addedAt，
     * 且中途失败会把歌单留在空表状态。
     */
    @Transaction
    suspend fun reorder(playlistId: Long, orderedSongIds: List<Long>) {
        orderedSongIds.forEachIndexed { i, songId -> setPosition(playlistId, songId, i) }
    }

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun deleteSongsOf(playlistId: Long)

    @Transaction
    suspend fun deletePlaylist(id: Long) {
        deleteSongsOf(id)
        delete(id)
    }

    /** 清空全部歌单（维护用） */
    @Query("DELETE FROM playlist_songs")
    suspend fun clearAllSongs()

    @Query("DELETE FROM playlists")
    suspend fun clearAllPlaylists()

    @Transaction
    suspend fun clearAll() {
        clearAllSongs()
        clearAllPlaylists()
    }
}

// ---------------------------------------------------------------- 播放队列与播放态

@Dao
interface QueueDao {

    @Query("SELECT * FROM queue_songs ORDER BY position ASC")
    suspend fun all(): List<QueueSongEntity>

    @Query("SELECT songId FROM queue_songs ORDER BY position ASC")
    suspend fun songIds(): List<Long>

    @Insert
    suspend fun insertAll(rows: List<QueueSongEntity>)

    @Query("DELETE FROM queue_songs")
    suspend fun clear()

    /** 以当前队列为准整体重写（几百行，事务开销可忽略） */
    @Transaction
    suspend fun replaceAll(rows: List<QueueSongEntity>) {
        clear()
        insertAll(rows)
    }
}

@Dao
interface PlayerStateDao {

    @Query("SELECT * FROM player_state WHERE id = 1")
    suspend fun get(): PlayerStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: PlayerStateEntity)

    /** 只推进播放进度（进度计时器每几秒调一次，不动队列与模式） */
    @Query("UPDATE player_state SET positionMs = :positionMs, updatedAt = :now WHERE id = 1")
    suspend fun updatePosition(positionMs: Long, now: Long)

    @Query("DELETE FROM player_state")
    suspend fun clear()
}

// ---------------------------------------------------------------- 搜索历史

@Dao
interface SearchHistoryDao {

    @Query("SELECT query FROM search_history ORDER BY usedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<String>

    @Query("INSERT OR IGNORE INTO search_history (query, usedAt, hits) VALUES (:query, :usedAt, 0)")
    suspend fun insertIfAbsent(query: String, usedAt: Long)

    @Query("UPDATE search_history SET usedAt = :usedAt, hits = hits + 1 WHERE query = :query")
    suspend fun bumpQuery(query: String, usedAt: Long)

    /**
     * 记录一次搜索。
     *
     * 不用 `INSERT ... ON CONFLICT DO UPDATE`：Android 各版本的 SQLite 对内联 upsert 支持不一
     * （老系统不认识），拆成「插入忽略 + 更新」两步在事务里等价且处处可用。
     */
    @Transaction
    suspend fun add(query: String, usedAt: Long) {
        insertIfAbsent(query, usedAt)
        bumpQuery(query, usedAt)
    }

    /** 历史只保留最近若干条 */
    @Query("DELETE FROM search_history WHERE query NOT IN (SELECT query FROM search_history ORDER BY usedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

// ---------------------------------------------------------------- 收听日志

@Dao
interface ListenLogDao {

    /** 取回从某个小时起的全部桶（升序），收听报告据此聚合出日曲线与时段分布 */
    @Query("SELECT * FROM listen_log WHERE hourEpoch >= :fromHour ORDER BY hourEpoch ASC")
    suspend fun since(fromHour: Long): List<ListenLogEntity>

    /** 该小时是否已有桶（没有则先建一个空的，避免 UPDATE 匹配不到行） */
    @Query("INSERT OR IGNORE INTO listen_log (hourEpoch, ms) VALUES (:hour, 0)")
    suspend fun ensureHour(hour: Long)

    @Query("UPDATE listen_log SET ms = ms + :delta WHERE hourEpoch = :hour")
    suspend fun addMs(hour: Long, delta: Long)

    /**
     * 累加一段收听到当前小时桶。
     *
     * 不用内联 upsert：Android 各版本 SQLite 对 `ON CONFLICT DO UPDATE` 支持不一，
     * 「插入忽略 + 更新」两步在事务里等价且处处可用（与搜索历史同口径）。
     */
    @Transaction
    suspend fun add(hour: Long, delta: Long) {
        if (delta <= 0L) return
        ensureHour(hour)
        addMs(hour, delta)
    }

    @Query("DELETE FROM listen_log WHERE hourEpoch < :beforeHour")
    suspend fun prune(beforeHour: Long): Int

    @Query("DELETE FROM listen_log")
    suspend fun clear()
}
