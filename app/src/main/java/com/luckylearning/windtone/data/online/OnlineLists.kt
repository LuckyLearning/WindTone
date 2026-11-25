package com.luckylearning.windtone.data.online

import android.content.Context
import com.luckylearning.windtone.data.db.MusicDb
import com.luckylearning.windtone.data.db.PlaylistEntity
import com.luckylearning.windtone.data.db.PlaylistSongEntity
import com.luckylearning.windtone.data.db.SongEntity
import com.luckylearning.windtone.data.db.toSong
import com.luckylearning.windtone.data.model.Song
import com.luckylearning.windtone.data.online.source.OnlineTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 在线来源的「我的列表」：从平台歌单**收进我的列表**产生的那些本地列表。
 *
 * ## 为什么按「内容」判定来源
 * `playlists` 表没有来源列（加列 = 破坏性迁移 = 清库，见 [SavedSheetStore] 的说明）。
 * 这里用两条依据，任一成立即视为在线列表：
 *  1. [SavedSheetStore] 里有映射（新收进的，最准）；
 *  2. 列表里至少有 1 首 `lx:` 曲目（历史收进、映射丢失时的兜底）。
 *
 * 本地自建列表的曲目全部来自扫描，uri 不可能是 `lx:`，所以不会误判。
 *
 * ## 去重
 * v1.3.2 之前的保存逻辑每次都新建列表，用户看不到「已收藏」反馈就会再点一次，
 * 于是出现两条同名列表（都 59 首）。[mergeDuplicates] 负责把这类历史重复合并掉，
 * [saveSheet] 之后的保存路径本身也不再新建重复。
 */
object OnlineLists {

    data class Summary(
        val id: Long,
        val name: String,
        /** 列表内曲目数 */
        val count: Int,
        /** 封面取样（最多 4 首，用于 2x2 拼图） */
        val cover: List<Song>,
        val createdAt: Long,
    )

    sealed interface SaveResult {
        /** 第一次收进，新建了列表 */
        data class Created(val playlistId: Long) : SaveResult

        /** 已经收过，这次把曲目合并进原列表 */
        data class Updated(val playlistId: Long) : SaveResult

        data object Failed : SaveResult
    }

    // ---------------------------------------------------------------- 读

    /** 全部在线列表，新建的排前面 */
    suspend fun summaries(context: Context): List<Summary> = withContext(Dispatchers.IO) {
        val db = MusicDb.get(context)
        val dao = db.playlistDao()
        val entities = dao.all()
        if (entities.isEmpty()) return@withContext emptyList()
        val links = dao.allSongs().groupBy { it.playlistId }
        val uriById = db.songDao().allIdUri().associate { it.id to it.uri }
        val mapped = SavedSheetStore(context).all().values.toSet()

        val online = entities.filter { p ->
            p.id in mapped || links[p.id].orEmpty().any { row ->
                OnlineKeys.isOnlineKey(uriById[row.songId])
            }
        }
        if (online.isEmpty()) return@withContext emptyList()

        // 封面一次取回：每条列表最多 4 首
        val coverIds = online.flatMap { p ->
            links[p.id].orEmpty().take(COVER_SAMPLE).map { it.songId }
        }.distinct()
        val covers = db.songDao().byIds(coverIds).associateBy { it.id }

        online.map { p ->
            val rows = links[p.id].orEmpty()
            Summary(
                id = p.id,
                name = p.name,
                count = rows.size,
                cover = rows.take(COVER_SAMPLE).mapNotNull { covers[it.songId]?.toSong() },
                createdAt = p.createdAt,
            )
        }.sortedByDescending { it.createdAt }
    }

    /** 列表内歌曲（按 position 排序，缺失/被排除的行跳过） */
    suspend fun songs(context: Context, playlistId: Long): List<Song> = withContext(Dispatchers.IO) {
        val db = MusicDb.get(context)
        val rows = db.playlistDao().songsOf(playlistId)
        if (rows.isEmpty()) return@withContext emptyList()
        val byId = db.songDao().byIds(rows.map { it.songId }).associateBy { it.id }
        rows.mapNotNull { byId[it.songId]?.toSong() }
    }

    /** 这个歌单是否已经收进我的列表（映射失效但同名在线列表存在时也算） */
    suspend fun isSaved(context: Context, sheet: OnlineSonglists.Sheet): Boolean =
        withContext(Dispatchers.IO) {
            val db = MusicDb.get(context)
            val dao = db.playlistDao()
            val mapped = SavedSheetStore(context).get(sheet.key)
            if (mapped != null && dao.byId(mapped) != null) return@withContext true
            findByNameOnline(dao, db, sheet.name) != null
        }

    // ---------------------------------------------------------------- 写

