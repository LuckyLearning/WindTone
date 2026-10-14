package com.luckylearning.windtone.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 应用数据库（v4）。
 *
 * 八张表分三类（详见 docs/数据库设计-MusicDb.md）：
 *  - **曲库**：[SongEntity]、[FolderEntity] —— 扫描得来的数据，可由重扫重建；
 *  - **用户数据**：[SongStateEntity]、[PlaylistEntity]、[PlaylistSongEntity]、
 *    [QueueSongEntity]、[PlayerStateEntity]、[SearchHistoryEntity] —— 不可再生，删歌时随外键级联清理；
 *  - 派生缓存（封面）不入库，放 `cacheDir/covers`。
 *
 * ⚠️ **本版不保留任何历史迁移路径**（产品口径：不做跨版本兼容，安装即全新）。
 * 因此 `fallbackToDestructiveMigration()` 是唯一的版本兜底：结构对不上就整库重建，
 * 曲库由重扫恢复，歌单 / 收藏 / 统计会丢。
 *
 * 改 schema 时：version +1 → 同步更新 docs/数据库设计-MusicDb.md → 重新导出 schema 快照。
 * 若日后需要保住用户数据，再补 `addMigrations(...)` 即可（Room 会优先走迁移而非兜底）。
 */
@Database(
    entities = [
        SongEntity::class,
        FolderEntity::class,
        SongStateEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        QueueSongEntity::class,
        PlayerStateEntity::class,
        SearchHistoryEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class MusicDb : RoomDatabase() {

    abstract fun songDao(): SongDao
    abstract fun folderDao(): FolderDao
    abstract fun songStateDao(): SongStateDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun queueDao(): QueueDao
    abstract fun playerStateDao(): PlayerStateDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        const val DB_NAME = "music_player.db"

        @Volatile
        private var instance: MusicDb? = null

        fun get(context: Context): MusicDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MusicDb::class.java,
                DB_NAME,
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
