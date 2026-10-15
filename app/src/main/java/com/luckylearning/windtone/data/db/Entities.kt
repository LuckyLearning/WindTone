package com.luckylearning.windtone.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v5 数据模型。
 *
 * 设计原则（详见 docs/数据层重构规划.md）：
 *  1. **整数主键**：所有表用自增 `id` 做主键，超长的媒体 URI 退化为唯一自然键 `uri`，
 *     只在外键映射与跨进程比对时使用。
 *  2. **单一事实源**：曲库、目录、每曲状态、队列各只有一个存放处。
 *  3. **关系显式化**：用户数据（收藏/歌单/队列）以 `songId` 外键指向曲库并级联删除，
 *     "删一首歌要清哪些地方"由数据库负责。
 *  4. **软删**：文件暂时不可见时只置 [SongEntity.missingSince]，收藏/歌单/统计全部保留。
 *
 * v5 相对 v4 的增补（第五期）：
 *  - [SongEntity.excluded] / [FolderEntity.excluded]：用户主动「移出曲库 / 排除目录」，
 *    重扫不再收进来，但保留行与全部用户数据；
 *  - [ListenLogEntity]：按小时聚合的收听时长桶，供收听报告画曲线与时段分布。
 */

// ---------------------------------------------------------------- 曲库

/**
 * 曲库：一次扫描得到的全部媒体元数据。
 *
 * 与旧的 `songs_cache` 的区别：
 *  - 不再是"可随时丢弃的缓存"，而是**曲库唯一真相**，行 ID 稳定；
 *  - 新增 [lastSeenAt] / [missingSince] 支持增量扫描与软删。
 */