    /**
     * 收进我的列表。
     *
     * 幂等：同一个歌单重复点，第二次起只做「补进新曲目」，不再新建列表。
     * 这也是 v1.3.2「列表里出现两条一模一样」的直接修复。
     */
    suspend fun saveSheet(
        context: Context,
        sheet: OnlineSonglists.Sheet,
        tracks: List<OnlineTrack>,
    ): SaveResult = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext SaveResult.Failed
        runCatching {
            val db = MusicDb.get(context)
            val dao = db.playlistDao()
            val store = SavedSheetStore(context)
            val songs = OnlineLibrary(context).upsertAll(tracks)
            if (songs.isEmpty()) return@withContext SaveResult.Failed
            val name = displayName(sheet)

            val mapped = store.get(sheet.key)?.takeIf { dao.byId(it) != null }
            val target = mapped ?: findByNameOnline(dao, db, name)

            val pid = if (target != null) {
                appendSongs(dao, target, songs)
                target
            } else {
                val id = dao.insert(PlaylistEntity(name = name, createdAt = System.currentTimeMillis()))
                appendSongs(dao, id, songs)
                id
            }
            store.put(sheet.key, pid)
            // 顺手把同名的历史重复并进来（用户可能已经点出两条）
            mergeSameNameInto(dao, db, pid, name, store)
            if (target != null) SaveResult.Updated(pid) else SaveResult.Created(pid)
        }.getOrDefault(SaveResult.Failed)
    }

    /**
     * 全局合并同名在线列表，返回合并掉的条数。
     *
     * 只在打开洛雪「我的列表」时调用（幂等、纯本地）：用户的历史重复不必等下一次点保存才清掉。
     * 判据写得很窄 —— **同名 + 都含在线曲目**，本地自建同名列表不参与。
     */
    suspend fun mergeDuplicates(context: Context): Int = withContext(Dispatchers.IO) {
        runCatching {
            val db = MusicDb.get(context)
            val dao = db.playlistDao()
            val store = SavedSheetStore(context)
            val entities = dao.all()
            if (entities.size < 2) return@withContext 0
            val links = dao.allSongs().groupBy { it.playlistId }
            val uriById = db.songDao().allIdUri().associate { it.id to it.uri }
            val mapped = store.all().values.toSet()

            fun isOnline(id: Long): Boolean =
                id in mapped || links[id].orEmpty().any { row -> OnlineKeys.isOnlineKey(uriById[row.songId]) }

            var merged = 0
            entities.groupBy { it.name.trim() }.values.forEach { group ->
                val online = group.filter { isOnline(it.id) }
                if (online.size < 2) return@forEach
                // 保留最早创建的那条（用户第一次收进来的），其余并进去
                val keep = online.minByOrNull { it.createdAt } ?: return@forEach
                online.filter { it.id != keep.id }.forEach { other ->
                    mergeInto(dao, keep.id, dao.songsOf(other.id))
                    dao.delete(other.id)
                    store.repoint(other.id, keep.id)
                    merged++
                }
            }
            merged
        }.getOrDefault(0)
    }

    // ---------------------------------------------------------------- 内部

    private const val COVER_SAMPLE = 4

    private fun displayName(sheet: OnlineSonglists.Sheet): String {
        val fallback = "在线歌单"
        return sheet.name.trim().ifBlank { fallback }.take(80)
    }

    /**
     * 把歌曲追加到列表尾部。
     *
     * 先查已有 songId 再跳过：`insertSong` 是 REPLACE，直接插会把已存在那首的
     * position 改成末尾（顺序被打乱），虽然曲目不重复，但用户看到的歌单顺序会跳。
     */
    private suspend fun appendSongs(
        dao: com.luckylearning.windtone.data.db.PlaylistDao,
        playlistId: Long,
        songs: List<Song>,
    ) {
        val now = System.currentTimeMillis()
        val existing = dao.songsOf(playlistId).mapTo(mutableSetOf()) { it.songId }
        var pos = dao.maxPosition(playlistId) + 1
        songs.forEach { song ->
            val dbId = song.dbId ?: return@forEach
            if (!existing.add(dbId)) return@forEach
            dao.insertSong(PlaylistSongEntity(playlistId, dbId, pos++, now))
        }
    }

    /** 同名的在线列表（用于映射丢失时认领，而不是再建一条）；[exclude] 用于跳过已选中的那条 */
    private suspend fun findByNameOnline(
        dao: com.luckylearning.windtone.data.db.PlaylistDao,
        db: MusicDb,
        name: String,
        exclude: Long? = null,
    ): Long? {
        val target = name.trim()
        if (target.isEmpty()) return null
        val uriById = db.songDao().allIdUri().associate { it.id to it.uri }
        val links = dao.allSongs().groupBy { it.playlistId }
        return dao.all().firstOrNull { p ->
            p.id != exclude && p.name.trim() == target && links[p.id].orEmpty().any { row ->
                OnlineKeys.isOnlineKey(uriById[row.songId])
            }
        }?.id
    }

    /** 把 [playlistId] 之外的同名在线列表并进来并删除 */
    private suspend fun mergeSameNameInto(
        dao: com.luckylearning.windtone.data.db.PlaylistDao,
        db: MusicDb,
        playlistId: Long,
        name: String,
        store: SavedSheetStore,
    ) {
        // findByNameOnline 一次只返回一条，循环直到没有别的同名在线列表
        while (true) {
            val other = findByNameOnline(dao, db, name, exclude = playlistId) ?: return
            mergeInto(dao, playlistId, dao.songsOf(other))
            dao.delete(other)
            store.repoint(other, playlistId)
        }
    }

    /** 把 [rows] 中 target 还没有的曲目追加到末尾（已存在的跳过，保持原有顺序） */
    private suspend fun mergeInto(
        dao: com.luckylearning.windtone.data.db.PlaylistDao,
        targetId: Long,
        rows: List<PlaylistSongEntity>,
    ) {
        if (rows.isEmpty()) return
        val existing = dao.songsOf(targetId).mapTo(mutableSetOf()) { it.songId }
        var pos = dao.maxPosition(targetId) + 1
        rows.forEach { row ->
            if (existing.add(row.songId)) {
                dao.insertSong(PlaylistSongEntity(targetId, row.songId, pos++, row.addedAt))
            }
        }
    }
}
