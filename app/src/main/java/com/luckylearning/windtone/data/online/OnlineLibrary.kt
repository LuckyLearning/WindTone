package com.luckylearning.windtone.data.online

import android.content.Context
import com.luckylearning.windtone.data.db.MusicDb
import com.luckylearning.windtone.data.db.toEntity
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.online.source.OnlineTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 在线曲目的**入库出口**：把一首搜索结果变成曲库里的一行。
 *
 * 为什么在线歌必须进 `songs` 表（而不是自建一张在线表）：
 * `song_state`（收藏 / 歌词偏移 / 播放统计）、`playlists`、`queue_songs`、`listen_log`
 * 全部以 `songs.id` 为外键。另开一张曲表意味着这四张表都要加第二套外键或改成多态 ——
 * 那就是在数据层直接否掉「在线与本地同级」这件事。
 *
 * 进表之后只有一条纪律：**扫描对账必须跳过在线行**。`SongDao.markMissing` 的 SQL 已按
 * `source IN ('MEDIA_STORE','FOLDER','FILE')` 过滤，否则每次扫描都会把在线收藏标成
 * 「文件不见了」，再被 `deleteMissing` 连歌带收藏一起级联删掉。
 *
 * 这条路径只写「身份 + 元信息」，**不写直链**：直链有有效期，落盘只会存一堆过期链接。
 */
class OnlineLibrary(context: Context) {

    private val appContext = context.applicationContext
    private val store = OnlineTrackStore(appContext)

    /**
     * 插入或更新一首在线曲，返回带曲库主键的 [Song]。
     *
     * 同一稳定键重复入库走更新，**主键保持不变** —— 收藏、歌单、播放统计都挂在
     * 这个主键上，换一次 id 就等于把用户数据全脱钩。
     */
    suspend fun upsert(track: OnlineTrack): Song = withContext(Dispatchers.IO) {
        val dao = MusicDb.get(appContext).songDao()
        val song = upsertOne(dao, track)
        store.put(track)
        song
    }

    /**
     * 批量入库（搜索结果整页入队时用）。
     *
     * 与逐条调用 [upsert] 的区别只在**写盘次数**：`OnlineTrackStore` 每次 `put`
     * 都会把整份 JSON 重写一遍，30 首逐条写就是 30 次全量序列化（O(n²)）；
     * 这里改成全部写库后一次 `putAll`。播放行为完全一致。
     */
    suspend fun upsertAll(tracks: List<OnlineTrack>): List<Song> = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext emptyList()
        val dao = MusicDb.get(appContext).songDao()
        val songs = tracks.map { upsertOne(dao, it) }
        store.putAll(tracks)
        songs
    }

    private suspend fun upsertOne(
        dao: com.luckylearning.windtone.data.db.SongDao,
        track: OnlineTrack,
    ): Song {
        val existing = dao.byUri(track.stableKey)
        val now = System.currentTimeMillis()
        val entity = track.toSong()
            .toEntity(existing?.id ?: 0L, folderId = null)
            .copy(
                // 在线曲永远不参与本地扫描对账，lastSeenAt 一律 0
                lastSeenAt = 0L,
                missingSince = 0L,
                // 用户把它移出曲库是明确意图，重复入库不能替用户反悔
                excluded = existing?.excluded ?: false,
                // 首次入库才记时间，「最近添加」的排序才不会被每次播放打乱
                dateAdded = existing?.dateAdded?.takeIf { it > 0L } ?: now,
            )
        val id = if (existing == null) {
            dao.insert(entity)
        } else {
            dao.update(entity)
            existing.id
        }
        return track.toSong(dateAdded = entity.dateAdded, dbId = id)
    }

    /** 取回某首歌的源内原始信息（取直链 / 歌词 / 封面都要它） */
    suspend fun trackOf(stableKey: String): OnlineTrack? = store.get(stableKey)

    /** 从曲库移除一条在线曲（连带它的用户数据，由外键级联负责） */
    suspend fun remove(stableKey: String) = withContext(Dispatchers.IO) {
        val dao = MusicDb.get(appContext).songDao()
        dao.byUri(stableKey)?.let { dao.deleteById(it.id) }
        store.remove(stableKey)
        OnlineUrlMemo.forget(stableKey)
    }

    /**
     * 回收「曲库里已经没有」的源内信息。
     *
     * 用户删歌 / 移出曲库 / 换音源之后，`online_tracks.json` 里会留下再也不会用到的条目。
     * 不做这件事不会出错，但文件会一直变大；在「设置 → 在线与音源 → 清理」里手动触发即可。
     */
    suspend fun pruneOrphans(): Int = withContext(Dispatchers.IO) {
        val dao = MusicDb.get(appContext).songDao()
        // 用 onlineUris（含已排除 / 软删）而不是 visible：被排除的在线歌用户还能放回来，
        // 它的 raw 一旦清掉就再也取不到直链，只能重新搜一遍。
        val alive = dao.onlineUris().toSet()
        val before = store.size()
        store.prune(alive)
        before - store.size()
    }
}