@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["folderId"]),
        Index(value = ["folderPath"]),
        Index(value = ["albumId"]),
        Index(value = ["missingSince"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 媒体 URI（原 stableKey），唯一自然键 */
    val uri: String,
    /** 来源：MEDIA_STORE / FOLDER / FILE */
    val source: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumId: Long = 0L,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val mimeType: String? = null,
    val displayName: String = "",
    /** 绝对路径（仅深度扫描 / Android 10 及以下有值） */
    val path: String? = null,
    /** 所属导入目录（SAF 来源才有值；深度扫描 / 媒体库为 null） */
    val folderId: Long? = null,
    /**
     * 文件夹视图的分组键。
     * SAF 来源 = 所选目录的解码文档 ID（`primary:Music/华语`，整棵子树共用）；
     * 深度扫描 = 音频文件所在目录的绝对路径。
     */
    val folderPath: String? = null,
    /** 来源内数字 ID（MediaStore 的 _ID 等），仅作展示与排障 */
    val mediaStoreId: Long = 0L,
    val trackNo: Int = 0,
    /** 已定位到的同名 .lrc / .txt 歌词文件 */
    val lrcUri: String? = null,
    val dateAdded: Long = 0L,
    /** 本轮扫描见到它的时间戳；`< 本次扫描开始时间` 即视为本轮未出现 */
    val lastSeenAt: Long = 0L,
    /** 0 = 可用；> 0 = 文件在本机暂不可见（软删），保留收藏/歌单/统计 */
    val missingSince: Long = 0L,
    /**
     * 用户主动「移出曲库」的标记（默认 false）。
     *
     * 与 [missingSince] 的区别：后者表示「文件暂时不在本机」，文件回来就自动恢复；
     * 而本标记是**用户的意图**——重扫也不会再把它收进曲库，直到用户主动取消。
     * 行本身与其收藏 / 统计 / 歌词偏移全部保留，所以放回来时用户数据一点不丢。
     * 重复歌曲清理的「保留最优、其余移出」也复用它（只移出、不删文件）。
     */
    val excluded: Boolean = false,
)

// ---------------------------------------------------------------- 导入目录

/**
 * 导入目录：**目录列表的唯一事实源**（取代旧 `scanned_folders` + `prefs.folderUris` 的双写）。
 * 同时承担顺序（[sort]）、展示（[name] / [fullPath]）与统计（[songCount]）职责。
 */
@Entity(
    tableName = "folders",
    indices = [Index(value = ["docId"], unique = true)],
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** SAF 树 URI；FILE / MEDIA_STORE 来源没有树 URI，存空串 */
    val treeUri: String,
    /** 目录唯一标识：SAF 解码文档 ID（`primary:Music/华语`）/ 绝对路径 */
    val docId: String,
    /** 叶目录名（列表标题） */
    val name: String,
    /** 冒号后的完整路径：`Music/华语`（同名分组消歧用） */
    val fullPath: String,
    /** 用户添加顺序 */
    val sort: Int,
    val addedAt: Long,
    /** 该目录下当前可用的歌曲数（扫描后重算，冗余列避免分组时再聚合） */
    val songCount: Int = 0,
    /**
     * 排除标记（默认 false）：扫描时**跳过**这棵目录树。
     *
     * 与「移除目录」的区别是**保留已导入的歌**——把铃声 / 录音 / 播客这类目录
     * 标为排除，既能阻止它们被扫进来，又不会把之前已经导入的歌一起清掉。
     * 目录本身与它的持久化读写权限都保留，随时可取消排除。
     */
    val excluded: Boolean = false,
)

// ---------------------------------------------------------------- 每曲本地状态

/**
 * 每曲一行本地状态：合并原 `play_stats` + `song_overrides` + Prefs 里的逐曲歌词偏移。
 *
 * 为什么合成一张宽表：这三类数据都以"一首歌"为单位、都随歌曲消失、读列表时要一起取。
 * 拆开只会带来多次查询与多处清理。"清空播放统计"只需 UPDATE 统计三列，天然不动收藏与编辑。
 */
@Entity(
    tableName = "song_state",
    indices = [Index(value = ["favorite"])],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SongStateEntity(
    @PrimaryKey val songId: Long,
    val favorite: Boolean = false,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0L,
    /** 累计收听时长（毫秒） */
    val playedMs: Long = 0L,
    /** 逐曲歌词偏移（毫秒），正数表示歌词提前 */
    val lyricOffsetMs: Int = 0,
    /** 本地标签编辑（null = 不覆盖原标签） */
    val overrideTitle: String? = null,
    val overrideArtist: String? = null,
    val overrideAlbum: String? = null,
    val updatedAt: Long = 0L,
)

// ---------------------------------------------------------------- 歌单

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    indices = [Index(value = ["songId"])],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlaylistSongEntity(
    val playlistId: Long,
    val songId: Long,
    val position: Int,
    val addedAt: Long,
)

// ---------------------------------------------------------------- 播放队列与播放态

/**
 * 播放队列。整体"以当前队列为准"重写（几百行的事务开销 < 5ms），
 * 所以不需要为 position 冲突做交换逻辑，用自增 id 做主键。
 */
@Entity(
    tableName = "queue_songs",
    indices = [Index(value = ["songId"]), Index(value = ["position"])],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class QueueSongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val position: Int,
    val songId: Long,
)

/** 播放态：永远只有一行（id = 1），承载断点续播所需的全部信息。 */
@Entity(tableName = "player_state")
data class PlayerStateEntity(
    @PrimaryKey val id: Int = 1,
    val currentSongId: Long? = null,
    val positionMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
    val updatedAt: Long = 0L,
)

// ---------------------------------------------------------------- 搜索历史

@Entity(tableName = "search_history", indices = [Index(value = ["usedAt"])])
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val usedAt: Long,
    val hits: Int = 1,
)

// ---------------------------------------------------------------- 收听日志

/**
 * 收听时长的小时桶（收听报告的**唯一新数据源**）。
 *
 * 为什么必须有这张表：`song_state` 只有「累计时长 + 最后一次播放时间」两个聚合值，
 * 无法还原「最近 7 天每天听了多久」「一天里什么时段听得多」——那是收听报告的核心。
 * 每行是「某一小时累计听了多少毫秒」，写完即聚合，行数是「听过的小时数」（一年也就几千行）。
 *
 * 只记时长、不记歌曲：TOP 榜 / 总时长直接从 `song_state` 出（那里本来就有），
 * 记到歌一级会让写入放大成「每 30 秒每首歌一行」，得不偿失。
 * 超过 [RETENTION_DAYS] 的桶在写入时顺手裁掉，表大小恒定有界。
 *
 * 无外键：它统计的是「设备在这些小时里放了多少音乐」，与具体歌曲是否被删无关。
 */
@Entity(tableName = "listen_log")
data class ListenLogEntity(
    /** 桶的小时起点（`epochMillis / 3600000`），主键即桶序号 */
    @PrimaryKey val hourEpoch: Long,
    /** 该小时内累计收听毫秒数 */
    val ms: Long,
) {
    companion object {
        /** 保留天数：一年多一点，足够画年度报告又不至于无限增长 */
        const val RETENTION_DAYS = 400
    }
}
