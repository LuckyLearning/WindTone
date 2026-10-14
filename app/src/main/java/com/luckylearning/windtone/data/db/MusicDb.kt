package com.luckylearning.windtone.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource

// ---------------------------------------------------------------- 扫描缓存

@Entity(tableName = "songs_cache")
data class SongCacheEntity(
    @PrimaryKey val uri: String,
    val id: Long,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumId: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String?,
    val displayName: String,
    val path: String?,
    val folderPath: String?,
    val source: String,
    val trackNo: Int,
    val lrcUri: String?,
    val dateAdded: Long,
) {
    companion object {
        fun from(song: Song) = SongCacheEntity(
            uri = song.stableKey,
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            albumId = song.albumId,
            durationMs = song.durationMs,
            sizeBytes = song.sizeBytes,
            mimeType = song.mimeType,
            displayName = song.displayName,
            path = song.path,
            folderPath = song.folderPath,
            source = song.source.name,
            trackNo = song.trackNo,
            lrcUri = song.lrcUri?.toString(),
            dateAdded = song.dateAdded,
        )
    }

    fun toSong(): Song? {
        // 旧版本缓存里的 WEBDAV 行（功能已移除）：来源无法识别的直接丢弃，避免云端 URI 混进本地曲库
        val src = runCatching { SongSource.valueOf(source) }.getOrNull() ?: return null
        return Song(
            id = id,
            uri = android.net.Uri.parse(uri),
            title = title,
            artist = artist,
            album = album,
            albumId = albumId,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            mimeType = mimeType,
            displayName = displayName,
            path = path,
            folderPath = folderPath,
            source = src,
            trackNo = trackNo,
            lrcUri = lrcUri?.let { android.net.Uri.parse(it) },
            dateAdded = dateAdded,
        )
    }
}

@Dao
interface SongCacheDao {
    @Query("SELECT * FROM songs_cache")
    suspend fun all(): List<SongCacheEntity>

    @Query("DELETE FROM songs_cache")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(songs: List<SongCacheEntity>)

    @Query("SELECT COUNT(*) FROM songs_cache")
    suspend fun count(): Int

    @Query("DELETE FROM songs_cache WHERE uri = :uri")
    suspend fun delete(uri: String)
}

// ---------------------------------------------------------------- 扫描目录登记

/**
 * 已扫描目录的登记表。
 *
 * 文件夹视图的分组键是「完整目录标识」（SAF 解码文档 ID / 绝对父路径），标题只显示
 * 最后一个文件夹名 —— 两个不同位置的同名目录会各自成组但标题一样。这张表把每个
 * 扫描过的目录记录下来（自增 id / 完整路径 / 叶名 / 树 URI），供同名分组消歧与
 * 后续「目录管理」类功能使用。扫描成功后逐目录 upsert，移除目录时删行。
 */
@Entity(
    tableName = "scanned_folders",
    indices = [Index(value = ["docId"], unique = true)],
)
data class ScannedFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** SAF 树 URI；FILE / MEDIA_STORE 来源的目录没有树 URI，存空串 */
    val treeUri: String,
    /** 目录唯一标识：SAF 为解码后的文档 ID（如 primary:Music/华语），本地路径为绝对路径 */
    val docId: String,
    /** 完整路径（SAF 取冒号后的路径部分）：Music/华语 */
    val fullPath: String,
    /** 叶目录名：华语 */
    val name: String,
    val addedAt: Long,
)

@Dao
interface ScannedFolderDao {
    @Query("SELECT * FROM scanned_folders ORDER BY addedAt ASC")
    suspend fun all(): List<ScannedFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: ScannedFolderEntity)

    @Query("DELETE FROM scanned_folders WHERE docId IN (:docIds)")
    suspend fun deleteByDocIds(docIds: List<String>)

    @Query("DELETE FROM scanned_folders")
    suspend fun clear()
}

// ---------------------------------------------------------------- 歌单

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songUri"],
    indices = [Index("playlistId")],
)
data class PlaylistSongEntity(
    val playlistId: Long,
    val songUri: String,
    val position: Int,
    val addedAt: Long,
)

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

    @Query("DELETE FROM playlist_songs WHERE playlistId = :id")
    suspend fun deleteSongsOf(id: Long)

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :id ORDER BY position ASC")
    suspend fun songsOf(id: Long): List<PlaylistSongEntity>

    /**
     * 一次性取回所有歌单的关联行。
     * 原先刷新歌单列表要对每个歌单单独查一次（N+1），这里改成单条 SQL + 内存分组。
     */
    @Query("SELECT * FROM playlist_songs ORDER BY playlistId ASC, position ASC")
    suspend fun allSongs(): List<PlaylistSongEntity>

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :id")
    suspend fun countOf(id: Long): Int

    @Query("SELECT MAX(position) FROM playlist_songs WHERE playlistId = :id")
    suspend fun maxPosition(id: Long): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: PlaylistSongEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songUri = :songUri")
    suspend fun removeSong(playlistId: Long, songUri: String)

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE songUri = :songUri")
    suspend fun playlistCountOf(songUri: String): Int

    /** 从所有歌单中移除某首歌（删除歌曲文件后的联动清理） */
    @Query("DELETE FROM playlist_songs WHERE songUri = :songUri")
    suspend fun removeSongEverywhere(songUri: String)
}

