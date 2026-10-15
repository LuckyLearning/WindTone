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

    /** 曲库里当前可用的歌（软删的行不返回） */
    @Query("SELECT * FROM songs WHERE missingSince = 0")
    suspend fun visible(): List<SongEntity>

    /** 全量（含软删），维护与自检用 */
    @Query("SELECT * FROM songs")
    suspend fun all(): List<SongEntity>

    /** 一次性取回 id ↔ uri 映射，供扫描落库与队列保存批量换算 */
    @Query("SELECT id, uri FROM songs")
    suspend fun allIdUri(): List<IdUri>

    @Query("SELECT id FROM songs WHERE uri = :uri")
    suspend fun idOf(uri: String): Long?

    @Query("SELECT * FROM songs WHERE uri = :uri")
    suspend fun byUri(uri: String): SongEntity?

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
     */
    @Query("UPDATE songs SET missingSince = :now WHERE missingSince = 0 AND lastSeenAt < :scanStart")
    suspend fun markMissing(scanStart: Long, now: Long): Int

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM songs WHERE missingSince > 0")
    suspend fun deleteMissing(): Int

    @Query("DELETE FROM songs")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM songs WHERE missingSince = 0")
    suspend fun countVisible(): Int

    @Query("SELECT COUNT(*) FROM songs WHERE missingSince > 0")
    suspend fun countMissing(): Int

    @Query("SELECT folderId, COUNT(*) AS songCount FROM songs WHERE missingSince = 0 AND folderId IS NOT NULL GROUP BY folderId")
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
