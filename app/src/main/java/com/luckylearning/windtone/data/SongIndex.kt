package com.luckylearning.windtone.data

import com.luckylearning.windtone.data.db.SongStateEntity
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.model.SongSource

/**
 * 曲库的**内存索引**：`uri → 主键` 与「每曲状态」两张表的常驻副本。
 *
 * 为什么单独抽一个类：这两份数据是曲库域与播放域**共用**的——
 *  - 曲库侧要拿状态做收藏标记、标签覆盖、最近/最常播放；
 *  - 播放侧要拿主键写播放统计、累计收听时长、歌词偏移、队列。
 *
 * 放在 ViewModel 里会让「播放引擎」反过来依赖 ViewModel；放在这里，
 * 两侧都只依赖它一个纯数据组件（不持有 Context、不认识界面）。
 *
 * 线程约定：`idByUri` 只在主线程改（曲库刷新/删歌都在主线程发起），
 * `states` 用整表替换（`@Volatile`）保证读侧永远看到一致快照。
 */
class SongIndex {

    private val idByUri = HashMap<String, Long>()

    /** 每首歌的来源（v6 收听报告分类型用）；与 idByUri 在 rebuild 时一起建 */
    @Volatile
    private var sourceById: Map<Long, SongSource> = emptyMap()

    @Volatile
    private var states: Map<Long, SongStateEntity> = emptyMap()

    // ------------------------------------------------------------------ uri ↔ 主键

    @Synchronized
    fun idOf(uri: String): Long? = idByUri[uri]

    /** 优先用 Song 自带的 dbId（持久化时已回填），回落查表 */
    fun idOf(song: Song): Long? =
        song.dbId.takeIf { it != 0L } ?: idByUri[song.stableKey]?.takeIf { it != 0L }

    fun idOrZero(song: Song): Long = idOf(song) ?: 0L

    /** 曲库重建后整体刷新映射。刚入库、尚未并进曲库列表的歌靠 [remember] 补进索引。 */
    @Synchronized
    fun rebuild(songs: List<Song>) {
        idByUri.clear()
        for (s in songs) if (s.dbId != 0L) idByUri[s.stableKey] = s.dbId
        sourceById = songs.filter { it.dbId != 0L }.associateBy({ it.dbId }, { it.source })
    }

    /**
     * 把已入库的歌记进索引，不丢弃现有条目。
     *
     * 洛雪起播会先 upsert 进数据库再播放，但曲库列表要等到下次扫描才会合并这些歌。
     * 若不在起播前 remember，[idOf] 对 mediaId 会落空，播放次数和最近播放都写不进去。
     */
    @Synchronized
    fun remember(songs: List<Song>) {
        var sources = sourceById
        for (s in songs) {
            if (s.dbId == 0L) continue
            idByUri[s.stableKey] = s.dbId
            sources = sources + (s.dbId to s.source)
        }
        sourceById = sources
    }

    /** 按 mediaId（即 stableKey）补一条映射，供统计在索引未命中时回查数据库后写回。 */
    @Synchronized
    fun remember(mediaId: String, songId: Long, source: SongSource) {
        if (songId == 0L || mediaId.isEmpty()) return
        idByUri[mediaId] = songId
        sourceById = sourceById + (songId to source)
    }

    /** 该主键歌曲的来源；索引里没有时按本地曲处理（读侧兜底，不写库） */
    @Synchronized
    fun sourceOf(songId: Long): SongSource = sourceById[songId] ?: SongSource.MEDIA_STORE

    @Synchronized
    fun removeUri(uri: String) {
        idByUri.remove(uri)
    }

    @Synchronized
    fun removeUris(uris: Collection<String>) {
        uris.forEach { idByUri.remove(it) }
    }

    @Synchronized
    fun clearIds() {
        idByUri.clear()
        sourceById = emptyMap()
    }

    // ------------------------------------------------------------------ 每曲状态

    fun stateOf(songId: Long): SongStateEntity? = states[songId]

    fun stateOf(song: Song): SongStateEntity? = states[idOrZero(song)]

    /** 统计概览 / 最近 / 最常用它遍历全部状态 */
    fun allStates(): Collection<SongStateEntity> = states.values

    fun replaceStates(rows: List<SongStateEntity>) {
        states = rows.associateBy { it.songId }
    }

    /** 整表变换（如「清空播放统计」把所有行的计数归零） */
    fun transformStates(transform: (SongStateEntity) -> SongStateEntity) {
        states = states.mapValues { (_, v) -> transform(v) }
    }

    /**
     * 就地更新一首歌的状态。
     * 统计写入走这里而不是重查整张表——播放计数是按次发生的，重查代价不划算。
     */
    fun bump(songId: Long, transform: (SongStateEntity) -> SongStateEntity) {
        val cur = states[songId] ?: SongStateEntity(songId = songId)
        states = states + (songId to transform(cur))
    }

    /** 歌曲从曲库彻底删除（数据库行已被 CASCADE 清掉） */
    fun dropStates(ids: Collection<Long>) {
        if (ids.isNotEmpty()) states = states - ids.toSet()
    }

    fun clearStates() {
        states = emptyMap()
    }

    fun lyricOffsetOf(song: Song): Int = stateOf(song)?.lyricOffsetMs ?: 0
}