// ---------------------------------------------------------------- 播放统计 / 收藏

@Entity(tableName = "play_stats")
data class PlayStatEntity(
    @PrimaryKey val songUri: String,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0L,
    val favorite: Boolean = false,
    /** 累计收听时长（毫秒），用于统计页 */
    val playedMs: Long = 0L,
)

@Dao
interface PlayStatDao {
    @Query("SELECT * FROM play_stats")
    suspend fun all(): List<PlayStatEntity>

    @Query("SELECT * FROM play_stats WHERE songUri = :uri")
    suspend fun byUri(uri: String): PlayStatEntity?

    /**
     * 确保统计行存在。
     * 历史 bug：此前只有 UPDATE 没有 INSERT，未收藏过的歌曲永远写不进 play_stats，
     * 导致「最近播放 / 最常播放 / 播放次数」长期为空。
     */
    @Query("INSERT OR IGNORE INTO play_stats (songUri, playCount, lastPlayedAt, favorite, playedMs) VALUES (:uri, 0, 0, 0, 0)")
    suspend fun ensureRow(uri: String)

    @Query("UPDATE play_stats SET playCount = playCount + 1, lastPlayedAt = :now, playedMs = playedMs + :listenedMs WHERE songUri = :uri")
    suspend fun bump(uri: String, now: Long, listenedMs: Long)

    @Query("UPDATE play_stats SET playedMs = playedMs + :delta WHERE songUri = :uri")
    suspend fun addPlayedMs(uri: String, delta: Long)

    @Query("UPDATE play_stats SET favorite = :fav WHERE songUri = :uri")
    suspend fun setFavorite(uri: String, fav: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stat: PlayStatEntity)

    /** 清空播放统计，但保留收藏状态 */
    @Query("UPDATE play_stats SET playCount = 0, lastPlayedAt = 0, playedMs = 0")
    suspend fun resetStats()

    @Query("SELECT COUNT(*) FROM play_stats WHERE playCount > 0")
    suspend fun playedSongCount(): Int

    @Query("SELECT SUM(playCount) FROM play_stats")
    suspend fun totalPlayCount(): Long?

    @Query("SELECT SUM(playedMs) FROM play_stats")
    suspend fun totalPlayedMs(): Long?

    @Query("SELECT COUNT(*) FROM play_stats WHERE favorite = 1")
    suspend fun favoriteCount(): Int

    /** 移除某首歌的播放统计行（清空播放次数、收藏等一切记录） */
    @Query("DELETE FROM play_stats WHERE songUri = :uri")
    suspend fun delete(uri: String)
}

// ---------------------------------------------------------------- 歌曲信息覆盖（本地标签编辑）

@Entity(tableName = "song_overrides")
data class SongOverrideEntity(
    @PrimaryKey val songUri: String,
    val title: String?,
    val artist: String?,
    val album: String?,
)

@Dao
interface SongOverrideDao {
    @Query("SELECT * FROM song_overrides")
    suspend fun all(): List<SongOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(override: SongOverrideEntity)

    @Query("DELETE FROM song_overrides WHERE songUri = :uri")
    suspend fun delete(uri: String)
}

// ---------------------------------------------------------------- 数据库

@Database(
    entities = [
        SongCacheEntity::class,
        ScannedFolderEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        PlayStatEntity::class,
        SongOverrideEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class MusicDb : RoomDatabase() {

    abstract fun songCacheDao(): SongCacheDao
    abstract fun scannedFolderDao(): ScannedFolderDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playStatDao(): PlayStatDao
    abstract fun overrideDao(): SongOverrideDao

    companion object {
        @Volatile
        private var instance: MusicDb? = null

        /** v1 -> v2：play_stats 增加「累计收听时长」列，保留用户的歌单与收藏 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE play_stats ADD COLUMN playedMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v2 -> v3：新增 scanned_folders 目录登记表 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `scanned_folders` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`treeUri` TEXT NOT NULL, `docId` TEXT NOT NULL, `fullPath` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, `addedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_scanned_folders_docId` ON `scanned_folders` (`docId`)",
                )
            }
        }

        fun get(context: Context): MusicDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MusicDb::class.java,
                "music_player.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
